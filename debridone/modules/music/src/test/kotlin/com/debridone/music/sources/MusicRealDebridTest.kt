package com.debridone.music.sources

import com.debridone.music.Fixtures
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.awaitTrue
import com.debridone.music.library.TrackFilter
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
 * The user's own Real-Debrid cloud as a Music source, against a local server that answers as
 * Real-Debrid's API documentation describes (see [StandInRealDebrid]): sign-in with a code, what
 * is listed, playback through the real engine, saving, renewing the sign-in, profiles, and its
 * independence from addons. The real service is not contacted by any test.
 */
class MusicRealDebridTest {
    private val env = MusicTestEnvironment()
    private val cloud = StandInRealDebrid()

    @AfterTest
    fun tearDown() {
        env.close()
        cloud.close()
    }

    private fun signIn(music: com.debridone.music.MusicServices, profileId: Long = PROFILE_A): SourceConnection {
        val pending = music.sources.beginRealDebridSignIn(cloud.origin)
        cloud.approved = true
        return assertNotNull(music.sources.finishRealDebridSignIn(profileId, pending))
    }

    /** Restarts Music without its start-up synchronisation, so that the test decides when the cloud is read. */
    private fun restart(): com.debridone.music.MusicServices {
        env.currentModule.services.value!!.preferences.set(com.debridone.music.MusicPreferences.RESCAN_ON_START, "false")
        return env.open()
    }

    private fun secrets() = listOfNotNull(
        cloud.accessToken, cloud.refreshToken, StandInRealDebrid.CLIENT_SECRET, StandInRealDebrid.DEVICE_CODE, StandInRealDebrid.PRIVATE_TOKEN,
    )

    private fun assertNoSecretOnDisk(why: String) = secrets().forEach { assertFalse(env.dataFolderContains(it), "$why: $it must be in the credential store only") }

    private fun songs(music: com.debridone.music.MusicServices, connection: SourceConnection, profileId: Long = PROFILE_A) =
        music.library.tracks(profileId, TrackFilter(sourceId = connection.id))

    // ---------------------------------------------------------------- sign-in

    @Test
    fun signingInWithACodeConnectsTheCloudAndKeepsTheCredentialsSecret() {
        val music = env.open()
        val pending = music.sources.beginRealDebridSignIn(cloud.origin)
        assertEquals(StandInRealDebrid.USER_CODE, pending.userCode)
        assertEquals("https://real-debrid.com/device", pending.verificationUrl)
        assertEquals(5, pending.intervalSeconds)

        // Until the user allows it on Real-Debrid's page, nothing is connected and nothing is stored.
        assertNull(music.sources.finishRealDebridSignIn(PROFILE_A, pending))
        assertEquals(emptyList(), music.sources.connections(PROFILE_A))

        cloud.approved = true
        val connection = assertNotNull(music.sources.finishRealDebridSignIn(PROFILE_A, pending))
        assertEquals(RealDebridAdapter.ID, connection.adapter)
        assertEquals("Real-Debrid", connection.name)
        assertEquals(StandInRealDebrid.USERNAME, connection.username)
        assertEquals("premium", connection.config["type"])
        assertEquals(true, connection.lastTestOk)

        // What Real-Debrid handed over is in the credential store, and only there.
        val stored = assertNotNull(env.storedSecret(PROFILE_A, "source-${connection.id}"))
        assertTrue(stored.contains(cloud.accessToken!!) && stored.contains(cloud.refreshToken!!) && stored.contains(StandInRealDebrid.CLIENT_SECRET))
        assertNoSecretOnDisk("after signing in")

        // The same account is not connected twice.
        val again = music.sources.beginRealDebridSignIn(cloud.origin)
        assertTrue(assertFailsWith<SourceException> { music.sources.finishRealDebridSignIn(PROFILE_A, again) }.message!!.contains("already connected"))
        assertEquals(1, music.sources.connections(PROFILE_A).size)

        // Removing the connection removes the credentials with it.
        music.sources.remove(PROFILE_A, connection.id)
        assertNull(env.storedSecret(PROFILE_A, "source-${connection.id}"))
    }

    @Test
    fun aPrivateApiTokenIsTheOtherWayIn() {
        val music = env.open()
        val wrong = assertFailsWith<SourceException> { music.sources.addRealDebridWithToken(PROFILE_A, "NOTTHETOKEN0123456789ABCDEFGHIJKLMNOP", cloud.origin) }
        assertTrue(wrong.message!!.contains("does not accept"), wrong.message)
        assertFalse(wrong.message!!.contains("NOTTHETOKEN"))
        assertTrue(assertFailsWith<SourceException> { music.sources.addRealDebridWithToken(PROFILE_A, "short", cloud.origin) }.message!!.contains("does not look like"))
        assertEquals(emptyList(), music.sources.connections(PROFILE_A), "a refused token leaves nothing behind")

        val connection = music.sources.addRealDebridWithToken(PROFILE_A, "  ${StandInRealDebrid.PRIVATE_TOKEN}\n", cloud.origin)
        assertEquals(StandInRealDebrid.USERNAME, connection.username)
        assertTrue(env.storedSecret(PROFILE_A, "source-${connection.id}")!!.contains(StandInRealDebrid.PRIVATE_TOKEN))
        assertFalse(env.dataFolderContains(StandInRealDebrid.PRIVATE_TOKEN))
        assertEquals(6, music.sources.sync(PROFILE_A, connection.id).total)
        assertFalse(cloud.requests.any { it.startsWith("POST /oauth") }, "a private token is never renewed")
    }

    // ---------------------------------------------------------------- what is listed

    @Test
    fun onlyTheAudioAlreadyInTheCloudIsListedAndNothingIsEverChanged() {
        var music = env.open()
        val connection = signIn(music)
        assertEquals(SyncSummary(6, 6, 0, 0), music.sources.sync(PROFILE_A, connection.id))

        val listed = songs(music, connection)
        assertEquals(
            setOf("Opening", "Second Light", "Third Wave", "Sixth Sense", "recording", "Evening"),
            listed.map { it.title }.toSet(),
            "the film, the cover, the unfinished torrent and the packed one are not songs",
        )
        // Real-Debrid knows files, not tags: names come from the files and their folder.
        val opening = listed.single { it.title == "Opening" }
        assertEquals("Test Artist", opening.artist)
        assertEquals("First Album", opening.album)
        assertEquals(2021, opening.year)
        assertEquals(1, opening.trackNo)
        assertEquals("MP3", opening.format)
        assertEquals(Files.size(Fixtures.mp3), opening.fileSize)
        assertEquals(listOf(1, 2, 3), listed.filter { it.album == "First Album" }.sortedBy { it.trackNo }.map { it.trackNo })
        assertEquals("FLAC", listed.single { it.title == "Second Light" }.format)
        assertEquals(6, listed.single { it.title == "Sixth Sense" }.trackNo)
        assertEquals("WAV", listed.single { it.title == "recording" }.format, "an audio type is enough when the name says nothing")
        val evening = listed.single { it.title == "Evening" }
        assertEquals("7 Lamps", evening.artist, "a band's name that starts with a number is not a track number")
        assertEquals("OPUS", evening.format)

        // One request for the list of torrents, one for each finished torrent, one for the downloads.
        assertEquals(3, cloud.requests.count { it.startsWith("GET /rest/1.0/torrents/info/") })
        assertFalse(cloud.requests.any { it.endsWith("/BUSY1") }, "an unfinished torrent is not looked into")

        // Synchronising again changes nothing and does not ask about the same torrents twice,
        // not even after a restart: what a finished torrent contains is remembered.
        assertEquals(SyncSummary(6, 0, 0, 0), music.sources.sync(PROFILE_A, connection.id))
        music = restart()
        assertEquals(SyncSummary(6, 0, 0, 0), music.sources.sync(PROFILE_A, connection.id))
        assertEquals(3, cloud.requests.count { it.startsWith("GET /rest/1.0/torrents/info/") })

        // A torrent the user deleted on Real-Debrid leaves the library at the next synchronisation.
        cloud.deletedTorrents += "ALBUM1"
        val after = music.sources.sync(PROFILE_A, connection.id)
        // "Opening" is still there as a link generated earlier; the two other songs of the album are gone.
        assertEquals(4, after.total)
        assertEquals(setOf("Opening", "Sixth Sense", "recording", "Evening"), songs(music, connection).map { it.title }.toSet())

        // Every method called reads; none adds, selects or deletes.
        val called = cloud.requests.map { if (it.startsWith("GET /rest/1.0/torrents/info/")) "GET /rest/1.0/torrents" else it }.toSet()
        assertTrue(StandInRealDebrid.READ_ONLY.containsAll(called), "unexpected methods: ${called - StandInRealDebrid.READ_ONLY}")
        assertFalse(cloud.requests.any { it.startsWith("POST /rest/1.0/unrestrict") }, "listing asks for no download address")
        assertNoSecretOnDisk("after synchronising")
    }

    @Test
    fun aListingThatFailsNeverEmptiesTheLibrary() {
        val connection = signIn(env.open())
        env.currentModule.services.value!!.sources.sync(PROFILE_A, connection.id)

        // A torrent finishes downloading while Real-Debrid has trouble answering about torrents.
        val music = restart()
        assertEquals(6, songs(music, connection).size)
        cloud.busyFinished = true
        cloud.infoBroken = true
        val broken = assertFailsWith<SourceException> { music.sources.sync(PROFILE_A, connection.id) }
        assertTrue(broken.message!!.contains("not available"), broken.message)
        assertEquals(6, songs(music, connection).size, "a listing cut short is not a smaller cloud")
        assertEquals(broken.message, music.sources.connection(PROFILE_A, connection.id)!!.lastError)
        cloud.infoBroken = false

        cloud.locked = true
        val locked = assertFailsWith<SourceException> { music.sources.sync(PROFILE_A, connection.id) }
        assertTrue(locked.message!!.contains("permission denied") && locked.message!!.contains("locked"), locked.message)
        assertEquals(6, songs(music, connection).size)
        cloud.locked = false

        // Working again: the newly finished album arrives, and nothing else changed.
        assertEquals(SyncSummary(7, 1, 0, 0), music.sources.sync(PROFILE_A, connection.id))
        assertNull(music.sources.connection(PROFILE_A, connection.id)!!.lastError)
        val elsewhere = songs(music, connection).single { it.title == "Elsewhere" }
        assertEquals("Other Artist", elsewhere.artist)
        assertEquals("Second Album", elsewhere.album)
    }

    @Test
    fun requestsAreSpacedAndALimitIsWaitedOutOnce() {
        val pauses = ArrayList<Long>()
        val adapter = RealDebridAdapter(pause = { pauses += it })
        val pending = adapter.beginSignIn(cloud.origin)
        assertNull(adapter.pollSignIn(pending), "not approved yet")
        cloud.approved = true
        val secret = assertNotNull(adapter.pollSignIn(pending))
        val connection = SourceConnection(7, RealDebridAdapter.ID, "Real-Debrid", cloud.origin, StandInRealDebrid.USERNAME, emptyMap(), true, 1, null, null, null, null)

        val listed = ArrayList<RemoteTrack>()
        adapter.listTracks(connection, secret) { listed += it }
        assertEquals(6, listed.size)
        assertEquals(3, pauses.size, "a pause before each look into a torrent")
        assertTrue(pauses.all { it in 200..1_000 }, "under 250 requests a minute: $pauses")

        // Told to slow down once: it waits properly and asks again.
        pauses.clear()
        cloud.rateLimited.set(1)
        listed.clear()
        adapter.listTracks(connection, secret) { listed += it }
        assertEquals(6, listed.size)
        assertEquals(listOf(20_000L), pauses.filter { it > 1_000 })

        // Told again straight after: it gives up with a plain message instead of hammering.
        cloud.rateLimited.set(5)
        val refused = assertFailsWith<SourceException> { adapter.listTracks(connection, secret) { } }
        assertTrue(refused.message!!.contains("limiting requests"), refused.message)
        assertEquals(2, 5 - cloud.rateLimited.get(), "asked twice, not five times")
    }

    // ---------------------------------------------------------------- playback and saving

    @Test
    fun cloudFilesPlayThroughTheRealEngineWithAnAddressMadeWhenTheSongStarts() {
        requireLibmpv()
        val music = env.open { MpvAudioEngine(audioOutput = "null") }
        val connection = signIn(music)
        music.sources.sync(PROFILE_A, connection.id)
        val album = songs(music, connection).filter { it.album == "First Album" }.sortedBy { it.trackNo }
        val sixth = songs(music, connection).single { it.title == "Sixth Sense" }

        music.player.play(album + sixth, startIndex = 1)
        assertTrue(music.player.state.value.queue.all { it.uri.startsWith(DEFERRED_SCHEME) }, "the queue holds no address, only a promise to ask")
        awaitTrue(describe = { "the cloud file to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        assertEquals("Second Light", music.player.state.value.current?.title)
        assertNull(music.player.state.value.lastError)
        assertEquals(listOf("${StandInRealDebrid.LINK_HOST}/ALBUM1B"), cloud.forms.mapNotNull { it["link"] }, "only the song that plays was turned into an address")
        assertTrue(cloud.requests.any { it == "GET /files/02%20-%20Second%20Light.flac" }, "and it was played from that address")
        awaitTrue(describe = { "the length to be learned from the file" }) { music.player.state.value.durationMs > 1_000 }

        // A link generated earlier plays from the address Real-Debrid already has for it.
        music.player.skipTo(3)
        awaitTrue(describe = { "the generated link to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.current?.title == "Sixth Sense" && it.status == PlaybackStatus.PLAYING && it.positionMs > 200 }
        }
        assertEquals(1, cloud.forms.count { it.containsKey("link") }, "nothing new was unlocked for it")
        assertNoSecretOnDisk("after playing (queue, history, library)")
    }

    @Test
    fun cloudFilesCanBeSavedAndThenPlayWithoutRealDebrid() {
        val music = env.open()
        val connection = signIn(music)
        music.sources.sync(PROFILE_A, connection.id)
        val all = songs(music, connection)
        val second = all.single { it.title == "Second Light" }
        val recording = all.single { it.title == "recording" }

        assertTrue(music.sources.canDownload(PROFILE_A, second), "they are the user's own files")
        assertEquals(DEFERRED_SCHEME + second.id, music.sources.resolveUri(PROFILE_A, second))
        assertEquals(2, music.sources.download(PROFILE_A, listOf(second.id, recording.id)))
        assertNull(music.sources.activity.value.lastDownloadError)

        val savedSecond = Paths.get(music.library.track(PROFILE_A, second.id)!!.offlinePath!!)
        val savedRecording = Paths.get(music.library.track(PROFILE_A, recording.id)!!.offlinePath!!)
        assertTrue(savedSecond.fileName.toString().endsWith(".flac"), savedSecond.toString())
        assertTrue(Files.readAllBytes(savedSecond).contentEquals(Files.readAllBytes(Fixtures.flac)))
        assertTrue(Files.readAllBytes(savedRecording).contentEquals(Files.readAllBytes(Fixtures.wav)))

        // Saved songs play from the file: with the service gone, and after a restart.
        cloud.close()
        val reopened = restart()
        assertEquals(savedSecond.toString(), reopened.sources.resolveUri(PROFILE_A, reopened.library.track(PROFILE_A, second.id)!!))
        // A song that was not saved says in words that the service cannot be reached.
        val third = all.single { it.title == "Third Wave" }
        val unreachable = assertFailsWith<SourceException> { reopened.sources.resolveAtPlayTime(PROFILE_A, third.id) }
        assertEquals("Real-Debrid could not be reached.", unreachable.message)
    }

    // ---------------------------------------------------------------- the sign-in over time

    @Test
    fun anExpiringSignInIsRenewedAndTheNewOneIsKept() {
        cloud.tokenLifetimeSeconds = 30
        val music = env.open()
        val connection = signIn(music)
        val first = cloud.accessToken!!
        assertEquals("ACCESS-1-SECRET", first)

        // About to expire: it is renewed before it is used, and what is stored is the new one.
        cloud.tokenLifetimeSeconds = 3_600
        assertTrue(music.sources.test(PROFILE_A, connection.id).isSuccess)
        assertEquals("ACCESS-2-SECRET", cloud.accessToken)
        val stored = env.storedSecret(PROFILE_A, "source-${connection.id}")!!
        assertTrue(stored.contains("ACCESS-2-SECRET") && stored.contains("REFRESH-2-SECRET") && !stored.contains(first))
        assertEquals("REFRESH-1-SECRET", cloud.forms.last { it["grant_type"] != null }["code"], "renewed with the refresh token, as documented")

        // Good for an hour now: no further renewal, also after a restart.
        val reopened = restart()
        assertEquals(6, reopened.sources.sync(PROFILE_A, connection.id).total)
        assertEquals(2, cloud.requests.count { it == "POST /oauth/v2/token" })
        assertNoSecretOnDisk("after renewing")

        // The user withdraws DebridOne's rights on Real-Debrid: said in words, nothing is lost locally.
        cloud.revoked = true
        val refused = reopened.sources.test(PROFILE_A, connection.id).exceptionOrNull()
        assertTrue(refused is SourceException && refused.message!!.contains("sign in again"), refused?.message)
        assertEquals(refused!!.message, reopened.sources.connection(PROFILE_A, connection.id)!!.lastError)
        assertFailsWith<SourceException> { reopened.sources.sync(PROFILE_A, connection.id) }
        assertEquals(6, songs(reopened, connection).size)
        secrets().forEach { assertFalse(refused.message!!.contains(it)) }
    }

    // ---------------------------------------------------------------- profiles and addons

    @Test
    fun eachProfileHasItsOwnCloudAndAddonsHaveNoPartInIt() {
        val music = env.open()
        val connection = signIn(music)
        music.sources.sync(PROFILE_A, connection.id)

        // Another profile sees neither the connection, nor its songs, nor its credentials.
        env.switchProfile(PROFILE_B)
        assertEquals(emptyList(), music.sources.connections(PROFILE_B))
        assertEquals(emptyList(), music.library.tracks(PROFILE_B, TrackFilter()).filter { !it.isLocal })
        assertNull(env.storedSecret(PROFILE_B, "source-${connection.id}"))
        assertFailsWith<SourceException> { music.sources.sync(PROFILE_B, connection.id) }
        assertFailsWith<SourceException> { music.sources.resolveAtPlayTime(PROFILE_B, songs(music, connection).first().id) }
        env.switchProfile(PROFILE_A)

        // The cloud is a personal source, not a catalogue and not an addon.
        assertFalse(connection in music.sources.catalogs(PROFILE_A))
        assertEquals(emptyList(), music.sources.addonConnections(PROFILE_A))

        StandInAddon().use { addon ->
            val installed = music.sources.addAddon(PROFILE_A, addon.manifestAddress)
            // Installing an addon gives it nothing of the cloud: its own songs still may not be saved,
            // and nothing it is sent mentions Real-Debrid or its credentials.
            val addonSong = music.sources.materialize(PROFILE_A, installed.id, music.sources.search(PROFILE_A, installed.id, "opening").tracks, keep = true).single()
            assertFalse(music.sources.canDownload(PROFILE_A, addonSong))
            music.sources.resolveAtPlayTime(PROFILE_A, addonSong.id)
            music.sources.diagnoseAddon(PROFILE_A, installed.id, "test")
            assertTrue(addon.requests.isNotEmpty())
            (secrets() + listOf("real-debrid", "Bearer", StandInRealDebrid.USERNAME)).forEach { secret ->
                assertFalse(addon.requests.any { it.contains(secret, ignoreCase = true) }, "the addon was sent $secret")
            }
            assertFalse(env.storedSecret(PROFILE_A, "source-${installed.id}")!!.contains("ACCESS"), "each connection has its own secret")
            // And the cloud is asked nothing on an addon's behalf.
            val before = cloud.requests.size
            music.sources.search(PROFILE_A, installed.id, "light")
            music.sources.resolveAtPlayTime(PROFILE_A, addonSong.id)
            assertEquals(before, cloud.requests.size)

            // Removing the addon leaves the cloud's songs alone, and the other way round.
            music.sources.remove(PROFILE_A, installed.id)
            assertEquals(6, songs(music, connection).size)
        }
        music.sources.remove(PROFILE_A, connection.id)
        assertEquals(emptyList(), songs(music, connection))

        // A plugin manifest cannot ask for the cloud either: Real-Debrid is not an adapter a manifest may name.
        for (adapter in listOf(RealDebridAdapter.ID, EclipseAddonAdapter.ID)) {
            val manifest = """{"debridoneMusicPlugin":1,"id":"org.example.cloud","name":"My cloud","adapter":"$adapter","baseUrl":"https://example.org"}"""
            val refusal = MusicPluginManifest.parse(manifest).exceptionOrNull()
            assertTrue(refusal is SourceException && refusal.message!!.contains("does not have"), "a manifest naming \"$adapter\" must be refused: ${refusal?.message}")
        }
    }
}
