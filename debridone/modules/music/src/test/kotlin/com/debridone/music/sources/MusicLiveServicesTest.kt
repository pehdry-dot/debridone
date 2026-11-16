package com.debridone.music.sources

import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.awaitTrue
import com.debridone.music.library.TrackFilter
import com.debridone.music.library.TrackInput
import com.debridone.music.metadata.MusicMetadataService
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.requireLibmpv
import com.debridone.music.summary
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * The same adapters as [MusicSourcesTest], against the real public services: the Navidrome and
 * Jellyfin projects' demo servers, MusicBrainz and LRCLIB. Runs only with `DEBRIDONE_LIVE_TESTS=1`.
 *
 * The demo accounts are the ones those projects publish for trying out clients.
 */
class MusicLiveServicesTest {
    private val env = MusicTestEnvironment()

    @BeforeTest
    fun onlyWhenAskedFor() {
        Assume.assumeTrue("live service tests are off (set DEBRIDONE_LIVE_TESTS=1)", System.getenv("DEBRIDONE_LIVE_TESTS") == "1")
    }

    @AfterTest
    fun tearDown() = env.close()

    private fun describe(music: MusicServices, sourceId: Long): String {
        val tracks = music.library.tracks(PROFILE_A, TrackFilter(sourceId = sourceId))
        return "${tracks.size} songs, ${music.library.albums(PROFILE_A).size} albums, ${music.library.artists(PROFILE_A).size} artists, " +
            "${tracks.count { it.artwork != null }} with a cover; formats ${tracks.mapNotNull { it.format }.groupingBy { it }.eachCount()}"
    }

    @Test
    fun navidromeDemoServer() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val wrong = runCatching { music.sources.add(PROFILE_A, SubsonicAdapter.ID, SourceDraft("", "https://demo.navidrome.org", "demo", "definitely-not-the-password")) }
        assertEquals("Wrong user name or password.", wrong.exceptionOrNull()?.message)

        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, SourceDraft("", "https://demo.navidrome.org", "demo", "demo"))
        println("LIVE Navidrome: connected as '${connection.name}'")
        val summary = music.sources.sync(PROFILE_A, connection.id)
        println("LIVE Navidrome: sync $summary; ${describe(music, connection.id)}")
        assertTrue(summary.total > 0, "the demo server has songs")
        val tracks = music.library.tracks(PROFILE_A, TrackFilter(sourceId = connection.id))
        assertTrue(tracks.all { it.title.isNotBlank() && it.durationMs > 0 })
        assertTrue(tracks.any { it.artwork != null && Files.isRegularFile(Path.of(it.artwork!!.filePath)) }, "covers were fetched")

        // Offline copy: the stream endpoint really serves the file with our sign-in.
        val track = tracks.minBy { it.fileSize.takeIf { size -> size > 0 } ?: Long.MAX_VALUE }
        assertEquals(1, music.sources.download(PROFILE_A, listOf(track.id)), music.sources.activity.value.lastDownloadError)
        val copy = Path.of(assertNotNull(music.library.track(PROFILE_A, track.id)!!.offlinePath))
        println("LIVE Navidrome: downloaded '${track.title}' (${track.format}), ${Files.size(copy)} bytes")
        assertTrue(Files.size(copy) > 10_000)

        // And it plays: first the downloaded copy, then (copy removed) straight from the server.
        music.player.play(listOf(music.library.track(PROFILE_A, track.id)!!))
        awaitTrue(timeoutMs = 20_000, describe = { "the downloaded copy to play: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 1_000 }
        }
        music.player.stop()
        music.sources.removeDownload(PROFILE_A, track.id)
        music.player.play(listOf(music.library.track(PROFILE_A, track.id)!!))
        val streamed = runCatching {
            awaitTrue(timeoutMs = 30_000, describe = { "the stream to play: ${music.player.state.value.summary()}" }) {
                music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 1_500 }
            }
        }
        println("LIVE Navidrome: streaming straight from the server through libmpv: " + if (streamed.isSuccess) "played" else "did not play here (${music.player.state.value.lastError?.message ?: streamed.exceptionOrNull()?.message})")
        music.player.stop()
    }

    @Test
    fun jellyfinDemoServer() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, JellyfinAdapter.ID, SourceDraft("", "https://demo.jellyfin.org/stable", "demo", ""))
        println("LIVE Jellyfin: connected as '${connection.name}'")
        assertTrue(music.sources.test(PROFILE_A, connection.id).isSuccess)
        val summary = music.sources.sync(PROFILE_A, connection.id)
        println("LIVE Jellyfin: sync $summary; ${describe(music, connection.id)}")
        assertTrue(summary.total > 0, "the demo server has songs")
        val tracks = music.library.tracks(PROFILE_A, TrackFilter(sourceId = connection.id))
        assertTrue(tracks.all { it.title.isNotBlank() })
        val track = tracks.filter { it.durationMs > 0 }.minBy { it.fileSize.takeIf { size -> size > 0 } ?: Long.MAX_VALUE }
        assertEquals(1, music.sources.download(PROFILE_A, listOf(track.id)), music.sources.activity.value.lastDownloadError)
        val copy = Path.of(assertNotNull(music.library.track(PROFILE_A, track.id)!!.offlinePath))
        println("LIVE Jellyfin: downloaded '${track.title}' (${track.format}), ${Files.size(copy)} bytes")
        assertTrue(Files.size(copy) > 10_000)
    }

    @Test
    fun musicBrainzAndLrclib() {
        val music = env.open()
        val id = music.library.upsert(PROFILE_A, TrackInput(remoteId = "live-1", sourceId = 999, title = "Yesterday", artist = "The Beatles", album = "Help!", durationMs = 125_000))

        val matches = music.metadata.findRecordings(PROFILE_A, id)
        println("LIVE MusicBrainz: ${matches.size} candidates; best: ${matches.firstOrNull()}")
        assertTrue(matches.isNotEmpty())
        val best = matches.first()
        assertEquals("Yesterday", best.title)
        assertTrue(best.artist!!.contains("Beatles"))
        assertTrue(best.score >= 90)

        val applied = music.metadata.apply(PROFILE_A, id, best, fetchCover = false)
        println("LIVE MusicBrainz: applied $applied -> ${music.library.track(PROFILE_A, id)!!.let { "${it.title} / ${it.artist} / ${it.album} / ${it.year} / track ${it.trackNo}" }}")

        val lyrics = assertNotNull(music.metadata.lyrics(PROFILE_A, id), "LRCLIB has lyrics for this song")
        println("LIVE LRCLIB: origin ${lyrics.origin}, ${lyrics.synced.size} timed lines, starts: ${lyrics.plain?.lineSequence()?.firstOrNull()}")
        assertEquals(MusicMetadataService.ORIGIN_LRCLIB, lyrics.origin)
        assertTrue(lyrics.plain!!.startsWith("Yesterday"))
        assertTrue(lyrics.synced.size > 5)
    }
}
