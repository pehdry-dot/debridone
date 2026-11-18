package com.debridone.manga.sources

import com.debridone.manga.suwayomi.MangaServer
import com.debridone.manga.suwayomi.SuwayomiApi
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
class MangaExtensions(private val server: MangaServer, private val sources: MangaSources) {
    private val api: SuwayomiApi get() = server.api()

    private val _revision = MutableStateFlow(0L)

    /** Changes when the stores or extensions changed. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private fun changed() {
        _revision.update { it + 1 }
        sources.invalidate()
    }

    fun stores(): List<SwStore> = api.stores()

    /**
     * Adds a store by the address of its index. Suwayomi fetches and checks it; when it refuses,
     * its reason is thrown. Both the current store format and the older `index.min.json`
     * repositories are accepted (Suwayomi upgrades the latter itself when the store offers it).
     */
    fun addStore(address: String): SwStore {
        val text = address.trim()
        val uri = runCatching { URI(text) }.getOrNull()
        require(uri != null && uri.host != null && (uri.scheme == "https" || uri.scheme == "http")) {
            "\"$address\" is not a web address. A store is added by the address of its index, which starts with https://."
        }
        val store = api.addStore(text)
        runCatching { api.fetchExtensions() }
        changed()
        return store
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
        require(name.endsWith(".apk", ignoreCase = true)) { "\"$name\" is not an extension package. Mihon/Tachiyomi extensions are .apk files." }
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
