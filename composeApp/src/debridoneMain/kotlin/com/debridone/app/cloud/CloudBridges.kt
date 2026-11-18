package com.debridone.app.cloud

import com.debridone.cloud.CloudFileRow
import com.debridone.cloud.Download
import com.debridone.cloud.MediaClass

/**
 * How a cloud file reaches the section that can open it. The cloud pages know these three small
 * interfaces and nothing of Streaming, Music or Manga themselves; each section is handed only its
 * own kind of file, one file (or one selection) at a time, by the user's own action.
 *
 * A bridge that is not set means the action is simply not offered.
 */
internal object CloudBridges {
    @Volatile
    var video: VideoBridge? = null

    @Volatile
    var music: MusicBridge? = null

    @Volatile
    var manga: MangaBridge? = null

    /** The section a finished download of this shelf can be added to, when there is one. */
    fun importer(mediaClass: MediaClass, fileName: String): LibraryImporter? = when {
        mediaClass == MediaClass.MUSIC -> music
        mediaClass == MediaClass.COMIC || manga?.canRead(fileName) == true -> manga
        else -> null
    }
}

/** A section that can take a downloaded file into its own library. */
internal interface LibraryImporter {
    /** "Music", "Manga". */
    val sectionName: String

    /** The key recorded with the download ("music", "manga"). */
    val sectionKey: String

    /** Adds the finished [download] to the section's library; returns a message for the user. */
    suspend fun addToLibrary(profileId: Long, download: Download): String

    /** Takes it out of the section's library again (before its local copy is deleted). */
    suspend fun removeFromLibrary(profileId: Long, download: Download)
}

internal interface VideoBridge {
    /** Plays a cloud video in the Streaming player: from its local copy when there is one, else from the cloud. */
    fun play(profileId: Long, file: CloudFileRow, local: Download?)

    /** Plays a downloaded video whose cloud entry may be gone. */
    fun playLocal(profileId: Long, download: Download)
}

internal interface MusicBridge : LibraryImporter {
    /** Plays cloud audio in the Music player, the first of [files] first. */
    fun play(profileId: Long, files: List<CloudFileRow>)

    fun playLocal(profileId: Long, download: Download)

    /** Adds cloud songs to the Music library without downloading them. */
    suspend fun addCloudFilesToLibrary(profileId: Long, files: List<CloudFileRow>): String
}

internal interface MangaBridge : LibraryImporter {
    /** True when the Manga reader can open a file of this name (CBZ, ZIP). */
    fun canRead(fileName: String): Boolean

    /** Why it cannot, in words ("RAR archives are not supported"), or null when it can. */
    fun unsupportedReason(fileName: String): String?

    /** Opens a downloaded archive in the Manga reader, importing it first when needed. */
    suspend fun read(profileId: Long, download: Download): String?
}
