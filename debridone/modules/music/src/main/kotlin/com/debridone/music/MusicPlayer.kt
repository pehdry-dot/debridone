package com.debridone.music

import com.debridone.music.library.MusicLibraryRepository
import com.debridone.music.library.Track
import com.debridone.music.playback.AudioDevice
import com.debridone.music.playback.MusicPlaybackEngine
import com.debridone.music.playback.MusicPlayerController
import com.debridone.music.playback.PlaybackError
import com.debridone.music.playback.PlaybackStatus
import com.debridone.music.playback.PlayerObserver
import com.debridone.music.playback.PlayerState
import com.debridone.music.playback.QueueItem
import com.debridone.music.playback.RepeatMode
import com.debridone.platform.db.SqliteDatabase
import com.debridone.platform.db.getBooleanInt
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long

/**
 * The Music player as the rest of the app sees it: plays library tracks, remembers each profile's
 * queue and position, and writes listening history.
 *
 * It lives in the module, not in a screen, so music keeps playing while the user is in another
 * section of DebridOne.
 */
class MusicPlayer internal constructor(
    private val library: MusicLibraryRepository,
    private val database: SqliteDatabase,
    private val engineFactory: () -> MusicPlaybackEngine,
    private val scope: CoroutineScope,
    /** Turns a library track into what the engine can open (a path, or a stream URL of its source). */
    private val resolveUri: (profileId: Long, track: Track) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _engineError = MutableStateFlow<String?>(null)

    /** Why the audio engine could not be started, when it could not. */
    val engineError: StateFlow<String?> = _engineError.asStateFlow()

    private val _nowPlaying = MutableStateFlow<Track?>(null)

    /** The library entry of the current track, kept up to date with edits and favourites. */
    val nowPlaying: StateFlow<Track?> = _nowPlaying.asStateFlow()

    private val _engineDescription = MutableStateFlow<String?>(null)
    val engineDescription: StateFlow<String?> = _engineDescription.asStateFlow()

    @Volatile
    private var profileId: Long? = null

    @Volatile
    private var controller: MusicPlayerController? = null
    private var mirrorJob: Job? = null
    private var lastSaved: SavedSignature? = null
    private val lock = Any()

    init {
        // The current track's library row: refreshed when the track or the library changes.
        scope.launch(Dispatchers.IO) {
            combine(state.map { it.current?.trackId }.distinctUntilChanged(), library.revision) { trackId, _ -> trackId }
                .collect { trackId ->
                    val profile = profileId
                    _nowPlaying.value = if (trackId == null || profile == null) null else runCatching { library.track(profile, trackId) }.getOrNull()
                }
        }
        // Save the session a little after it changes, and the position every few seconds.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                runCatching { saveSession(force = false) }
            }
        }
        // A track whose length the tag reader could not determine learns it from the engine.
        scope.launch(Dispatchers.IO) {
            state.map { Triple(it.current?.trackId, it.current?.durationMs ?: 0, it.durationMs) }.distinctUntilChanged().collect { (trackId, known, measured) ->
                val profile = profileId
                if (trackId != null && profile != null && known <= 0 && measured > 0) {
                    runCatching { library.updateDuration(profile, trackId, measured) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- playing

    /** Replaces the queue with [tracks] and plays from [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean? = null) {
        val profile = profileId ?: return
        if (tracks.isEmpty()) return
        val player = ensureController() ?: return
        val startTrack = tracks.getOrNull(startIndex)
        val items = tracks.mapNotNull { toQueueItem(profile, it) }
        if (items.isEmpty()) return
        val start = items.indexOfFirst { it.trackId == startTrack?.id }.coerceAtLeast(0)
        player.playQueue(items, start, shuffle)
    }

    fun playNext(tracks: List<Track>) {
        val profile = profileId ?: return
        ensureController()?.playNext(tracks.mapNotNull { toQueueItem(profile, it) })
    }

    fun addToQueue(tracks: List<Track>) {
        val profile = profileId ?: return
        ensureController()?.addToQueue(tracks.mapNotNull { toQueueItem(profile, it) })
    }

    fun togglePlayPause() {
        ensureController()?.togglePlayPause()
    }

    fun resume() {
        ensureController()?.play()
    }

    fun pause() {
        controller?.pause()
        scope.launch(Dispatchers.IO) { runCatching { saveSession(force = true) } }
    }

    fun next() = controller?.next() ?: Unit
    fun previous() = controller?.previous() ?: Unit
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs) ?: Unit
    fun skipTo(orderPosition: Int) = controller?.skipTo(orderPosition) ?: Unit
    fun removeFromQueue(orderPosition: Int) = controller?.removeFromQueue(orderPosition) ?: Unit
    fun moveInQueue(from: Int, to: Int) = controller?.moveInQueue(from, to) ?: Unit
    fun clearQueue() = controller?.clearQueue() ?: Unit
    fun stop() = controller?.stop() ?: Unit
    fun setVolume(percent: Int) = ensureController()?.setVolume(percent) ?: Unit
    fun toggleMute() = ensureController()?.toggleMute() ?: Unit
    fun setShuffle(enabled: Boolean) = ensureController()?.setShuffle(enabled) ?: Unit
    fun cycleRepeat() = ensureController()?.cycleRepeat() ?: Unit
    fun setRepeat(mode: RepeatMode) = ensureController()?.setRepeat(mode) ?: Unit
    fun dismissError() = controller?.dismissError() ?: Unit

    fun audioDevices(): List<AudioDevice> = engine?.audioDevices().orEmpty()

    /** Selects the output device (`auto` follows the system). Remembered for when the engine starts. */
    fun setAudioDevice(id: String) {
        audioDevice = id.ifBlank { "auto" }
        runCatching { engine?.setAudioDevice(audioDevice) }
    }

    @Volatile
    private var audioDevice: String = "auto"

    @Volatile
    private var engine: MusicPlaybackEngine? = null

    // ---------------------------------------------------------------- profiles

    /**
     * Switches to another profile's session: the current profile's queue and position are saved and
     * its playback stops; the new profile's last queue is loaded paused. Music never carries over
     * from one profile to another.
     */
    fun switchProfile(newProfileId: Long?) {
        synchronized(lock) {
            if (newProfileId == profileId && (controller != null || newProfileId == null)) return
            runCatching { saveSession(force = true) }
            controller?.let { player ->
                player.stop()
                player.awaitIdle()
            }
            profileId = newProfileId
            lastSaved = null
            if (newProfileId == null) {
                controller?.clearQueue()
                controller?.awaitIdle()
                _nowPlaying.value = null
                return
            }
            val saved = loadSession(newProfileId)
            val player = ensureController()
            if (player == null) {
                _state.value = PlayerState()
                return
            }
            if (saved == null) {
                player.restore(emptyList(), emptyList(), -1, 0, shuffle = false, repeat = RepeatMode.OFF, volume = _state.value.volume, muted = false)
            } else {
                player.restore(saved.items, saved.order, saved.orderPosition, saved.positionMs, saved.shuffle, saved.repeat, saved.volume, saved.muted)
            }
            player.awaitIdle()
        }
    }

    /** Forgets [deletedProfileId]'s saved session. */
    fun deleteProfile(deletedProfileId: Long) {
        database.execute("DELETE FROM music_playback_state WHERE profile_id = ?", deletedProfileId)
    }

    /** Drops tracks that are no longer in the library from the queue. */
    fun removeDeletedFromQueue() {
        val profile = profileId ?: return
        val player = controller ?: return
        val current = state.value
        if (current.queue.isEmpty()) return
        val known = library.tracksByIds(profile, current.queue.map { it.trackId }.distinct()).map { it.id }.toSet()
        current.order.indices.reversed().forEach { position ->
            if (current.queue[current.order[position]].trackId !in known) player.removeFromQueue(position)
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { saveSession(force = true) }
            mirrorJob?.cancel()
            controller?.close()
            controller = null
            engine = null
        }
    }

    // ---------------------------------------------------------------- internals

    private fun ensureController(): MusicPlayerController? {
        controller?.let { return it }
        synchronized(lock) {
            controller?.let { return it }
            val created = try {
                engineFactory()
            } catch (t: Throwable) {
                _engineError.value = t.message ?: "The audio engine could not be started."
                return null
            }
            _engineError.value = null
            _engineDescription.value = created.description
            engine = created
            if (audioDevice != "auto") runCatching { created.setAudioDevice(audioDevice) }
            val player = MusicPlayerController(created, observer = observer)
            controller = player
            mirrorJob = scope.launch { player.state.collect { _state.value = it } }
            return player
        }
    }

    private val observer = object : PlayerObserver {
        override fun onTrackPlayed(item: QueueItem, listenedMs: Long, completed: Boolean) {
            val profile = profileId ?: return
            runCatching { library.recordPlay(profile, item.trackId, listenedMs, completed) }
        }

        override fun onPlaybackError(error: PlaybackError) {
            // A local file that is gone is flagged, so lists can show it as missing.
            val profile = profileId ?: return
            runCatching {
                val track = library.track(profile, error.trackId) ?: return
                val path = track.path ?: return
                if (track.isLocal && !track.missing && !Files.exists(Paths.get(path))) {
                    library.setMissing(listOf(track.id), true)
                    library.notifyChanged()
                }
            }
        }
    }

    private fun toQueueItem(profile: Long, track: Track): QueueItem? {
        val uri = resolveUri(profile, track) ?: return null
        return QueueItem(
            trackId = track.id,
            uri = uri,
            title = track.title,
            artist = track.displayArtist,
            album = track.album,
            artworkPath = track.artwork?.filePath,
            durationMs = track.durationMs,
        )
    }

    private data class SavedSession(
        val items: List<QueueItem>,
        val order: List<Int>,
        val orderPosition: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeat: RepeatMode,
        val volume: Int,
        val muted: Boolean,
    )

    /** What has to differ for a save to be worth writing. */
    private data class SavedSignature(
        val trackIds: List<Long>,
        val order: List<Int>,
        val orderPosition: Int,
        val positionBucket: Long,
        val shuffle: Boolean,
        val repeat: RepeatMode,
        val volume: Int,
        val muted: Boolean,
    )

    private fun saveSession(force: Boolean) {
        val profile = profileId ?: return
        if (controller == null) return
        val current = state.value
        val position = if (current.status == PlaybackStatus.ENDED) 0 else current.positionMs
        val signature = SavedSignature(
            current.queue.map { it.trackId }, current.order, current.orderPosition, position / POSITION_BUCKET_MS,
            current.shuffle, current.repeat, current.volume, current.muted,
        )
        if (!force && signature == lastSaved) return
        // Only track ids are stored: a stream URL may carry credentials and must not be persisted.
        database.execute(
            """
            INSERT INTO music_playback_state (profile_id, queue, play_order, order_position, position_ms, shuffle, repeat_mode, volume, muted, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(profile_id) DO UPDATE SET queue = excluded.queue, play_order = excluded.play_order,
                order_position = excluded.order_position, position_ms = excluded.position_ms, shuffle = excluded.shuffle,
                repeat_mode = excluded.repeat_mode, volume = excluded.volume, muted = excluded.muted, updated_at = excluded.updated_at
            """.trimIndent(),
            profile, JsonArray(signature.trackIds.map(::JsonPrimitive)).toString(), JsonArray(current.order.map(::JsonPrimitive)).toString(),
            current.orderPosition, position, current.shuffle, current.repeat.name, current.volume, current.muted, clock(),
        )
        lastSaved = signature
    }

    private fun loadSession(profile: Long): SavedSession? {
        val row = database.queryOne("SELECT * FROM music_playback_state WHERE profile_id = ?", profile) { rs ->
            SavedRow(
                rs.getString("queue"), rs.getString("play_order"), rs.getInt("order_position"), rs.getLong("position_ms"),
                rs.getBooleanInt("shuffle"), rs.getString("repeat_mode"), rs.getInt("volume"), rs.getBooleanInt("muted"),
            )
        } ?: return null
        val ids = runCatching { Json.parseToJsonElement(row.queue).jsonArray.map { (it as JsonPrimitive).long } }.getOrDefault(emptyList())
        val order = runCatching { Json.parseToJsonElement(row.order).jsonArray.map { (it as JsonPrimitive).long.toInt() } }.getOrDefault(emptyList())
        val tracks = library.tracksByIds(profile, ids.distinct()).associateBy { it.id }

        // Tracks that left the library since the session was saved drop out; the order follows.
        val keptIndexes = ArrayList<Int>()
        val items = ArrayList<QueueItem>()
        ids.forEachIndexed { index, id ->
            val item = tracks[id]?.let { toQueueItem(profile, it) }
            if (item != null) {
                keptIndexes += index
                items += item
            }
        }
        val remap = keptIndexes.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        val currentOld = order.getOrNull(row.orderPosition)
        val newOrder = order.mapNotNull { remap[it] }
        val validOrder = newOrder.takeIf { it.size == items.size && it.toSet().size == items.size } ?: items.indices.toList()
        val currentSurvived = currentOld != null && currentOld in remap
        val newPosition = if (currentSurvived) validOrder.indexOf(remap.getValue(currentOld!!)).coerceAtLeast(0) else 0
        return SavedSession(
            items = items,
            order = validOrder,
            orderPosition = newPosition,
            positionMs = if (currentSurvived) row.positionMs else 0,
            shuffle = row.shuffle,
            repeat = runCatching { RepeatMode.valueOf(row.repeat) }.getOrDefault(RepeatMode.OFF),
            volume = row.volume,
            muted = row.muted,
        )
    }

    private class SavedRow(
        val queue: String, val order: String, val orderPosition: Int, val positionMs: Long,
        val shuffle: Boolean, val repeat: String, val volume: Int, val muted: Boolean,
    )

    private companion object {
        const val SAVE_INTERVAL_MS = 2_000L
        const val POSITION_BUCKET_MS = 5_000L
    }
}
