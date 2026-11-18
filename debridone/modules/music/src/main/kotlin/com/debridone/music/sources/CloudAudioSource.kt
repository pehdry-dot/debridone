package com.debridone.music.sources

import kotlinx.coroutines.flow.StateFlow

/**
 * Audio files the application offers Music from the user's connected cloud accounts.
 *
 * Music defines this interface and the application implements it, so Music stays independent of
 * how accounts are connected: it is handed audio files only, each with a name, a size and a way
 * to ask for its address when it plays. It never sees a sign-in, a key, or a file of another kind.
 */
interface CloudAudioSource {
    /** A connected account the active profile may use. [key] is stable; [label] is for display ("Real-Debrid · name"). */
    data class Account(val key: String, val label: String)

    /** One audio file. [key] is stable for the file; [path] is its path inside its cloud entry, folders included. */
    data class File(val key: String, val path: String, val sizeBytes: Long, val mimeType: String?)

    /** Changes whenever the accounts, or what is listed in them, changed. */
    val revision: StateFlow<Long>

    fun accounts(profileId: Long): List<Account>

    fun audioFiles(profileId: Long, accountKey: String): List<File>

    /** An address for one file, made now. It is used at once and never stored. */
    fun address(profileId: Long, accountKey: String, fileKey: String): String

    /**
     * Asks the application to download these files to this computer with its own download manager
     * (Music does not fetch cloud files itself). Returns a sentence about what was queued.
     */
    fun download(profileId: Long, accountKey: String, fileKeys: List<String>): String
}
