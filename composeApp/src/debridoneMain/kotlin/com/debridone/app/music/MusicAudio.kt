package com.debridone.app.music

import com.debridone.music.playback.MpvAudioEngine
import com.debridone.music.playback.MusicNativeRuntime
import com.debridone.music.playback.MusicPlaybackEngine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where Music finds its audio engine in this application.
 *
 * In the packaged macOS app the engine is the libmpv that ships inside the bundle, loaded through
 * DebridOne's own small music bridge. Music does not use Streaming's video player or its bridge.
 * When developing (Linux, or macOS without a bundle) the system's libmpv is used instead.
 */
object MusicAudio {
    const val BRIDGE_LIBRARY = "libdebridone_music_bridge.dylib"
    private const val LIBMPV = "libmpv.2.dylib"

    /**
     * `DEBRIDONE_MUSIC_AO` selects mpv's audio output driver. It exists for machines without a
     * sound device (CI, remote sessions): `null` plays silently in real time.
     */
    private val audioOutputOverride: String? = System.getenv("DEBRIDONE_MUSIC_AO")?.trim()?.takeIf { it.isNotEmpty() }

    private val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    fun createEngine(): MusicPlaybackEngine = MpvAudioEngine(runtime(), audioOutputOverride)

    /** The directory inside the app bundle that holds the native libraries, if this is a bundle. */
    fun bundledNativeDirectory(): Path? {
        if (!isMac) return null
        val resources = System.getProperty("compose.application.resources.dir")?.takeIf { it.isNotBlank() } ?: return null
        return Paths.get(resources, "native", "macos").takeIf { Files.isDirectory(it) }
    }

    fun bundledBridge(): Path? = bundledNativeDirectory()?.resolve(BRIDGE_LIBRARY)?.takeIf { Files.isRegularFile(it) }

    fun runtime(): MusicNativeRuntime {
        val directory = bundledNativeDirectory() ?: return MusicNativeRuntime.System
        val bridge = directory.resolve(BRIDGE_LIBRARY)
        val libmpv = directory.resolve(LIBMPV)
        if (!Files.isRegularFile(libmpv)) return MusicNativeRuntime.System
        // The bridge is linked against libmpv with an @loader_path search path; opening it first is
        // what lets the dynamic loader find libmpv's own dependencies in the same directory.
        return MusicNativeRuntime(preload = listOfNotNull(bridge.takeIf { Files.isRegularFile(it) }), libmpv = libmpv)
    }
}
