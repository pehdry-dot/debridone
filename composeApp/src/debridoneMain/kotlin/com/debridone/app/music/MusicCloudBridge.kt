package com.debridone.app.music

import com.debridone.app.DebridOneRuntime
import com.debridone.music.MusicServices
import com.debridone.music.library.Track
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Music offers the rest of the application for audio that comes from the user's connected
 * cloud accounts, and the places it asks to be taken to.
 *
 * Music knows no account, sign-in or address. It is told which audio files exist (by a name the
 * application chose for each), asks for one file's address at the moment it plays, and is told
 * when a file was saved to this computer. Its library, playlists, covers and player are its own;
 * its addons are never part of this.
 */
object MusicCloudBridge {
    /** Set by the application: opens Settings › Connected Accounts. Null: there is no such page. */
    @Volatile
    var openConnectedAccounts: (() -> Unit)? = null

    /** Set by the application: shows the audio files of the connected accounts in the file browser. */
    @Volatile
    var openCloudFiles: (() -> Unit)? = null

    /** Set by the application: reads the active profile's accounts again. */
    @Volatile
    var refreshAccounts: (() -> Unit)? = null

    /** One audio file of one account, by the names the application gave Music for them. */
    data class Key(val account: String, val file: String)

    private fun services(): MusicServices = DebridOneRuntime.modules.music.services.value ?: throw IOException("Music is not running.")

    /** The engine, for the profile that is looking at the screen. */
    private fun active(profileId: Long): MusicServices {
        val module = DebridOneRuntime.modules.music
        val active = module.activeProfile.value ?: throw IOException("Choose a profile first.")
        if (active != profileId) throw IOException("Music is open for another profile.")
        return services()
    }

    private suspend fun tracks(profileId: Long, keys: List<Key>): List<Track> = withContext(Dispatchers.IO) {
        val services = active(profileId)
        fun find() = keys.groupBy { it.account }.flatMap { (account, files) -> services.sources.cloudTracks(profileId, account, files.map { it.file }) }
        val found = find()
        if (found.size == keys.size) return@withContext found
        // The listing may be newer than what Music read last.
        DebridOneRuntime.modules.music.refreshCloud()
        find()
    }

    private suspend fun <T> answer(fallback: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Result.failure(IOException(e.message ?: fallback))
    } catch (e: RuntimeException) {
        Result.failure(IOException(fallback))
    }

    /** Plays cloud songs in Music's player. Returns what to tell the user when that is not possible. */
    suspend fun play(profileId: Long, keys: List<Key>): String? = answer("That could not be played.") {
        val tracks = tracks(profileId, keys)
        if (tracks.isEmpty()) throw IOException(if (keys.size == 1) "Music does not list this file as audio it can play." else "Music does not list these files as audio it can play.")
        val player = services().player
        player.play(tracks, 0)
        player.engineError.value?.let { "Audio is unavailable: $it" }
    }.fold({ it }, { it.message })

    /** Adds cloud songs to the active profile's library. Returns a sentence for the user. */
    suspend fun addToLibrary(profileId: Long, keys: List<Key>): String = answer("That could not be added to Music.") {
        val tracks = tracks(profileId, keys)
        if (tracks.isEmpty()) throw IOException("Music does not list ${if (keys.size == 1) "this file" else "these files"} as audio.")
        val kept = withContext(Dispatchers.IO) { services().library.keepTracks(profileId, tracks.map { it.id }) }
        when {
            kept == 0 -> if (tracks.size == 1) "“${tracks[0].title}” is already in your Music library." else "These songs are already in your Music library."
            kept == 1 -> "“${tracks.first().title}” is in your Music library."
            else -> "$kept songs are in your Music library."
        }
    }.fold({ it }, { it.message ?: "That could not be added to Music." })

    /** Takes cloud songs out of the library again. They stay listed under Cloud Music; nothing is deleted. */
    suspend fun removeFromLibrary(profileId: Long, key: Key) = withContext(Dispatchers.IO) {
        val services = services()
        services.sources.removeFromLibrary(profileId, services.sources.cloudTracks(profileId, key.account, listOf(key.file)).map { it.id })
    }

    /**
     * A cloud song now has a copy on this computer ([path]), or no longer has one (null). With a
     * copy it plays from the file, its own tags and cover are used, and with [addToLibrary] it
     * joins the library. False when Music lists no such song.
     */
    suspend fun fileSaved(profileId: Long, key: Key, path: Path?, addToLibrary: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching { services().sources.cloudFileSaved(profileId, key.account, key.file, path, addToLibrary) }.getOrDefault(false)
    }

    /** Adds an audio file on this computer to the library like any file the user added by hand. Returns its song. */
    suspend fun importFile(profileId: Long, path: Path): Track? = withContext(Dispatchers.IO) {
        val services = services()
        val file = path.toAbsolutePath().normalize()
        services.scanner.importFiles(profileId, listOf(file))
        services.library.localTrack(profileId, file.toString())
    }

    /** Plays a song of the library that is a file on this computer. */
    suspend fun playFile(profileId: Long, path: Path): String? = answer("That could not be played.") {
        active(profileId)
        val track = importFile(profileId, path) ?: throw IOException("${path.fileName} is not an audio file Music can read.")
        val player = services().player
        player.play(listOf(track), 0)
        player.engineError.value?.let { "Audio is unavailable: $it" }
    }.fold({ it }, { it.message })

    /** Plays the saved copy of a cloud song. False when Music lists no such song. */
    suspend fun playSaved(profileId: Long, key: Key): Boolean = withContext(Dispatchers.IO) {
        val services = runCatching { active(profileId) }.getOrNull() ?: return@withContext false
        val track = services.sources.cloudTracks(profileId, key.account, listOf(key.file)).firstOrNull() ?: return@withContext false
        services.player.play(listOf(track), 0)
        true
    }

    /** Forgets a file that came in through [importFile]. The file itself is the caller's business. */
    suspend fun removeFile(profileId: Long, path: Path) = withContext(Dispatchers.IO) {
        val services = services()
        services.library.localTrack(profileId, path.toAbsolutePath().normalize().toString())?.let {
            services.library.removeTracks(profileId, listOf(it.id))
            services.player.removeDeletedFromQueue()
        }
        Unit
    }
}
