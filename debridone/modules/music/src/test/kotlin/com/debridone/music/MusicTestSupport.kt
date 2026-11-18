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
    val aiff: Path = root.resolve("Loose Files/Cancao Nona.aiff")
    val adts: Path = root.resolve("Loose Files/12 - Untagged Tone.aac")

    /** The seven tracks of "First Album", in track order. */
    val albumFiles: List<Path> = listOf(mp3, flac, aac, alac, wav, ogg, opus)

    /** Every audio file in the fixture tree. */
    const val TOTAL_TRACKS = 10

    /** Label to file for every format the Music module claims to play. */
    val formats: List<Pair<String, Path>> = listOf(
        "MP3" to mp3,
        "FLAC" to flac,
        "AAC (M4A)" to aac,
        "ALAC (M4A)" to alac,
        "WAV" to wav,
        "OGG Vorbis" to ogg,
        "Opus" to opus,
        "AIFF" to aiff,
        "AAC (raw ADTS)" to adts,
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

    fun item(path: Path, id: Long = path.name.take(2).toLongOrNull() ?: path.name.hashCode().toLong()): QueueItem =
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

/**
 * A throwaway DebridOne data directory with a copy of the audio fixtures, and a Music module opened
 * on it. Opening it again simulates restarting the application.
 */
class MusicTestEnvironment : AutoCloseable {
    val root: Path = Files.createTempDirectory("debridone-music-test")
    val musicFolder: Path = Fixtures.copyTo(root.resolve("My Music"))
    private val paths = com.debridone.platform.AppPaths(root.resolve("data"), root.resolve("cache"))
    private val secrets = com.debridone.platform.secrets.FileSecretStore(root.resolve("secrets"))
    private var module: com.debridone.music.MusicModule? = null

    val firstAlbum: Path get() = musicFolder.resolve("Test Artist/First Album")

    /** Starts (or restarts) the module with [profileId] active and waits for its start-up scan. */
    fun open(
        profileId: Long? = PROFILE_A,
        metadataEndpoints: com.debridone.music.metadata.MetadataEndpoints = com.debridone.music.metadata.MetadataEndpoints(),
        cloudAudio: com.debridone.music.sources.CloudAudioSource? = null,
        engineFactory: () -> com.debridone.music.playback.MusicPlaybackEngine = { com.debridone.music.playback.FakePlaybackEngine() },
    ): MusicServices {
        module?.close()
        val storage = com.debridone.module.storage.ModuleStorage(com.debridone.module.ModuleId.MUSIC, paths, secrets)
        val opened = MusicModule(storage, engineFactory = engineFactory, metadataEndpoints = metadataEndpoints, cloudAudio = cloudAudio)
        module = opened
        kotlinx.coroutines.runBlocking {
            opened.start()
            check(opened.status.value == com.debridone.module.ModuleStatus.Running) { "Music did not start: ${opened.status.value}" }
            opened.onProfileChanged(profileId)
            opened.rescan().join()
        }
        return checkNotNull(opened.services.value)
    }

    fun switchProfile(profileId: Long?) {
        val current = checkNotNull(module)
        kotlinx.coroutines.runBlocking {
            current.onProfileChanged(profileId)
            current.rescan().join()
        }
    }

    fun deleteProfile(profileId: Long) = kotlinx.coroutines.runBlocking { checkNotNull(module).deleteProfileData(profileId) }

    val currentModule: MusicModule get() = checkNotNull(module)

    /** Reads a secret Music stored for [profileId], straight from the secret store. */
    fun storedSecret(profileId: Long, name: String): String? =
        com.debridone.platform.secrets.ScopedSecretStore(secrets, "module.music.profile.$profileId").get(name)

    /** Puts a secret where Music keeps its own for [profileId], as an earlier version of it would have. */
    fun storeSecret(profileId: Long, name: String, value: String) =
        com.debridone.platform.secrets.ScopedSecretStore(secrets, "module.music.profile.$profileId").put(name, value)

    /** Runs [sql] on Music's database file directly, to leave behind what an earlier version would have. */
    fun rawSql(sql: String) {
        val file = Files.walk(root.resolve("data")).use { files -> files.filter { it.fileName.toString() == "music.db" }.findFirst().get() }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }
    }

    /** Every byte Music wrote under its data folder, to prove a password is not among them. */
    fun dataFolderContains(text: String): Boolean {
        val needle = text.toByteArray()
        return Files.walk(root.resolve("data")).use { files ->
            files.filter { Files.isRegularFile(it) }.anyMatch { file ->
                val bytes = Files.readAllBytes(file)
                (0..bytes.size - needle.size).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
            }
        }
    }

    override fun close() {
        module?.close()
        module = null
        root.toFile().deleteRecursively()
    }

    companion object {
        const val PROFILE_A = 1001L
        const val PROFILE_B = 1002L
    }
}
