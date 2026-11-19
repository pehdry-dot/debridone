package com.debridone.manga.sources

import com.debridone.manga.suwayomi.SwStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which addresses are tried for a store. Nothing is contacted here. */
class StoreAddressTest {
    @Test
    fun anAddressEndingInIndexJsonIsFollowedByItsNeighbours() {
        assertEquals(
            listOf("https://stores.example/repo/index.json", "https://stores.example/repo/index.min.json", "https://stores.example/repo/repo.json"),
            storeAddressCandidates("  https://stores.example/repo/index.json "),
        )
        // What follows a question mark belongs to every file of the folder; a fragment belongs to none.
        assertEquals(
            listOf("https://stores.example/r/index.json?token=abc", "https://stores.example/r/index.min.json?token=abc", "https://stores.example/r/repo.json?token=abc"),
            storeAddressCandidates("https://stores.example/r/index.json?token=abc#top"),
        )
        assertEquals("https://stores.example/r/INDEX.JSON", storeAddressCandidates("https://stores.example/r/INDEX.JSON").first())
        assertEquals(3, storeAddressCandidates("https://stores.example/r/INDEX.JSON").size)
    }

    @Test
    fun addressesTheServerAlreadyTakesAreTriedAsTheyAreAndNothingElse() {
        for (address in listOf(
            "https://stores.example/repo/index.min.json", "https://stores.example/repo/repo.json", "https://stores.example/repo/index.pb",
            "https://stores.example/repo/", "https://stores.example/my-index.json", "https://stores.example/index.json/list",
        )) {
            assertEquals(listOf(address), storeAddressCandidates(address), address)
        }
    }

    @Test
    fun aPageAboutAFileOnGitHubBecomesTheFileItself() {
        assertEquals(
            "https://raw.githubusercontent.com/someone/extensions/repo/index.min.json",
            githubFileAddress("https://github.com/someone/extensions/blob/repo/index.min.json"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/someone/extensions/repo/index.min.json",
            githubFileAddress("https://www.github.com/someone/extensions/raw/repo/index.min.json?raw=true"),
        )
        // And the two together: the page of an index.json.
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/someone/extensions/repo/index.json",
                "https://raw.githubusercontent.com/someone/extensions/repo/index.min.json",
                "https://raw.githubusercontent.com/someone/extensions/repo/repo.json",
            ),
            storeAddressCandidates("https://github.com/someone/extensions/blob/repo/index.json"),
        )
        // Other addresses of GitHub, and other hosts, are left alone.
        for (address in listOf(
            "https://github.com/someone/extensions", "https://raw.githubusercontent.com/someone/extensions/repo/index.min.json",
            "https://gitlab.example/someone/extensions/blob/repo/index.min.json",
        )) {
            assertEquals(address, githubFileAddress(address), address)
        }
    }

    @Test
    fun whatWasAddedSaysWhichFileItWasReadFrom() {
        val store = SwStore("https://stores.example/r/index.min.json", "A Store", "", null, null, "", legacy = true)
        val asTyped = StoreAddition(store, typed = "https://stores.example/r/index.min.json", readFrom = "https://stores.example/r/index.min.json")
        assertNull(asTyped.otherFile)
        assertFalse(asTyped.changed)
        val neighbour = StoreAddition(store, typed = "https://stores.example/r/index.json?x=1", readFrom = "https://stores.example/r/index.min.json?x=1")
        assertEquals("index.min.json", neighbour.otherFile)
        assertTrue(neighbour.changed)
        val page = StoreAddition(store, typed = "https://github.com/a/b/blob/repo/index.min.json", readFrom = "https://raw.githubusercontent.com/a/b/repo/index.min.json")
        assertNull(page.otherFile, "the same file, at its own address")
        assertTrue(page.changed)
    }
}
