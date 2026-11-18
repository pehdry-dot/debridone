package com.debridone.music.playback

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One entry of the play queue: enough to play and to show it without touching the library. */
/**
 * Start of the `uri` of a queue item whose real address is asked for when it is about to play
 * (a song of an addon, whose addresses expire). See [MusicPlayerController]'s `resolver`.
 */
const val DEFERRED_SCHEME = "debridone-deferred:"

data class QueueItem(
    val trackId: Long,
    /** File path or URL handed to the engine. */
    val uri: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkPath: String? = null,
    val durationMs: Long = 0,
)

enum class RepeatMode { OFF, ALL, ONE }

enum class PlaybackStatus {
    /** Nothing loaded. */
    IDLE,

    /** A track was requested and has not produced audio yet. */
    LOADING,
    PLAYING,
    PAUSED,

    /** The queue played to its end. */
    ENDED,
}

data class PlaybackError(val trackId: Long, val title: String, val message: String)

data class PlayerState(
    val queue: List<QueueItem> = emptyList(),
    /** Playback order as indices into [queue]. Identity unless shuffle is on. */
    val order: List<Int> = emptyList(),
    /** Position of the current track inside [order], or -1. */
    val orderPosition: Int = -1,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Int = 100,
    val muted: Boolean = false,
    /** The most recent track that failed to play, until it is dismissed or a new queue starts. */
    val lastError: PlaybackError? = null,
) {
    val currentIndex: Int get() = order.getOrNull(orderPosition) ?: -1
    val current: QueueItem? get() = queue.getOrNull(currentIndex)
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING
    val hasNext: Boolean get() = orderPosition in 0 until order.lastIndex || (repeat == RepeatMode.ALL && order.isNotEmpty())
    val hasPrevious: Boolean get() = orderPosition > 0 || (repeat == RepeatMode.ALL && order.isNotEmpty())

    /** Tracks that will play after the current one, in order. */
    val upNext: List<QueueItem> get() = if (orderPosition < 0) emptyList() else order.drop(orderPosition + 1).map { queue[it] }
}

/** What the controller reports outwards; implemented by the Music module (history, persistence). */
interface PlayerObserver {
    /** A track stopped being the current one after [listenedMs] of actual listening. */
    fun onTrackPlayed(item: QueueItem, listenedMs: Long, completed: Boolean) {}

    /** The queue or the playback settings changed and should be saved. */
    fun onSessionChanged(state: PlayerState) {}

    /** A track could not be played. The player has already moved on or stopped. */
    fun onPlaybackError(error: PlaybackError) {}
}

/**
 * The player: queue, shuffle, repeat, automatic progression, volume and error handling on top of
 * a [MusicPlaybackEngine].
 *
 * Every state change runs on one private thread, so user commands and engine callbacks cannot
 * interleave. The state is published as an immutable [PlayerState].
 */
class MusicPlayerController(
    private val engine: MusicPlaybackEngine,
    private val random: Random = Random.Default,
    private val observer: PlayerObserver = object : PlayerObserver {},
    /**
     * For items whose address is only known when they are about to play (their `uri` starts with
     * [DEFERRED_SCHEME]): returns the address to open. It is called off the player thread, may take
     * a few seconds (it asks the song's source) and may throw with a message for the listener.
     */
    private val resolver: ((QueueItem) -> String)? = null,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "debridone-music-player").apply { isDaemon = true }
    }

    /** Runs [resolver]; a source that is slow to answer must not hold up pause, seek or skip. */
    private val resolving = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "debridone-music-resolve").apply { isDaemon = true }
    }

    /** Counts loads, so the answer for a song that was skipped while it was being resolved is dropped. */
    private var loadTicket = 0L
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Token of the load the engine is expected to report on; events of older loads are ignored. */
    private var activeToken: Long = -1
    private var listenedMs: Long = 0
    private var lastPositionMs: Long = -1
    private var consecutiveErrors = 0
    private var pendingAdvance: ScheduledFuture<*>? = null

    /** Position to start from when a restored session is resumed. */
    private var resumePositionMs: Long = 0

    @Volatile
    private var closed = false

    init {
        engine.setListener(object : PlaybackEngineListener {
            override fun onTrackStarted(token: Long) = post { handleStarted(token) }
            override fun onTrackEnded(token: Long, reason: TrackEndReason, error: String?) = post { handleEnded(token, reason, error) }
            override fun onPosition(token: Long, positionMs: Long) = post { handlePosition(token, positionMs) }
            override fun onDuration(token: Long, durationMs: Long) = post {
                if (token == activeToken && durationMs > 0) update { it.copy(durationMs = durationMs) }
            }
            override fun onPaused(paused: Boolean) = post { handlePaused(paused) }
        })
    }

    // ---------------------------------------------------------------- commands

    /** Replaces the queue with [items] and starts playing at [startIndex]. */
    fun playQueue(items: List<QueueItem>, startIndex: Int = 0, shuffle: Boolean? = null) = post {
        if (items.isEmpty()) return@post
        finishCurrent(completed = false)
        val start = startIndex.coerceIn(0, items.lastIndex)
        val shuffled = shuffle ?: _state.value.shuffle
        val order = buildOrder(items.size, start, shuffled)
        consecutiveErrors = 0
        update {
            it.copy(queue = items, order = order, orderPosition = order.indexOf(start), shuffle = shuffled, lastError = null)
        }
        loadCurrent(startPositionMs = 0, paused = false)
        notifySession()
    }

    /** Adds [items] to the end of the queue. Starts playback if nothing was queued. */
    fun addToQueue(items: List<QueueItem>) = post {
        if (items.isEmpty()) return@post
        val before = _state.value
        if (before.queue.isEmpty()) {
            postDirect { playQueueNow(items) }
            return@post
        }
        val firstNew = before.queue.size
        update { it.copy(queue = it.queue + items, order = it.order + (firstNew until firstNew + items.size)) }
        notifySession()
    }

    /** Inserts [items] right after the current track. */
    fun playNext(items: List<QueueItem>) = post {
        if (items.isEmpty()) return@post
        val before = _state.value
        if (before.queue.isEmpty()) {
            postDirect { playQueueNow(items) }
            return@post
        }
        val firstNew = before.queue.size
        val insertAt = (before.orderPosition + 1).coerceIn(0, before.order.size)
        val order = before.order.toMutableList().apply { addAll(insertAt, (firstNew until firstNew + items.size).toList()) }
        update { it.copy(queue = it.queue + items, order = order) }
        notifySession()
    }

    /** Removes the entry at [orderPosition] of the playback order. */
    fun removeFromQueue(orderPosition: Int) = post {
        val before = _state.value
        if (orderPosition !in before.order.indices) return@post
        val removedIndex = before.order[orderPosition]
        val removingCurrent = orderPosition == before.orderPosition
        if (removingCurrent) finishCurrent(completed = false)

        val queue = before.queue.toMutableList().apply { removeAt(removedIndex) }
        val order = before.order.toMutableList().apply { removeAt(orderPosition) }
            .map { if (it > removedIndex) it - 1 else it }
        val newPosition = when {
            order.isEmpty() -> -1
            orderPosition < before.orderPosition -> before.orderPosition - 1
            removingCurrent -> orderPosition.coerceAtMost(order.lastIndex)
            else -> before.orderPosition
        }
        update { it.copy(queue = queue, order = order, orderPosition = newPosition) }
        if (removingCurrent) {
            if (order.isEmpty()) stopNow() else loadCurrent(0, paused = !before.isPlaying)
        }
        notifySession()
    }

    /** Moves an upcoming entry within the playback order (drag to reorder "Up next"). */
    fun moveInQueue(fromOrderPosition: Int, toOrderPosition: Int) = post {
        val before = _state.value
        if (fromOrderPosition !in before.order.indices || toOrderPosition !in before.order.indices) return@post
        if (fromOrderPosition == toOrderPosition) return@post
        val currentIndex = before.currentIndex
        val order = before.order.toMutableList()
        order.add(toOrderPosition, order.removeAt(fromOrderPosition))
        update { it.copy(order = order, orderPosition = order.indexOf(currentIndex)) }
        notifySession()
    }

    fun clearQueue() = post {
        finishCurrent(completed = false)
        stopNow()
        update { PlayerState(shuffle = it.shuffle, repeat = it.repeat, volume = it.volume, muted = it.muted) }
        notifySession()
    }

    fun play() = post {
        val current = _state.value
        when {
            current.current == null -> Unit
            current.status == PlaybackStatus.PAUSED && activeToken >= 0 -> engine.setPaused(false)
            current.status == PlaybackStatus.IDLE || current.status == PlaybackStatus.ENDED || activeToken < 0 -> {
                val resume = resumePositionMs
                resumePositionMs = 0
                loadCurrent(startPositionMs = if (current.status == PlaybackStatus.ENDED) 0 else resume, paused = false)
            }
            else -> engine.setPaused(false)
        }
    }

    fun pause() = post {
        if (_state.value.isPlaying && activeToken >= 0) engine.setPaused(true)
    }

    fun togglePlayPause() = post {
        if (_state.value.isPlaying) postDirect { if (activeToken >= 0) engine.setPaused(true) } else postDirect { playNow() }
    }

    fun next() = post { advance(userInitiated = true) }

    /** Restarts the track when more than a few seconds in, otherwise goes to the previous one. */
    fun previous() = post {
        val current = _state.value
        if (current.current == null) return@post
        if (current.positionMs > RESTART_THRESHOLD_MS || !current.hasPrevious) {
            seekNow(0)
            return@post
        }
        finishCurrent(completed = false)
        val target = if (current.orderPosition > 0) current.orderPosition - 1 else current.order.lastIndex
        update { it.copy(orderPosition = target) }
        loadCurrent(0, paused = !current.isPlaying)
        notifySession()
    }

    /** Jumps to the entry at [orderPosition] of the playback order. */
    fun skipTo(orderPosition: Int) = post {
        val current = _state.value
        if (orderPosition !in current.order.indices) return@post
        finishCurrent(completed = false)
        update { it.copy(orderPosition = orderPosition) }
        loadCurrent(0, paused = false)
        notifySession()
    }

    fun seekTo(positionMs: Long) = post { seekNow(positionMs) }

    fun dismissError() = post { update { it.copy(lastError = null) } }

    fun setVolume(percent: Int) = post {
        val volume = percent.coerceIn(0, 100)
        engine.setVolume(volume)
        update { it.copy(volume = volume) }
        notifySession()
    }

    fun setMuted(muted: Boolean) = post {
        engine.setMuted(muted)
        update { it.copy(muted = muted) }
        notifySession()
    }

    fun toggleMute() = post { postDirect { setMutedNow(!_state.value.muted) } }

    fun setShuffle(enabled: Boolean) = post {
        val before = _state.value
        if (before.shuffle == enabled) return@post
        val currentIndex = before.currentIndex
        val order = if (before.queue.isEmpty()) emptyList() else buildOrder(before.queue.size, currentIndex.coerceAtLeast(0), enabled)
        update { it.copy(shuffle = enabled, order = order, orderPosition = if (currentIndex >= 0) order.indexOf(currentIndex) else -1) }
        notifySession()
    }

    fun setRepeat(mode: RepeatMode) = post {
        update { it.copy(repeat = mode) }
        notifySession()
    }

    fun cycleRepeat() = post {
        val next = when (_state.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        update { it.copy(repeat = next) }
        notifySession()
    }

    /** Stops playback and forgets the queue position, keeping the queue itself. */
    fun stop() = post {
        finishCurrent(completed = false)
        stopNow()
        update { it.copy(status = PlaybackStatus.IDLE, positionMs = 0) }
        notifySession()
    }

    /**
     * Loads a saved session without making a sound: the queue is shown paused at [positionMs] and
     * starts from there when the user presses play.
     */
    fun restore(
        queue: List<QueueItem>,
        order: List<Int>,
        orderPosition: Int,
        positionMs: Long,
        shuffle: Boolean,
        repeat: RepeatMode,
        volume: Int,
        muted: Boolean,
    ) = post {
        finishCurrent(completed = false)
        stopNow()
        val validOrder = order.takeIf { it.size == queue.size && it.toSet() == queue.indices.toSet() } ?: queue.indices.toList()
        val position = if (queue.isEmpty()) -1 else orderPosition.coerceIn(0, validOrder.lastIndex)
        val safeVolume = volume.coerceIn(0, 100)
        engine.setVolume(safeVolume)
        engine.setMuted(muted)
        resumePositionMs = positionMs.coerceAtLeast(0)
        _state.value = PlayerState(
            queue = queue,
            order = validOrder,
            orderPosition = position,
            status = PlaybackStatus.IDLE,
            positionMs = if (queue.isEmpty()) 0 else resumePositionMs,
            durationMs = queue.getOrNull(validOrder.getOrNull(position) ?: -1)?.durationMs ?: 0,
            shuffle = shuffle,
            repeat = repeat,
            volume = safeVolume,
            muted = muted,
        )
    }

    /**
     * Returns once every command sent so far has been applied, including what those commands
     * queued in turn. Used by tests and before saving a session on shutdown.
     */
    fun awaitIdle(timeoutMs: Long = 5_000) {
        repeat(2) {
            if (closed) return
            runCatching { executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    override fun close() {
        if (closed) return
        runCatching {
            executor.submit {
                finishCurrent(completed = false)
                pendingAdvance?.cancel(false)
            }.get(2, TimeUnit.SECONDS)
        }
        closed = true
        engine.setListener(null)
        executor.shutdownNow()
        resolving.shutdownNow()
        engine.close()
    }

    // --------------------------------------------------------- engine callbacks

    private fun handleStarted(token: Long) {
        if (token != activeToken) return
        consecutiveErrors = 0
        update { it.copy(status = if (it.status == PlaybackStatus.LOADING) PlaybackStatus.PLAYING else it.status) }
    }

    private fun handlePosition(token: Long, positionMs: Long) {
        if (token != activeToken) return
        if (_state.value.status == PlaybackStatus.PLAYING) {
            val delta = positionMs - lastPositionMs
            // Count only steady forward progress, not seeks.
            if (lastPositionMs >= 0 && delta in 1..MAX_PROGRESS_STEP_MS) listenedMs += delta
        }
        lastPositionMs = positionMs
        update { it.copy(positionMs = positionMs) }
    }

    private fun handlePaused(paused: Boolean) {
        if (activeToken < 0) return
        update {
            when {
                paused && (it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.LOADING) -> it.copy(status = PlaybackStatus.PAUSED)
                !paused && it.status == PlaybackStatus.PAUSED -> it.copy(status = PlaybackStatus.PLAYING)
                else -> it
            }
        }
    }

    private fun handleEnded(token: Long, reason: TrackEndReason, error: String?) {
        if (token != activeToken) return
        when (reason) {
            TrackEndReason.STOPPED -> Unit
            TrackEndReason.FINISHED -> {
                finishCurrent(completed = true)
                activeToken = -1
                if (_state.value.repeat == RepeatMode.ONE) loadCurrent(0, paused = false) else advance(userInitiated = false)
            }
            TrackEndReason.ERROR -> {
                val failed = _state.value.current
                activeToken = -1
                listenedMs = 0
                lastPositionMs = -1
                consecutiveErrors++
                if (failed != null) {
                    reportError(PlaybackError(failed.trackId, failed.title, error ?: "This track could not be played."))
                }
                // Skip a broken track, but never spin through a queue where nothing plays.
                if (consecutiveErrors >= _state.value.queue.size || _state.value.repeat == RepeatMode.ONE) {
                    update { it.copy(status = PlaybackStatus.IDLE, positionMs = 0) }
                } else {
                    pendingAdvance?.cancel(false)
                    pendingAdvance = executor.schedule({ runGuarded { advance(userInitiated = false) } }, ERROR_SKIP_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun advance(userInitiated: Boolean) {
        val current = _state.value
        if (current.order.isEmpty()) return
        finishCurrent(completed = false)
        val atEnd = current.orderPosition >= current.order.lastIndex
        if (atEnd && current.repeat != RepeatMode.ALL) {
            if (userInitiated) return // "Next" on the last track does nothing.
            // The queue is over: rewind to its first track so that Play starts it again.
            stopNow()
            update { it.copy(status = PlaybackStatus.ENDED, orderPosition = 0, positionMs = 0, durationMs = it.queue[it.order[0]].durationMs) }
            notifySession()
            return
        }
        update { it.copy(orderPosition = if (atEnd) 0 else it.orderPosition + 1) }
        loadCurrent(0, paused = userInitiated && current.status == PlaybackStatus.PAUSED)
        notifySession()
    }

    private fun loadCurrent(startPositionMs: Long, paused: Boolean) {
        pendingAdvance?.cancel(false)
        val item = _state.value.current ?: return
        listenedMs = 0
        lastPositionMs = -1
        update {
            it.copy(
                status = if (paused) PlaybackStatus.PAUSED else PlaybackStatus.LOADING,
                positionMs = startPositionMs,
                durationMs = item.durationMs,
            )
        }
        val resolve = resolver
        if (resolve == null || !item.uri.startsWith(DEFERRED_SCHEME)) {
            loadTicket++
            open(item, item.uri, startPositionMs, paused)
            return
        }
        // Ask the song's source where the audio is, off this thread. Whatever was playing is
        // stopped first: the state already shows the new song.
        stopNow()
        val ticket = loadTicket
        runCatching {
            resolving.execute {
                val outcome = runCatching { resolve(item) }
                post {
                    if (ticket != loadTicket) return@post
                    outcome.fold(
                        onSuccess = { uri -> open(item, uri, startPositionMs, paused) },
                        onFailure = { failure -> loadFailed(item, failure.message) },
                    )
                }
            }
        }.onFailure { loadFailed(item, null) }
    }

    private fun open(item: QueueItem, uri: String, startPositionMs: Long, paused: Boolean) {
        activeToken = try {
            engine.load(uri, startPositionMs, paused)
        } catch (e: RuntimeException) {
            update { it.copy(status = PlaybackStatus.IDLE) }
            reportError(PlaybackError(item.trackId, item.title, e.message ?: "This track could not be played."))
            -1L
        }
    }

    /** A song whose address could not be obtained: reported, then skipped like one that failed to decode. */
    private fun loadFailed(item: QueueItem, message: String?) {
        activeToken = -1
        listenedMs = 0
        lastPositionMs = -1
        consecutiveErrors++
        reportError(PlaybackError(item.trackId, item.title, message ?: "This track could not be played."))
        if (consecutiveErrors >= _state.value.queue.size || _state.value.repeat == RepeatMode.ONE) {
            update { it.copy(status = PlaybackStatus.IDLE, positionMs = 0) }
        } else {
            pendingAdvance?.cancel(false)
            pendingAdvance = executor.schedule({ runGuarded { advance(userInitiated = false) } }, ERROR_SKIP_DELAY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun playQueueNow(items: List<QueueItem>) {
        val order = buildOrder(items.size, 0, _state.value.shuffle)
        consecutiveErrors = 0
        update { it.copy(queue = items, order = order, orderPosition = 0, lastError = null) }
        loadCurrent(0, paused = false)
        notifySession()
    }

    private fun playNow() {
        val current = _state.value
        when {
            current.current == null -> Unit
            activeToken >= 0 && current.status == PlaybackStatus.PAUSED -> engine.setPaused(false)
            else -> {
                val resume = if (current.status == PlaybackStatus.ENDED) 0 else resumePositionMs
                resumePositionMs = 0
                loadCurrent(resume, paused = false)
            }
        }
    }

    private fun seekNow(positionMs: Long) {
        val current = _state.value
        if (current.current == null) return
        val target = positionMs.coerceIn(0, if (current.durationMs > 0) current.durationMs else Long.MAX_VALUE)
        if (activeToken >= 0) {
            lastPositionMs = -1
            engine.seekTo(target)
        } else {
            resumePositionMs = target
        }
        update { it.copy(positionMs = target) }
    }

    private fun setMutedNow(muted: Boolean) {
        engine.setMuted(muted)
        update { it.copy(muted = muted) }
        notifySession()
    }

    private fun stopNow() {
        pendingAdvance?.cancel(false)
        // An address still being asked for belongs to a song that is no longer wanted.
        loadTicket++
        if (activeToken >= 0) {
            activeToken = -1
            runCatching { engine.stop() }
        }
    }

    /** Reports how long the outgoing track was really listened to. */
    private fun finishCurrent(completed: Boolean) {
        val item = _state.value.current
        if (item != null && (completed || listenedMs >= MIN_LISTEN_MS)) {
            val listened = listenedMs
            runCatching { observer.onTrackPlayed(item, listened, completed) }
        }
        listenedMs = 0
        lastPositionMs = -1
    }

    private fun buildOrder(size: Int, startIndex: Int, shuffle: Boolean): List<Int> {
        if (!shuffle) return (0 until size).toList()
        val rest = (0 until size).filter { it != startIndex }.shuffled(random)
        return listOf(startIndex) + rest
    }

    private fun reportError(error: PlaybackError) {
        update { it.copy(lastError = error) }
        runCatching { observer.onPlaybackError(error) }
    }

    private fun notifySession() {
        val snapshot = _state.value
        runCatching { observer.onSessionChanged(snapshot) }
    }

    private inline fun update(change: (PlayerState) -> PlayerState) {
        _state.value = change(_state.value)
    }

    private fun post(block: () -> Unit) {
        if (closed) return
        runCatching { executor.execute { runGuarded(block) } }
    }

    /** Already on the player thread: run inline. */
    private inline fun postDirect(block: () -> Unit) = block()

    private inline fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // A failing command must not take the player thread down with it.
            val item = _state.value.current
            update { it.copy(lastError = PlaybackError(item?.trackId ?: -1, item?.title.orEmpty(), t.message ?: t::class.simpleName.orEmpty())) }
        }
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
        const val MAX_PROGRESS_STEP_MS = 2_000L
        const val MIN_LISTEN_MS = 1_000L
        const val ERROR_SKIP_DELAY_MS = 400L
    }
}
