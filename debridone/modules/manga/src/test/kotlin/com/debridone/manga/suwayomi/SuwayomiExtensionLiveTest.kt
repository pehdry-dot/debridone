package com.debridone.manga.suwayomi

import org.junit.Assume
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A real Mihon/Tachiyomi extension, installed by the real Suwayomi-Server from a real extension
 * store, reading from a real server: the Komga extension against Komga's public demo
 * (https://demo.komga.org, an instance its authors run for trying Komga out, with public-domain
 * books and published demo credentials).
 *
 * Runs only with DEBRIDONE_LIVE_TESTS=1 and with DEBRIDONE_TEST_EXTENSION_STORE set to the address
 * of an extension store's index. DebridOne ships no store address, and neither does this test: the
 * person who runs it names the store, exactly as a person using the app does.
 */
class SuwayomiExtensionLiveTest {
    @Test
    fun aRealExtensionIsInstalledConfiguredSearchedAndRead() {
        SuwayomiTestRuntime.assumeLive()
        val store = System.getenv("DEBRIDONE_TEST_EXTENSION_STORE").orEmpty()
        Assume.assumeTrue("No extension store named (set DEBRIDONE_TEST_EXTENSION_STORE)", store.isNotBlank())
        val runtime = SuwayomiTestRuntime.runtimeOrSkip()
        val root = Files.createTempDirectory("debridone-suwayomi-live")
        val manager = SuwayomiServerManager(root, { runtime })
        try {
            var api = SuwayomiApi(SuwayomiTransport(manager.start(1)))

            // The store.
            val added = api.addStore(store)
            println("store: name=${added.name} legacy=${added.legacy}")
            assertEquals(listOf(added.indexUrl), api.stores().map { it.indexUrl })
            val offered = api.fetchExtensions()
            println("extensions offered: ${offered.size}, languages: ${offered.map { it.lang }.distinct().size}")
            assertTrue(offered.size > 10, "the store lists extensions")
            assertTrue(offered.none { it.installed })

            // Install.
            val komga = assertNotNull(offered.firstOrNull { it.pkgName.endsWith(".all.komga") }, "the store offers the Komga extension")
            val installed = assertNotNull(api.installExtension(komga.pkgName))
            assertTrue(installed.installed)
            println("installed: ${installed.name} ${installed.versionName} (${installed.pkgName})")
            val sourceIds = api.extensionSources().getValue(komga.pkgName)
            assertTrue(sourceIds.isNotEmpty(), "the extension registered its sources")
            val source = api.sources().first { it.id == sourceIds.first() }
            assertTrue(source.configurable)

            // Configure the source through its own preferences.
            fun text(title: String) = api.sourceDetails(source.id).preferences.filterIsInstance<SwPreference.Text>().first { it.title == title }
            api.setSourcePreference(source.id, SwPreferenceChange(text("Address").position, text = "https://demo.komga.org"))
            api.setSourcePreference(source.id, SwPreferenceChange(text("Username").position, text = "demo@komga.org"))
            api.setSourcePreference(source.id, SwPreferenceChange(text("Password").position, text = "komga-demo"))
            assertEquals("https://demo.komga.org", text("Address").value)

            // Browse, search, filters.
            val details = api.sourceDetails(source.id)
            println("filters: ${details.filters.map { it::class.simpleName + ":" + it.name }}")
            val popular = api.browse(source.id, SuwayomiApi.BrowseType.POPULAR, 1)
            println("popular: ${popular.items.map { it.title }}")
            assertTrue(popular.items.isNotEmpty(), "the source lists series")
            // The demo also holds e-books, which the extension lists without chapters ("No chapters
            // found" is the extension's own answer); the first series that has chapters is read.
            val readable = popular.items.firstNotNullOfOrNull { candidate ->
                runCatching { api.refresh(candidate.id) }.getOrNull()?.takeIf { it.second.isNotEmpty() }
            }
            val (full, chapters) = assertNotNull(readable, "one of the listed series has chapters")
            val wanted = full.title
            val found = api.browse(source.id, SuwayomiApi.BrowseType.SEARCH, 1, query = wanted.split(" ").first())
            assertTrue(found.items.any { it.title == wanted }, "search finds \"$wanted\": ${found.items.map { it.title }}")
            val series = found.items.first { it.title == wanted }
            assertEquals(full.id, series.id, "a search result is the same series the server already knows")

            // Details, chapters, pages.
            assertTrue(full.initialized)
            assertTrue(chapters.isNotEmpty(), "the series has chapters")
            assertTrue(SuwayomiTransport(manager.start(1)).bytes(assertNotNull(full.thumbnailUrl)).size > 500, "the cover is an image")
            val chapter = api.chapters(series.id).first()
            val (opened, pages) = api.chapterPages(chapter.id)
            assertTrue(pages.isNotEmpty() && opened.pageCount == pages.size)
            val transport = SuwayomiTransport(manager.start(1))
            val page = transport.bytes(pages.first())
            val jpeg = page[0] == 0xFF.toByte() && page[1] == 0xD8.toByte()
            val png = page.copyOf(4).contentEquals(SampleComics.PNG_SIGNATURE)
            val webp = String(page.copyOfRange(8, 12)) == "WEBP"
            assertTrue(jpeg || png || webp, "the first page is a picture")
            println("read: \"${full.title}\", ${chapters.size} chapter(s), ${pages.size} page(s), first page ${page.size} bytes")

            // Progress and library, then a restart of the server.
            api.updateChapters(listOf(chapter.id), lastPageRead = 1)
            api.setInLibrary(listOf(series.id), true)
            manager.stop()
            api = SuwayomiApi(SuwayomiTransport(manager.start(1)))
            assertTrue(api.extensions().single { it.pkgName == komga.pkgName }.installed, "the extension is still installed")
            assertEquals("https://demo.komga.org", text("Address").value)
            val kept = api.library().single()
            assertEquals(wanted, kept.title)
            assertEquals(1, kept.lastReadChapter?.lastPageRead)
            assertTrue(api.browse(source.id, SuwayomiApi.BrowseType.POPULAR, 1).items.isNotEmpty(), "the source still works")

            // Removing the extension removes its sources; removing the store empties the list.
            api.uninstallExtension(komga.pkgName)
            assertTrue(api.sources().none { it.id in sourceIds })
            api.removeStore(added.indexUrl)
            assertTrue(api.stores().isEmpty())
        } finally {
            manager.close()
            root.toFile().deleteRecursively()
        }
    }
}
