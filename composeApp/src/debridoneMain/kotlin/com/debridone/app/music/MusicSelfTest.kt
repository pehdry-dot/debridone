package com.debridone.app.music

import com.debridone.module.ModuleId
import com.debridone.module.ModuleStatus
import com.debridone.module.storage.ModuleStorage
import com.debridone.music.MusicModule
import com.debridone.music.MusicServices
import com.debridone.music.playback.PlaybackStatus
import com.debridone.platform.AppPaths
import com.debridone.platform.secrets.FileSecretStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * A check that Music really works in this build of the application, run without opening a window:
 *
 *     DebridOne --music-self-test /path/to/a/folder/of/audio/files
 *
 * It uses a temporary data folder (never the user's), imports the folder, plays one file of every
 * format from start to end through the same engine the app uses, restarts the Music engine and
 * confirms that the library and the play counts are still there. The exit code is 0 only if every
 * step passed. The build workflow runs this against the packaged macOS app.
 */
object MusicSelfTest {
    private const val FLAG = "--music-self-test"
    private const val ENVIRONMENT = "DEBRIDONE_MUSIC_SELF_TEST"
    private const val PROFILE = 1001L

    /** Returns false when no self-test was requested; otherwise runs it and exits the process. */
    fun runIfRequested(args: Array<String>): Boolean {
        val index = args.indexOf(FLAG)
        val folder = when {
            index >= 0 -> args.getOrNull(index + 1)
            else -> System.getenv(ENVIRONMENT)?.takeIf { it.isNotBlank() }
        } ?: return false.also { if (index >= 0) fail("$FLAG needs the path of a folder with audio files.") }
        val code = try {
            if (run(Paths.get(folder))) 0 else 1
        } catch (t: Throwable) {
            println("FAILED with an unexpected error: $t")
            t.printStackTrace(System.out)
            1
        }
        System.out.flush()
        exitProcess(code)
    }

    private fun fail(message: String): Nothing {
        println(message)
        exitProcess(2)
    }

    private val failures = ArrayList<String>()

    private fun check(condition: Boolean, what: String) {
        println((if (condition) "  ok    " else "  FAIL  ") + what)
        if (!condition) failures += what
    }

    private fun run(folder: Path): Boolean {
        println("DebridOne Music self-test")
        println("Audio folder: $folder")
        if (!Files.isDirectory(folder)) fail("That folder does not exist.")
        val workspace = Files.createTempDirectory("debridone-music-self-test")
        try {
            val runtime = MusicAudio.runtime()
            println("Bundled native directory: ${MusicAudio.bundledNativeDirectory() ?: "none (using the system libmpv)"}")
            println("libmpv: ${runtime.libmpv ?: "system"}; preloaded: ${runtime.preload.joinToString().ifEmpty { "nothing" }}")
            println("Audio output: ${System.getenv("DEBRIDONE_MUSIC_AO") ?: "default device"}")
            if (MusicAudio.bundledNativeDirectory() != null) {
                check(MusicAudio.bundledBridge() != null, "the Music bridge library is in the app bundle")
                check(MusicSystemIntegration.isAvailable, "the Music bridge library loads")
                check((MusicSystemIntegration.bridgedMpvApiVersion() ?: 0) > 0, "the bridge reaches libmpv (client API ${MusicSystemIntegration.bridgedMpvApiVersion()})")
            }

            val paths = AppPaths(workspace.resolve("data"), workspace.resolve("cache"))
            val secrets = FileSecretStore(workspace.resolve("secrets"))
            fun open(): Pair<MusicModule, MusicServices> {
                val module = MusicModule(ModuleStorage(ModuleId.MUSIC, paths, secrets), engineFactory = MusicAudio::createEngine)
                runBlocking {
                    module.start()
                    module.onProfileChanged(PROFILE)
                    module.rescan().join()
                }
                val status = module.status.value
                if (status != ModuleStatus.Running) fail("Music did not start: $status")
                return module to checkNotNull(module.services.value)
            }

            println("\n1. Import")
            var (module, music) = open()
            runBlocking { module.addFolder(folder).join() }
            val tracks = music.library.tracks(PROFILE)
            check(tracks.isNotEmpty(), "found ${tracks.size} audio files")
            check(tracks.none { it.durationMs <= 0 }, "every file has a duration")
            val tagged = tracks.count { it.artist != null }
            check(tagged > 0, "$tagged files have tags (artist) read from them")
            val withArtwork = tracks.count { it.artwork != null && Files.isRegularFile(Paths.get(it.artwork!!.filePath)) }
            check(withArtwork > 0, "$withArtwork files have cover art stored on disk")
            println("   albums: " + music.library.albums(PROFILE).joinToString { "${it.title} (${it.trackCount})" })

            println("\n2. Playback, one file per format")
            val byFormat = tracks.groupBy { listOfNotNull(it.format, it.codec?.takeIf { codec -> "ADTS" in codec }?.let { "raw" }).joinToString(" ") }
            for ((format, candidates) in byFormat.toSortedMap()) {
                val track = candidates.first()
                music.player.play(listOf(track))
                music.player.engineError.value?.let { fail("The audio engine could not start: $it") }
                val started = await(8_000) { music.player.state.value.let { it.status == PlaybackStatus.PLAYING && it.positionMs > 0 } }
                val ended = started && await(track.durationMs + 8_000) { music.player.state.value.status == PlaybackStatus.ENDED }
                val error = music.player.state.value.lastError
                check(started && ended && error == null, "$format: ${track.path?.let { Paths.get(it).fileName }}" + (error?.let { " (${it.message})" } ?: if (!started) " (did not start)" else if (!ended) " (did not finish)" else ""))
                music.player.dismissError()
            }
            println("   engine: ${music.player.engineDescription.value}")

            println("\n3. Pause, seek and resume")
            val longest = tracks.maxBy { it.durationMs }
            music.player.play(listOf(longest))
            check(await(8_000) { music.player.state.value.positionMs > 200 }, "playback started")
            println("   while playing: " + music.player.diagnostics().entries.joinToString(", ") { "${it.key} = ${it.value}" })
            music.player.pause()
            check(await(4_000) { music.player.state.value.status == PlaybackStatus.PAUSED }, "pause")
            Thread.sleep(300)
            val held = music.player.state.value.positionMs
            Thread.sleep(500)
            check(music.player.state.value.positionMs - held <= 80, "the position holds while paused")
            music.player.seekTo(longest.durationMs / 2)
            check(await(4_000) { abs(music.player.state.value.positionMs - longest.durationMs / 2) < 400 }, "seek to the middle")
            music.player.resume()
            check(await(longest.durationMs + 8_000) { music.player.state.value.status == PlaybackStatus.ENDED }, "resume and finish")

            println("\n4. Restart")
            val playedBefore = await(4_000) { music.library.tracks(PROFILE).count { it.playCount > 0 } >= byFormat.size }
            check(playedBefore, "every played file was written to the listening history")
            val before = music.library.tracks(PROFILE).associate { it.path to it.playCount }
            module.close()
            val reopened = open()
            module = reopened.first
            music = reopened.second
            val after = music.library.tracks(PROFILE).associate { it.path to it.playCount }
            check(after == before, "the library and play counts are unchanged after restarting (${after.size} songs)")
            check(music.library.tracks(PROFILE).count { it.artwork != null && Files.isRegularFile(Paths.get(it.artwork!!.filePath)) } == withArtwork, "cover art is still there")
            check(music.player.state.value.queue.isNotEmpty() && music.player.state.value.status == PlaybackStatus.IDLE, "the last queue is restored, paused")
            module.close()
        } finally {
            runCatching { workspace.toFile().deleteRecursively() }
        }
        println()
        println(if (failures.isEmpty()) "MUSIC SELF-TEST PASSED" else "MUSIC SELF-TEST FAILED: ${failures.size} check(s)\n" + failures.joinToString("\n") { "  - $it" })
        return failures.isEmpty()
    }

    private fun abs(value: Long): Long = if (value < 0) -value else value

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }
}
