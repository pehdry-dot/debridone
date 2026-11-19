package com.debridone.manga.mangayomi

import com.debridone.manga.net.MangaHttp
import com.debridone.manga.sources.MangaExtensions
import com.debridone.manga.sources.githubFileAddress
import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SwStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One extension or source an index offers, as the import panel shows it. */
data class ImportItem(
    /** The package name it is (or would be) installed under. */
    val key: String,
    val name: String,
    val lang: String,
    val site: String,
    /** A web address (Mangayomi) or a path of the manga server (a store's extension). */
    val iconUrl: String,
    /** "JavaScript", "Dart" or "Mihon/Tachiyomi". */
    val kind: String,
    val version: String,
    val nsfw: Boolean,
    val compatibility: Compatibility,
    /** The version installed in this profile, or null. */
    val installedVersion: String?,
    val updateAvailable: Boolean,
    /** An extension of the person's stores that is the same site, when this one cannot run. */
    val alternative: String? = null,
) {
    val installable: Boolean get() = compatibility.support != Support.NONE
}

/** What an address or a file holds, ready to be installed from. */
data class ImportPreview(
    val address: String,
    val format: IndexFormat,
    val title: String,
    val items: List<ImportItem>,
    /** Something to know about the whole list (a store that was registered, a format that cannot be used). */
    val note: String,
    /** The store the manga server now reads updates from, when the list was a Mihon/Tachiyomi store's. */
    val store: SwStore? = null,
)

/** How an installation went, item by item. */
data class InstallReport(val installed: List<String>, val failed: List<Pair<String, String>>, val skipped: List<Pair<String, String>>)

/** An index the person imported from, kept with the profile's library so its updates can be found. */
data class KnownIndex(val address: String, val title: String, val format: IndexFormat, val addedAt: Long)

/**
 * Adding extensions from an index, wherever it comes from: an address pasted into Manga's
 * Extensions tab, or a JSON file. The format is recognised first, then each entry is judged:
 *
 * - a Mihon/Tachiyomi store's list: the store is registered with the manga server (which then also
 *   finds its updates) and the chosen extensions are installed by the server, as they are;
 * - a Mangayomi index: each JavaScript source is fetched, checked, and packed with DebridOne's
 *   Mangayomi bridge into an extension the server installs and runs; Dart sources, anime and novel
 *   sources are listed with the reason they cannot run.
 *
 * Nothing is presented as installed before the manga server confirmed it. What was imported is
 * kept in the server's own key-value store, so it belongs to the profile's library and its backups.
 */
class ExtensionImports(
    private val server: MangaServer,
    private val extensions: MangaExtensions,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fetch: (String) -> ByteArray = { url -> download(url) },
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val api get() = server.api()

    /** Source code fetched while looking at an index, kept for the installation that follows. */
    private val code = ConcurrentHashMap<String, String>()

    // ---- looking -----------------------------------------------------------------------------------

    /** Reads the list at [address] and judges what it offers. Registers nothing unless it is a store of the manga server's own kind. */
    fun inspect(address: String): ImportPreview {
        val typed = address.trim()
        require(typed.startsWith("https://") || typed.startsWith("http://")) { "Paste a web address (https://…) of an index, or choose a JSON file." }
        val url = githubFileAddress(typed)
        val bytes = fetch(url)
        return preview(url, ExtensionIndexDetector.detect(bytes, url))
    }

    /** Reads a list saved on this computer. A store's list from a file cannot be registered (the server needs its address). */
    fun inspectFile(file: Path): ImportPreview {
        val size = Files.size(file)
        require(size in 1..MAX_INDEX_BYTES) { "\"${file.fileName}\" is not an index of extensions (${size / 1024} KB)." }
        val detected = ExtensionIndexDetector.detect(Files.readAllBytes(file), file.fileName.toString())
        if (detected.format in setOf(IndexFormat.SUWAYOMI_LIST, IndexFormat.SUWAYOMI_REPO, IndexFormat.SUWAYOMI_BINARY)) {
            return ImportPreview(file.toString(), detected.format, detected.format.label, emptyList(), "This is a Mihon/Tachiyomi store's list. The manga server reads such stores from their web address, so paste the address instead of the file.")
        }
        return preview(file.toUri().toString(), detected)
    }

    private fun preview(address: String, detected: DetectedIndex): ImportPreview = when (detected.format) {
        IndexFormat.MANGAYOMI -> mangayomiPreview(address, detected.mangayomi)
        IndexFormat.SUWAYOMI_LIST, IndexFormat.SUWAYOMI_REPO, IndexFormat.SUWAYOMI_BINARY -> storePreview(address)
        IndexFormat.LNREADER -> ImportPreview(address, detected.format, detected.format.label, emptyList(), "LNReader's plugins are for light novels; Manga reads manga.")
        IndexFormat.AIDOKU -> ImportPreview(address, detected.format, detected.format.label, emptyList(), "Aidoku's sources are WebAssembly packages made for its iOS app; the manga server cannot run them.")
        IndexFormat.UNKNOWN -> throw IOException(detected.note.ifBlank { "This is not a list of extensions DebridOne knows." })
    }

    private fun mangayomiPreview(address: String, entries: List<MangayomiEntry>): ImportPreview {
        val installed = installedRecords()
        val serverExtensions = runCatching { api.extensions() }.getOrDefault(emptyList())
        // The code of each JavaScript source, once per file (one file can serve many languages).
        val scripts = entries.filter { it.codeLanguage == 1 && it.itemType == 0 }.map { it.codeUrl }.filter { it.startsWith("http") }.distinct()
        scripts.parallelStream().forEach { url -> if (!code.containsKey(url)) runCatching { code[url] = String(fetch(url), Charsets.UTF_8) } }
        val byName = serverExtensions.groupBy { it.name.lowercase() }
        val items = entries.map { entry ->
            val compatibility = MangayomiCompatibility.judge(entry, code[entry.codeUrl]).let {
                if (it.support == Support.ADAPTER && !code.containsKey(entry.codeUrl)) it.copy(warnings = it.warnings + "Its code could not be fetched just now; it is fetched again when you install it.") else it
            }
            val record = installed[entry.packageName]
            ImportItem(
                key = entry.packageName,
                name = entry.name,
                lang = entry.lang,
                site = entry.baseUrl,
                iconUrl = entry.iconUrl,
                kind = entry.language,
                version = entry.version,
                nsfw = entry.nsfw,
                compatibility = compatibility,
                installedVersion = record?.version,
                updateAvailable = record != null && record.version != entry.version,
                alternative = if (compatibility.support == Support.NONE) byName[entry.name.lowercase()]?.firstOrNull()?.let { "Your stores offer a Mihon extension of the same name: ${it.name}." } else null,
            )
        }
        val usable = items.count { it.installable }
        val title = "${titleOf(address)} · ${plural(entries.size, "source")}"
        val note = buildString {
            append("$usable of ${entries.size} can run here.")
            val dart = entries.count { it.codeLanguage != 1 && it.itemType == 0 }
            if (dart > 0) append(" $dart are written in Dart, which needs Mangayomi's own interpreter.")
            val other = entries.count { it.itemType != 0 }
            if (other > 0) append(" $other are anime or novel sources.")
        }
        remember(address, title, IndexFormat.MANGAYOMI)
        return ImportPreview(address, IndexFormat.MANGAYOMI, title, items, note)
    }

    /** A Mihon/Tachiyomi store: registered with the manga server, which reads its list and keeps it up to date. */
    private fun storePreview(address: String): ImportPreview {
        val added = extensions.addStoreFrom(address)
        val store = added.store
        val offered = extensions.list().filter { it.storeIndexUrl == store.indexUrl }
        val items = offered.map { ext ->
            ImportItem(
                key = ext.pkgName, name = ext.name, lang = ext.lang, site = "", iconUrl = ext.iconUrl, kind = "Mihon/Tachiyomi", version = ext.versionName,
                nsfw = ext.contentWarning == "NSFW",
                compatibility = Compatibility(Support.DIRECT, "A Mihon/Tachiyomi extension: the manga server installs and runs it as it is."),
                installedVersion = if (ext.installed) ext.versionName else null, updateAvailable = ext.installed && ext.hasUpdate,
            )
        }
        val readFrom = if (added.otherFile != null) " (read from ${added.otherFile} beside the address given)" else ""
        return ImportPreview(
            store.indexUrl, IndexFormat.SUWAYOMI_LIST, "${store.name.ifBlank { "Store" }} · ${plural(items.size, "extension")}", items,
            "This store is now one of the profile's stores$readFrom: the manga server finds updates of what you install from it.", store,
        )
    }

    // ---- installing --------------------------------------------------------------------------------

    /** Installs the chosen items of [preview]. [onProgress] is told before each one. */
    fun install(preview: ImportPreview, keys: Collection<String>, onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> }): InstallReport {
        val chosen = preview.items.filter { it.key in keys }
        val installed = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        val skipped = ArrayList<Pair<String, String>>()
        chosen.forEachIndexed { index, item ->
            onProgress(index, chosen.size, item.name)
            if (!item.installable) {
                skipped += item.name to item.compatibility.reason
                return@forEachIndexed
            }
            try {
                when (preview.format) {
                    IndexFormat.MANGAYOMI -> installMangayomi(preview.address, item.key)
                    else -> extensions.install(item.key) ?: throw IOException("The manga server did not install it.")
                }
                installed += item.name
            } catch (failure: Exception) {
                failed += item.name to (failure.message ?: failure.javaClass.simpleName)
            }
        }
        onProgress(chosen.size, chosen.size, "")
        extensions.refreshed()
        return InstallReport(installed, failed, skipped)
    }

    private fun installMangayomi(indexAddress: String, packageName: String) {
        val entry = entriesOf(indexAddress).firstOrNull { it.packageName == packageName } ?: throw IOException("The index no longer lists this source.")
        val script = code[entry.codeUrl] ?: String(fetch(entry.codeUrl), Charsets.UTF_8).also { code[entry.codeUrl] = it }
        val compatibility = MangayomiCompatibility.judge(entry, script)
        if (compatibility.support == Support.NONE) throw IOException(compatibility.reason)
        val jar = MangayomiPackage.build(entry, script, indexAddress)
        val current = runCatching { api.extensions() }.getOrDefault(emptyList()).firstOrNull { it.pkgName == entry.packageName }
        // The server takes a new version over the old one; the same version again has to be removed first.
        if (current != null && current.installed && current.versionCode == entry.versionCode.toLong()) api.uninstallExtension(entry.packageName)
        val result = api.installExtensionFile("${entry.packageName}.jar", jar)
        if (!result.installed) throw IOException("The manga server did not install it.")
        saveRecord(entry.packageName, InstalledRecord(indexAddress, entry.id, entry.lang, entry.name, entry.version, entry.codeUrl, clock(), entry.iconUrl, entry.baseUrl))
    }

    private val entryCache = ConcurrentHashMap<String, List<MangayomiEntry>>()

    private fun entriesOf(indexAddress: String): List<MangayomiEntry> = entryCache.getOrPut(indexAddress) {
        val bytes = if (indexAddress.startsWith("file:")) Files.readAllBytes(Path.of(URI(indexAddress))) else fetch(indexAddress)
        ExtensionIndexDetector.detect(bytes, indexAddress).mangayomi
    }

    /** Removes an extension DebridOne built from a Mangayomi source, and its record. */
    fun uninstall(packageName: String) {
        extensions.uninstall(packageName)
        val records = installedRecords().toMutableMap()
        if (records.remove(packageName) != null) writeRecords(records)
    }

    // ---- updates -----------------------------------------------------------------------------------

    /** Reads the imported Mangayomi indexes again and says which installed sources have a newer version there. */
    fun checkUpdates(): List<ImportItem> {
        entryCache.clear()
        code.clear()
        val records = installedRecords()
        return records.values.map { it.indexAddress }.distinct().flatMap { address ->
            runCatching { mangayomiPreview(address, entriesOf(address)) }.getOrNull()?.items.orEmpty().filter { it.updateAvailable }
        }
    }

    /** Installs the newer version of an installed Mangayomi source. */
    fun update(packageName: String) {
        val record = installedRecords()[packageName] ?: throw IOException("DebridOne did not install this source from an index.")
        entryCache.remove(record.indexAddress)
        code.remove(record.codeUrl)
        installMangayomi(record.indexAddress, packageName)
    }

    // ---- what is kept ------------------------------------------------------------------------------

    data class InstalledRecord(
        val indexAddress: String, val id: Long, val lang: String, val name: String, val version: String, val codeUrl: String, val installedAt: Long,
        val iconUrl: String = "", val baseUrl: String = "",
    )

    /** The Mangayomi sources DebridOne installed in this profile, by package name. */
    fun installedRecords(): Map<String, InstalledRecord> {
        val text = runCatching { api.globalMeta()[INSTALLED_KEY] }.getOrNull() ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(text).jsonObject.mapValues { (_, v) ->
                val o = v.jsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                InstalledRecord(s("indexAddress"), (o["id"] as? JsonPrimitive)?.longOrNull ?: 0, s("lang"), s("name"), s("version"), s("codeUrl"), (o["installedAt"] as? JsonPrimitive)?.longOrNull ?: 0, s("iconUrl"), s("baseUrl"))
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveRecord(packageName: String, record: InstalledRecord) = writeRecords(installedRecords() + (packageName to record))

    private fun writeRecords(records: Map<String, InstalledRecord>) {
        val value = JsonObject(records.mapValues { (_, r) ->
            JsonObject(mapOf(
                "indexAddress" to JsonPrimitive(r.indexAddress), "id" to JsonPrimitive(r.id), "lang" to JsonPrimitive(r.lang), "name" to JsonPrimitive(r.name),
                "version" to JsonPrimitive(r.version), "codeUrl" to JsonPrimitive(r.codeUrl), "installedAt" to JsonPrimitive(r.installedAt),
                "iconUrl" to JsonPrimitive(r.iconUrl), "baseUrl" to JsonPrimitive(r.baseUrl),
            ))
        })
        api.setGlobalMeta(INSTALLED_KEY, value.toString())
    }

    /** The indexes imported in this profile (Mangayomi indexes; stores are the server's own list). */
    fun indexes(): List<KnownIndex> {
        val text = runCatching { api.globalMeta()[INDEXES_KEY] }.getOrNull() ?: return emptyList()
        return runCatching {
            (json.parseToJsonElement(text) as JsonArray).map { item ->
                val o = item.jsonObject
                fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                KnownIndex(s("address"), s("title"), runCatching { IndexFormat.valueOf(s("format")) }.getOrDefault(IndexFormat.MANGAYOMI), (o["addedAt"] as? JsonPrimitive)?.longOrNull ?: 0)
            }
        }.getOrDefault(emptyList())
    }

    fun forgetIndex(address: String) {
        val kept = indexes().filter { it.address != address }
        writeIndexes(kept)
    }

    private fun remember(address: String, title: String, format: IndexFormat) {
        if (address.startsWith("file:")) return
        val known = indexes()
        val same = known.firstOrNull { it.address == address }
        when {
            same == null -> writeIndexes(known + KnownIndex(address, title, format, clock()))
            same.title != title -> writeIndexes(known.map { if (it.address == address) it.copy(title = title) else it })
        }
    }

    private fun writeIndexes(list: List<KnownIndex>) {
        api.setGlobalMeta(INDEXES_KEY, JsonArray(list.map {
            JsonObject(mapOf("address" to JsonPrimitive(it.address), "title" to JsonPrimitive(it.title), "format" to JsonPrimitive(it.format.name), "addedAt" to JsonPrimitive(it.addedAt)))
        }).toString())
    }

    companion object {
        const val INDEXES_KEY = "debridone.mangayomi.indexes"
        const val INSTALLED_KEY = "debridone.mangayomi.installed"
        private const val MAX_INDEX_BYTES = 8L * 1024 * 1024
        private const val MAX_CODE_BYTES = 2 * 1024 * 1024

        private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

        /** A short name for an index: its GitHub repository when it is one, else its site, else the file's name. */
        fun titleOf(address: String): String {
            val uri = runCatching { URI(address) }.getOrNull()
            val parts = uri?.path.orEmpty().split('/').filter { it.isNotBlank() }
            return when {
                uri?.host == "raw.githubusercontent.com" && parts.size >= 2 -> "${parts[0]}/${parts[1]}"
                uri?.host == "github.com" && parts.size >= 2 -> "${parts[0]}/${parts[1]}"
                uri?.scheme == "file" -> parts.lastOrNull() ?: "File"
                uri?.host != null -> uri.host.removePrefix("www.")
                else -> address
            }
        }

        private fun download(url: String): ByteArray {
            val response = MangaHttp.get(url, mapOf("Accept" to "application/json, text/plain, */*"), maxBytes = MAX_INDEX_BYTES.toInt())
            if (response.status !in 200..299) throw IOException("The address answered ${response.status}${if (response.status == 404) " (nothing there)" else ""}: $url")
            return response.body
        }
    }
}

/** Builds the extension jar the manga server installs for one Mangayomi source. */
object MangayomiPackage {
    private const val BRIDGE_CLASS = "com.debridone.mangayomi.bridge.MangayomiSource"

    /** The bridge's classes, as DebridOne carries them. */
    fun bridge(): ByteArray = MangayomiPackage::class.java.classLoader.getResourceAsStream("mangayomi/bridge.jar")?.use { it.readBytes() }
        ?: throw IOException("This build of DebridOne has no Mangayomi bridge.")

    fun build(entry: MangayomiEntry, code: String, indexAddress: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            ZipInputStream(bridge().inputStream()).use { input ->
                while (true) {
                    val item = input.nextEntry ?: break
                    if (item.isDirectory || item.name.startsWith("META-INF/")) continue
                    zip.putNextEntry(ZipEntry(item.name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest(entry).toByteArray())
            zip.closeEntry()
            val meta = JsonObject(entry.json + mapOf("debridoneSourceId" to JsonPrimitive(entry.sourceId), "debridoneIndex" to JsonPrimitive(indexAddress)))
            zip.putNextEntry(ZipEntry("debridone-mangayomi/source.json"))
            zip.write(meta.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("debridone-mangayomi/source.js"))
            zip.write(code.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * The manifest the manga server reads a jar extension by: package, version, the class that
     * provides the source, and whether it is for adults. The version name's first two numbers are
     * the extension library version the server checks (1.6).
     */
    fun manifest(entry: MangayomiEntry): String {
        fun attr(text: String) = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${attr(entry.packageName)}" android:versionCode="${entry.versionCode}" android:versionName="1.6.${entry.versionCode}">
    <uses-feature android:name="tachiyomi.extension" />
    <application android:label="${attr(entry.name)}">
        <meta-data android:name="tachiyomi.extension.class" android:value="$BRIDGE_CLASS" />
        <meta-data android:name="tachiyomi.extension.nsfw" android:value="${if (entry.nsfw) 1 else 0}" />
    </application>
</manifest>
"""
    }
}
