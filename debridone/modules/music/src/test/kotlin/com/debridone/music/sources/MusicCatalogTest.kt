package com.debridone.music.sources

import com.debridone.module.PluginActionResult
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.awaitTrue
import com.debridone.music.library.TrackFilter
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.requireLibmpv
import com.debridone.music.summary
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Online catalogues and plugins against local stand-in servers that answer in the services' real
 * formats: Audius, Navidrome/OpenSubsonic, Jellyfin, a Music Catalog server, and plugin manifests.
 */
class MusicCatalogTest {
    private val env = MusicTestEnvironment()
    private val servers = StandInServers()

    @AfterTest
    fun tearDown() {
        env.close()
        servers.close()
    }

    private val none = SourceDraft("", "", "", "")
    private fun audius() = none.copy(baseUrl = servers.audiusUrl)
    private fun subsonic() = SourceDraft("", servers.subsonicUrl, StandInServers.SUBSONIC_USER, StandInServers.SUBSONIC_PASSWORD)
    private fun jellyfin() = SourceDraft("", servers.jellyfinUrl, StandInServers.JELLYFIN_USER, StandInServers.JELLYFIN_PASSWORD)

    // ---------------------------------------------------------------- Audius

    @Test
    fun audiusNeedsNoAccountAndIsSearched() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, audius())
        assertEquals("Audius", connection.name)
        assertNull(env.storedSecret(PROFILE_A, "source-${connection.id}"), "nothing is put in the Keychain for a source without sign-in")
        assertEquals(listOf(connection.id), music.sources.catalogs(PROFILE_A).map { it.id })
        assertTrue(music.sources.capabilities(connection).let { it.searchTracks && it.searchArtists && it.featured && it.genres })
        assertTrue(servers.audiusRequests.all { it.contains("app_name=DebridOne") }, "every request names the application, as Audius asks")

        val results = music.sources.search(PROFILE_A, connection.id, "open")
        assertEquals(listOf("Open Road"), results.tracks.map { it.title })
        val track = results.tracks.single()
        assertEquals("Indie Artist", track.artist)
        assertEquals("Electronic", track.genre)
        assertEquals(2024, track.year)
        assertEquals(2_000, track.durationMs)
        assertTrue(track.playable)
        assertEquals(listOf("Indie Artist"), results.artists.map { it.name })
        assertTrue(results.artists.single().verified)
        assertEquals(1200, results.artists.single().followerCount)
        assertEquals(listOf("Open Roads"), results.albums.map { it.title })
        assertEquals(listOf("Late Night Mix"), results.playlists.map { it.name })

        assertEquals(listOf("Night Shift"), music.sources.featured(PROFILE_A, connection.id, genre = "House").map { it.title })
        assertEquals(3, music.sources.featured(PROFILE_A, connection.id).size)
        assertTrue("House" in music.sources.genres(PROFILE_A, connection.id))
        assertEquals(3, music.sources.artistTracks(PROFILE_A, connection.id, "u-1").size)
        assertEquals(2, music.sources.playlistTracks(PROFILE_A, connection.id, "PL1").size)
        assertEquals(2, music.sources.albumTracks(PROFILE_A, connection.id, "ALB1").size)

        val twice = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, AudiusAdapter.ID, audius()) }
        assertTrue(twice.message!!.contains("already set up"), twice.message)
        // Nothing is copied from a catalogue: a synchronisation is a no-op and removes nothing.
        assertEquals(0, music.sources.sync(PROFILE_A, connection.id).added)
        assertTrue(music.sources.test(PROFILE_A, connection.id).isSuccess)
    }

    @Test
    fun aCatalogueSongStaysOutOfTheLibraryUntilItIsKept() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, audius())
        val localCount = music.library.trackCount(PROFILE_A)
        val featured = music.sources.featured(PROFILE_A, connection.id)
        assertEquals(listOf(true, true, false), featured.map { it.playable }, "the supporters-only track is listed as not playable")

        val played = music.sources.materialize(PROFILE_A, connection.id, featured, keep = false)
        assertEquals(listOf("Open Road", "Night Shift"), played.map { it.title }, "a track Audius will not stream is never made playable")
        assertTrue(played.all { it.hidden && it.sourceId == connection.id })
        assertEquals(localCount, music.library.trackCount(PROFILE_A), "playing from a catalogue adds nothing to the library")
        assertTrue(music.library.search(PROFILE_A, "Open Road").tracks.isEmpty())
        assertTrue(music.library.albums(PROFILE_A).none { it.artist == "Indie Artist" })
        assertTrue(music.library.artists(PROFILE_A).none { it.name == "Indie Artist" })
        assertEquals(0, music.sources.connection(PROFILE_A, connection.id)!!.trackCount)
        assertFalse(music.sources.canDownload(PROFILE_A, played.first()), "Audius is streamed, not downloaded")

        // It is in the listening history once it was listened to.
        music.library.recordPlay(PROFILE_A, played.first().id, 2_000, completed = true)
        assertEquals("Open Road", music.library.recentlyPlayed(PROFILE_A, 5).first().title)

        // Keeping it puts it in the library proper; asking again does not duplicate it.
        val kept = music.sources.materialize(PROFILE_A, connection.id, featured.take(1), keep = true).single()
        assertEquals(played.first().id, kept.id)
        assertFalse(kept.hidden)
        assertEquals(localCount + 1, music.library.trackCount(PROFILE_A))
        assertEquals(listOf("Open Road"), music.library.search(PROFILE_A, "open road").tracks.map { it.title })
        assertTrue(music.library.artists(PROFILE_A).any { it.name == "Indie Artist" })

        // A favourite is kept too.
        music.library.setTrackFavorite(PROFILE_A, played[1].id, true)
        assertFalse(music.library.track(PROFILE_A, played[1].id)!!.hidden)
        assertEquals(localCount + 2, music.library.trackCount(PROFILE_A))

        // Playing it again from the catalogue does not hide what was kept.
        music.sources.materialize(PROFILE_A, connection.id, featured, keep = false)
        assertEquals(localCount + 2, music.library.trackCount(PROFILE_A))
    }

    @Test
    fun forgottenCatalogueSongsArePurgedButNotOnesInPlaylists() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, audius())
        val (first, second) = music.sources.materialize(PROFILE_A, connection.id, music.sources.featured(PROFILE_A, connection.id), keep = false)
        val playlist = music.playlists.create(PROFILE_A, "Finds")
        music.playlists.add(PROFILE_A, playlist.id, listOf(second.id))

        assertEquals(1, music.library.purgeHidden(PROFILE_A, before = System.currentTimeMillis() + 1_000))
        assertNull(music.library.track(PROFILE_A, first.id))
        assertNotNull(music.library.track(PROFILE_A, second.id), "a song in a playlist is not forgotten")
    }

    @Test
    fun coversComeFromAnotherNodeWhenTheFirstIsDown() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, audius())
        val featured = music.sources.featured(PROFILE_A, connection.id)
        assertTrue(featured.first().artworkUrl!!.contains("dead-node.invalid"))
        assertTrue(featured.first().artworkAlternates.first().startsWith("${servers.baseUrl}/audius/mirror/content/"))

        val file = music.sources.artworkFile(PROFILE_A, connection.id, null, featured.first().artworkUrl, featured.first().artworkAlternates)
        assertTrue(file != null && Files.size(file) > 0, "the browsing cover was fetched from the mirror")

        val tracks = music.sources.materialize(PROFILE_A, connection.id, featured, keep = true)
        assertTrue(tracks.all { it.artwork == null })
        assertEquals(2, music.sources.fetchArtwork(PROFILE_A, connection.id, featured))
        assertNotNull(music.library.track(PROFILE_A, tracks.first().id)!!.artwork)
        assertEquals(0, music.sources.fetchArtwork(PROFILE_A, connection.id, featured), "covers are fetched once")
    }

    @Test
    fun anAudiusTrackPlaysThroughTheRealEngine() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, audius())
        val featured = music.sources.featured(PROFILE_A, connection.id)
        var outcome: Result<Int>? = null

        env.currentModule.playRemote(connection.id, featured, startIndex = 1) { outcome = it }
        awaitTrue(describe = { "the catalogue songs to be queued: $outcome" }) { outcome != null }
        assertEquals(2, outcome!!.getOrThrow())
        assertEquals("Night Shift", music.player.state.value.current?.title, "playback starts at the song that was chosen")
        awaitTrue(describe = { "the Audius stream to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        assertNull(music.player.state.value.lastError)
        assertTrue(servers.audiusRequests.any { it.startsWith("/v1/tracks/AUD2/stream") }, "the engine asked Audius for the stream")
        assertTrue(servers.audiusRequests.any { it.startsWith("/node/AUD2.mp3") }, "and followed its redirect to the node with the audio")
        awaitTrue(describe = { "covers to arrive after playback started" }) { music.player.state.value.current?.let { music.library.track(PROFILE_A, it.trackId)?.artwork } != null }
    }

    @Test
    fun eachProfileSetsUpItsOwnCatalogues() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, AudiusAdapter.ID, audius())
        music.sources.materialize(PROFILE_A, connection.id, music.sources.featured(PROFILE_A, connection.id), keep = true)

        assertTrue(music.sources.catalogs(PROFILE_B).isEmpty())
        assertTrue(music.library.tracks(PROFILE_B, TrackFilter(includeHidden = true)).none { it.sourceId == connection.id })
        assertFailsWith<SourceException> { music.sources.search(PROFILE_B, connection.id, "open") }

        music.sources.setEnabled(PROFILE_A, connection.id, false)
        val off = assertFailsWith<SourceException> { music.sources.search(PROFILE_A, connection.id, "open") }
        assertTrue(off.message!!.contains("turned off"), off.message)
        music.sources.setEnabled(PROFILE_A, connection.id, true)
        music.sources.remove(PROFILE_A, connection.id)
        assertTrue(music.library.tracks(PROFILE_A, TrackFilter(includeHidden = true)).none { it.sourceId == connection.id })
    }

    @Test
    fun anUnreachableCatalogueIsReportedAndRecorded() {
        val music = env.open()
        val unused = java.net.ServerSocket(0).use { it.localPort }
        val down = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, AudiusAdapter.ID, none.copy(baseUrl = "http://127.0.0.1:$unused/v1")) }
        assertTrue(down.message!!.contains("could not be reached"), down.message)
        val wrong = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, AudiusAdapter.ID, none.copy(baseUrl = "${servers.baseUrl}/radio")) }
        assertTrue(wrong.message!!.contains("Audius"), wrong.message)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty(), "nothing is saved for an address that does not answer like Audius")
    }

    // ---------------------------------------------------------------- personal servers

    @Test
    fun aNavidromeServerIsSearchedAndItsPlaylistsImported() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, SubsonicAdapter.ID, subsonic())
        music.sources.sync(PROFILE_A, connection.id)
        val synced = music.library.trackCount(PROFILE_A, TrackFilter(sourceId = connection.id))

        val results = music.sources.search(PROFILE_A, connection.id, "se")
        assertEquals(setOf("Second Light", "Sixth Sense", "Elsewhere"), results.tracks.map { it.title }.toSet())
        assertEquals(listOf("Second Album"), results.albums.map { it.title })
        assertEquals("al-2", results.albums.single().artworkKey)
        assertEquals(listOf("Test Artist"), music.sources.search(PROFILE_A, connection.id, "test").artists.map { it.name })
        assertEquals(listOf("Electronic", "Jazz"), music.sources.genres(PROFILE_A, connection.id))
        assertEquals(listOf("Elsewhere"), music.sources.featured(PROFILE_A, connection.id, genre = "Jazz").map { it.title })
        assertEquals(4, music.sources.featured(PROFILE_A, connection.id).size)
        assertEquals(3, music.sources.artistTracks(PROFILE_A, connection.id, "ar-test-artist").size)
        assertEquals(listOf("Opening", "Second Light", "Sixth Sense"), music.sources.albumTracks(PROFILE_A, connection.id, "al-1").map { it.title })

        // Search results of a server that was synchronised are the songs already in the library.
        val playable = music.sources.materialize(PROFILE_A, connection.id, results.tracks, keep = false)
        assertTrue(playable.none { it.hidden })
        assertEquals(synced, music.library.trackCount(PROFILE_A, TrackFilter(sourceId = connection.id)))

        val remote = music.sources.ownPlaylists(PROFILE_A, connection.id).single()
        assertEquals("Road Trip", remote.name)
        assertEquals(2, remote.trackCount)
        var imported: Result<Int>? = null
        env.currentModule.importRemotePlaylist(connection.id, remote) { imported = it }
        awaitTrue(describe = { "the playlist to be imported: $imported" }) { imported != null }
        assertEquals(2, imported!!.getOrThrow())
        val local = music.playlists.playlists(PROFILE_A).single { it.name == "Road Trip" }
        assertEquals(listOf("Elsewhere", "Second Light"), music.playlists.tracks(PROFILE_A, local.id).map { it.title }, "in the server's order")
        assertTrue(music.playlists.playlists(PROFILE_B).isEmpty())
    }

    @Test
    fun aJellyfinServerIsSearchedAndBrowsed() {
        val music = env.open()
        val connection = music.sources.add(PROFILE_A, JellyfinAdapter.ID, jellyfin())
        val results = music.sources.search(PROFILE_A, connection.id, "second")
        assertEquals(listOf("Second Light"), results.tracks.map { it.title })
        assertEquals(listOf("Second Album"), results.albums.map { it.title })
        assertEquals(listOf("Test Artist"), music.sources.search(PROFILE_A, connection.id, "test").artists.map { it.name })
        assertEquals(listOf("Evening"), music.sources.search(PROFILE_A, connection.id, "evening").playlists.map { it.name })
        assertEquals(listOf("Electronic", "Jazz"), music.sources.genres(PROFILE_A, connection.id))
        assertEquals(listOf("Elsewhere"), music.sources.featured(PROFILE_A, connection.id, genre = "Jazz").map { it.title })
        assertEquals(3, music.sources.albumTracks(PROFILE_A, connection.id, "al-1").size)
        assertEquals(1, music.sources.artistTracks(PROFILE_A, connection.id, "jar-other-artist").size)
        assertEquals(listOf("Evening"), music.sources.ownPlaylists(PROFILE_A, connection.id).map { it.name })
        assertEquals(listOf("Sixth Sense", "Elsewhere"), music.sources.playlistTracks(PROFILE_A, connection.id, "jpl-1").map { it.title })
        assertTrue(servers.rejected.isEmpty(), "every request carried the token: ${servers.rejected}")
    }

    // ---------------------------------------------------------------- a catalogue server (the plugin protocol)

    @Test
    fun aCatalogueServerIsConnectedSearchedAndPlayed() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val connection = music.sources.add(PROFILE_A, HttpCatalogAdapter.ID, none.copy(baseUrl = servers.catalogUrl))
        assertEquals("Example Catalogue", connection.name, "the name comes from the server's manifest")

        val results = music.sources.search(PROFILE_A, connection.id, "river")
        assertEquals(listOf("River"), results.tracks.map { it.title })
        assertEquals(listOf("Catalogue Band"), results.artists.map { it.name })
        assertEquals(listOf("Catalogue Album"), results.albums.map { it.title })
        assertEquals(listOf("Staff Picks"), results.playlists.map { it.name })
        assertEquals(listOf("Folk", "Rock"), music.sources.genres(PROFILE_A, connection.id))
        assertEquals(listOf("Staff Picks"), music.sources.ownPlaylists(PROFILE_A, connection.id).map { it.name })
        assertEquals(2, music.sources.playlistTracks(PROFILE_A, connection.id, "cpl-1").size)

        val featured = music.sources.featured(PROFILE_A, connection.id)
        assertEquals(listOf("River", "Mountain", "Direct One", "Not Allowed", "Bad Address"), featured.map { it.title })
        assertEquals(listOf(true, true, true, false, true), featured.map { it.playable })
        // Addresses that are not http(s) are never taken from a catalogue: the song falls back to
        // the catalogue's own stream address, and the bogus cover is dropped.
        assertTrue(featured.none { it.remoteId.contains("file:") || it.artworkUrl?.startsWith("javascript") == true })
        assertNull(featured.last().artworkUrl)

        val tracks = music.sources.materialize(PROFILE_A, connection.id, featured, keep = false)
        assertEquals(4, tracks.size)
        assertEquals("${servers.catalogUrl}/tracks/c-evil/stream", music.sources.resolveUri(PROFILE_A, tracks.last()))
        assertEquals("${servers.baseUrl}/radio/live.mp3", music.sources.resolveUri(PROFILE_A, tracks[2]), "a song may carry its own http address")
        music.player.play(tracks.take(1))
        awaitTrue(describe = { "the catalogue stream to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        assertTrue(servers.catalogRequests.any { it.startsWith("/catalog/tracks/c-1/stream") })
    }

    @Test
    fun aCatalogueServerWithATokenKeepsItInTheKeychain() {
        val music = env.open()
        val refused = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, HttpCatalogAdapter.ID, none.copy(baseUrl = servers.privateCatalogUrl, secret = "wrong")) }
        assertTrue(refused.message!!.contains("refused the access token"), refused.message)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())

        val connection = music.sources.add(PROFILE_A, HttpCatalogAdapter.ID, none.copy(baseUrl = servers.privateCatalogUrl, secret = StandInServers.CATALOG_TOKEN))
        assertEquals(StandInServers.CATALOG_TOKEN, env.storedSecret(PROFILE_A, "source-${connection.id}"))
        assertFalse(env.dataFolderContains(StandInServers.CATALOG_TOKEN), "the token is not in the database")
        servers.rejected.clear()
        val track = music.sources.materialize(PROFILE_A, connection.id, music.sources.search(PROFILE_A, connection.id, "river").tracks, keep = true).single()
        val address = assertNotNull(music.sources.resolveUri(PROFILE_A, track))
        assertTrue(address.endsWith("/tracks/c-1/stream?token=${StandInServers.CATALOG_TOKEN}"), "the engine opens the stream with the token")
        assertTrue(servers.rejected.isEmpty())
        assertNull(music.sources.resolveUri(PROFILE_B, track), "another profile cannot use this profile's connection")
    }

    // ---------------------------------------------------------------- plugins: importing a manifest

    @Test
    fun aStreamListPluginIsImportedFromAnAddress() {
        val music = env.open()
        val registry = env.currentModule.plugins
        val result = runBlocking { registry.importFromLocation("${servers.baseUrl}/plugins/radio.json") }
        assertTrue(result is PluginActionResult.Success, "got $result")
        val plugin = registry.installed.value.single { it.id == "org.example.radio" }
        assertEquals("Example Radio", plugin.name)
        assertEquals("1.0.0", plugin.version)
        assertFalse(plugin.builtIn)
        assertTrue(plugin.lastError!!.contains("Dead Air"), "the stream that does not serve audio is reported: ${plugin.lastError}")

        val connection = assertNotNull(music.sources.pluginConnection(PROFILE_A, "org.example.radio"))
        assertEquals("Example Radio", connection.name)
        val stations = music.library.tracks(PROFILE_A, TrackFilter(sourceId = connection.id))
        assertEquals(setOf("Station One", "Station Two"), stations.map { it.title }.toSet())
        assertEquals("Jazz", stations.first { it.title == "Station One" }.genre)
        assertEquals("${servers.baseUrl}/radio/second.mp3", music.sources.resolveUri(PROFILE_A, stations.first { it.title == "Station Two" }), "a playlist address is followed to the stream")
        assertEquals(2, music.sources.streams(PROFILE_A).size)

        // Another profile sees the plugin but has to set it up itself.
        assertNull(music.sources.pluginConnection(PROFILE_B, "org.example.radio"))
        assertEquals("Example Radio", music.sources.manifests().single().name)
        music.sources.connectPlugin(PROFILE_B, music.sources.manifest("org.example.radio")!!)
        assertEquals(2, music.sources.streams(PROFILE_B).size)

        // The plugin survives a restart, and removing it takes its stations with it, for everyone.
        val reopened = env.open()
        assertTrue(env.currentModule.plugins.installed.value.any { it.id == "org.example.radio" })
        assertEquals(2, reopened.sources.streams(PROFILE_A).size)
        assertTrue(runBlocking { env.currentModule.plugins.remove("org.example.radio") } is PluginActionResult.Success)
        assertTrue(reopened.sources.streams(PROFILE_A).isEmpty())
        assertTrue(reopened.sources.streams(PROFILE_B).isEmpty())
        assertTrue(reopened.sources.manifests().isEmpty())
        assertTrue(env.currentModule.plugins.installed.value.none { it.id == "org.example.radio" })
    }

    @Test
    fun pluginsAreImportedFromFilesAndPackages() {
        val music = env.open()
        val registry = env.currentModule.plugins
        val manifest = """{"debridoneMusicPlugin":1,"id":"org.example.local","name":"Local Catalogue","version":"0.3","adapter":"debridone-catalog","baseUrl":"${servers.catalogUrl}"}"""
        val file = env.root.resolve("local-catalogue.json")
        Files.writeString(file, manifest)
        assertTrue(runBlocking { registry.importFromLocation(file.toString()) } is PluginActionResult.Success)
        val connection = assertNotNull(music.sources.pluginConnection(PROFILE_A, "org.example.local"), "a catalogue without sign-in is set up at once")
        assertEquals(listOf("Mountain"), music.sources.search(PROFILE_A, connection.id, "mountain").tracks.map { it.title })

        val packaged = env.root.resolve("radio.zip")
        ZipOutputStream(Files.newOutputStream(packaged)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"debridoneMusicPlugin":1,"id":"org.example.zipped","name":"Zipped Radio","adapter":"streams","streams":[{"name":"One","url":"${servers.baseUrl}/radio/live.mp3"}]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("code.js"))
            zip.write("throw new Error('never run')".toByteArray())
            zip.closeEntry()
        }
        assertTrue(runBlocking { registry.importFromLocation(packaged.toString()) } is PluginActionResult.Success)
        assertEquals(listOf("One"), music.library.tracks(PROFILE_A, TrackFilter(sourceId = music.sources.pluginConnection(PROFILE_A, "org.example.zipped")!!.id)).map { it.title })

        val empty = env.root.resolve("empty.zip")
        ZipOutputStream(Files.newOutputStream(empty)).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("nothing".toByteArray())
            zip.closeEntry()
        }
        val noManifest = runBlocking { registry.importFromLocation(empty.toString()) }
        assertTrue(noManifest is PluginActionResult.Failure && noManifest.message.contains("manifest.json"), "got $noManifest")
        val missing = runBlocking { registry.importFromLocation(env.root.resolve("nope.json").toString()) }
        assertTrue(missing is PluginActionResult.Failure && missing.message.contains("no file"), "got $missing")
    }

    @Test
    fun aServerPresetWaitsForTheUserToSignIn() {
        val music = env.open()
        val registry = env.currentModule.plugins
        assertTrue(runBlocking { registry.importFromLocation("${servers.baseUrl}/plugins/navidrome.json") } is PluginActionResult.Success)
        val manifest = assertNotNull(music.sources.manifest("org.example.family-server"))
        assertFalse(manifest.connectsWithoutSignIn)
        assertNull(music.sources.pluginConnection(PROFILE_A, manifest.id), "nothing is connected without the user's sign-in")

        val wrong = assertFailsWith<SourceException> { music.sources.connectPlugin(PROFILE_A, manifest, SourceDraft("", "", StandInServers.SUBSONIC_USER, "nope")) }
        assertEquals("Wrong user name or password.", wrong.message)
        val setup = music.sources.connectPlugin(PROFILE_A, manifest, SourceDraft("", "", StandInServers.SUBSONIC_USER, StandInServers.SUBSONIC_PASSWORD))
        assertEquals("Family Server", setup.connection.name)
        assertEquals(servers.subsonicUrl, setup.connection.baseUrl, "the address comes from the manifest")
        assertEquals("org.example.family-server", setup.connection.pluginId)
        assertEquals(StandInServers.SUBSONIC_PASSWORD, env.storedSecret(PROFILE_A, "source-${setup.connection.id}"))
        assertEquals(4, music.sources.sync(PROFILE_A, setup.connection.id).added)

        assertTrue(runBlocking { registry.remove(manifest.id) } is PluginActionResult.Success)
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())
        assertNull(env.storedSecret(PROFILE_A, "source-${setup.connection.id}"), "the stored password goes with the plugin")
        assertEquals(0, music.library.trackCount(PROFILE_A, TrackFilter(sourceId = setup.connection.id)))
    }

    @Test
    fun otherKindsOfManifestAreRefusedWithAReason() {
        val music = env.open()
        val registry = env.currentModule.plugins
        fun refusal(name: String): String {
            val result = runBlocking { registry.importFromLocation("${servers.baseUrl}/plugins/$name") }
            assertTrue(result is PluginActionResult.Failure, "$name gave $result")
            return (result as PluginActionResult.Failure).message
        }
        assertTrue(refusal("stremio.json").contains("Streaming add-on"), "a Streaming add-on is named for what it is")
        assertTrue(refusal("future.json").contains("plugin format 2"))
        assertTrue(refusal("unknown-adapter.json").contains("\"spotify\" adapter"))
        assertTrue(refusal("page.html").contains("web page"))
        assertTrue(refusal("missing.json").contains("no manifest"))
        assertEquals(5, registry.installed.value.size, "nothing was installed")
        assertTrue(music.sources.manifests().isEmpty())
        assertTrue(music.sources.connections(PROFILE_A).isEmpty())
    }

    @Test
    fun manifestsAreValidated() {
        fun error(json: String) = MusicPluginManifest.parse(json).exceptionOrNull()?.message ?: "accepted"
        val ok = """{"debridoneMusicPlugin":1,"id":"org.example.ok","name":"Fine","adapter":"streams","streams":[{"name":"S","url":"https://radio.example.org/s.mp3"}]}"""
        val parsed = MusicPluginManifest.parse(ok).getOrThrow()
        assertEquals("org.example.ok", parsed.id)
        assertEquals(IntegrationKind.PLAYBACK_SOURCE, parsed.kind)
        assertEquals(parsed, MusicPluginManifest.parse(parsed.toJson()).getOrThrow(), "a manifest is stored and read back unchanged")

        assertTrue(error("[1,2]").contains("not a JSON object"))
        assertTrue(error("""{"name":"x"}""").contains("debridoneMusicPlugin"))
        assertTrue(error(ok.replace("org.example.ok", "Bad Id!")).contains("not valid"))
        assertTrue(error(ok.replace(""""name":"Fine",""", "")).contains("no \"name\""))
        assertTrue(error(ok.replace("https://radio.example.org/s.mp3", "http://radio.example.org/s.mp3")).contains("must use https"))
        assertEquals("accepted", error(ok.replace("https://radio.example.org/s.mp3", "http://192.168.1.20:8000/s.mp3")), "plain http is fine on the local network")
        assertTrue(error(ok.replace("https://radio.example.org/s.mp3", "file:///etc/passwd")).contains("http or https"))
        assertTrue(error(ok.replace("https://radio.example.org/s.mp3", "https://user:pw@radio.example.org/s.mp3")).contains("user name or password"))
        assertTrue(error("""{"debridoneMusicPlugin":1,"id":"org.example.c","name":"C","adapter":"debridone-catalog"}""").contains("needs a \"baseUrl\""))
        assertTrue(error("""{"debridoneMusicPlugin":1,"id":"org.example.s","name":"S","adapter":"streams"}""").contains("at least one stream"))
        assertTrue(error("x".repeat(MusicPluginManifest.MAX_BYTES + 1)).contains("larger than"))
        assertEquals(MusicPluginManifest.AUTH_PASSWORD, MusicPluginManifest.parse("""{"debridoneMusicPlugin":1,"id":"org.example.n","name":"N","adapter":"subsonic","baseUrl":"https://m.example.org"}""").getOrThrow().auth)
    }

    @Test
    fun aPluginCannotTakeTheIdOfABuiltInIntegration() {
        env.open()
        val file = env.root.resolve("fake-audius.json")
        Files.writeString(file, """{"debridoneMusicPlugin":1,"id":"audius","name":"Not Audius","adapter":"streams","streams":[{"name":"S","url":"${servers.baseUrl}/radio/live.mp3"}]}""")
        val result = runBlocking { env.currentModule.plugins.importFromLocation(file.toString()) }
        assertTrue(result is PluginActionResult.Failure && result.message.contains("built-in"), "got $result")
        assertEquals("Audius", env.currentModule.plugins.installed.value.single { it.id == "audius" }.name)
    }
}
