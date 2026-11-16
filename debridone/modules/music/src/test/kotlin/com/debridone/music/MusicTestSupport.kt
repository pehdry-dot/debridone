package com.debridone.music

import com.debridone.music.playback.MpvLibrary
import com.debridone.music.playback.MusicNativeRuntime
import com.debridone.music.playback.MusicPlayerController
import com.debridone.music.playback.PlayerState
import com.debridone.music.playback.QueueItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.name
import org.junit.Assume

/** The generated audio fixtures (see scripts/debridone/generate-music-fixtures.sh). */
object Fixtures {
    val root: Path = Paths.get(checkNotNull(Fixtures::class.java.getResource("/audio-fixtures")) { "audio fixtures are missing" }.toURI())
    val firstAlbum: Path = root.resolve("Test Artist/First Album")
    val secondAlbum: Path = root.resolve("Other Artist/Second Album")

    val mp3: Path = firstAlbum.resolve("01 Opening.mp3")
    val flac: Path = firstAlbum.resolve("02 Second Light.flac")
    val aac: Path = firstAlbum.resolve("03 Third Wave.m4a")
    val alac: Path = firstAlbum.resolve("04 Fourth Wall.m4a")
    val wav: Path = firstAlbum.resolve("05 Fifth Element.wav")
    val ogg: Path = firstAlbum.resolve("06 Sixth Sense.ogg")
    val opus: Path = firstAlbum.resolve("07 Seventh Heaven.opus")
    val elsewhere: Path = secondAlbum.resolve("01 Elsewhere.mp3")

    /** Label to file for every format the Music module claims to play. */
    val formats: List<Pair<String, Path>> = listOf(
        "MP3" to mp3,
        "FLAC" to flac,
        "AAC (M4A)" to aac,
        "ALAC (M4A)" to alac,
        "WAV" to wav,
        "OGG Vorbis" to ogg,
        "Opus" to opus,
    )

    /** Copies the fixture tree into [target] so a test can move, change or delete files. */
    fun copyTo(target: Path): Path {
        Files.walk(root).use { stream ->
            stream.forEach { source ->
                val destination = target.resolve(root.relativize(source).toString())
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return target
    }

    fun item(path: Path, id: Long = path.name.take(2).toLong()): QueueItem =
        QueueItem(trackId = id, uri = path.toString(), title = path.name.substringBeforeLast('.').drop(3), durationMs = 2_000)
}

/**
 * Playback tests need libmpv. Without it they are skipped, unless `DEBRIDONE_REQUIRE_LIBMPV=1`
 * (set in CI), in which case a missing library is a failure rather than a silent skip.
 */
fun requireLibmpv() {
    val available = MpvLibrary.isAvailable(MusicNativeRuntime.System)
    if (System.getenv("DEBRIDONE_REQUIRE_LIBMPV") == "1") {
        check(available) { "libmpv is required for this test run but could not be loaded." }
    }
    Assume.assumeTrue("libmpv is not installed; playback tests skipped", available)
}

/** Polls until [condition] holds; fails with [describe] after [timeoutMs]. */
fun awaitTrue(timeoutMs: Long = 8_000, describe: () -> String = { "condition" }, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Timed out after $timeoutMs ms waiting for ${describe()}")
}

fun MusicPlayerController.awaitState(timeoutMs: Long = 8_000, what: String, predicate: (PlayerState) -> Boolean): PlayerState {
    awaitTrue(timeoutMs, { "$what; last state: ${state.value.summary()}" }) { predicate(state.value) }
    return state.value
}

fun PlayerState.summary(): String =
    "status=$status track=${current?.title} pos=$positionMs/$durationMs order=$order@$orderPosition error=${lastError?.message}"
