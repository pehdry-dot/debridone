package com.debridone.cloud

/**
 * Sorts a cloud file onto one of the browser's shelves by what it **is**, not by what its name
 * suggests it is about:
 *
 * - The extension decides. The MIME type a provider reports is used only when the extension is
 *   missing or unknown.
 * - Video is "TV Shows" only when the path carries an explicit episode marker (`S01E02`, `1x02`,
 *   a `Season 1` folder). Every other video is listed under "Movies". Nothing is identified: a
 *   concert film or a home video is a "Movie" here simply because it is a video without an
 *   episode marker.
 * - "Manga & Comics" are the comic-book archive types. A plain ZIP or a folder of images is
 *   "Other Files", because that is all that can be said without opening it; the browser offers
 *   "Read in Manga" for those as the user's own choice.
 */
object MediaFileClassifier {
    val VIDEO = setOf("mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mpg", "mpeg", "wmv", "flv", "ogv")
    val AUDIO = setOf("mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "aiff", "aif", "alac", "wma", "ape", "wv")
    val COMIC = setOf("cbz", "cbr", "cb7", "cbt")
    val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
    val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz")

    /** Things that could run. They are downloaded and revealed like any file, never opened. */
    val EXECUTABLE = setOf("app", "command", "sh", "bash", "zsh", "pkg", "mpkg", "dmg", "jar", "scpt", "workflow", "action", "exe", "msi", "bat", "cmd", "com", "py", "rb", "pl", "js", "terminal", "tool")

    private val COMIC_TYPES = setOf("application/vnd.comicbook+zip", "application/vnd.comicbook-rar", "application/x-cbz", "application/x-cbr")
    private val EPISODE = Regex("(?i)(?<![a-z0-9])s\\d{1,2}[ ._-]?e\\d{1,3}(?![0-9])|(?<![a-z0-9])\\d{1,2}x\\d{2,3}(?![a-z0-9])")
    private val SEASON_FOLDER = Regex("(?i)^(season|series|temporada|saison|staffel)[ ._-]?\\d{1,2}$|^s\\d{1,2}$")

    fun extension(path: String): String = path.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 1..8 }.orEmpty()

    fun classify(path: String, mimeType: String? = null): MediaClass {
        val extension = extension(path)
        val type = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val known = extension in VIDEO || extension in AUDIO || extension in COMIC || extension in IMAGE || extension in ARCHIVE || extension in EXECUTABLE || extension in DOCUMENT
        return when {
            extension in VIDEO -> video(path)
            extension in AUDIO -> MediaClass.MUSIC
            extension in COMIC -> MediaClass.COMIC
            known -> MediaClass.OTHER
            type.startsWith("video/") -> video(path)
            type.startsWith("audio/") -> MediaClass.MUSIC
            type in COMIC_TYPES -> MediaClass.COMIC
            else -> MediaClass.OTHER
        }
    }

    /** True when the path itself says "this is an episode". */
    fun hasEpisodeMarker(path: String): Boolean =
        EPISODE.containsMatchIn(path.substringAfterLast('/')) || path.split('/').dropLast(1).any { SEASON_FOLDER.matches(it.trim()) || EPISODE.containsMatchIn(it) }

    private fun video(path: String): MediaClass = if (hasEpisodeMarker(path)) MediaClass.TV_SHOW else MediaClass.MOVIE

    fun isExecutable(path: String): Boolean = extension(path) in EXECUTABLE

    private val DOCUMENT = setOf("pdf", "epub", "mobi", "txt", "nfo", "srt", "ass", "sub", "idx", "vtt", "cue", "log", "m3u", "m3u8", "sfv", "md5", "json", "xml", "html", "htm", "doc", "docx", "iso", "img", "bin")
}
