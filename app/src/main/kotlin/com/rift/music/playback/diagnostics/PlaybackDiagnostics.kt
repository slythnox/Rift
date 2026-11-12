/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.diagnostics

import androidx.media3.common.Player
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Diagnostic event types tracking playback lifecycle, state transitions,
 * errors, and recovery operations.
 */
enum class PlaybackEventType {
    STATE_CHANGE,
    PLAY_WHEN_READY_CHANGE,
    IS_PLAYING_CHANGE,
    ERROR,
    RECOVERY_ATTEMPT,
    AUDIO_FOCUS,
    NETWORK_CHANGE,
    BUFFER_UNDERRUN,
    RETRY,
    PROVIDER_SELECTION,
    WATCHDOG_STALL
}

/**
 * Structured diagnostic record for playback event history.
 */
data class PlaybackDiagnosticEvent(
    val id: Long,
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionId: String,
    val mediaId: String?,
    val title: String?,
    val provider: String?,
    val eventType: PlaybackEventType,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val details: String? = null
) {
    val playbackStateName: String
        get() = when (playbackState) {
            Player.STATE_IDLE -> "STATE_IDLE"
            Player.STATE_BUFFERING -> "STATE_BUFFERING"
            Player.STATE_READY -> "STATE_READY"
            Player.STATE_ENDED -> "STATE_ENDED"
            else -> "UNKNOWN($playbackState)"
        }
}

/**
 * In-memory circular diagnostics buffer holding the most recent 100 playback events
 * for tracing silent stops, audio focus issues, and error recoveries.
 */
object PlaybackDiagnostics {
    private const val MAX_CAPACITY = 100
    private val counter = AtomicLong(0)
    private val buffer = ConcurrentLinkedDeque<PlaybackDiagnosticEvent>()
    private val _events = MutableStateFlow<List<PlaybackDiagnosticEvent>>(emptyList())
    val events: StateFlow<List<PlaybackDiagnosticEvent>> = _events.asStateFlow()

    @Volatile
    var currentSessionId: String = "session_0"

    fun record(
        eventType: PlaybackEventType,
        mediaId: String?,
        title: String? = null,
        provider: String? = null,
        playbackState: Int = Player.STATE_IDLE,
        playWhenReady: Boolean = false,
        isPlaying: Boolean = false,
        details: String? = null,
        sessionId: String = currentSessionId
    ) {
        val event = PlaybackDiagnosticEvent(
            id = counter.incrementAndGet(),
            sessionId = sessionId,
            mediaId = mediaId,
            title = title,
            provider = provider,
            eventType = eventType,
            playbackState = playbackState,
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            details = details
        )
        buffer.addLast(event)
        while (buffer.size > MAX_CAPACITY) {
            buffer.pollFirst()
        }
        _events.value = buffer.toList()
    }

    fun getRecentEvents(): List<PlaybackDiagnosticEvent> = buffer.toList()

    fun clear() {
        buffer.clear()
        _events.value = emptyList()
    }
}
