package com.debridone.cloud

import com.debridone.cloud.CloudException.Reason
import com.debridone.cloud.CloudTestEnvironment.Companion.PROFILE_A
import com.debridone.cloud.testing.StandInTorBox
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What is listed from the two accounts, how it is sorted onto shelves, found, kept through
 * failures, turned into download addresses, and removed when the user asks. Against the stand-ins.
 */
class CloudListingTest {
    private val env = CloudTestEnvironment()

    @AfterTest
    fun tearDown() = env.close()

    private fun infoRequests() = env.realDebrid.requests.count { it.startsWith("GET /rest/1.0/torrents/info/") }

    @Test
    fun realDebridShowsTorrentsWithTheirFilesAndGeneratedLinksOnce() {
        var cloud = env.open()
        val account = env.connectRealDebrid(cloud)
        val summary = cloud.files.refresh(PROFILE_A, account.id)
        assertEquals(10, summary.entries, "seven torrents and three generated links")

        val items = cloud.files.items(PROFILE_A, CloudQuery(sort = SortBy.NAME, descending = false))
        assertEquals(
            listOf("A.Holiday.Film.2020.1080p.mkv", "Broken Transfer", "Comics Pack", "Loose Song.ogg", "Other Artist - Second Album", "Packed Album", "Some.Show.S01.1080p", "Test Artist - First Album (2021) [FLAC]", "tool.pkg"),
            items.map { it.name },
            "the episode that is also a generated link is listed once, with its torrent",
        )
        fun item(name: String) = items.single { it.name == name }
        fun files(name: String) = cloud.files.files(PROFILE_A, item(name).id)

        // States, in the providers' words and in DebridOne's.
        assertEquals(ItemStatus.READY, item("Comics Pack").status)
        assertEquals(ItemStatus.DOWNLOADING, item("Other Artist - Second Album").status)
        assertEquals(0.4f, item("Other Artist - Second Album").progress)
        assertEquals(ItemStatus.ERROR, item("Broken Transfer").status)
        assertEquals("error", item("Broken Transfer").rawStatus)
        assertEquals(ItemKind.LINK, item("Loose Song.ogg").kind)
        assertEquals(java.time.Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), item("Comics Pack").createdAt)
        assertEquals(java.time.Instant.parse("2026-01-02T04:04:05Z").toEpochMilli(), item("Comics Pack").completedAt)

        // Files: only what was selected, on the shelf their type puts them on.
        val album = files("Test Artist - First Album (2021) [FLAC]")
        assertEquals(listOf("01 - Opening.mp3", "02 - Second Light.flac", "03 - Third Wave.m4a", "cover.jpg"), album.map { it.name })
        assertEquals(listOf(MediaClass.MUSIC, MediaClass.MUSIC, MediaClass.MUSIC, MediaClass.OTHER), album.map { it.mediaClass })
        assertEquals("Test Artist - First Album (2021) [FLAC]", album.first().folder)
        assertEquals(setOf(MediaClass.MUSIC, MediaClass.OTHER), item("Test Artist - First Album (2021) [FLAC]").classes)
        assertEquals(listOf(MediaClass.TV_SHOW, MediaClass.TV_SHOW), files("Some.Show.S01.1080p").map { it.mediaClass })
        assertEquals(listOf(MediaClass.MOVIE), files("A.Holiday.Film.2020.1080p.mkv").map { it.mediaClass })
        assertEquals(listOf("notes.txt" to MediaClass.OTHER, "Vol 01.cbz" to MediaClass.COMIC, "Vol 02.cbz" to MediaClass.COMIC), files("Comics Pack").map { it.name to it.mediaClass })
        assertEquals(emptyList(), files("Other Artist - Second Album"), "an unfinished torrent has nothing to hand out")
        assertEquals(listOf(MediaClass.OTHER), files("tool.pkg").map { it.mediaClass })
        // Packed into one archive by the provider: offered as that one download, not guessed at.
        assertEquals("packed", item("Packed Album").filesState)
        assertEquals(listOf("Packed Album (packed by Real-Debrid)"), files("Packed Album").map { it.name })

        // One question per finished torrent, and only once: not at the next reading, not after a restart.
        assertEquals(5, infoRequests())
        cloud.files.refresh(PROFILE_A, account.id)
        cloud = env.open()
        assertEquals(10, cloud.files.refresh(PROFILE_A, account.id).entries)
        assertEquals(5, infoRequests())
        assertEquals(9, cloud.files.items(PROFILE_A).size)

        // Listing only reads.
        assertTrue(env.realDebrid.requests.none { it.startsWith("POST /rest") || it.startsWith("DELETE") }, env.realDebrid.requests.toString())
    }

    @Test
    fun torBoxShowsTorrentsUsenetAndWebDownloadsWithStatesAndChecksums() {
        val cloud = env.open()
        val account = env.connectTorBox(cloud)
        assertEquals(RefreshSummary(entries = 5, files = 5, removed = 0), cloud.files.refresh(PROFILE_A, account.id))

        val items = cloud.files.items(PROFILE_A)
        assertEquals(mapOf(ItemKind.TORRENT to 2, ItemKind.USENET to 1, ItemKind.WEB to 2), items.groupingBy { it.kind }.eachCount())
        val album = items.single { it.name == "Other Artist - Second Album" }
        assertEquals(ItemStatus.READY, album.status)
        assertEquals("cached", album.rawStatus)
        assertEquals(java.time.Instant.parse("2026-03-08T21:21:28Z").toEpochMilli(), album.createdAt)
        assertEquals(java.time.Instant.parse("2026-03-08T21:31:28Z").toEpochMilli(), album.completedAt)
        assertEquals(java.time.Instant.parse("2026-12-07T21:21:41Z").toEpochMilli(), album.expiresAt)
        val running = items.single { it.name == "Still.Downloading.2024" }
        assertEquals(ItemStatus.DOWNLOADING, running.status)
        assertEquals(0.4f, running.progress)

        val songs = cloud.files.files(PROFILE_A, album.id)
        assertEquals(listOf("01 Elsewhere.mp3", "02 Somewhere.flac"), songs.map { it.name })
        assertEquals("Other Artist - Second Album/01 Elsewhere.mp3", songs.first().path)
        assertEquals("audio/mpeg", songs.first().mimeType)
        assertTrue(songs.all { it.md5?.length == 32 && it.mediaClass == MediaClass.MUSIC })
        assertEquals(MediaClass.COMIC, cloud.files.files(PROFILE_A, items.single { it.name == "Comic Issue 7.cbz" }.id).single().mediaClass)
        assertEquals(MediaClass.OTHER, cloud.files.files(PROFILE_A, items.single { it.name == "Photo Archive.zip" }.id).single().mediaClass, "a plain ZIP is not called a comic")

        // Only finished files are offered to the sections.
        assertEquals(listOf("Documentary.2019.1080p.mkv"), cloud.files.filesOf(PROFILE_A, setOf(MediaClass.MOVIE, MediaClass.TV_SHOW)).map { it.name })

        // TorBox's own cache is skipped only when the user asks for a fresh list.
        assertTrue(env.torBox.bypassCache.all { it.isEmpty() })
        cloud.files.refresh(PROFILE_A, account.id, fresh = true)
        assertEquals(listOf("true", "true", "true"), env.torBox.bypassCache.takeLast(3))
    }

    @Test
    fun entriesAreFilteredSearchedAndSortedAcrossBothAccounts() {
        val cloud = env.open()
        env.connectRealDebrid(cloud)
        env.connectTorBox(cloud)
        cloud.files.refreshAll(PROFILE_A).values.forEach { it.getOrThrow() }
        fun names(query: CloudQuery) = cloud.files.items(PROFILE_A, query).map { it.name }

        assertEquals(14, names(CloudQuery()).size)
        assertEquals(5, names(CloudQuery(provider = ProviderId.TORBOX)).size)
        assertEquals(listOf("Documentary.2019"), names(CloudQuery(provider = ProviderId.TORBOX, kinds = setOf(ItemKind.USENET))))
        assertEquals(setOf("Photo Archive.zip", "Comic Issue 7.cbz"), names(CloudQuery(kinds = setOf(ItemKind.WEB))).toSet())
        assertEquals(setOf("Loose Song.ogg", "tool.pkg"), names(CloudQuery(kinds = setOf(ItemKind.LINK))).toSet())

        // Shelves: an entry is on a shelf when one of its files is.
        assertEquals(setOf("Test Artist - First Album (2021) [FLAC]", "Loose Song.ogg", "Other Artist - Second Album"), names(CloudQuery(mediaClass = MediaClass.MUSIC)).toSet())
        assertEquals(listOf("Some.Show.S01.1080p"), names(CloudQuery(mediaClass = MediaClass.TV_SHOW)))
        assertEquals(setOf("A.Holiday.Film.2020.1080p.mkv", "Documentary.2019", "Still.Downloading.2024"), names(CloudQuery(mediaClass = MediaClass.MOVIE)).toSet())
        assertEquals(setOf("Comics Pack", "Comic Issue 7.cbz"), names(CloudQuery(mediaClass = MediaClass.COMIC)).toSet())

        // Search looks at entry and file names, without minding capitals; wildcards are plain characters.
        assertEquals(listOf("Test Artist - First Album (2021) [FLAC]"), names(CloudQuery(search = "second LIGHT")))
        assertEquals(setOf("Other Artist - Second Album"), names(CloudQuery(search = "second album", provider = ProviderId.TORBOX)).toSet())
        assertEquals(emptyList(), names(CloudQuery(search = "100%_nothing")))

        // Sorting.
        assertEquals("A.Holiday.Film.2020.1080p.mkv", names(CloudQuery(sort = SortBy.NAME, descending = false)).first())
        assertEquals("tool.pkg", names(CloudQuery(sort = SortBy.NAME, descending = true)).first())
        val bySize = cloud.files.items(PROFILE_A, CloudQuery(sort = SortBy.SIZE, descending = true))
        assertEquals(bySize.map { it.sizeBytes }.sortedDescending(), bySize.map { it.sizeBytes })
        val byDate = cloud.files.items(PROFILE_A, CloudQuery(sort = SortBy.DATE, descending = true))
        assertEquals(byDate.map { it.createdAt ?: 0 }.sortedDescending(), byDate.map { it.createdAt ?: 0 })
        assertEquals(ItemStatus.DOWNLOADING, cloud.files.items(PROFILE_A, CloudQuery(sort = SortBy.STATUS, descending = false)).first().status)
    }

    @Test
    fun aRefreshThatFailsKeepsWhatWasListedAndChangesArriveAtTheNextOne() {
        val cloud = env.open()
        val account = env.connectRealDebrid(cloud)
        cloud.files.refresh(PROFILE_A, account.id)
        val before = cloud.files.items(PROFILE_A).map { it.name }.toSet()

        // A torrent finishes while Real-Debrid has trouble answering about torrents.
        env.realDebrid.busyFinished = true
        env.realDebrid.infoBroken = true
        val broken = assertFailsWith<CloudException> { cloud.files.refresh(PROFILE_A, account.id) }
        assertEquals(Reason.UNAVAILABLE, broken.reason)
        assertEquals(before, cloud.files.items(PROFILE_A).map { it.name }.toSet(), "a reading cut short is not a smaller account")
        assertEquals(AccountStatus.ERROR, cloud.accounts.account(PROFILE_A, account.id)!!.status)
        assertEquals(broken.message, cloud.accounts.account(PROFILE_A, account.id)!!.lastError)
        env.realDebrid.infoBroken = false

        // Locked account, too many requests: said in words, nothing lost.
        env.realDebrid.locked = true
        assertEquals(Reason.FORBIDDEN, assertFailsWith<CloudException> { cloud.files.refresh(PROFILE_A, account.id) }.reason)
        env.realDebrid.locked = false
        env.realDebrid.rateLimited.set(9)
        assertEquals(Reason.RATE_LIMITED, assertFailsWith<CloudException> { cloud.files.refresh(PROFILE_A, account.id) }.reason)
        assertEquals(7, env.realDebrid.rateLimited.get(), "asked twice, then stopped instead of hammering")
        env.realDebrid.rateLimited.set(0)
        assertEquals(before, cloud.files.items(PROFILE_A).map { it.name }.toSet())

        // Working again: the finished album has its file now, and the account is fine.
        cloud.files.refresh(PROFILE_A, account.id)
        val finished = cloud.files.items(PROFILE_A).single { it.name == "Other Artist - Second Album" }
        assertEquals(ItemStatus.READY, finished.status)
        assertEquals(listOf("01 Elsewhere.mp3"), cloud.files.files(PROFILE_A, finished.id).map { it.name })
        assertEquals(AccountStatus.CONNECTED, cloud.accounts.account(PROFILE_A, account.id)!!.status)
        assertNull(cloud.accounts.account(PROFILE_A, account.id)!!.lastError)

        // Something removed on Real-Debrid's own site leaves at the next reading, and the link that
        // duplicated one of its files is shown again as what it now is: a generated link on its own.
        env.realDebrid.deletedTorrents += "SHOW1"
        assertEquals(1, cloud.files.refresh(PROFILE_A, account.id).removed)
        val after = cloud.files.items(PROFILE_A).map { it.name }
        assertFalse("Some.Show.S01.1080p" in after)
        assertTrue("Some.Show.S01E01.mkv" in after)
    }

    @Test
    fun addressesAreMadeWhenNeededAndAreNeverWrittenDown() {
        val cloud = env.open()
        val realDebrid = env.connectRealDebrid(cloud)
        val torBox = env.connectTorBox(cloud)
        cloud.files.refreshAll(PROFILE_A).values.forEach { it.getOrThrow() }
        fun fetch(url: String): ByteArray = (URI(url).toURL().openConnection() as HttpURLConnection).inputStream.use { it.readAllBytes() }

        // A torrent's file: its Real-Debrid link is turned into an address; asked again, the address
        // Real-Debrid already made is used instead of making another record.
        val song = cloud.files.filesOf(PROFILE_A, setOf(MediaClass.MUSIC), accountId = realDebrid.id).first { it.name == "02 - Second Light.flac" }
        val first = cloud.files.resolve(PROFILE_A, song.id)
        assertEquals(song.sizeBytes, fetch(first.url).size.toLong())
        assertEquals(listOf("https://real-debrid.com/d/ALBUM1B"), env.realDebrid.forms.mapNotNull { it["link"] })
        cloud.files.resolve(PROFILE_A, song.id)
        assertEquals(1, env.realDebrid.forms.count { it.containsKey("link") }, "the generated link that now exists is reused")
        // Told that address stopped working, it asks for a new one.
        cloud.files.resolve(PROFILE_A, song.id, fresh = true)
        assertEquals(2, env.realDebrid.forms.count { it.containsKey("link") })

        // A generated link: looked up in the account's list.
        val loose = cloud.files.filesOf(PROFILE_A, setOf(MediaClass.MUSIC), accountId = realDebrid.id).single { it.name == "Loose Song.ogg" }
        assertEquals(loose.sizeBytes, fetch(cloud.files.resolve(PROFILE_A, loose.id).url).size.toLong())

        // TorBox: asked every time, with the key where TorBox wants it; the address that comes back has no key in it.
        val other = cloud.files.filesOf(PROFILE_A, setOf(MediaClass.MUSIC), accountId = torBox.id).first()
        val link = cloud.files.resolve(PROFILE_A, other.id)
        assertFalse(link.url.contains(StandInTorBox.API_KEY))
        assertEquals(other.sizeBytes, fetch(link.url).size.toLong())
        cloud.files.resolve(PROFILE_A, other.id)
        assertEquals(2, env.torBox.requests.count { it == "GET /v1/api/torrents/requestdl" }, "TorBox is asked every time")
        assertEquals("ResolvedLink(…)", link.toString(), "an address does not print itself")

        // A file that is not ready, a service without servers: the provider's own words, never the key or an address.
        env.torBox.noServers = true
        val unavailable = assertFailsWith<CloudException> { cloud.files.resolve(PROFILE_A, other.id) }
        assertEquals(Reason.UNAVAILABLE, unavailable.reason)
        assertTrue(unavailable.message!!.contains("no download servers"), unavailable.message)
        assertFalse(unavailable.message!!.contains(StandInTorBox.API_KEY) || unavailable.message!!.contains("127.0.0.1"))
        env.torBox.noServers = false
        env.torBox.close()
        val unreachable = assertFailsWith<CloudException> { cloud.files.resolve(PROFILE_A, other.id) }
        assertEquals("TorBox could not be reached.", unreachable.message)

        // Nothing of all this is in the database: no address, no key, no token.
        assertFalse(env.dataFolderContains("/files/"), "no download address is stored")
        env.secretTexts().forEach { assertFalse(env.dataFolderContains(it), it) }
    }

    @Test
    fun deletingFromTheCloudHappensOnlyWhenAskedAndOnlyThere() {
        val cloud = env.open()
        val realDebrid = env.connectRealDebrid(cloud)
        val torBox = env.connectTorBox(cloud)
        cloud.files.refreshAll(PROFILE_A).values.forEach { it.getOrThrow() }
        fun item(name: String, provider: ProviderId? = null) = cloud.files.items(PROFILE_A).single { it.name == name && (provider == null || it.provider == provider) }

        // Refreshing, resolving and disconnecting never delete anything at the provider.
        cloud.files.resolve(PROFILE_A, cloud.files.files(PROFILE_A, item("Some.Show.S01.1080p").id).first().id)
        assertTrue(env.realDebrid.requests.none { it.startsWith("DELETE") } && env.torBox.bodies.isEmpty())

        // Real-Debrid: a torrent, then a generated link.
        assertTrue(cloud.files.canDeleteFromCloud(item("Some.Show.S01.1080p")))
        cloud.files.deleteFromCloud(PROFILE_A, item("Some.Show.S01.1080p").id)
        assertEquals(listOf("SHOW1"), env.realDebrid.deletedTorrents.toList())
        assertTrue(cloud.files.items(PROFILE_A).none { it.name == "Some.Show.S01.1080p" })
        cloud.files.deleteFromCloud(PROFILE_A, item("Loose Song.ogg").id)
        assertEquals(listOf("DL2"), env.realDebrid.deletedDownloads.toList())
        assertEquals(2, env.realDebrid.requests.count { it.startsWith("DELETE") }, "exactly what was asked for")

        // TorBox: each kind with the request its documentation gives.
        cloud.files.deleteFromCloud(PROFILE_A, item("Other Artist - Second Album", ProviderId.TORBOX).id)
        cloud.files.deleteFromCloud(PROFILE_A, item("Documentary.2019").id)
        cloud.files.deleteFromCloud(PROFILE_A, item("Comic Issue 7.cbz").id)
        assertEquals(
            listOf("""{"torrent_id":101,"operation":"delete"}""", """{"usenet_id":"201","operation":"delete"}""", """{"webdl_id":302,"operation":"delete"}"""),
            env.torBox.bodies.toList(),
        )
        assertEquals(listOf(102L), env.torBox.torrents.map { it.id })
        assertEquals(emptyList(), env.torBox.usenet.toList())
        assertEquals(listOf(301L), env.torBox.web.map { it.id })
        assertEquals(setOf("Still.Downloading.2024", "Photo Archive.zip"), cloud.files.items(PROFILE_A, CloudQuery(provider = ProviderId.TORBOX)).map { it.name }.toSet())

        // Already gone at the provider: the entry simply leaves the list.
        env.realDebrid.deletedTorrents += "FILM1"
        cloud.files.deleteFromCloud(PROFILE_A, item("A.Holiday.Film.2020.1080p.mkv").id)
        assertTrue(cloud.files.items(PROFILE_A).none { it.name.startsWith("A.Holiday") })

        // Another profile cannot delete from an account that is not its own.
        val comics = item("Comics Pack")
        assertEquals(Reason.NOT_FOUND, assertFailsWith<CloudException> { cloud.files.deleteFromCloud(CloudTestEnvironment.PROFILE_B, comics.id) }.reason)
        assertFalse("COMICS1" in env.realDebrid.deletedTorrents)
        assertEquals(setOf(realDebrid.id, torBox.id), cloud.accounts.visibleTo(PROFILE_A).map { it.id }.toSet())
    }

    @Test
    fun filesAreSortedOntoShelvesByWhatTheyAre() {
        fun shelf(path: String, mime: String? = null) = MediaFileClassifier.classify(path, mime)
        // Video: an episode only when the path says so.
        assertEquals(MediaClass.TV_SHOW, shelf("Show/Some.Show.S01E02.1080p.mkv"))
        assertEquals(MediaClass.TV_SHOW, shelf("Show/Some Show - 2x05 - Title.mp4"))
        assertEquals(MediaClass.TV_SHOW, shelf("Show/Season 2/Episode Title.mkv"))
        assertEquals(MediaClass.TV_SHOW, shelf("Show/S03/03.avi"))
        assertEquals(MediaClass.MOVIE, shelf("A.Film.2019.1080p.x264.mkv"), "a year and a resolution are not an episode")
        assertEquals(MediaClass.MOVIE, shelf("Concert 1920x1080.mp4"), "nor is a picture size")
        assertEquals(MediaClass.MOVIE, shelf("Show.Name.101.mkv"), "an ambiguous number is not guessed at")
        // Audio and comics.
        for (name in listOf("a.mp3", "a.FLAC", "a.m4a", "a.aac", "a.wav", "a.ogg", "a.opus", "a.aiff", "a.alac")) assertEquals(MediaClass.MUSIC, shelf(name), name)
        for (name in listOf("v1.cbz", "v1.CBR", "v1.cb7")) assertEquals(MediaClass.COMIC, shelf(name), name)
        // The extension decides; the type is asked only when the extension says nothing.
        assertEquals(MediaClass.OTHER, shelf("archive.zip", "application/zip"))
        assertEquals(MediaClass.OTHER, shelf("cover.jpg", "audio/mpeg"), "a known extension is not overruled by a wrong type")
        assertEquals(MediaClass.MUSIC, shelf("recording", "audio/x-wav"))
        assertEquals(MediaClass.MOVIE, shelf("clip.bin2", "video/mp4"))
        assertEquals(MediaClass.COMIC, shelf("issue", "application/vnd.comicbook+zip"))
        assertEquals(MediaClass.OTHER, shelf("readme"))
        assertEquals(MediaClass.OTHER, shelf("Great Songs.pdf"), "a name that sounds like music is not music")
        // Things that could run are recognised, so they are never opened.
        for (name in listOf("x.app", "x.pkg", "x.command", "x.sh", "x.exe", "x.jar", "x.dmg")) assertTrue(MediaFileClassifier.isExecutable(name), name)
        assertFalse(MediaFileClassifier.isExecutable("x.mkv") || MediaFileClassifier.isExecutable("x.cbz"))
    }
}
