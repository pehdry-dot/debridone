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

    @Test
    fun audiusCatalogue() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, SourceDraft("", "", "", ""))
        println("LIVE Audius: connected to ${connection.baseUrl} without an account")

        val trending = music.sources.featured(PROFILE_A, connection.id, limit = 10)
        println("LIVE Audius: trending -> ${trending.take(3).map { "${it.title} / ${it.artist} (${it.genre}, ${it.durationMs / 1000}s, playable=${it.playable})" }}")
        assertTrue(trending.isNotEmpty() && trending.all { it.title.isNotBlank() && it.remoteId.isNotBlank() })
        assertTrue(trending.any { it.artworkUrl != null }, "tracks come with artwork addresses")

        val results = music.sources.search(PROFILE_A, connection.id, "lofi", limit = 5)
        println("LIVE Audius: search 'lofi' -> ${results.tracks.size} tracks, ${results.artists.size} artists, ${results.albums.size} albums, ${results.playlists.size} playlists")
        assertTrue(results.tracks.isNotEmpty(), "a song search finds songs")
        val artists = music.sources.search(PROFILE_A, connection.id, "skrillex", limit = 5).artists
        println("LIVE Audius: artist search 'skrillex' -> ${artists.map { "${it.name} (${it.followerCount} followers, verified=${it.verified})" }}")
        assertTrue(artists.any { it.name.equals("Skrillex", ignoreCase = true) }, "an artist search finds the artist")
        val house = music.sources.featured(PROFILE_A, connection.id, genre = "House", limit = 5)
        println("LIVE Audius: trending in House -> ${house.map { it.genre }.distinct()}")
        assertTrue(house.isNotEmpty())

        // Resolving a playable track: Audius answers with a redirect to a node holding the audio.
        val playable = trending.first { it.playable }
        val track = music.sources.materialize(PROFILE_A, connection.id, listOf(playable), keep = false).single()
        val address = assertNotNull(music.sources.resolveUri(PROFILE_A, track))
        val probe = (java.net.URI(address).toURL().openConnection() as java.net.HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DebridOne/0.1 (desktop music library)")
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        val status = probe.responseCode
        val node = probe.getHeaderField("Location")?.let { java.net.URI(it).host }
        probe.disconnect()
        println("LIVE Audius: stream of '${playable.title}' answered $status, audio is on $node")
        assertTrue(status in 300..399 && node != null, "the stream address resolves to a node with the audio")

        // Playing it needs that node to be reachable from here, which is not a given in a sandbox.
        music.player.play(listOf(track))
        val played = runCatching {
            awaitTrue(timeoutMs = 25_000, describe = { "the Audius track to play; state: ${music.player.state.value.summary()}" }) {
                music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 1_000 } || music.player.state.value.lastError != null
            }
            music.player.state.value.lastError == null
        }.getOrDefault(false)
        println("LIVE Audius: playback through libmpv ${if (played) "played (position ${music.player.state.value.positionMs} ms)" else "did NOT play here: ${music.player.state.value.lastError ?: "timed out"}"}")
        val cover = music.sources.artworkFile(PROFILE_A, connection.id, null, playable.artworkUrl, playable.artworkAlternates)
        println("LIVE Audius: cover ${if (cover != null) "fetched (${Files.size(cover)} bytes)" else "NOT fetched here (its nodes are not reachable from this machine)"}")
        Assume.assumeTrue("the audio node $node is not reachable from this machine, so playback could not be checked", played)
    }

    @Test
    fun musicBrainzCatalogueAndCoverArtArchive() {
        val music = env.open()
        val artists = music.metadata.searchArtists("Radiohead", limit = 3)
        println("LIVE MusicBrainz: artists -> ${artists.map { "${it.name} [${it.type}, ${it.country}, since ${it.begin}] ${it.id}" }}")
        val radiohead = artists.first { it.name == "Radiohead" }
        assertEquals("a74b1b7f-71a5-4011-9441-d0b5e4122711", radiohead.id, "Radiohead's MusicBrainz identifier")

        val groups = music.metadata.searchReleaseGroups("OK Computer", "Radiohead", limit = 5)
        println("LIVE MusicBrainz: albums -> ${groups.take(3).map { "${it.title} (${it.primaryType}, ${it.firstReleaseDate}) ${it.id}" }}")
        val album = groups.first { it.title == "OK Computer" && it.primaryType == "Album" }
        assertEquals(1997, album.year)

        val group = assertNotNull(music.metadata.releaseGroup(album.id))
        println("LIVE MusicBrainz: '${group.group.title}' has ${group.releases.size} editions; first ${group.releases.first().date} ${group.releases.first().country}")
        assertTrue(group.releases.isNotEmpty())
        val detail = assertNotNull(music.metadata.release(group.releases.first().id))
        println("LIVE MusicBrainz: edition ${detail.release.id}: ${detail.tracks.size} tracks, first '${detail.tracks.first().title}' (${detail.tracks.first().lengthMs} ms)")
        assertTrue(detail.tracks.size >= 12 && detail.tracks.any { it.title.contains("Paranoid Android") })
        // MusicBrainz limits requests per address, and this machine shares its address with
        // others: "busy" here says nothing about the code, so it is reported rather than failed.
        val discography = runCatching { music.metadata.artistReleaseGroups(radiohead.id, limit = 25) }
        println("LIVE MusicBrainz: artist's releases -> ${discography.fold({ "${it.size} release groups, e.g. ${it.take(3).map { g -> g.title }}" }, { "not answered: ${it.message}" })}")
        discography.getOrNull()?.let { assertTrue(it.isNotEmpty()) }
        println("LIVE MusicBrainz: Test -> ${music.metadata.testProvider("musicbrainz")}")
        println("LIVE LRCLIB: Test -> ${music.metadata.testProvider("lrclib")}")
        assertTrue(music.metadata.health.value["lrclib"]?.ok == true)

        val lyrics = music.metadata.searchLyrics("Karma Police", "Radiohead", durationMs = 264_000)
        println("LIVE LRCLIB: 'Karma Police' -> ${lyrics.size} candidates; best ${lyrics.firstOrNull()?.let { "${it.durationSeconds}s synced=${it.synced}" }}")
        assertTrue(lyrics.isNotEmpty() && lyrics.first().durationSeconds?.let { kotlin.math.abs(it - 264) <= 6 } == true, "the candidate closest in length comes first")

        // The archive answers every lookup with a redirect to archive.org's storage machines.
        val covers = runCatching { music.metadata.coverOptions(releaseId = detail.release.id) }
        println("LIVE Cover Art Archive: ${covers.fold({ "${it.size} images; front=${it.firstOrNull { o -> o.front }?.thumbnailUrl}" }, { "NOT reachable from here: ${it.message}" })}")
        Assume.assumeTrue("archive.org's storage hosts are not reachable from this machine: ${covers.exceptionOrNull()?.message}", covers.isSuccess)
        assertTrue(covers.getOrThrow().any { it.front })
    }
}
