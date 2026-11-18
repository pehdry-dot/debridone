package com.debridone.cloud

import com.debridone.cloud.CloudTestEnvironment.Companion.PROFILE_A
import com.debridone.cloud.CloudTestEnvironment.Companion.PROFILE_B
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The download manager, fetching files of the stand-in accounts to a temporary folder: folders,
 * progress, interruptions and resuming, expired addresses, name conflicts, disk space, checksums,
 * restarts, limits, profiles.
 */
class DownloadManagerTest {
    private val env = CloudTestEnvironment()

    @AfterTest
    fun tearDown() = env.close()

    private class Fixture(val cloud: CloudServices, val realDebrid: CloudAccount, val torBox: CloudAccount)

    private fun connected(): Fixture {
        val cloud = env.open()
        val realDebrid = env.connectRealDebrid(cloud)
        val torBox = env.connectTorBox(cloud)
        cloud.files.refreshAll(PROFILE_A).values.forEach { it.getOrThrow() }
        return Fixture(cloud, realDebrid, torBox)
    }

    private fun CloudServices.file(name: String, provider: ProviderId = ProviderId.REAL_DEBRID): CloudFileRow =
        files.filesOf(PROFILE_A, MediaClass.entries.toSet()).first { it.name == name && it.provider == provider }

    private fun CloudServices.download(id: Long): Download = downloads.downloads.value.first { it.id == id }

    private fun CloudServices.awaitState(id: Long, state: DownloadState, timeoutMs: Long = 15_000): Download {
        awaitTrue(timeoutMs, { "download $id to be $state; it is ${download(id).let { "${it.state} ${it.receivedBytes}/${it.totalBytes} ${it.error}" }}" }) { download(id).state == state }
        return download(id)
    }

    private fun served(env: CloudTestEnvironment, name: String): ByteArray =
        (env.realDebrid.torrents.flatMap { it.files }.mapNotNull { it.file } + env.realDebrid.downloads.map { it.file } + (env.torBox.torrents + env.torBox.usenet + env.torBox.web).flatMap { it.files })
            .first { it.name == name }.bytes

    @Test
    fun filesGoToTheFolderOfTheirShelfAndAreCheckedWhereTheProviderGivesAChecksum() {
        val f = connected()
        val song = f.cloud.file("01 - Opening.mp3")
        val episode = f.cloud.file("Some.Show.S01E02.mkv")
        val comic = f.cloud.file("Vol 01.cbz")
        val film = f.cloud.file("A.Holiday.Film.2020.1080p.mkv")
        val loose = f.cloud.file("Loose Song.ogg")
        val other = f.cloud.file("notes.txt")
        val torBoxSong = f.cloud.file("02 Somewhere.flac", ProviderId.TORBOX)

        val result = f.cloud.downloads.enqueue(PROFILE_A, listOf(song, episode, comic, film, loose, other, torBoxSong).map { it.id })
        assertEquals(emptyList(), result.problems)
        assertEquals(7, result.queued.size)
        val done = result.queued.map { f.cloud.awaitState(it.id, DownloadState.DONE) }

        val root = env.downloadFolder
        assertEquals(
            listOf(
                root.resolve("Music/Test Artist - First Album (2021) [FLAC]/01 - Opening.mp3"),
                root.resolve("Video/Some.Show.S01.1080p/Some.Show.S01E02.mkv"),
                root.resolve("Manga/Comics Pack/Vol 01.cbz"),
                // A torrent that is one file: no folder of its own is invented around it.
                root.resolve("Video/A.Holiday.Film.2020.1080p.mkv"),
                root.resolve("Music/Loose Song.ogg"),
                root.resolve("Comics Pack/notes.txt"),
                root.resolve("Music/Other Artist - Second Album/02 Somewhere.flac"),
            ).map { it.toString() },
            done.map { it.destination },
        )
        done.forEach { download ->
            assertTrue(download.fileExists)
            assertTrue(Files.readAllBytes(Paths.get(download.destination)).contentEquals(served(env, download.fileName)), download.fileName)
            assertEquals(download.totalBytes, download.receivedBytes)
            assertFalse(Files.exists(Paths.get(download.destination + ".part")))
            assertNotNull(download.finishedAt)
        }
        // Real-Debrid gives no checksum; TorBox does, and it matched.
        assertNull(done.first().verified)
        assertEquals(true, done.last().verified)
        assertEquals(done.first().id, f.cloud.downloads.localCopy(PROFILE_A, song)?.id)

        // Asking for the same file again says it is already there.
        val again = f.cloud.downloads.enqueue(PROFILE_A, listOf(song.id))
        assertEquals(listOf("01 - Opening.mp3 is already downloaded."), again.problems)

        // The profile can choose its folders, per shelf.
        val music = env.root.resolve("My Music Folder")
        f.cloud.downloads.settings.setFolder(PROFILE_A, MediaClass.MUSIC, music)
        val second = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("02 - Second Light.flac").id)).queued.single()
        assertEquals(music.resolve("Test Artist - First Album (2021) [FLAC]/02 - Second Light.flac").toString(), f.cloud.awaitState(second.id, DownloadState.DONE).destination)
        // And one download can be sent somewhere else altogether.
        val elsewhere = env.root.resolve("Elsewhere")
        val third = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("03 - Third Wave.m4a").id), folder = elsewhere).queued.single()
        assertTrue(f.cloud.awaitState(third.id, DownloadState.DONE).destination.startsWith(elsewhere.toString()))

        // No address, key or token was written down along the way.
        assertFalse(env.dataFolderContains("/files/"))
        env.secretTexts().forEach { assertFalse(env.dataFolderContains(it), it) }
    }

    @Test
    fun aDroppedConnectionIsContinuedFromWhereItStopped() {
        val f = connected()
        val episode = f.cloud.file("Some.Show.S01E01.mkv")
        env.realDebrid.dropAfterBytes.set(16 * 1024)
        env.realDebrid.dropTimes.set(2)

        val id = f.cloud.downloads.enqueue(PROFILE_A, listOf(episode.id)).queued.single().id
        val done = f.cloud.awaitState(id, DownloadState.DONE)
        assertTrue(Files.readAllBytes(Paths.get(done.destination)).contentEquals(served(env, "Some.Show.S01E01.mkv")))
        assertEquals(listOf("", "bytes=16384-", "bytes=32768-"), env.realDebrid.ranges.toList(), "each new attempt asks only for what is missing")

        // A server that cannot continue: the download starts over rather than gluing wrong halves together.
        env.realDebrid.ranges.clear()
        env.realDebrid.ignoreRanges = true
        env.realDebrid.dropTimes.set(1)
        val film = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("A.Holiday.Film.2020.1080p.mkv").id)).queued.single().id
        val whole = f.cloud.awaitState(film, DownloadState.DONE)
        assertTrue(Files.readAllBytes(Paths.get(whole.destination)).contentEquals(served(env, "A.Holiday.Film.2020.1080p.mkv")))
        assertEquals(listOf("", "bytes=16384-"), env.realDebrid.ranges.toList())

        // A connection that never comes back: after a few tries the download is marked failed, with words, and can be tried again.
        env.realDebrid.ignoreRanges = false
        env.realDebrid.dropAfterBytes.set(0)
        env.realDebrid.dropTimes.set(1_000)
        val comic = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Vol 02.cbz").id)).queued.single().id
        val failed = f.cloud.awaitState(comic, DownloadState.FAILED)
        assertTrue(failed.error!!.contains("connection was lost"), failed.error)
        env.realDebrid.dropTimes.set(0)
        f.cloud.downloads.retry(comic)
        assertTrue(f.cloud.awaitState(comic, DownloadState.DONE).fileExists)
    }

    @Test
    fun pauseKeepsWhatWasFetchedResumeContinuesAndCancelThrowsItAway() {
        val f = connected()
        env.realDebrid.slowMs = 60
        val id = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Some.Show.S01E01.mkv").id)).queued.single().id
        awaitTrue(describe = { "some bytes to arrive" }) { f.cloud.download(id).receivedBytes > 0 }
        assertEquals(DownloadState.RUNNING, f.cloud.download(id).state)

        f.cloud.downloads.pause(id)
        val paused = f.cloud.awaitState(id, DownloadState.PAUSED)
        val part = Paths.get(paused.destination + ".part")
        awaitTrue(describe = { "the transfer to stop" }) { val size = Files.size(part); Thread.sleep(150); Files.size(part) == size }
        val kept = Files.size(part)
        assertTrue(kept in 1 until paused.totalBytes, "part of the file is kept: $kept of ${paused.totalBytes}")
        assertFalse(Files.exists(Paths.get(paused.destination)))

        env.realDebrid.slowMs = 0
        f.cloud.downloads.resume(id)
        val done = f.cloud.awaitState(id, DownloadState.DONE)
        assertTrue(Files.readAllBytes(Paths.get(done.destination)).contentEquals(served(env, "Some.Show.S01E01.mkv")))
        assertEquals("bytes=$kept-", env.realDebrid.ranges.last(), "continued, not started again")

        // Cancel: the partial file goes, the record stays as history and can be retried.
        env.realDebrid.slowMs = 60
        val second = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Some.Show.S01E02.mkv").id)).queued.single().id
        awaitTrue(describe = { "some bytes to arrive" }) { f.cloud.download(second).receivedBytes > 0 }
        f.cloud.downloads.cancel(second)
        val cancelled = f.cloud.awaitState(second, DownloadState.CANCELLED)
        awaitTrue(describe = { "the partial file to be gone" }) { !Files.exists(Paths.get(cancelled.destination + ".part")) }
        assertFalse(Files.exists(Paths.get(cancelled.destination)))
        env.realDebrid.slowMs = 0
        f.cloud.downloads.retry(second)
        assertTrue(f.cloud.awaitState(second, DownloadState.DONE).fileExists)
    }

    @Test
    fun anAddressThatExpiredIsReplacedByAFreshOne() {
        val f = connected()
        // TorBox: the first two addresses it hands out no longer work when they are used.
        env.torBox.bornExpired.set(2)
        val id = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("01 Elsewhere.mp3", ProviderId.TORBOX).id)).queued.single().id
        assertTrue(f.cloud.awaitState(id, DownloadState.DONE).fileExists)
        assertEquals(3, env.torBox.requests.count { it == "GET /v1/api/torrents/requestdl" })

        // Real-Debrid: the address of the existing generated link is stale; a new one is made by unrestricting again.
        env.realDebrid.expireAddresses()
        env.realDebrid.bornExpired.set(1_000)
        val episode = f.cloud.file("Some.Show.S01E01.mkv")
        val stale = f.cloud.downloads.enqueue(PROFILE_A, listOf(episode.id)).queued.single().id
        val failed = f.cloud.awaitState(stale, DownloadState.FAILED)
        assertEquals("The download address is no longer valid, and a new one did not work either.", failed.error)
        assertTrue(env.realDebrid.forms.count { it["link"] == "https://real-debrid.com/d/SHOW1A" } >= 1, "it did ask for new ones")
        env.realDebrid.bornExpired.set(0)
        f.cloud.downloads.retry(stale)
        assertTrue(f.cloud.awaitState(stale, DownloadState.DONE).fileExists)

        // A busy download server is waited out.
        env.realDebrid.busyTimes.set(2)
        val film = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("A.Holiday.Film.2020.1080p.mkv").id)).queued.single().id
        assertTrue(f.cloud.awaitState(film, DownloadState.DONE).fileExists)
    }

    @Test
    fun aNameThatIsTakenIsNeverWrittenOverUnlessTheProfileChoseThat() {
        val f = connected()
        val song = f.cloud.file("01 - Opening.mp3")
        val target = env.downloadFolder.resolve("Music/Test Artist - First Album (2021) [FLAC]/01 - Opening.mp3")
        Files.createDirectories(target.parent)
        Files.write(target, "someone else's file".toByteArray())

        // Default: keep both; the new file gets a number.
        val kept = f.cloud.awaitState(f.cloud.downloads.enqueue(PROFILE_A, listOf(song.id)).queued.single().id, DownloadState.DONE)
        assertEquals(target.resolveSibling("01 - Opening (2).mp3").toString(), kept.destination)
        assertEquals("someone else's file", String(Files.readAllBytes(target)))

        // Skip: nothing is queued, and the reason is given.
        f.cloud.downloads.deleteLocalCopy(kept.id)
        f.cloud.downloads.settings.setConflictPolicy(PROFILE_A, ConflictPolicy.SKIP)
        val skipped = f.cloud.downloads.enqueue(PROFILE_A, listOf(song.id))
        assertEquals(emptyList(), skipped.queued)
        assertTrue(skipped.problems.single().contains("a file of that name is already in"), skipped.problems.toString())
        assertEquals("someone else's file", String(Files.readAllBytes(target)))

        // Replace: only when chosen.
        f.cloud.downloads.settings.setConflictPolicy(PROFILE_A, ConflictPolicy.REPLACE)
        val replaced = f.cloud.awaitState(f.cloud.downloads.enqueue(PROFILE_A, listOf(song.id)).queued.single().id, DownloadState.DONE)
        assertEquals(target.toString(), replaced.destination)
        assertTrue(Files.readAllBytes(target).contentEquals(served(env, "01 - Opening.mp3")))

        // Names from a provider are made safe: they cannot leave the folder or hide.
        assertEquals("_", DownloadManager.safeSegment(".."))
        assertEquals("_", DownloadManager.safeSegment("."))
        assertEquals("a_b_c", DownloadManager.safeSegment("a/b\\c"))
        assertEquals("etc_passwd", DownloadManager.safeSegment("  etc:passwd. "))
        assertEquals("", DownloadManager.safeSegment("   "))
        assertEquals(180, DownloadManager.safeSegment("x".repeat(500)).length)
    }

    @Test
    fun aDownloadThatDoesNotFitOnTheDiskIsRefusedBeforeItStarts() {
        val f = connected()
        val episode = f.cloud.file("Some.Show.S01E01.mkv")
        // 600 MB free, 500 MB to be kept free (the default): a file of any size fits only if 500 MB remain.
        env.freeBytes = 500L * 1024 * 1024 + episode.sizeBytes - 1
        val refused = f.cloud.downloads.enqueue(PROFILE_A, listOf(episode.id))
        assertEquals(emptyList(), refused.queued)
        assertTrue(refused.problems.single().contains("not enough free disk space"), refused.problems.toString())
        assertFalse(env.realDebrid.requests.any { it == "GET /files" }, "nothing was fetched")

        // Several files are counted together.
        env.freeBytes = 500L * 1024 * 1024 + episode.sizeBytes + 10
        val two = f.cloud.downloads.enqueue(PROFILE_A, listOf(episode.id, f.cloud.file("Some.Show.S01E02.mkv").id))
        assertEquals(1, two.queued.size)
        assertEquals(1, two.problems.size)
        f.cloud.awaitState(two.queued.single().id, DownloadState.DONE)

        // The warning can be switched off.
        f.cloud.downloads.settings.setSpaceReserveMb(PROFILE_A, 0)
        env.freeBytes = 1
        assertEquals(1, f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Some.Show.S01E02.mkv").id)).queued.size)
    }

    @Test
    fun aFileThatDoesNotMatchItsChecksumIsNotKept() {
        val f = connected()
        env.torBox.wrongChecksumFor = 101L to 0
        f.cloud.files.refresh(PROFILE_A, f.torBox.id, fresh = true)
        val id = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("01 Elsewhere.mp3", ProviderId.TORBOX).id)).queued.single().id
        val failed = f.cloud.awaitState(id, DownloadState.FAILED)
        assertTrue(failed.error!!.contains("did not match the checksum"), failed.error)
        assertEquals(false, failed.verified)
        assertFalse(Files.exists(Paths.get(failed.destination)) || Files.exists(Paths.get(failed.destination + ".part")))
    }

    @Test
    fun theQueueAndTheHistorySurviveARestartAndUnfinishedDownloadsContinue() {
        var f = connected()
        val finished = f.cloud.awaitState(f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("01 - Opening.mp3").id)).queued.single().id, DownloadState.DONE)
        // One at a time, so that the second one waits in the queue.
        f.cloud.downloads.settings.setMaxConcurrent(PROFILE_A, 1)
        env.realDebrid.slowMs = 60
        val running = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Some.Show.S01E01.mkv").id)).queued.single().id
        awaitTrue(describe = { "some bytes to arrive" }) { f.cloud.download(running).receivedBytes > 0 }
        val paused = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Vol 01.cbz").id)).queued.single().id
        f.cloud.downloads.pause(paused)

        // The application quits in the middle of a transfer.
        env.realDebrid.slowMs = 0
        val cloud = env.open()
        assertEquals(setOf(finished.id, running, paused), cloud.downloads.forProfile(PROFILE_A).map { it.id }.toSet())
        assertTrue(cloud.download(finished.id).fileExists, "the history is still there, with where the file is")
        assertEquals(DownloadState.PAUSED, cloud.download(paused).state, "what was paused stays paused")
        val continued = cloud.awaitState(running, DownloadState.DONE)
        assertTrue(Files.readAllBytes(Paths.get(continued.destination)).contentEquals(served(env, "Some.Show.S01E01.mkv")))
        assertTrue(env.realDebrid.ranges.last().startsWith("bytes=") && env.realDebrid.ranges.last() != "bytes=0-", "it went on from the partial file: ${env.realDebrid.ranges}")

        // Deleting the local copy touches this computer only; the record stays, the cloud is untouched.
        assertTrue(cloud.downloads.deleteLocalCopy(finished.id))
        assertFalse(Files.exists(Paths.get(finished.destination)))
        assertFalse(cloud.download(finished.id).fileExists)
        assertNotNull(cloud.download(finished.id).fileDeletedAt)
        assertTrue(env.realDebrid.requests.none { it.startsWith("DELETE") })
        // A file removed behind DebridOne's back is noticed.
        Files.delete(Paths.get(continued.destination))
        assertFalse(cloud.download(running).fileExists)
        // Clearing the history removes records, never files or anything in the cloud.
        cloud.downloads.clearHistory(PROFILE_A)
        assertEquals(listOf(paused), cloud.downloads.forProfile(PROFILE_A).map { it.id })
        f = Fixture(cloud, f.realDebrid, f.torBox)
        assertEquals(9, f.cloud.files.items(PROFILE_A, CloudQuery(provider = ProviderId.REAL_DEBRID)).size)
    }

    @Test
    fun onlySoManyDownloadsRunAtOnce() {
        val f = connected()
        f.cloud.downloads.settings.setMaxConcurrent(PROFILE_A, 2)
        env.realDebrid.slowMs = 40
        val ids = f.cloud.downloads.enqueue(PROFILE_A, listOf("01 - Opening.mp3", "02 - Second Light.flac", "03 - Third Wave.m4a", "Vol 01.cbz", "Vol 02.cbz").map { f.cloud.file(it).id }).queued.map { it.id }
        assertEquals(5, ids.size)
        var most = 0
        awaitTrue(30_000, { "all five to finish" }) {
            val states = f.cloud.downloads.forProfile(PROFILE_A).map { it.state }
            most = maxOf(most, states.count { it == DownloadState.RUNNING })
            states.all { it == DownloadState.DONE }
        }
        assertEquals(2, most, "never more than the limit, and the limit was used")
        assertTrue(f.cloud.download(ids.first()).bytesPerSecond == 0L, "a finished download has no speed")
    }

    @Test
    fun speedAndTimeLeftAreReportedWhileADownloadRuns() {
        val f = connected()
        env.realDebrid.slowMs = 300
        val id = f.cloud.downloads.enqueue(PROFILE_A, listOf(f.cloud.file("Some.Show.S01E01.mkv").id)).queued.single().id
        awaitTrue(describe = { "a speed to be measured" }) { f.cloud.download(id).let { it.state == DownloadState.RUNNING && it.bytesPerSecond > 0 } }
        val running = f.cloud.download(id)
        assertTrue(running.fraction!! > 0f && running.fraction!! < 1f)
        assertNotNull(running.secondsLeft)
        env.realDebrid.slowMs = 0
        assertNull(f.cloud.awaitState(id, DownloadState.DONE).secondsLeft)
    }

    @Test
    fun eachProfileHasItsOwnQueueSettingsAndHistory() {
        val cloud = env.open()
        val shared = env.connectTorBox(cloud, PROFILE_A, shared = true)
        cloud.files.refresh(PROFILE_A, shared.id)
        val song = cloud.files.filesOf(PROFILE_B, setOf(MediaClass.MUSIC)).first()
        val theirs: Path = env.root.resolve("Second profile")
        cloud.downloads.settings.setFolder(PROFILE_B, null, theirs)
        cloud.downloads.settings.setMaxConcurrent(PROFILE_B, 1)

        val a = cloud.awaitState(cloud.downloads.enqueue(PROFILE_A, listOf(song.id)).queued.single().id, DownloadState.DONE)
        val b = cloud.awaitState(cloud.downloads.enqueue(PROFILE_B, listOf(song.id)).queued.single().id, DownloadState.DONE)
        assertTrue(a.destination.startsWith(env.downloadFolder.toString()))
        assertTrue(b.destination.startsWith(theirs.resolve("Music").toString()), b.destination)
        assertEquals(listOf(a.id), cloud.downloads.forProfile(PROFILE_A).map { it.id })
        assertEquals(listOf(b.id), cloud.downloads.forProfile(PROFILE_B).map { it.id })
        assertEquals(3, cloud.downloads.settings.maxConcurrent(PROFILE_A))
        assertNull(cloud.downloads.localCopy(PROFILE_A, song)?.takeIf { it.id == b.id })

        // Deleting a profile removes its queue, history and settings; the files it downloaded stay where the user has them.
        cloud.deleteProfile(PROFILE_B)
        assertEquals(emptyList(), cloud.downloads.forProfile(PROFILE_B))
        assertTrue(Files.exists(Paths.get(b.destination)))
        assertEquals(env.downloadFolder, cloud.downloads.settings.defaultFolder(PROFILE_B))
        assertEquals(listOf(a.id), cloud.downloads.forProfile(PROFILE_A).map { it.id })

        // Disconnecting an account stops its unfinished transfers and keeps finished files and records.
        env.torBox.slowMs = 60
        val running = cloud.downloads.enqueue(PROFILE_A, listOf(cloud.files.filesOf(PROFILE_A, setOf(MediaClass.MOVIE)).first().id)).queued.single().id
        awaitTrue(describe = { "some bytes to arrive" }) { cloud.download(running).receivedBytes > 0 }
        cloud.disconnect(PROFILE_A, shared.id)
        assertEquals("The TorBox account was disconnected.", cloud.awaitState(running, DownloadState.FAILED).error)
        assertTrue(cloud.download(a.id).fileExists)
        assertEquals(emptyList(), cloud.files.items(PROFILE_A))
    }
}
