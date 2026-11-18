package com.debridone.music.sources

/**
 * The audio of the user's connected cloud accounts, as a Music source. One connection per account
 * the active profile may use; they are created and removed by themselves as accounts come and go
 * ([MusicSourceManager.reconcileCloud]) and hold no sign-in of their own.
 *
 * Cloud songs are listed **outside** the library (under "Cloud Music") until the user adds them:
 * a cloud can hold thousands of files and the library is the user's own selection. A song's
 * address is asked from the application when it starts playing; saving a song goes through the
 * application's download manager.
 */
class CloudLibraryAdapter(private val cloud: CloudAudioSource) : MusicSourceAdapter {
    override val id = ID
    override val displayName = "Cloud"
    override val description = "Audio files in your connected cloud accounts (Settings › Connected Accounts)."
    override val kind = IntegrationKind.PERSONAL_CLOUD
    override val fields = emptyList<SourceField>()
    override val syncsLibrary = true
    override val allowsDownload = true
    override val resolvesAtPlayTime = true
    override val listsOutsideLibrary = true

    private fun profile(connection: SourceConnection): Long =
        connection.config[PROFILE]?.toLongOrNull() ?: throw SourceException("This cloud connection belongs to no profile.")

    private fun account(connection: SourceConnection): String = connection.username ?: throw SourceException("This cloud connection has no account.")

    /** Cloud connections are made by [MusicSourceManager.reconcileCloud], never from a form. */
    override fun connect(draft: SourceDraft): SourceSession = throw SourceException("Cloud accounts are connected in Settings › Connected Accounts.")

    override fun test(connection: SourceConnection, secret: String) {
        if (cloud.accounts(profile(connection)).none { it.key == account(connection) }) {
            throw SourceException("${connection.name} is no longer connected for this profile.")
        }
    }

    override fun listTracks(connection: SourceConnection, secret: String, onPage: (List<RemoteTrack>) -> Unit) {
        val files = guarded(connection) { cloud.audioFiles(profile(connection), account(connection)) }
        CloudTrackNames.tracks(files).chunked(200).forEach(onPage)
    }

    override fun streamUrl(connection: SourceConnection, secret: String, remoteId: String): String =
        guarded(connection) { cloud.address(profile(connection), account(connection), remoteId) }

    override fun artworkUrl(connection: SourceConnection, secret: String, artworkKey: String): String = artworkKey

    override fun saveThroughApplication(connection: SourceConnection, remoteIds: List<String>): String? =
        guarded(connection) { cloud.download(profile(connection), account(connection), remoteIds) }

    /** What the application says goes to the listener as it is; anything unforeseen is not quoted. */
    private fun <T> guarded(connection: SourceConnection, block: () -> T): T = try {
        block()
    } catch (e: SourceException) {
        throw e
    } catch (e: java.io.IOException) {
        throw SourceException(e.message ?: "${connection.name} could not be reached.")
    } catch (e: RuntimeException) {
        throw SourceException("${connection.name} could not be read.")
    }

    companion object {
        const val ID = "cloud"
        const val PROFILE = "profile"
    }
}

/**
 * Songs from file names and folders. A cloud knows files, not tags, so this reads the usual
 * conventions ("03 - Title", "Artist - Title", a folder called "Artist - Album (1999)") and
 * nothing more; the result can be corrected in the library like any other song, and a file that
 * is downloaded and added as a local file is read with its real tags.
 */
internal object CloudTrackNames {
    fun tracks(files: List<CloudAudioSource.File>): List<RemoteTrack> =
        files.groupBy { it.path.trim('/').substringBeforeLast('/', "") }.flatMap { (folderPath, group) ->
            val sorted = group.sortedBy { it.path.lowercase() }
            // A folder in which every song's name starts with a number is an album in track order.
            val numbered = sorted.size > 1 && sorted.all { LOOSE_NUMBER.containsMatchIn(it.path.substringAfterLast('/')) }
            val folder = folderPath.substringAfterLast('/').ifBlank { null }
            sorted.mapIndexedNotNull { index, file ->
                val name = file.path.substringAfterLast('/')
                val format = audioFormat(name, file.mimeType) ?: return@mapIndexedNotNull null
                track(file.key, name, folder, format, file.sizeBytes, if (folder != null) index + 1 else null, numbered)
            }
        }

    private fun track(remoteId: String, fileName: String, folder: String?, format: String, size: Long, number: Int?, numberedFolder: Boolean): RemoteTrack {
        val stem = fileName.substringBeforeLast('.')
        // On its own, a leading number is a track number only when it is written like one ("03 Title",
        // "3. Title"): "7 Lamps - Evening" is a band, not track seven.
        val numbered = (if (numberedFolder) LOOSE_NUMBER else TRACK_NUMBER).find(stem)
        val rest = clean(numbered?.groupValues?.get(2) ?: stem)
        val named = rest.contains(" - ")
        val trackArtist = rest.substringBefore(" - ").trim().takeIf { named && it.isNotEmpty() }
        val title = (if (trackArtist != null) rest.substringAfter(" - ") else rest).trim().ifEmpty { clean(stem) }

        val year = folder?.let { YEAR.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val folderName = folder?.let { clean(it.replace(BRACKETED, " ")) }?.takeIf { it.isNotEmpty() }
        val albumArtist = folderName?.substringBefore(" - ")?.trim()?.takeIf { folderName.contains(" - ") && it.isNotEmpty() }
        val album = (if (albumArtist != null) folderName.substringAfter(" - ") else folderName)?.trim()?.takeIf { it.isNotEmpty() }
        return RemoteTrack(
            remoteId = remoteId,
            title = title.take(300),
            artist = (trackArtist ?: albumArtist)?.take(300),
            album = album?.take(300),
            albumArtist = albumArtist?.take(300),
            genre = null,
            year = year,
            trackNo = numbered?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 } ?: number,
            discNo = null,
            durationMs = 0,
            format = format,
            bitrateKbps = null,
            sizeBytes = size,
            artworkKey = null,
        )
    }

    private fun clean(text: String): String = text.replace('_', ' ').replace(SPACES, " ").trim()

    /** The audio format of a file by its name, or by its type when the name says nothing; null when it is not audio. */
    private fun audioFormat(name: String, mimeType: String?): String? {
        val extension = name.substringAfterLast('.', "").lowercase()
        AUDIO_EXTENSIONS[extension]?.let { return it }
        return mimeType?.takeIf { it.startsWith("audio/", ignoreCase = true) }?.substringAfter('/')?.substringBefore(';')?.trim()?.lowercase()?.let { AUDIO_TYPES[it] }
    }

    private val TRACK_NUMBER = Regex("^\\s*(0\\d{1,2}(?=\\s)|\\d{1,3}(?=\\s*[-._)]))\\s*[-._)]?\\s*(.+)$")
    private val LOOSE_NUMBER = Regex("^\\s*(\\d{1,3})\\s*[-._)\\s]\\s*(.+)$")
    private val YEAR = Regex("[(\\[]((?:19|20)\\d{2})[)\\]]")
    private val BRACKETED = Regex("\\[[^\\]]*]|\\((?:19|20)\\d{2}\\)")
    private val SPACES = Regex("\\s+")
    private val AUDIO_EXTENSIONS = mapOf(
        "mp3" to "mp3", "flac" to "flac", "m4a" to "m4a", "m4b" to "m4a", "aac" to "aac", "ogg" to "ogg", "oga" to "ogg",
        "opus" to "opus", "wav" to "wav", "aiff" to "aiff", "aif" to "aiff",
    )
    private val AUDIO_TYPES = mapOf(
        "mpeg" to "mp3", "mp3" to "mp3", "flac" to "flac", "x-flac" to "flac", "mp4" to "m4a", "x-m4a" to "m4a", "aac" to "aac",
        "ogg" to "ogg", "opus" to "opus", "wav" to "wav", "x-wav" to "wav", "wave" to "wav", "aiff" to "aiff", "x-aiff" to "aiff",
    )
}
