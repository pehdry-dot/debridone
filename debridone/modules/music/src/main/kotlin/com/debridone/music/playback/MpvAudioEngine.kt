package com.debridone.music.playback

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Audio playback through libmpv (FFmpeg decoders), using an mpv instance that belongs to Music
 * alone. Video output is disabled; the instance never creates a window.
 *
 * @param audioOutput mpv `--ao` value. Null uses the platform default (CoreAudio on macOS). The
 *   value `"null"` selects mpv's silent output, which still plays in real time; tests use it.
 */
class MpvAudioEngine(
    runtime: MusicNativeRuntime = MusicNativeRuntime.System,
    audioOutput: String? = null,
) : MusicPlaybackEngine {

    private val api: MpvApi = MpvLibrary.load(runtime)
    private val handle: Pointer
    private val closed = AtomicBoolean(false)
    private val eventThread: Thread

    @Volatile
    private var listener: PlaybackEngineListener? = null

    /** Entry id of the file mpv is currently on, taken from its start-file event. */
    @Volatile
    private var activeEntryId: Long = 0

    /** True between mpv's file-loaded and end-file events. Only touched by the event thread. */
    private var fileLoaded = false

    override val description: String

    init {
        handle = api.mpv_create() ?: throw MusicNativeException("libmpv could not create a player instance.")
        try {
            // Never read the user's mpv configuration or scripts: this instance is the app's own.
            option("config", "no")
            optionalOption("load-scripts", "no")
            optionalOption("ytdl", "no")
            optionalOption("terminal", "no")
            optionalOption("input-default-bindings", "no")
            optionalOption("input-vo-keyboard", "no")
            optionalOption("osc", "no")
            // Audio only: no window, no cover-art video track.
            option("vid", "no")
            option("vo", "null")
            optionalOption("audio-display", "no")
            // Stay alive between tracks so the next load starts instantly.
            option("idle", "yes")
            optionalOption("keep-open", "no")
            optionalOption("gapless-audio", "weak")
            optionalOption("audio-client-name", "DebridOne")
            optionalOption("volume-max", "100")
            // Without any sound device, keep time moving silently instead of failing every track.
            optionalOption("audio-fallback-to-null", "yes")
            // Streams from a music server go through the same proxy as the rest of the system,
            // when the environment names one.
            proxyFromEnvironment()?.let { optionalOption("http-proxy", it) }
            if (audioOutput != null) option("ao", audioOutput)

            requireOk(api.mpv_initialize(handle), "initialise the audio engine")
            api.mpv_observe_property(handle, 1, "time-pos", Mpv.FORMAT_DOUBLE)
            api.mpv_observe_property(handle, 2, "duration", Mpv.FORMAT_DOUBLE)
            api.mpv_observe_property(handle, 3, "pause", Mpv.FORMAT_FLAG)
        } catch (t: Throwable) {
            api.mpv_terminate_destroy(handle)
            throw t
        }
        val version = readString("mpv-version") ?: "mpv"
        val ffmpeg = readString("ffmpeg-version")
        description = if (ffmpeg != null) "$version (FFmpeg $ffmpeg)" else version

        eventThread = Thread(::eventLoop, "debridone-music-engine").apply {
            isDaemon = true
            start()
        }
    }

    override fun setListener(listener: PlaybackEngineListener?) {
        this.listener = listener
    }

    override fun load(uri: String, startPositionMs: Long, startPaused: Boolean): Long {
        ensureOpen()
        // Per-file options are applied as properties so this works across mpv versions whose
        // `loadfile` argument order differs.
        api.mpv_set_property_string(handle, "start", if (startPositionMs > 0) seconds(startPositionMs) else "none")
        api.mpv_set_property_string(handle, "pause", if (startPaused) "yes" else "no")

        val result = Memory(NODE_SIZE).apply { clear() }
        requireOk(api.mpv_command_ret(handle, arrayOf("loadfile", uri, "replace"), result), "load the track")
        try {
            return readEntryId(result)
        } finally {
            api.mpv_free_node_contents(result)
        }
    }

    override fun setPaused(paused: Boolean) {
        ensureOpen()
        api.mpv_set_property_string(handle, "pause", if (paused) "yes" else "no")
    }

    override fun seekTo(positionMs: Long) {
        ensureOpen()
        // Ignored by mpv when nothing is loaded; that is fine.
        api.mpv_command(handle, arrayOf("seek", seconds(positionMs.coerceAtLeast(0)), "absolute"))
    }

    override fun stop() {
        if (closed.get()) return
        api.mpv_command(handle, arrayOf("stop"))
    }

    override fun setVolume(percent: Int) {
        ensureOpen()
        api.mpv_set_property_string(handle, "volume", percent.coerceIn(0, 100).toString())
    }

    override fun setMuted(muted: Boolean) {
        ensureOpen()
        api.mpv_set_property_string(handle, "mute", if (muted) "yes" else "no")
    }

    override fun audioDevices(): List<AudioDevice> {
        if (closed.get()) return emptyList()
        val json = readString("audio-device-list") ?: return emptyList()
        return runCatching {
            Json.parseToJsonElement(json).jsonArray.map { element ->
                val device = element.jsonObject
                val id = device.getValue("name").jsonPrimitive.content
                AudioDevice(id, device["description"]?.jsonPrimitive?.content ?: id)
            }
        }.getOrDefault(emptyList())
    }

    override fun setAudioDevice(id: String) {
        ensureOpen()
        api.mpv_set_property_string(handle, "audio-device", id.ifBlank { "auto" })
    }

    override fun diagnostics(): Map<String, String> {
        if (closed.get()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for ((label, name) in listOf("Output driver" to "current-ao", "Output device" to "audio-device", "Sample rate" to "audio-params/samplerate", "Channels" to "audio-params/hr-channels", "Codec" to "audio-codec-name")) {
            readString(name)?.takeIf { it.isNotBlank() }?.let { result[label] = it }
        }
        return result
    }

    /** Reads a raw mpv property. For diagnostics and tests. */
    internal fun property(name: String): String? = if (closed.get()) null else readString(name)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener = null
        // The event thread must leave mpv_wait_event before the handle is destroyed.
        api.mpv_wakeup(handle)
        if (Thread.currentThread() !== eventThread) {
            runCatching { eventThread.join(3_000) }
        }
        api.mpv_terminate_destroy(handle)
    }

    private fun eventLoop() {
        while (!closed.get()) {
            val event = api.mpv_wait_event(handle, 0.5)
            if (closed.get()) return
            when (event.getInt(Mpv.EVENT_ID_OFFSET)) {
                Mpv.EVENT_NONE -> Unit
                Mpv.EVENT_SHUTDOWN -> return
                Mpv.EVENT_START_FILE -> {
                    fileLoaded = false
                    event.getPointer(Mpv.EVENT_DATA_OFFSET)?.let { activeEntryId = it.getLong(0) }
                }
                Mpv.EVENT_FILE_LOADED -> {
                    fileLoaded = true
                    val entryId = activeEntryId
                    dispatch { it.onTrackStarted(entryId) }
                }
                Mpv.EVENT_END_FILE -> {
                    fileLoaded = false
                    onEndFile(event)
                }
                Mpv.EVENT_PROPERTY_CHANGE -> onPropertyChange(event)
            }
        }
    }

    private fun onEndFile(event: Pointer) {
        val data = event.getPointer(Mpv.EVENT_DATA_OFFSET) ?: return
        val entryId = data.getLong(8)
        when (data.getInt(Mpv.END_FILE_REASON_OFFSET)) {
            Mpv.END_EOF -> dispatch { it.onTrackEnded(entryId, TrackEndReason.FINISHED, null) }
            Mpv.END_ERROR -> {
                val message = api.mpv_error_string(data.getInt(Mpv.END_FILE_ERROR_OFFSET)) ?: "playback failed"
                dispatch { it.onTrackEnded(entryId, TrackEndReason.ERROR, message) }
            }
            else -> dispatch { it.onTrackEnded(entryId, TrackEndReason.STOPPED, null) }
        }
    }

    private fun onPropertyChange(event: Pointer) {
        val property = event.getPointer(Mpv.EVENT_DATA_OFFSET) ?: return
        val data = property.getPointer(Mpv.PROPERTY_DATA_OFFSET) ?: return
        when (property.getInt(Mpv.PROPERTY_FORMAT_OFFSET)) {
            Mpv.FORMAT_DOUBLE -> {
                val millis = (data.getDouble(0) * 1000.0).toLong().coerceAtLeast(0)
                val entryId = activeEntryId
                when (event.getLong(Mpv.EVENT_USERDATA_OFFSET)) {
                    // A position only means something once the file is really playing.
                    1L -> if (fileLoaded) dispatch { it.onPosition(entryId, millis) }
                    2L -> dispatch { it.onDuration(entryId, millis) }
                }
            }
            Mpv.FORMAT_FLAG -> {
                val flag = data.getInt(0) != 0
                if (event.getLong(Mpv.EVENT_USERDATA_OFFSET) == 3L) dispatch { it.onPaused(flag) }
            }
        }
    }

    /** A listener that throws must never kill the engine thread. */
    private inline fun dispatch(call: (PlaybackEngineListener) -> Unit) {
        val target = listener ?: return
        runCatching { call(target) }
    }

    private fun option(name: String, value: String) {
        requireOk(api.mpv_set_option_string(handle, name, value), "set $name")
    }

    /** For options that only tune behaviour and may not exist in every libmpv build. */
    private fun optionalOption(name: String, value: String) {
        api.mpv_set_option_string(handle, name, value)
    }

    private fun requireOk(status: Int, action: String) {
        if (status < 0) {
            throw MusicNativeException("The audio engine could not $action: ${api.mpv_error_string(status) ?: status}")
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "The audio engine is closed." }
    }

    private fun readString(property: String): String? {
        val pointer = api.mpv_get_property_string(handle, property) ?: return null
        return try {
            pointer.getString(0, "UTF-8")
        } finally {
            api.mpv_free(pointer)
        }
    }

    /**
     * `loadfile` returns a map node holding `playlist_entry_id`.
     * `struct mpv_node { union { ...; int64_t int64; struct mpv_node_list *list; } u; int format; }`
     * `struct mpv_node_list { int num; struct mpv_node *values; char **keys; }`
     */
    private fun readEntryId(node: Pointer): Long {
        if (node.getInt(8) != FORMAT_NODE_MAP) return 0
        val list = node.getPointer(0) ?: return 0
        val count = list.getInt(0)
        val values = list.getPointer(8) ?: return 0
        val keys = list.getPointer(16) ?: return 0
        for (index in 0 until count) {
            val key = keys.getPointer(index.toLong() * Native.POINTER_SIZE)?.getString(0, "UTF-8") ?: continue
            if (key == "playlist_entry_id") {
                val value = values.share(index * NODE_SIZE)
                if (value.getInt(8) == Mpv.FORMAT_INT64) return value.getLong(0)
            }
        }
        return 0
    }

    private fun seconds(millis: Long): String = String.format(Locale.ROOT, "%.3f", millis / 1000.0)

    /** The conventional proxy variables, as command-line tools read them. Never logged. */
    private fun proxyFromEnvironment(): String? = listOf("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")
        .firstNotNullOfOrNull { name -> runCatching { System.getenv(name) }.getOrNull()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") } }

    private companion object {
        const val NODE_SIZE = 16L
        const val FORMAT_NODE_MAP = 8
    }
}
