package com.debridone.music.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Where the native audio runtime lives. The host application supplies this; the Music engine
 * never searches the app bundle itself and never depends on another module's player.
 *
 * @param preload libraries to open first, in order. On macOS this is DebridOne's small music
 *   bridge, which is linked with an `@loader_path` rpath and therefore pulls in the bundled
 *   `libmpv.2.dylib` together with its `@rpath` dependencies.
 * @param libmpv absolute path of libmpv, or null to use the system library (Linux development).
 */
data class MusicNativeRuntime(
    val preload: List<Path> = emptyList(),
    val libmpv: Path? = null,
) {
    companion object {
        /** libmpv installed on the system (Linux development and CI). */
        val System = MusicNativeRuntime()
    }
}

class MusicNativeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The subset of the mpv client API (`mpv/client.h`) that audio playback needs. */
@Suppress("FunctionName")
internal interface MpvApi : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer?)

    /** `args` is passed as a NULL-terminated `const char **`; JNA appends the terminator. */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int

    /** Like [mpv_command] but fills `result` (a `mpv_node *`) that must be freed with [mpv_free_node_contents]. */
    fun mpv_command_ret(ctx: Pointer, args: Array<String>, result: Pointer): Int
    fun mpv_free_node_contents(node: Pointer)
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int

    /** Returns a `mpv_event *` that stays valid until the next call on the same handle. */
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_error_string(error: Int): String?
}

internal object Mpv {
    // mpv_event_id
    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_START_FILE = 6
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    const val EVENT_PLAYBACK_RESTART = 21
    const val EVENT_PROPERTY_CHANGE = 22

    // mpv_format
    const val FORMAT_NONE = 0
    const val FORMAT_STRING = 1
    const val FORMAT_FLAG = 3
    const val FORMAT_INT64 = 4
    const val FORMAT_DOUBLE = 5

    // mpv_end_file_reason
    const val END_EOF = 0
    const val END_STOP = 2
    const val END_QUIT = 3
    const val END_ERROR = 4
    const val END_REDIRECT = 5

    // struct mpv_event { int event_id; int error; uint64_t reply_userdata; void *data; } (LP64)
    const val EVENT_ID_OFFSET = 0L
    const val EVENT_ERROR_OFFSET = 4L
    const val EVENT_USERDATA_OFFSET = 8L
    const val EVENT_DATA_OFFSET = 16L

    // struct mpv_event_property { const char *name; int format; void *data; } (LP64)
    const val PROPERTY_NAME_OFFSET = 0L
    const val PROPERTY_FORMAT_OFFSET = 8L
    const val PROPERTY_DATA_OFFSET = 16L

    // struct mpv_event_end_file { int reason; int error; ... }
    const val END_FILE_REASON_OFFSET = 0L
    const val END_FILE_ERROR_OFFSET = 4L
}

/** Loads libmpv once per process and prepares the C locale mpv insists on. */
internal object MpvLibrary {
    @Suppress("FunctionName")
    private interface CLibrary : Library {
        fun setlocale(category: Int, locale: String?): Pointer?
    }

    @Volatile
    private var loaded: MpvApi? = null

    /** mpv takes file names and option values as UTF-8, whatever the JVM's default charset is. */
    private val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")

    @Synchronized
    fun load(runtime: MusicNativeRuntime): MpvApi {
        loaded?.let { return it }
        require(Native.POINTER_SIZE == 8) { "The Music engine needs a 64-bit runtime." }
        ensureCNumericLocale()
        try {
            runtime.preload.forEach { library ->
                if (!Files.isRegularFile(library)) {
                    throw MusicNativeException("Audio runtime file is missing: $library")
                }
                NativeLibrary.getInstance(library.toAbsolutePath().toString())
            }
            val api = if (runtime.libmpv != null) {
                if (!Files.isRegularFile(runtime.libmpv)) {
                    throw MusicNativeException("libmpv is missing: ${runtime.libmpv}")
                }
                Native.load(runtime.libmpv.toAbsolutePath().toString(), MpvApi::class.java, options)
            } else {
                loadSystemLibrary()
            }
            loaded = api
            return api
        } catch (e: MusicNativeException) {
            throw e
        } catch (e: UnsatisfiedLinkError) {
            throw MusicNativeException("The audio engine (libmpv) could not be loaded: ${e.message}", e)
        }
    }

    /** True when libmpv can be loaded with [runtime]; used by tests and diagnostics. */
    fun isAvailable(runtime: MusicNativeRuntime): Boolean = runCatching { load(runtime) }.isSuccess

    private fun loadSystemLibrary(): MpvApi {
        var failure: UnsatisfiedLinkError? = null
        for (name in listOf("mpv", "libmpv.so.2", "libmpv.2.dylib")) {
            try {
                return Native.load(name, MpvApi::class.java, options)
            } catch (e: UnsatisfiedLinkError) {
                failure = e
            }
        }
        throw failure ?: UnsatisfiedLinkError("libmpv not found")
    }

    /**
     * mpv refuses to start unless LC_NUMERIC is "C": locales that write decimals with a comma
     * (Portuguese, German, French...) would break its option and number parsing.
     */
    private fun ensureCNumericLocale() {
        val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        // <locale.h>: LC_NUMERIC is 4 on macOS/BSD and 1 on glibc.
        val lcNumeric = if (os.contains("mac")) 4 else 1
        runCatching {
            Native.load("c", CLibrary::class.java).setlocale(lcNumeric, "C")
        }
    }
}
