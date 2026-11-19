package com.debridone.manga.sources

import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SuwayomiApi
import com.debridone.manga.suwayomi.SuwayomiException
import com.debridone.manga.suwayomi.SwExtension
import com.debridone.manga.suwayomi.SwStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extension stores and extensions, exactly as Suwayomi-Server has them. An extension is a
 * Mihon/Tachiyomi extension package; Suwayomi downloads, checks, converts and loads it, and it runs
 * inside Suwayomi's process, never inside DebridOne. DebridOne ships no store and no extension: a
 * store is an address the person adds.
 */
/** A store that was added: [readFrom] is the address the server took, which is not always the one [typed]. */
data class StoreAddition(val store: SwStore, val typed: String, val readFrom: String) {
    /** The name of the file the store was read from when that is another file than the one typed, else null. */
    val otherFile: String? get() = fileName(readFrom).takeIf { !it.equals(fileName(typed), ignoreCase = true) }

    /** True when the address was changed at all (another file, or the file's own address instead of a page about it). */
    val changed: Boolean get() = readFrom != typed.substringBefore('#')

    private fun fileName(address: String): String = address.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
}

/**
 * The addresses to try for a store, in order, starting with the one typed.
 *
 *  - An address of GitHub's page about a file (`github.com/<user>/<repo>/blob/<branch>/<file>`) is
 *    replaced by the address of the file itself: the page is not a store.
 *  - After an address ending in `index.json`: `index.min.json`, then `repo.json`, of the same
 *    folder. What follows a `?` is kept.
 *
 * Every other address is tried as it is and nothing else.
 */
internal fun storeAddressCandidates(address: String): List<String> {
    // What follows a "#" never reaches a server: it is not part of any file's address.
    val typed = githubFileAddress(address.trim()).substringBefore('#')
    val path = typed.substringBefore('?')
    val rest = typed.substring(path.length)
    if (!path.endsWith("/index.json", ignoreCase = true)) return listOf(typed)
    val folder = path.dropLast("index.json".length)
    return listOf(typed, folder + "index.min.json" + rest, folder + "repo.json" + rest)
}

/** `github.com/<user>/<repo>/blob/<branch>/<path>` (or `/raw/`) is a page about a file; this is the file's own address. */
internal fun githubFileAddress(address: String): String {
    val match = Regex("^https?://(?:www\\.)?github\\.com/([^/?#]+)/([^/?#]+)/(?:blob|raw)/([^?#]+)", RegexOption.IGNORE_CASE).find(address) ?: return address
    val (user, repository, rest) = match.destructured
    return "https://raw.githubusercontent.com/$user/$repository/$rest"
}

class MangaExtensions(private val server: MangaServer, private val sources: MangaSources) {
    private val api: SuwayomiApi get() = server.api()

    private val _revision = MutableStateFlow(0L)

    /** Changes when the stores or extensions changed. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Tells the screens that extensions changed (after an installation made elsewhere). */
    fun refreshed() = changed()

    private fun changed() {
        _revision.update { it + 1 }
        sources.invalidate()
    }

    fun stores(): List<SwStore> = api.stores()

    /**
     * Adds a store by the address of its index. Suwayomi fetches and checks it; when it refuses,
     * its reason is thrown. Both the current store format and the older `index.min.json`
     * repositories are accepted (Suwayomi upgrades the latter itself when the store offers it).
     * See [addStoreFrom] for the addresses DebridOne helps with.
     */
    fun addStore(address: String): SwStore = addStoreFrom(address).store

    /**
     * Adds a store and says which address it was read from.
     *
     * Suwayomi decides by what an address returns, with one exception: a plain list of
     * extensions (the older Tachiyomi/Mihon format) is taken only from an address ending in
     * `/index.min.json`. Such repositories publish the same list, readable, as `index.json`
     * beside it, and that is the address people often have. So the address is tried as typed
     * first, and when the server refuses one that ends in `index.json`, `index.min.json` and then
     * `repo.json` of the same folder are tried ([storeAddressCandidates]). An address copied from
     * GitHub's page of a file is turned into the address of the file itself.
     */
    fun addStoreFrom(address: String): StoreAddition {
        val text = address.trim()
        val uri = runCatching { URI(text) }.getOrNull()
        require(uri != null && uri.host != null && (uri.scheme == "https" || uri.scheme == "http")) {
            "\"$address\" is not a web address. A store is added by the address of its index, which starts with https://."
        }
        val candidates = storeAddressCandidates(text)
        var first: SuwayomiException? = null
        var last: SuwayomiException? = null
        for (candidate in candidates) {
            try {
                val store = api.addStore(candidate)
                runCatching { api.fetchExtensions() }
                changed()
                return StoreAddition(store, typed = text, readFrom = candidate)
            } catch (refusal: SuwayomiException) {
                // Only the server's refusal of this address leads to the next one; a server that
                // cannot be reached, or does not know us, is said at once.
                if (refusal.kind != SuwayomiException.Kind.SERVER) throw refusal
                if (first == null) first = refusal
                last = refusal
            }
        }
        if (candidates.size == 1) throw first!!
        throw SuwayomiException(
            SuwayomiException.Kind.SERVER,
            "The manga server cannot use this store. An address ending in index.json is the readable copy of a store's list; " +
                "beside it the server needs the store's index.min.json and repo.json, and it could not read them (${last?.message ?: "no reason given"}).",
        )
    }

    fun removeStore(indexUrl: String) {
        api.removeStore(indexUrl)
        runCatching { api.fetchExtensions() }
        changed()
    }

    /**
     * The extensions the server knows: the installed ones, and what the stores offered when last
     * asked. With [refresh], every store is asked for its current list first (which is how updates
     * are found).
     */
    fun list(refresh: Boolean = false): List<SwExtension> {
        val all = if (refresh) api.fetchExtensions().also { changed() } else api.extensions()
        return all.sortedWith(compareBy<SwExtension> { !it.installed }.thenBy { it.name.lowercase() }.thenBy { it.lang })
    }

    fun install(pkgName: String): SwExtension? = api.installExtension(pkgName).also { changed() }

    fun update(pkgName: String): SwExtension? = api.updateExtension(pkgName).also { changed() }

    /** Updates every installed extension that has a newer version in a store. Returns how many were updated. */
    fun updateAll(): Int {
        val outdated = api.fetchExtensions().filter { it.installed && it.hasUpdate }
        var done = 0
        var problem: Exception? = null
        for (extension in outdated) {
            try {
                api.updateExtension(extension.pkgName)
                done++
            } catch (failure: Exception) {
                problem = failure
            }
        }
        changed()
        if (done == 0 && problem != null) throw problem
        return done
    }

    fun uninstall(pkgName: String) {
        api.uninstallExtension(pkgName)
        changed()
    }

    /** Installs an extension from a package file (`.apk`) on this computer. Suwayomi checks that it is one. */
    fun installFile(file: Path): SwExtension {
        val name = file.fileName.toString()
        require(name.endsWith(".apk", ignoreCase = true) || name.endsWith(".jar", ignoreCase = true)) {
            "\"$name\" is not an extension package. Mihon/Tachiyomi extensions are .apk files (or .jar files made for Suwayomi)."
        }
        val size = Files.size(file)
        require(size in 1..MAX_PACKAGE_BYTES) { "\"$name\" is ${size / (1024 * 1024)} MB, which is too large to be an extension package." }
        return api.installExtensionFile(name, Files.readAllBytes(file)).also { changed() }
    }

    /** Which sources each installed extension provides (package name to source ids). */
    fun sourcesByExtension(): Map<String, List<String>> = api.extensionSources()

    private companion object {
        const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    }
}
