package com.debridone.music.sources

import com.debridone.music.Fixtures
import com.debridone.music.MusicServices
import com.debridone.music.MusicTestEnvironment
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_A
import com.debridone.music.MusicTestEnvironment.Companion.PROFILE_B
import com.debridone.music.awaitTrue
import com.debridone.music.library.Track
import com.debridone.music.library.TrackFilter
import com.debridone.music.playback.DEFERRED_SCHEME
import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.requireLibmpv
import com.debridone.music.summary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * What the application offers Music of the user's cloud accounts, in memory: accounts by name,
 * audio files by name, an address for one file, "save these". There is no provider behind it
 * and no sign-in in it, which is the point: Music is never given either.
 */
private class FakeCloud : CloudAudioSource {
    override val revision = MutableStateFlow(0L)
    val accountsOf = HashMap<Long, MutableList<CloudAudioSource.Account>>()
    val filesOf = HashMap<String, MutableList<CloudAudioSource.File>>()

    /** Where each file "is" right now. */
    val addressOf = HashMap<String, String>()
    val asked = CopyOnWriteArrayList<String>()
    val saved = CopyOnWriteArrayList<List<String>>()

    @Volatile
    var broken: IOException? = null

    fun account(profileId: Long, key: String, label: String, vararg files: CloudAudioSource.File) {
        accountsOf.getOrPut(profileId) { ArrayList() } += CloudAudioSource.Account(key, label)
        filesOf.getOrPut(key) { ArrayList() } += files
    }

    override fun accounts(profileId: Long): List<CloudAudioSource.Account> = accountsOf[profileId].orEmpty().toList()

    override fun audioFiles(profileId: Long, accountKey: String): List<CloudAudioSource.File> {
        broken?.let { throw it }
        check(accounts(profileId).any { it.key == accountKey }) { "Music asked for an account this profile may not use" }
        return filesOf[accountKey].orEmpty().toList()
    }

    override fun address(profileId: Long, accountKey: String, fileKey: String): String {
        broken?.let { throw it }
        check(accounts(profileId).any { it.key == accountKey }) { "Music asked for an account this profile may not use" }
        asked += fileKey
        return addressOf[fileKey] ?: throw IOException("This file is no longer in your account.")
    }

    override fun download(profileId: Long, accountKey: String, fileKeys: List<String>): String {
        saved += fileKeys
        return "${fileKeys.size} downloads started; they are listed under Downloads."
    }
}

/**
 * Cloud Music: the audio of the application's connected accounts as Music sees it. Music is given
 * names, never a sign-in; cloud songs are listed outside the library until added; an address is
 * asked when a song starts; saving goes through the application; and a saved file describes its
 * song with its own tags. Everything runs against [FakeCloud]: no provider is contacted.
 */
class MusicCloudTest {
    private val env = MusicTestEnvironment()
    private val cloud = FakeCloud()

    @AfterTest
    fun tearDown() = env.close()

    private fun file(key: String, path: String, size: Long = 4_000) = CloudAudioSource.File(key, path, size, null)

    private val album = arrayOf(
        file("torrent:ALBUM1:a", "Test Artist - First Album (2019)/01 - Opening.mp3"),
        file("torrent:ALBUM1:b", "Test Artist - First Album (2019)/02 - Second Light.flac"),
        file("torrent:ALBUM1:c", "Test Artist - First Album (2019)/03 - Third Wave.m4a"),
    )

    private fun open(profileId: Long = PROFILE_A, engine: (() -> com.debridone.music.playback.MusicPlaybackEngine)? = null): MusicServices {
        val music = if (engine == null) env.open(profileId, cloudAudio = cloud) else env.open(profileId, cloudAudio = cloud, engineFactory = engine)
        refresh()
        return music
    }

    private fun refresh() = runBlocking { env.currentModule.refreshCloud() }

    private fun listed(music: MusicServices, profileId: Long = PROFILE_A): List<Track> =
        music.library.tracks(profileId, TrackFilter(sourceIds = music.sources.cloudConnections(profileId).map { it.id }, includeHidden = true))

    private fun library(music: MusicServices, profileId: Long = PROFILE_A): List<Track> = music.library.tracks(profileId).filter { !it.isLocal }

    // ---------------------------------------------------------------- listing

    @Test
    fun cloudSongsAreListedOutsideTheLibraryUntilTheListenerAddsThem() {
        cloud.account(PROFILE_A, "7", "Real-Debrid", *album, file("link:DL3:x", "7 Lamps - Evening.ogg"))
        val music = open()

        val connection = music.sources.cloudConnections(PROFILE_A).single()
        assertEquals(CloudLibraryAdapter.ID, connection.adapter)
        assertEquals("Real-Debrid", connection.name)
        assertEquals(IntegrationKind.PERSONAL_CLOUD, music.sources.adapter(connection.adapter)!!.kind)
        assertNull(env.storedSecret(PROFILE_A, "source-${connection.id}"), "Music holds no sign-in for a cloud account")

        // Listed, with names read from files and folders...
        val songs = listed(music)
        assertEquals(listOf("Evening", "Opening", "Second Light", "Third Wave"), songs.map { it.title }.sorted())
        val opening = songs.single { it.title == "Opening" }
        assertEquals("Test Artist", opening.artist)
        assertEquals("First Album", opening.album)
        assertEquals(2019, opening.year)
        assertEquals(1, opening.trackNo)
        assertEquals("7 Lamps", songs.single { it.title == "Evening" }.artist, "a band whose name starts with a number is not track seven")
        // ...but not in the library: no song, no album, no artist, nothing to search.
        assertTrue(songs.all { it.hidden })
        assertEquals(emptyList(), library(music))
        assertEquals(0, connection.trackCount)
        assertTrue(music.library.albums(PROFILE_A).none { it.title == "First Album" && it.trackCount < 7 })
        assertTrue(music.library.search(PROFILE_A, "evening").tracks.isEmpty())

        // Added by the listener: in the library with its album and artist, and it stays added when the cloud is read again.
        assertEquals(1, music.library.keepTracks(PROFILE_A, listOf(opening.id)))
        refresh()
        val kept = library(music).single()
        assertEquals("Opening", kept.title)
        assertNotNull(kept.albumId)
        assertFalse(kept.hidden)
        assertEquals(4, listed(music).size, "added or not, the cloud is listed whole")

        // Removed from the library: listed again as before, deleted nowhere.
        music.sources.removeFromLibrary(PROFILE_A, listOf(opening.id))
        assertEquals(emptyList(), library(music))
        assertTrue(listed(music).single { it.id == opening.id }.hidden)

        // Old listings are not "songs played once and forgotten": tidying up leaves them.
        music.library.purgeHidden(PROFILE_A, Long.MAX_VALUE, music.sources.cloudConnections(PROFILE_A).map { it.id })
        assertEquals(4, listed(music).size)

        // A file that left the account leaves Music; a new one arrives.
        cloud.filesOf["7"]!!.removeIf { it.key == "link:DL3:x" }
        cloud.filesOf["7"]!! += file("usenet:9:z", "Other Artist - Second Album/01 Elsewhere.mp3")
        refresh()
        assertEquals(listOf("Elsewhere", "Opening", "Second Light", "Third Wave"), listed(music).map { it.title }.sorted())
    }

    @Test
    fun aListingThatFailsLeavesWhatWasListed() {
        cloud.account(PROFILE_A, "7", "Real-Debrid", *album)
        val music = open()
        music.library.keepTracks(PROFILE_A, listed(music).map { it.id })
        assertEquals(3, library(music).size)

        cloud.broken = IOException("Real-Debrid is not answering right now.")
        refresh()
        assertEquals(3, listed(music).size, "a listing that could not be read is not an empty cloud")
        assertEquals(3, library(music).size)
        val connection = music.sources.cloudConnections(PROFILE_A).single()
        val failure = assertFailsWith<SourceException> { music.sources.sync(PROFILE_A, connection.id) }
        assertEquals("Real-Debrid is not answering right now.", failure.message, "what the application said reaches the listener as it is")

        cloud.broken = null
        refresh()
        assertEquals(3, library(music).size)
        assertNull(music.sources.connection(PROFILE_A, connection.id)!!.lastError)
    }

    // ---------------------------------------------------------------- profiles and accounts

    @Test
    fun eachProfileSeesOnlyTheAccountsItMayUseAndTheyGoWhenTheAccountGoes() {
        cloud.account(PROFILE_A, "7", "Real-Debrid", *album)
        cloud.account(PROFILE_A, "8", "TorBox", file("torrent:101:q", "Other Artist - Second Album/01 Elsewhere.mp3"))
        cloud.account(PROFILE_B, "8", "TorBox")
        var music = open()
        runBlocking { env.currentModule.addFolder(env.musicFolder).join() }
        assertEquals(listOf("Real-Debrid", "TorBox"), music.sources.cloudConnections(PROFILE_A).map { it.name })
        assertEquals(4, listed(music).size)
        val elsewhere = listed(music).single { it.title == "Elsewhere" }
        music.library.keepTracks(PROFILE_A, listOf(elsewhere.id))

        // The other profile shares only the TorBox account, and has a library of its own.
        env.switchProfile(PROFILE_B)
        refresh()
        music = env.currentModule.services.value!!
        assertEquals(listOf("TorBox"), music.sources.cloudConnections(PROFILE_B).map { it.name })
        assertEquals(listOf("Elsewhere"), listed(music, PROFILE_B).map { it.title })
        assertEquals(emptyList(), library(music, PROFILE_B), "what profile A added is not in profile B's library")
        assertEquals(4, listed(music, PROFILE_A).size, "profile A's listing is untouched")

        // The account is renamed and then disconnected in Settings: Music follows, and keeps nothing of it.
        env.switchProfile(PROFILE_A)
        cloud.accountsOf[PROFILE_A]!!.replaceAll { if (it.key == "7") CloudAudioSource.Account("7", "Real-Debrid (listener)") else it }
        refresh()
        assertEquals(listOf("Real-Debrid (listener)", "TorBox"), music.sources.cloudConnections(PROFILE_A).map { it.name })
        cloud.accountsOf[PROFILE_A]!!.removeIf { it.key == "8" }
        refresh()
        assertEquals(listOf("Real-Debrid (listener)"), music.sources.cloudConnections(PROFILE_A).map { it.name })
        assertEquals(3, listed(music).size)
        assertEquals(emptyList(), library(music), "a song of a disconnected account cannot stay: there is nowhere to play it from")
        // The songs on this computer were never part of it.
        assertEquals(Fixtures.TOTAL_TRACKS, music.library.trackCount(PROFILE_A, TrackFilter(sourceId = 0)))
    }

    @Test
    fun cloudConnectionsCannotBeMadeOrReachedAnyOtherWay() {
        cloud.account(PROFILE_A, "7", "Real-Debrid", *album)
        val music = open()
        val connection = music.sources.cloudConnections(PROFILE_A).single()

        // Not from a form, and not by naming the adapter.
        val refused = assertFailsWith<SourceException> { music.sources.add(PROFILE_A, CloudLibraryAdapter.ID, SourceDraft("mine", "https://example.org", "user", "pw")) }
        assertTrue(refused.message!!.contains("Connected Accounts"), refused.message)
        assertEquals(1, music.sources.cloudConnections(PROFILE_A).size)

        // It is not a catalogue to search, not an addon, and not a plugin: nothing an addon manifest names can be it.
        assertTrue(music.sources.catalogs(PROFILE_A).none { it.id == connection.id })
        assertTrue(music.sources.addonConnections(PROFILE_A).isEmpty())
        assertTrue(music.sources.manifests().none { it.adapter == CloudLibraryAdapter.ID })

        // Should the application's cloud ever be missing, there is no such kind of source: nothing is listed
        // or played, and nothing the listener had is thrown away because of it.
        music.library.keepTracks(PROFILE_A, listed(music).map { it.id })
        env.currentModule.services.value!!.preferences.set(com.debridone.music.MusicPreferences.RESCAN_ON_START, "false")
        val alone = env.open()
        assertNull(alone.sources.adapter(CloudLibraryAdapter.ID))
        assertEquals(3, library(alone).size)
        assertNull(alone.sources.resolveUri(PROFILE_A, library(alone).first()))
        assertFailsWith<SourceException> { alone.sources.sync(PROFILE_A, connection.id) }
    }

    // ---------------------------------------------------------------- playback and saving

    @Test
    fun aCloudSongPlaysFromAnAddressAskedWhenItStarts() {
        requireLibmpv()
        cloud.account(PROFILE_A, "7", "Real-Debrid", *album)
        cloud.addressOf["torrent:ALBUM1:a"] = Fixtures.mp3.toString()
        cloud.addressOf["torrent:ALBUM1:b"] = Fixtures.flac.toString()
        val music = open { MpvAudioEngine(audioOutput = "null") }
        val songs = listed(music).sortedBy { it.trackNo }

        music.player.play(songs, startIndex = 1)
        assertTrue(music.player.state.value.queue.all { it.uri.startsWith(DEFERRED_SCHEME) }, "the queue holds no address, only a promise to ask")
        awaitTrue(describe = { "the cloud song to play; state: ${music.player.state.value.summary()}" }) {
            music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 300 }
        }
        assertEquals("Second Light", music.player.state.value.current?.title)
        assertNull(music.player.state.value.lastError)
        assertEquals(listOf("torrent:ALBUM1:b"), cloud.asked.toList(), "only the song that plays was asked for")
        assertFalse(env.dataFolderContains(Fixtures.flac.toString()), "the address is used, not kept")
        // Played but not added: it is in the history, not in the library.
        awaitTrue(describe = { "the play to be noted" }) { listed(music).single { it.title == "Second Light" }.lastPlayedAt != null || music.player.state.value.positionMs > 1_500 }
        assertEquals(emptyList(), library(music))

        // A file that is gone from the account says so instead of playing something else.
        music.player.skipTo(2)
        awaitTrue(describe = { "the missing file to be reported; state: ${music.player.state.value.summary()}" }) { music.player.state.value.lastError != null }
        assertTrue(music.player.state.value.lastError!!.message.contains("no longer in your account"), music.player.state.value.lastError!!.message)
    }

    @Test
    fun savingGoesThroughTheApplicationAndTheSavedFileDescribesItsSong() {
        // The cloud calls the file by a bare name: all Music can guess is a title.
        cloud.account(PROFILE_A, "7", "Real-Debrid", file("link:DL1:x", "track01.mp3"), *album)
        val music = open()
        val guessed = listed(music).single { it.title == "track01" }
        assertNull(guessed.artist)
        assertTrue(music.sources.canDownload(PROFILE_A, guessed))

        // "Download for offline" is handed to the application; Music fetches nothing itself.
        assertEquals(0, music.sources.download(PROFILE_A, listOf(guessed.id)))
        assertEquals(listOf(listOf("link:DL1:x")), cloud.saved.toList())
        assertEquals("1 downloads started; they are listed under Downloads.", music.sources.activity.value.downloadNote)
        assertNull(music.sources.activity.value.lastDownloadError)
        assertEquals(emptyList(), cloud.asked.toList(), "no address was asked: the application downloads")
        assertNull(listed(music).single { it.id == guessed.id }.offlinePath)

        // The application reports the finished file, in the user's own folder.
        val folder: Path = Files.createDirectories(env.root.resolve("Downloads/Music"))
        val copy = Files.copy(Fixtures.mp3, folder.resolve("track01.mp3"))
        assertTrue(music.sources.cloudFileSaved(PROFILE_A, "7", "link:DL1:x", copy))
        val saved = library(music).single()
        assertEquals(guessed.id, saved.id)
        assertEquals("Opening", saved.title, "the file's own tags replace the guess from its name")
        assertEquals("Test Artist", saved.artist)
        assertEquals("First Album", saved.album)
        assertNotNull(saved.artwork, "and so does its own cover")
        assertTrue(saved.durationMs > 1_000)
        assertEquals(copy.toString(), saved.offlinePath)
        assertEquals(copy.toString(), music.sources.resolveUri(PROFILE_A, saved), "it plays from the file from now on")
        assertTrue(music.library.tracks(PROFILE_A, TrackFilter(offlineOnly = true)).any { it.id == saved.id })

        // Reading the cloud again does not put the guess back.
        refresh()
        assertEquals("Opening", library(music).single().title)

        // "Remove the download" inside Music never deletes a file in the user's folder.
        music.sources.removeDownload(PROFILE_A, saved.id)
        assertTrue(Files.exists(copy))

        // The copy is deleted (in Downloads): the song stays in the library and plays from the cloud again.
        assertTrue(music.sources.cloudFileSaved(PROFILE_A, "7", "link:DL1:x", null))
        val after = library(music).single()
        assertNull(after.offlinePath)
        assertEquals(DEFERRED_SCHEME + after.id, music.sources.resolveUri(PROFILE_A, after))

        // A file downloaded from the file browser, without "add to the library", is only made known.
        val second = listed(music).single { it.title == "Second Light" }
        val flac = Files.copy(Fixtures.flac, folder.resolve("02 - Second Light.flac"))
        assertTrue(music.sources.cloudFileSaved(PROFILE_A, "7", "torrent:ALBUM1:b", flac, addToLibrary = false))
        assertTrue(listed(music).single { it.id == second.id }.hidden)
        assertEquals(flac.toString(), listed(music).single { it.id == second.id }.offlinePath)

        // Music knows no such song: the caller is told, and may add the file as a local one instead.
        assertFalse(music.sources.cloudFileSaved(PROFILE_A, "7", "torrent:NOPE:z", copy))
        assertFalse(music.sources.cloudFileSaved(PROFILE_A, "99", "link:DL1:x", copy))
    }

    @Test
    fun aSongAddedAndSavedOutlivesItsCloudEntryAndItsAccount() {
        cloud.account(PROFILE_A, "7", "Real-Debrid", *album)
        cloud.account(PROFILE_A, "8", "TorBox", file("torrent:101:q", "Other Artist - Second Album/01 Elsewhere.mp3"))
        val music = open()
        val folder: Path = Files.createDirectories(env.root.resolve("Downloads/Music"))
        val opening = Files.copy(Fixtures.mp3, folder.resolve("01 - Opening.mp3"))
        val elsewhere = Files.copy(Fixtures.elsewhere, folder.resolve("01 Elsewhere.mp3"))
        assertTrue(music.sources.cloudFileSaved(PROFILE_A, "7", "torrent:ALBUM1:a", opening))
        assertTrue(music.sources.cloudFileSaved(PROFILE_A, "8", "torrent:101:q", elsewhere))
        // A third song is added but not saved, a fourth saved but not added.
        val second = listed(music).single { it.title == "Second Light" }
        music.library.keepTracks(PROFILE_A, listOf(second.id))
        assertTrue(music.sources.cloudFileSaved(PROFILE_A, "7", "torrent:ALBUM1:c", Files.copy(Fixtures.aac, folder.resolve("03 - Third Wave.m4a")), addToLibrary = false))
        val saved = library(music).single { it.title == "Opening" }
        val playlist = music.playlists.create(PROFILE_A, "Kept")
        music.playlists.add(PROFILE_A, playlist.id, listOf(saved.id))
        music.library.setTrackFavorite(PROFILE_A, saved.id, true)

        // The album is deleted from the cloud (in Downloads, with its confirmation): its listing goes...
        cloud.filesOf["7"]!!.clear()
        refresh()
        assertEquals(emptyList(), listed(music).filter { it.sourceId != 0L && it.album == "First Album" })
        // ...but the song that was added and saved is still in the library, as the file it was saved as,
        val kept = assertNotNull(music.library.track(PROFILE_A, saved.id), "the same song, not a new one")
        assertTrue(kept.isLocal)
        assertEquals(opening.toString(), kept.path)
        assertEquals(opening.toString(), music.sources.resolveUri(PROFILE_A, kept))
        assertTrue(kept.favorite)
        assertEquals(listOf(saved.id), music.playlists.tracks(PROFILE_A, playlist.id).map { it.id })
        // while the one that was only added (nothing to play it from) and the one that was only saved (never in the library) are gone from Music.
        assertNull(music.library.track(PROFILE_A, second.id))
        assertTrue(music.library.tracks(PROFILE_A).none { it.title == "Third Wave" && !it.isLocal })
        assertTrue(Files.exists(folder.resolve("03 - Third Wave.m4a")), "no file was deleted")

        // The other account is disconnected: the same for its saved song.
        val other = library(music).single { it.title == "Elsewhere" }
        cloud.accountsOf[PROFILE_A]!!.removeIf { it.key == "8" }
        refresh()
        assertEquals(listOf("Real-Debrid"), music.sources.cloudConnections(PROFILE_A).map { it.name })
        val still = assertNotNull(music.library.track(PROFILE_A, other.id))
        assertTrue(still.isLocal)
        assertEquals(elsewhere.toString(), still.path)
        assertTrue(Files.exists(elsewhere))
    }

    @Test
    fun whatAnEarlierVersionLeftBehindIsCleanedUp() {
        // The first version kept a Real-Debrid sign-in inside Music. Its connection and its secret are removed.
        val music = env.open(cloudAudio = cloud)
        env.rawSql(
            "INSERT INTO music_sources (profile_id, adapter, name, base_url, username, config, enabled, priority, created_at, updated_at) " +
                "VALUES ($PROFILE_A, 'real-debrid', 'Real-Debrid', 'https://api.real-debrid.com', 'listener', '{}', 1, 1, 0, 0)",
        )
        val leftover = music.sources.connections(PROFILE_A).single { it.adapter == "real-debrid" }.id
        env.storeSecret(PROFILE_A, "source-$leftover", "old-token")
        assertNotNull(env.storedSecret(PROFILE_A, "source-$leftover"))

        cloud.account(PROFILE_A, "7", "Real-Debrid", *album)
        refresh()
        assertEquals(listOf(CloudLibraryAdapter.ID), music.sources.connections(PROFILE_A).map { it.adapter })
        assertNull(env.storedSecret(PROFILE_A, "source-$leftover"))
    }
}
