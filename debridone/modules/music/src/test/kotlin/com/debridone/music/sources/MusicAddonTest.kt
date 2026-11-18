package com.debridone.music.sources

import com.debridone.music.Fixtures
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.awaitTrue
import com.debridone.music.playback.DEFERRED_SCHEME
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.requireLibmpv
import com.debridone.music.summary
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Music addons in the Eclipse Music / BeatBoss manifest format, against a local addon that answers
 * in that format and serves DebridOne's own test recordings: installing from a manifest address,
 * browsing, search, details, playback through the real engine, saving, settings, profiles.
 */
class MusicAddonTest {
    private val env = MusicTestEnvironment()
    private val addon = StandInAddon()

    @AfterTest
    fun tearDown() {
        env.close()
        addon.close()
    }

    private fun streamRequests() = addon.requests.filter { it.startsWith("/stream/") }

    // ---------------------------------------------------------------- installing

    @Test
    fun anAddonIsInstalledFromItsManifestAddressAndTheAddressStaysSecret() {
        val music = env.open()
        val preview = music.sources.previewAddon(addon.manifestAddress)
        assertEquals("Test Tones", preview.name)
        assertEquals("org.debridone.test-addon", preview.id)
        val compatibility = preview.compatibility()
        assertTrue(compatibility.usable)
        assertTrue(compatibility.uses.any { it.startsWith("Search") } && compatibility.uses.any { it.startsWith("Playback") } && compatibility.uses.any { it.contains("2 browsing rows") })
        assertTrue(compatibility.ignores.any { it.contains("Library sync") } && compatibility.ignores.any { it.contains("ISRC") }, "what DebridOne does not use is said: ${compatibility.ignores}")
        assertEquals(emptyList(), music.sources.addonConnections(PROFILE_A), "a preview installs nothing")

        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        assertEquals(EclipseAddonAdapter.ID, connection.adapter)
        assertEquals("Test Tones", connection.name)
        assertEquals(addon.origin, connection.baseUrl, "only the server's name is kept in the open")
        assertEquals(true, connection.lastTestOk)
        assertEquals("1.2.0", music.sources.addonManifest(connection)?.version)

        // The address, with its token, is in the credential store and nowhere else.
        assertEquals("${addon.origin}/${StandInAddon.TOKEN}", env.storedSecret(PROFILE_A, "source-${connection.id}"))
        assertFalse(env.dataFolderContains(StandInAddon.TOKEN), "the token must not be in the database or any other file of Music")

        // The same addon cannot be installed twice; the base address works as well as the manifest's.
        val twice = assertFailsWith<SourceException> { music.sources.addAddon(PROFILE_A, "${addon.origin}/${StandInAddon.TOKEN}/") }
        assertTrue(twice.message!!.contains("already installed"))
        assertFalse(twice.message!!.contains(StandInAddon.TOKEN))
    }

    @Test
    fun whatIsNotAMusicAddonIsRefusedWithAReason() {
        val music = env.open()
        fun refusal(manifest: String): String {
            addon.manifestOverride = manifest
            return assertFailsWith<SourceException> { music.sources.addAddon(PROFILE_A, addon.manifestAddress) }.message!!
        }
        assertTrue(refusal("""{"id":"org.video","name":"Films","version":"1.0.0","resources":["stream"],"types":["movie","series"],"idPrefixes":["tt"]}""").contains("Streaming"))
        assertTrue(refusal("""{"id":"x","name":"Y","version":"1","resources":[{"name":"stream","types":["movie"]},"meta"],"types":["other"]}""").contains("Streaming"))
        assertTrue(refusal("""{"debridoneMusicPlugin":1,"id":"org.example.radio","name":"Radio","adapter":"streams","streams":[]}""").contains("DebridOne Music plugin"))
        assertTrue(refusal("<html><body>Sign in</body></html>").contains("web page"))
        assertTrue(refusal("""{"name":"No id","version":"1.0.0","resources":["search"]}""").contains("\"id\""))
        assertTrue(refusal("""{"id":"a.b","name":"No resources","version":"1.0.0"}""").contains("resources"))
        assertTrue(refusal("""{"id":"a.b","name":"Sync only","version":"1.0.0","resources":["library"]}""").contains("cannot be used"))
        assertTrue(refusal("""{"id":"a.b","name":"Film reel","version":"1.0.0","resources":["search"],"contentType":"video"}""").contains("content type"))
        assertEquals(emptyList(), music.sources.addonConnections(PROFILE_A), "a refused addon leaves nothing behind")

        // Addresses: https everywhere except this computer and the local network; no user name in the address.
        assertTrue(assertFailsWith<SourceException> { music.sources.previewAddon("http://addons.example.org/manifest.json") }.message!!.contains("https"))
        assertTrue(assertFailsWith<SourceException> { music.sources.previewAddon("https://user:pw@addons.example.org/manifest.json") }.message!!.contains("user name"))
        // A wrong token: the addon answers 401 and the message says what that usually means, without the address.
        val wrong = assertFailsWith<SourceException> { music.sources.previewAddon("${addon.origin}/not-my-token/manifest.json") }
        assertTrue(wrong.message!!.contains("token"))
        assertFalse(wrong.message!!.contains("not-my-token") || wrong.message!!.contains("127.0.0.1"))
    }

    // ---------------------------------------------------------------- browsing

    @Test
    fun searchDetailsAndRowsAreReadInTheAddonsOwnFormat() {
        val music = env.open()
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        val capabilities = music.sources.capabilities(connection)
        assertTrue(capabilities.searchTracks && capabilities.searchAlbums && capabilities.featured)
        assertFalse(capabilities.genres, "one row of songs: nothing to choose between")
        assertTrue(connection in music.sources.catalogs(PROFILE_A))

        val results = music.sources.search(PROFILE_A, connection.id, "light")
        assertEquals(listOf("Second Light"), results.tracks.map { it.title })
        val song = results.tracks.single()
        assertEquals("Test Artist", song.artist, "\"artistName\" is read as the artist")
        assertEquals(2_000, song.durationMs, "\"duration\" is in seconds")
        assertEquals("${addon.origin}/${StandInAddon.TOKEN}/audio/cover.jpg", song.artworkUrl, "\"albumCover\" is read as the cover")
        assertEquals("t:s2", song.remoteId)
        assertEquals("First Album", results.albums.single().title, "\"name\" is read as an album's title")
        assertEquals(4, results.albums.single().trackCount)
        assertEquals(2021, results.albums.single().year)
        assertEquals("Test Artist", results.artists.single().name)
        assertEquals("The Editor", results.playlists.single().owner)

        val album = music.sources.albumTracks(PROFILE_A, connection.id, "al1")
        assertEquals(listOf("Opening", "Second Light", "Third Wave", "Sixth Sense"), album.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), album.map { it.trackNo }, "songs of an album are numbered in the addon's order")
        assertTrue(album.all { it.album == "First Album" && it.albumArtist == "Test Artist" && it.year == 2021 })
        assertEquals(listOf("Opening", "Second Light"), music.sources.artistTracks(PROFILE_A, connection.id, "ar1").map { it.title })
        assertEquals(listOf("First Album"), music.sources.addonArtistAlbums(PROFILE_A, connection.id, "ar1").map { it.title })
        assertEquals(listOf("Third Wave", "Sixth Sense"), music.sources.playlistTracks(PROFILE_A, connection.id, "pl1").map { it.title })

        // Rows declared in the manifest.
        assertEquals(listOf("Top Tones" to "track", "New Releases" to "album"), music.sources.addonRows(connection).map { it.name to it.type })
        assertEquals(4, music.sources.featured(PROFILE_A, connection.id).size, "a row of songs is what Discover shows first")
        assertEquals(2_000, music.sources.addonRow(PROFILE_A, connection.id, "top").tracks.first().durationMs, "\"durationMs\" in rows")
        assertEquals(listOf("First Album"), music.sources.addonRow(PROFILE_A, connection.id, "new").albums.map { it.title })
        assertTrue(assertFailsWith<SourceException> { music.sources.albumTracks(PROFILE_A, connection.id, "nope") }.message!!.contains("Test Tones"))
        assertFalse(env.dataFolderContains(StandInAddon.TOKEN))
    }

    @Test
    fun settingsAreSentWithEveryRequestAsTheFormatAsks() {
        val music = env.open()
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        assertEquals(mapOf("quality" to "high", "preferOpus" to "true", "region" to "US", "gain" to "0"), music.sources.addonSettingValues(connection))

        music.sources.search(PROFILE_A, connection.id, "opening")
        assertTrue(addon.requests.last().let { it.contains("q=opening") && it.contains("quality=high") && it.contains("preferOpus=true") && it.contains("region=US") }, addon.requests.last())

        music.sources.setAddonSetting(PROFILE_A, connection.id, "quality", "low")
        music.sources.setAddonSetting(PROFILE_A, connection.id, "preferOpus", "false")
        music.sources.setAddonSetting(PROFILE_A, connection.id, "region", "PT-extra")
        music.sources.setAddonSetting(PROFILE_A, connection.id, "gain", "3")
        music.sources.albumTracks(PROFILE_A, connection.id, "al1")
        assertTrue(addon.requests.last().let { it.startsWith("/album/al1?") && it.contains("quality=low") && it.contains("preferOpus=false") && it.contains("region=PT") && !it.contains("PT-extra") && it.contains("gain=3") }, addon.requests.last())

        assertTrue(assertFailsWith<SourceException> { music.sources.setAddonSetting(PROFILE_A, connection.id, "quality", "ultra") }.message!!.contains("not one of the choices"))
        assertTrue(assertFailsWith<SourceException> { music.sources.setAddonSetting(PROFILE_A, connection.id, "gain", "40") }.message!!.contains("at most 6"))
        assertFailsWith<SourceException> { music.sources.setAddonSetting(PROFILE_A, connection.id, "nope", "1") }

        // The choices survive a restart and a fresh manifest; going back to the default removes the choice.
        val reopened = env.open()
        assertTrue(reopened.sources.test(PROFILE_A, connection.id).isSuccess)
        assertEquals("low", reopened.sources.addonSettingValues(reopened.sources.connection(PROFILE_A, connection.id)!!)["quality"])
        reopened.sources.setAddonSetting(PROFILE_A, connection.id, "quality", null)
        assertEquals("high", reopened.sources.addonSettingValues(reopened.sources.connection(PROFILE_A, connection.id)!!)["quality"])
    }

    // ---------------------------------------------------------------- playback

    @Test
    fun songsPlayThroughTheRealEngineAndAreResolvedOnlyWhenTheyStart() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        val songs = music.sources.albumTracks(PROFILE_A, connection.id, "al1")
        var outcome: Result<Int>? = null

        // Start at "Second Light": the addon says it is MP3; it is FLAC. The engine plays what it is.
        env.currentModule.playRemote(connection.id, songs, startIndex = 1) { outcome = it }
        awaitTrue(describe = { "the addon's songs to be queued: $outcome" }) { outcome != null }
        assertEquals(4, outcome!!.getOrThrow())
        assertTrue(music.player.state.value.queue.all { it.uri.startsWith(DEFERRED_SCHEME) }, "no address is in the queue, only a promise to ask")
        awaitTrue(describe = { "the addon's stream to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        assertEquals("Second Light", music.player.state.value.current?.title)
        assertNull(music.player.state.value.lastError)
        assertEquals(listOf("/stream/s2"), streamRequests().map { it.substringBefore('?') }, "only the song that plays was resolved")
        assertTrue(addon.requests.any { it.startsWith("/audio/s2") }, "and its audio was fetched from the address the addon gave")

        // The next song carries its own address (streamURL): it plays without a /stream call.
        music.player.next()
        awaitTrue(describe = { "the next song to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.current?.title == "Third Wave" && it.status == PlaybackStatus.PLAYING && it.positionMs > 200 }
        }
        assertEquals(1, streamRequests().size, "a song with a direct address is not resolved")

        // A song that is still being prepared is asked for again, as the format describes (503 with Retry-After).
        addon.preparing.set(2)
        music.player.next()
        awaitTrue(timeoutMs = 15_000, describe = { "the prepared song to play; state: ${music.player.state.value.summary()}; requests: ${streamRequests()}" }) {
            music.player.state.value.let { it.current?.title == "Sixth Sense" && it.status == PlaybackStatus.PLAYING && it.positionMs > 200 }
        }
        assertEquals(3, streamRequests().count { it.startsWith("/stream/s4") }, "asked three times: twice told to wait, then answered")
        assertNull(music.player.state.value.lastError)
        assertFalse(env.dataFolderContains(StandInAddon.TOKEN), "playing leaves no trace of the address either (queue, history, library)")
    }

    @Test
    fun aKeptSongPlaysAgainAfterARestartAndFailuresAreSaidInWords() {
        val music = env.open()
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        val found = music.sources.search(PROFILE_A, connection.id, "opening").tracks
        val kept = music.sources.materialize(PROFILE_A, connection.id, found, keep = true).single()
        assertEquals(DEFERRED_SCHEME + kept.id, music.sources.resolveUri(PROFILE_A, kept))

        val reopened = env.open()
        val again = reopened.library.track(PROFILE_A, kept.id)!!
        assertEquals("Opening", again.title)
        assertEquals("${addon.origin}/${StandInAddon.TOKEN}/audio/s1.bin", reopened.sources.resolveAtPlayTime(PROFILE_A, again.id), "the library keeps the song; the address is asked for anew")

        // The addon limits requests and says so: its own words reach the listener.
        addon.rateLimited = true
        val limited = assertFailsWith<SourceException> { reopened.sources.resolveAtPlayTime(PROFILE_A, again.id) }
        assertEquals("Test Tones: Rate limited. Too many new songs this hour. They play again in about 5 minutes.", limited.message)
        assertEquals(limited.message, reopened.sources.connection(PROFILE_A, connection.id)!!.lastError)
        addon.rateLimited = false

        // A protected stream is not played.
        addon.encrypted = true
        assertTrue(assertFailsWith<SourceException> { reopened.sources.resolveAtPlayTime(PROFILE_A, again.id) }.message!!.contains("protected"))
        addon.encrypted = false

        // An addon that answers the Eclipse app only: DebridOne does not pretend to be it.
        addon.eclipseOnly = true
        val refused = assertFailsWith<SourceException> { reopened.sources.search(PROFILE_A, connection.id, "opening") }
        assertTrue(refused.message!!.contains("Eclipse app only") || refused.message!!.contains("token"), refused.message)
        addon.eclipseOnly = false

        // Switched off: its songs are not played. Switched on again and the addon gone: said plainly, without its address.
        reopened.sources.setEnabled(PROFILE_A, connection.id, false)
        assertNull(reopened.sources.resolveUri(PROFILE_A, reopened.library.track(PROFILE_A, kept.id)!!))
        reopened.sources.setEnabled(PROFILE_A, connection.id, true)
        addon.close()
        val gone = assertFailsWith<SourceException> { reopened.sources.resolveAtPlayTime(PROFILE_A, again.id) }
        assertTrue(gone.message!!.startsWith("Test Tones could not be reached"))
        assertFalse(gone.message!!.contains(StandInAddon.TOKEN) || gone.message!!.contains("127.0.0.1"))
        assertFalse(env.dataFolderContains(StandInAddon.TOKEN), "not even the recorded error mentions the address")
    }

    // ---------------------------------------------------------------- saving

    @Test
    fun savingIsOffUntilSwitchedOnAndFilesAreNamedForWhatTheyAre() {
        val music = env.open()
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        val songs = music.sources.materialize(PROFILE_A, connection.id, music.sources.albumTracks(PROFILE_A, connection.id, "al1"), keep = true)
        val (opening, secondLight) = songs

        // Installing an addon does not allow saving its songs.
        assertFalse(music.sources.canDownload(PROFILE_A, opening))
        assertEquals(0, music.sources.download(PROFILE_A, listOf(opening.id)))
        assertTrue(music.sources.activity.value.lastDownloadError!!.contains("not switched on"))
        assertFalse(addon.requests.any { it.startsWith("/audio/") }, "nothing was fetched")

        music.sources.setAddonDownloads(PROFILE_A, connection.id, true)
        assertTrue(music.sources.canDownload(PROFILE_A, music.library.track(PROFILE_A, opening.id)!!))
        assertEquals(2, music.sources.download(PROFILE_A, listOf(opening.id, secondLight.id)))

        val savedOpening = Paths.get(music.library.track(PROFILE_A, opening.id)!!.offlinePath!!)
        val savedSecond = Paths.get(music.library.track(PROFILE_A, secondLight.id)!!.offlinePath!!)
        assertTrue(savedOpening.fileName.toString().endsWith(".mp3"))
        assertTrue(savedSecond.fileName.toString().endsWith(".flac"), "the addon called it mp3; the file is FLAC and is named so: ${savedSecond.fileName}")
        assertTrue(Files.readAllBytes(savedSecond).contentEquals(Files.readAllBytes(Fixtures.flac)))

        // A saved song plays from the file, with the addon gone.
        addon.close()
        assertEquals(savedOpening.toString(), music.sources.resolveUri(PROFILE_A, music.library.track(PROFILE_A, opening.id)!!))

        // Switching saving off again keeps what was saved but saves nothing new.
        music.sources.setAddonDownloads(PROFILE_A, connection.id, false)
        assertFalse(music.sources.canDownload(PROFILE_A, music.library.track(PROFILE_A, songs[2].id)!!))
        music.sources.removeDownload(PROFILE_A, opening.id)
        assertFalse(Files.exists(savedOpening))
    }

    // ---------------------------------------------------------------- profiles, diagnostics, lyrics

    @Test
    fun eachProfileHasItsOwnAddonsAndSettings() {
        val music = env.open()
        val mine = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        music.sources.setAddonSetting(PROFILE_A, mine.id, "quality", "low")
        music.sources.setAddonDownloads(PROFILE_A, mine.id, true)

        // The other profile sees nothing of it: no addon, no songs, no stored address.
        assertEquals(emptyList(), music.sources.addonConnections(PROFILE_B))
        assertEquals(emptyList(), music.sources.catalogs(PROFILE_B))
        assertNull(music.sources.connection(PROFILE_B, mine.id))
        assertNull(env.storedSecret(PROFILE_B, "source-${mine.id}"))
        assertTrue(assertFailsWith<SourceException> { music.sources.search(PROFILE_B, mine.id, "opening") }.message!!.contains("no longer exists"))

        // It can install the same addon for itself, with its own choices.
        env.switchProfile(PROFILE_B)
        val theirs = music.sources.addAddon(PROFILE_B, addon.manifestAddress)
        assertEquals("high", music.sources.addonSettingValues(theirs)["quality"])
        assertFalse(music.sources.adapter(EclipseAddonAdapter.ID)!!.allowsDownload(theirs))

        // Removing it in one profile leaves the other's as it was.
        music.sources.remove(PROFILE_B, theirs.id)
        assertNull(env.storedSecret(PROFILE_B, "source-${theirs.id}"))
        val still = music.sources.connection(PROFILE_A, mine.id)!!
        assertEquals("low", music.sources.addonSettingValues(still)["quality"])
        assertTrue(music.sources.adapter(EclipseAddonAdapter.ID)!!.allowsDownload(still))
        assertNotNull(env.storedSecret(PROFILE_A, "source-${mine.id}"))
    }

    @Test
    fun theConnectionTestSaysWhatWorksStepByStep() {
        val music = env.open()
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)

        val checks = music.sources.diagnoseAddon(PROFILE_A, connection.id, "test")
        assertEquals(listOf("Manifest", "Search", "Browsing rows", "Stream address", "Audio"), checks.map { it.step })
        assertTrue(checks.all { it.ok }, checks.toString())
        assertTrue(checks[1].detail.contains("4 songs, 1 album, 1 artist, 1 playlist"), checks[1].detail)
        assertTrue(checks[4].detail.contains("MP3"), "the audio is identified from its first bytes, whatever the server calls it: ${checks[4].detail}")
        assertTrue(checks.none { it.detail.contains(StandInAddon.TOKEN) || it.detail.contains("127.0.0.1") })
        assertEquals(true, music.sources.connection(PROFILE_A, connection.id)!!.lastTestOk)

        // An addon whose stream step fails: the failing step is named and recorded.
        addon.rateLimited = true
        val failing = music.sources.diagnoseAddon(PROFILE_A, connection.id, "test")
        assertEquals("Stream address", failing.last().step)
        assertFalse(failing.last().ok)
        val recorded = music.sources.connection(PROFILE_A, connection.id)!!
        assertEquals(false, recorded.lastTestOk)
        assertTrue(recorded.lastError!!.startsWith("Stream address: "))

        // The addon changed its id behind the same address: the plain test notices.
        addon.rateLimited = false
        addon.manifestOverride = StandInAddon.MANIFEST.replace("org.debridone.test-addon", "org.someone.else")
        assertTrue(music.sources.test(PROFILE_A, connection.id).exceptionOrNull()!!.message!!.contains("different addon"))
    }

    @Test
    fun anAddonThatOffersLyricsIsAskedForThem() {
        val music = env.open()
        val connection = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
        val songs = music.sources.materialize(PROFILE_A, connection.id, music.sources.albumTracks(PROFILE_A, connection.id, "al1"), keep = true)
        assertEquals("[00:00.50] A test tone begins\n[00:01.50] and ends", music.sources.addonLyrics(PROFILE_A, songs[0]))
        assertNull(music.sources.addonLyrics(PROFILE_A, songs[1]), "none for a song the addon has no lyrics for")
        assertTrue(addon.requests.any { it.startsWith("/lyrics?") && it.contains("artist=Test%20Artist") && it.contains("title=Opening") })
    }

    // ---------------------------------------------------------------- what audio really is

    @Test
    fun audioIsRecognisedFromItsFirstBytes() {
        fun kind(file: java.nio.file.Path) = AudioSniffer.fromBytes(Files.newInputStream(file).use { it.readNBytes(4_096) })
        assertEquals("mp3", kind(Fixtures.mp3))
        assertEquals("flac", kind(Fixtures.flac))
        assertEquals("m4a", kind(Fixtures.aac))
        assertEquals("m4a", kind(Fixtures.alac))
        assertEquals("wav", kind(Fixtures.wav))
        assertEquals("ogg", kind(Fixtures.ogg))
        assertEquals("opus", kind(Fixtures.opus))
        assertEquals("aiff", kind(Fixtures.aiff))
        assertEquals("aac", kind(Fixtures.adts))
        assertEquals("hls", AudioSniffer.fromBytes("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()))
        assertEquals("dash", AudioSniffer.fromBytes("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">".toByteArray()))
        assertNull(AudioSniffer.fromBytes("<html><body>Sign in to continue</body></html>".toByteArray()))
        assertEquals("flac", AudioSniffer.detect(ByteArray(8), "audio/flac; charset=binary"), "the server's word is used only when the bytes say nothing")
        assertNull(AudioSniffer.extension("hls"), "a playlist of segments is not one file")
    }
}
