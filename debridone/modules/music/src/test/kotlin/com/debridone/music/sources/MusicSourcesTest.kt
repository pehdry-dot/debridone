package com.debridone.music.sources

import com.debridone.module.PluginActionResult
import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.awaitTrue
import com.debridone.music.library.TrackFilter
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.requireLibmpv
import com.debridone.music.summary
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The source adapters against local stand-in servers, end to end through the library and player. */
class MusicSourcesTest {
    private val env = MusicTestEnvironment()
    private val servers = StandInServers()

    @AfterTest
    fun tearDown() {
        env.close()
        servers.close()
    }

    private fun subsonicDraft(password: String = StandInServers.SUBSONIC_PASSWORD, url: String = servers.subsonicUrl) =
        SourceDraft(name = "", baseUrl = url, username = StandInServers.SUBSONIC_USER, secret = password)

    private fun jellyfinDraft(password: String = StandInServers.JELLYFIN_PASSWORD) =
        SourceDraft(name = "Living room", baseUrl = servers.jellyfinUrl, username = StandInServers.JELLYFIN_USER, secret = password)

    private fun MusicServices.remoteTitles(sourceId: Long, profileId: Long = PROFILE_A) =
        library.tracks(profileId, TrackFilter(sourceId = sourceId)).map { it.title }.sorted()

    @Test
    fun aSourceIsOnlySavedWhenSignInWorks() {
        val music = env.open()

        val wrong = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft(password = "not the password")) }
        assertEquals("Wrong user name or password.", wrong.message)

        val unused = ServerSocket(0).use { it.localPort }
        val down = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft(url = "http://127.0.0.1:$unused")) }
        assertTrue(down.message!!.startsWith("The server could not be reached"), down.message)

        val notSubsonic = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft(url = "${servers.baseUrl}/radio/page.html")) }
        assertTrue(notSubsonic.message!!.contains("Subsonic"), notSubsonic.message)

        val notJellyfin = assertFailsWith<SourceException> {
            music.sources.add(PROFILE_A, JellyfinAdapter.ID, SourceDraft("", "${servers.baseUrl}/radio", "someone", "x"))
        }
        assertTrue(notJellyfin.message!!.contains("Jellyfin"), notJellyfin.message)

        assertFailsWith<SourceException> { music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft(url = "https://user:pw@example.invalid")) }
        assertFailsWith<SourceException> { music.sources.add(PROFILE_A, "spotify", subsonicDraft()) }

        assertTrue(music.sources.connections(PROFILE_A).isEmpty(), "nothing may be saved for a failed sign-in")
        assertEquals(0, music.library.trackCount(PROFILE_A))
    }

    @Test
    fun subsonicSyncBringsSongsAlbumsAndCovers() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        assertEquals("navidrome", connection.name)
        assertEquals(true, connection.lastTestOk)

        // The password is in the secret store and nowhere in Music's own files.
        assertEquals(StandInServers.SUBSONIC_PASSWORD, env.storedSecret(PROFILE_A, "source-${connection.id}"))
        assertFalse(env.dataFolderContains(StandInServers.SUBSONIC_PASSWORD), "the password must not be written to Music's data folder")

        val summary = music.sources.sync(PROFILE_A, connection.id)
        assertEquals(SyncSummary(total = 4, added = 4, removed = 0, artworkFetched = 2), summary)
        assertEquals(listOf("Elsewhere", "Opening", "Second Light", "Sixth Sense"), music.remoteTitles(connection.id))

        val albums = music.library.albums(PROFILE_A)
        assertEquals(listOf("First Album", "Second Album"), albums.map { it.title })
        assertEquals(listOf("Test Artist", "Other Artist"), albums.map { it.artist })
        assertTrue(albums.all { it.artwork != null && Files.isRegularFile(Path.of(it.artwork!!.filePath)) }, "covers fetched from the server")
        val opening = music.library.tracks(PROFILE_A).first { it.title == "Opening" }
        assertFalse(opening.isLocal)
        assertNull(opening.path)
        assertEquals("MP3", opening.format)
        assertEquals(2021, opening.year)
        assertEquals("Electronic", opening.genre)
        assertEquals(2_000, opening.durationMs)
        assertNotNull(opening.artwork)

        // A second sync changes nothing and fetches no cover again.
        assertEquals(SyncSummary(4, 0, 0, 0), music.sources.sync(PROFILE_A, connection.id))

        // The server lost a song and gained one.
        servers.albums[0].songs.removeIf { it.title == "Sixth Sense" }
        servers.albums[1].songs += StandInServers.Song("s-10", com.debridone.music.Fixtures.flac, "Late Arrival", 2, "flac")
        assertEquals(SyncSummary(4, 1, 1, 0), music.sources.sync(PROFILE_A, connection.id))
        assertEquals(listOf("Elsewhere", "Late Arrival", "Opening", "Second Light"), music.remoteTitles(connection.id))
        assertNotNull(music.sources.connection(PROFILE_A, connection.id)!!.lastSyncAt)
    }

    @Test
    fun aRemoteSongPlaysThroughTheRealEngine() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        music.sources.sync(PROFILE_A, connection.id)
        val track = music.library.tracks(PROFILE_A).first { it.title == "Second Light" }
        servers.accepted.clear()

        music.player.play(listOf(track))
        awaitTrue(describe = { "the stream to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        awaitTrue(describe = { "the stream to finish; state: ${music.player.state.value.summary()}" }) { music.player.state.value.status == PlaybackStatus.ENDED }
        assertNull(music.player.state.value.lastError)
        assertTrue(servers.accepted.any { it.endsWith("/rest/stream.view") }, "the engine fetched the stream with valid credentials")
        awaitTrue(describe = { "the play to be recorded" }) { music.library.track(PROFILE_A, track.id)!!.playCount == 1 }

        // The saved session holds track ids only, never the address with its credentials.
        music.player.pause()
        Thread.sleep(2_500)
        assertFalse(env.dataFolderContains("stream.view"), "a stream address must not be persisted")
    }

    @Test
    fun turningASourceOffKeepsItsSongsButDoesNotPlayThem() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        music.sources.sync(PROFILE_A, connection.id)
        val track = music.library.tracks(PROFILE_A).first()
        assertTrue(music.sources.resolveUri(PROFILE_A, track)!!.contains("/rest/stream.view?"))

        music.sources.setEnabled(PROFILE_A, connection.id, false)
        assertEquals(4, music.library.trackCount(PROFILE_A))
        assertTrue(music.library.tracks(PROFILE_A).all { it.missing })
        assertNull(music.sources.resolveUri(PROFILE_A, music.library.track(PROFILE_A, track.id)!!))
        assertFailsWith<SourceException> { music.sources.sync(PROFILE_A, connection.id) }

        music.sources.setEnabled(PROFILE_A, connection.id, true)
        assertTrue(music.library.tracks(PROFILE_A).none { it.missing })
        assertNotNull(music.sources.resolveUri(PROFILE_A, music.library.track(PROFILE_A, track.id)!!))
    }

    @Test
    fun offlineCopiesAndRemovingASource() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        music.sources.sync(PROFILE_A, connection.id)
        val tracks = music.library.tracks(PROFILE_A)
        val flac = tracks.first { it.title == "Second Light" }

        assertEquals(2, music.sources.download(PROFILE_A, listOf(flac.id, tracks.first { it.title == "Opening" }.id)))
        val offline = music.library.track(PROFILE_A, flac.id)!!
        val copy = Path.of(assertNotNull(offline.offlinePath))
        assertTrue(copy.fileName.toString().endsWith(".flac"))
        assertEquals(Files.size(com.debridone.music.Fixtures.flac), Files.size(copy))
        assertEquals(copy.toString(), music.sources.resolveUri(PROFILE_A, offline), "an offline copy is played instead of the stream")
        assertEquals(2, music.library.tracks(PROFILE_A, TrackFilter(sourceId = connection.id, offlineOnly = true)).size)
        assertEquals(0, music.sources.download(PROFILE_A, listOf(flac.id)), "already downloaded")

        music.sources.removeDownload(PROFILE_A, flac.id)
        assertFalse(Files.exists(copy))
        assertNull(music.library.track(PROFILE_A, flac.id)!!.offlinePath)

        val otherCopy = Path.of(music.library.tracks(PROFILE_A).first { it.title == "Opening" }.offlinePath!!)
        music.sources.remove(PROFILE_A, connection.id)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())
        assertEquals(0, music.library.trackCount(PROFILE_A))
        assertTrue(music.library.albums(PROFILE_A).isEmpty())
        assertNull(env.storedSecret(PROFILE_A, "source-${connection.id}"), "the stored password is deleted with the connection")
        assertFalse(Files.exists(otherCopy), "offline copies are deleted with the connection")
    }

    @Test
    fun jellyfinSignsInWithATokenAndSyncs() {
        val music = env.open()
        val wrong = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, JellyfinAdapter.ID, jellyfinDraft(password = "nope")) }
        assertEquals("Wrong user name or password.", wrong.message)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())

        val connection = music.sources.add(PROFILE_A, JellyfinAdapter.ID, jellyfinDraft())
        assertEquals("Living room", connection.name)
        assertEquals(StandInServers.JELLYFIN_USER_ID, connection.config[JellyfinAdapter.CONFIG_USER_ID])
        // Only the access token is kept; the password is not stored anywhere.
        assertEquals(StandInServers.JELLYFIN_TOKEN, env.storedSecret(PROFILE_A, "source-${connection.id}"))
        assertFalse(env.dataFolderContains(StandInServers.JELLYFIN_PASSWORD))
        assertFalse(env.dataFolderContains(StandInServers.JELLYFIN_TOKEN))

        assertTrue(music.sources.test(PROFILE_A, connection.id).isSuccess)
        assertEquals(SyncSummary(4, 4, 0, 2), music.sources.sync(PROFILE_A, connection.id))
        val opening = music.library.tracks(PROFILE_A).first { it.title == "Opening" }
        assertEquals("Test Artist", opening.artist)
        assertEquals("First Album", opening.album)
        assertEquals(1, opening.trackNo)
        assertEquals(2_000, opening.durationMs)
        assertNotNull(opening.artwork)
        val url = music.sources.resolveUri(PROFILE_A, opening)!!
        assertTrue(url.startsWith("${servers.jellyfinUrl}/Audio/s-1/stream?static=true&api_key="), MusicHttp.redact(url))
    }

    @Test
    fun aRejectedSavedSignInIsReported() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        // Someone changes the password on the server: the stored one stops working.
        val store = com.debridone.platform.secrets.FileSecretStore(env.root.resolve("secrets"))
        com.debridone.platform.secrets.ScopedSecretStore(store, "module.music.profile.$PROFILE_A").put("source-${connection.id}", "stale password")

        val result = music.sources.test(PROFILE_A, connection.id)
        assertEquals("Wrong user name or password.", result.exceptionOrNull()?.message)
        val recorded = music.sources.connection(PROFILE_A, connection.id)!!
        assertEquals(false, recorded.lastTestOk)
        assertEquals("Wrong user name or password.", recorded.lastError)
        assertFailsWith<SourceException> { music.sources.sync(PROFILE_A, connection.id) }
    }

    @Test
    fun connectionsBelongToOneProfile() {
        val music = env.open()
        val mine = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        music.sources.sync(PROFILE_A, mine.id)

        assertTrue(music.sources.connections(PROFILE_B).isEmpty())
        assertNull(music.sources.connection(PROFILE_B, mine.id))
        assertTrue(music.sources.test(PROFILE_B, mine.id).isFailure)
        assertFailsWith<SourceException> { music.sources.sync(PROFILE_B, mine.id) }
        music.sources.remove(PROFILE_B, mine.id)
        music.sources.setEnabled(PROFILE_B, mine.id, false)
        assertEquals(1, music.sources.connections(PROFILE_A).size)
        assertTrue(music.sources.connections(PROFILE_A).single().enabled)
        assertNull(env.storedSecret(PROFILE_B, "source-${mine.id}"))
        assertEquals(0, music.library.trackCount(PROFILE_B))

        // The same server can be added by another profile; the two are independent.
        val theirs = music.sources.add(PROFILE_B, SubsonicAdapter.ID, subsonicDraft())
        assertFailsWith<SourceException> { music.sources.add(PROFILE_B, SubsonicAdapter.ID, subsonicDraft()) }
        music.sources.sync(PROFILE_B, theirs.id)
        assertEquals(4, music.library.trackCount(PROFILE_B))

        env.deleteProfile(PROFILE_A)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())
        assertNull(env.storedSecret(PROFILE_A, "source-${mine.id}"), "a deleted profile's passwords are deleted")
        assertNotNull(env.storedSecret(PROFILE_B, "source-${theirs.id}"))
        assertEquals(4, music.library.trackCount(PROFILE_B))
    }

    @Test
    fun orderAndNames() {
        val music = env.open()
        val first = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonicDraft())
        val second = music.sources.add(PROFILE_A, JellyfinAdapter.ID, jellyfinDraft())
        assertEquals(listOf(first.id, second.id), music.sources.connections(PROFILE_A).map { it.id })
        music.sources.move(PROFILE_A, second.id, up = true)
        assertEquals(listOf(second.id, first.id), music.sources.connections(PROFILE_A).map { it.id })
        music.sources.move(PROFILE_A, second.id, up = true)
        assertEquals(listOf(second.id, first.id), music.sources.connections(PROFILE_A).map { it.id })
        music.sources.rename(PROFILE_A, first.id, "  Home server ")
        assertEquals("Home server", music.sources.connection(PROFILE_A, first.id)!!.name)
    }

    @Test
    fun streamAddressesMustReallyServeAudio() {
        val music = env.open()
        val radio = music.sources.addStream(PROFILE_A, "Stand-in FM", "${servers.baseUrl}/radio/live.mp3")
        assertEquals("Stand-in FM", radio.title)
        assertEquals("Radio", radio.genre)
        assertEquals("MP3", radio.format)
        assertEquals("${servers.baseUrl}/radio/live.mp3", music.sources.resolveUri(PROFILE_A, radio))
        val streams = music.sources.connections(PROFILE_A).single()
        assertEquals(MusicSourceManager.STREAM_ADAPTER, streams.adapter)

        // A playlist address is followed to the stream it names.
        val fromM3u = music.sources.addStream(PROFILE_A, "Via playlist", "${servers.baseUrl}/radio/station.m3u")
        assertEquals(radio.id, fromM3u.id, "the same stream is one entry")
        assertEquals("Via playlist", fromM3u.title)
        music.sources.addStream(PROFILE_A, "Via PLS", "${servers.baseUrl}/radio/station.pls")
        assertEquals(1, music.library.trackCount(PROFILE_A))

        for (bad in listOf(
            "${servers.baseUrl}/radio/page.html", "${servers.baseUrl}/radio/empty.m3u", "${servers.baseUrl}/radio/missing.mp3",
            "file:///etc/passwd", "ftp://example.invalid/a.mp3", "not an address", "http://user:pw@127.0.0.1/a.mp3",
        )) {
            assertFailsWith<SourceException>("should be refused: $bad") { music.sources.addStream(PROFILE_A, "Bad", bad) }
        }
        assertEquals(1, music.library.trackCount(PROFILE_A), "refused addresses add nothing")
        assertEquals(0, music.sources.download(PROFILE_A, listOf(radio.id)), "a live stream cannot be downloaded")
    }

    @Test
    fun aStreamPlaysThroughTheRealEngine() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val radio = music.sources.addStream(PROFILE_A, "Stand-in FM", "${servers.baseUrl}/radio/live.mp3")
        music.player.play(listOf(radio))
        awaitTrue(describe = { "the stream to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        assertNull(music.player.state.value.lastError)
    }

    @Test
    fun pastingAnAddressIntoThePluginRegistryCreatesNothing() {
        val music = env.open()
        val registry = env.currentModule.plugins
        awaitTrue(describe = { "built-in sources to be registered: ${registry.installed.value.map { it.id }}" }) { registry.installed.value.size == 4 }
        assertEquals(setOf("local-files", "subsonic", "jellyfin", "stream-url"), registry.installed.value.map { it.id }.toSet())
        assertTrue(registry.installed.value.all { it.builtIn })
        assertFalse(registry.importAvailable)

        val result = runBlocking { registry.importFromLocation("https://example.invalid/some-music-plugin.json") }
        assertTrue(result is PluginActionResult.NotAvailableYet, "got $result")
        assertEquals(4, registry.installed.value.size)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())
        assertTrue(runBlocking { registry.remove("subsonic") } is PluginActionResult.Failure, "built-in sources cannot be removed")
    }
}
