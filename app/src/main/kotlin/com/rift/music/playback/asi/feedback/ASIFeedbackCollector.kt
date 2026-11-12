/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.feedback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.rift.music.core.extensions.metadata
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.asi.model.CanonicalTrackId
import com.rift.music.playback.asi.model.SessionTrackEntry
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized, deterministic feedback collector for Project ASI 1.0.
 *
 * Implements:
 * 1. Explicit command-origin instrumentation across UI, MediaSession, notification, and Bluetooth.
 * 2. Unambiguous classification of natural completions, intentional skips, manual selections,
 *    replays, queue modifications, and system interruptions.
 * 3. Guarantee that playback errors and unclassified transitions never become negative taste signals.
 * 4. Duplicate-event suppression preventing double-counting.
 * 5. Persistent negative-signal journaling for early skips.
 */
@Singleton
class ASIFeedbackCollector @Inject constructor(
    val commandOriginRegistry: CommandOriginRegistry,
    val negativeSignalJournal: NegativeSignalJournal,
    val sessionProfile: ASISessionProfile,
    val config: ASIConfig,
) {
    constructor(
        commandOriginRegistry: CommandOriginRegistry,
        negativeSignalJournal: NegativeSignalJournal,
        sessionProfile: ASISessionProfile,
    ) : this(commandOriginRegistry, negativeSignalJournal, sessionProfile, ASIConfig())

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Tracks media items with recent errors to distinguish system errors from user skips
    private val recentErrorMediaIds = ConcurrentHashMap<String, Long>()

    // Duplicate-event suppression: monotonic key -> timestamp
    private var lastEventKey: String? = null
    private var lastEventTimeMs: Long = 0L

    // In-memory ring buffer of recent outcomes for inspection and testing
    private val recentOutcomes = ConcurrentLinkedDeque<PlaybackOutcome>()

    fun onPlayerError(error: PlaybackException, mediaId: String?) {
        if (mediaId != null && mediaId.isNotBlank()) {
            recentErrorMediaIds[mediaId] = System.currentTimeMillis()
        }
    }

    /**
     * Called on Media3 position discontinuity:
     * Handles track boundaries, explicit seeks, track removals, and replays.
     */
    fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
        knownDurationMs: Long? = null,
    ): PlaybackOutcome? {
        val oldMediaItem = oldPosition.mediaItem
        val oldMediaId = oldMediaItem?.mediaId
        val newMediaId = newPosition.mediaItem?.mediaId

        // If the media item did not change, check for in-track seek or replay
        if (oldMediaId == newMediaId && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || reason == Player.DISCONTINUITY_REASON_SEEK) {
                if (newPosition.positionMs <= 1000L && oldPosition.positionMs > config.earlySkipThresholdMs) {
                    val outcome = PlaybackOutcome.Replay(oldMediaId ?: "")
                    recordOutcome(outcome)
                    return outcome
                }
            }
            return null
        }

        val eventKey = "${oldMediaId}_${newMediaId}_$reason"
        val now = System.currentTimeMillis()
        if (eventKey == lastEventKey && now - lastEventTimeMs < config.duplicateEventCooldownMs) {
            return null // Duplicate event suppression
        }
        lastEventKey = eventKey
        lastEventTimeMs = now

        // 1. Check for involuntary system interruption (playback error, network watchdog)
        val hadRecentError = oldMediaId != null && (now - (recentErrorMediaIds[oldMediaId] ?: 0L) < 5000L)
        val hasDiagnosticError = oldMediaId != null && PlaybackDiagnostics.getRecentEvents().takeLast(10).any {
            it.mediaId == oldMediaId && (
                it.eventType == PlaybackEventType.ERROR ||
                it.eventType == PlaybackEventType.RECOVERY_ATTEMPT ||
                it.eventType == PlaybackEventType.WATCHDOG_STALL
            )
        }

        if (hadRecentError || hasDiagnosticError) {
            val outcome = PlaybackOutcome.SystemInterruption(
                mediaId = oldMediaId.orEmpty(),
                cause = InterruptionCause.PLAYBACK_ERROR,
                details = "Interrupted by player error or stream recovery"
            )
            recordOutcome(outcome)
            return outcome
        }

        // 2. Track Removal or Queue Modification
        if (reason == Player.DISCONTINUITY_REASON_REMOVE) {
            val outcome = PlaybackOutcome.QueueModification(
                mediaId = oldMediaId,
                details = "Removed from queue"
            )
            recordOutcome(outcome)
            return outcome
        }

        // 3. Natural Auto-Transition (track completed naturally at end)
        val oldDurationMs = knownDurationMs
            ?: (oldPosition.mediaItem?.metadata?.duration?.toLong()?.times(1000L))?.takeIf { it > 0 }
            ?: -1L

        val playTimeMs = oldPosition.positionMs
        val isNearEnd = oldDurationMs > 0 && playTimeMs >= (oldDurationMs * config.completionRatioThreshold).toLong()

        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION || isNearEnd) {
            val outcome = PlaybackOutcome.NaturalCompletion(
                mediaId = oldMediaId.orEmpty(),
                playTimeMs = playTimeMs,
                durationMs = oldDurationMs.coerceAtLeast(playTimeMs),
            )
            recordOutcome(outcome)
            sessionProfile.onTrackCompleted(
                SessionTrackEntry(
                    mediaId = oldMediaId.orEmpty(),
                    title = oldMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                    artist = oldMediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
                )
            )
            return outcome
        }

        // 4. Seek Discontinuity: Determine exact command origin
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            // Check for explicit Manual Track Selection
            val manualSelectCmd = commandOriginRegistry.consumeMatchingCommand<UserPlaybackCommand.ManualSelectTrack>(
                maxAgeMs = config.commandExpiryWindowMs
            )
            if (manualSelectCmd != null) {
                val outcome = PlaybackOutcome.ManualTrackSelection(
                    oldMediaId = oldMediaId,
                    newMediaId = newMediaId.orEmpty(),
                    targetIndex = manualSelectCmd.targetIndex,
                    source = manualSelectCmd.source,
                )
                recordOutcome(outcome)
                sessionProfile.onManualTrackSelection(
                    SessionTrackEntry(
                        mediaId = newMediaId.orEmpty(),
                        title = newPosition.mediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                        artist = newPosition.mediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
                    )
                )
                return outcome
            }

            // Check for explicit User Next Command (UI, Notification, Bluetooth AVRCP)
            val seekNextCmd = commandOriginRegistry.consumeMatchingCommand<UserPlaybackCommand.SeekNext>(
                maxAgeMs = config.commandExpiryWindowMs
            )
            if (seekNextCmd != null && oldMediaId != null) {
                val isEarly = playTimeMs < config.earlySkipThresholdMs
                val outcome = PlaybackOutcome.ExplicitUserSkip(
                    mediaId = oldMediaId,
                    playTimeMs = playTimeMs,
                    durationMs = oldDurationMs,
                    isEarly = isEarly,
                    source = seekNextCmd.source,
                )
                recordOutcome(outcome)

                val title = oldMediaItem.mediaMetadata.title?.toString().orEmpty()
                val artist = oldMediaItem.mediaMetadata.artist?.toString().orEmpty()
                val canonical = CanonicalTrackId.from(title, artist, (oldDurationMs / 1000L).toInt())

                sessionProfile.onExplicitSkip(
                    SessionTrackEntry(
                        mediaId = oldMediaId,
                        title = title,
                        artist = artist,
                    )
                )

                if (isEarly) {
                    scope.launch {
                        negativeSignalJournal.recordSkip(
                            canonicalId = canonical,
                            mediaId = oldMediaId,
                            isEarly = true,
                        )
                    }
                }
                return outcome
            }

            // Non-sequential jump without explicit command (e.g. external controller jump)
            val indexJump = newPosition.mediaItemIndex - oldPosition.mediaItemIndex
            if (kotlin.math.abs(indexJump) > 1 && oldMediaId != null) {
                val outcome = PlaybackOutcome.ManualTrackSelection(
                    oldMediaId = oldMediaId,
                    newMediaId = newMediaId.orEmpty(),
                    targetIndex = newPosition.mediaItemIndex,
                    source = CommandSource.INTERNAL_SYSTEM,
                )
                recordOutcome(outcome)
                sessionProfile.onManualTrackSelection(
                    SessionTrackEntry(
                        mediaId = newMediaId.orEmpty(),
                        title = newPosition.mediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                        artist = newPosition.mediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
                    )
                )
                return outcome
            }

            // Unclassified seek transition: MUST have NEUTRAL taste impact
            val unclassified = PlaybackOutcome.UnclassifiedTransition(
                mediaId = oldMediaId,
                playTimeMs = playTimeMs,
                reasonCode = reason,
            )
            recordOutcome(unclassified)
            return unclassified
        }

        return null
    }

    private fun recordOutcome(outcome: PlaybackOutcome) {
        recentOutcomes.addLast(outcome)
        while (recentOutcomes.size > 100) {
            recentOutcomes.pollFirst()
        }
    }

    fun getRecentOutcomes(limit: Int = 50): List<PlaybackOutcome> =
        recentOutcomes.toList().takeLast(limit)

    fun clear() {
        recentOutcomes.clear()
        recentErrorMediaIds.clear()
        lastEventKey = null
        lastEventTimeMs = 0L
    }
}
