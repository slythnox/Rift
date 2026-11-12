/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.feedback

/**
 * Origin of a playback control action.
 */
enum class CommandSourceType {
    IN_APP_UI,
    MEDIA_SESSION_CONTROLLER,
    SYSTEM_NOTIFICATION,
    BLUETOOTH,
    HARDWARE_KEY,
    INTERNAL_SYSTEM,
}

data class CommandSource(
    val type: CommandSourceType,
    val packageName: String? = null,
    val description: String? = null,
) {
    companion object {
        val IN_APP_UI = CommandSource(CommandSourceType.IN_APP_UI, description = "In-App UI Action")
        val SYSTEM_NOTIFICATION = CommandSource(CommandSourceType.SYSTEM_NOTIFICATION, description = "System Notification")
        val BLUETOOTH = CommandSource(CommandSourceType.BLUETOOTH, description = "Bluetooth AVRCP")
        val HARDWARE_KEY = CommandSource(CommandSourceType.HARDWARE_KEY, description = "Headset / Media Key")
        val INTERNAL_SYSTEM = CommandSource(CommandSourceType.INTERNAL_SYSTEM, description = "Internal Player Engine")

        fun fromPackageName(packageName: String?): CommandSource {
            if (packageName.isNullOrBlank()) return CommandSource(CommandSourceType.MEDIA_SESSION_CONTROLLER)
            val pkg = packageName.lowercase()
            return when {
                pkg.contains("bluetooth") || pkg.contains("avrcp") ->
                    CommandSource(CommandSourceType.BLUETOOTH, packageName = packageName)
                pkg.contains("android.systemui") || pkg.contains("notification") ->
                    CommandSource(CommandSourceType.SYSTEM_NOTIFICATION, packageName = packageName)
                else ->
                    CommandSource(CommandSourceType.MEDIA_SESSION_CONTROLLER, packageName = packageName)
            }
        }
    }
}

/**
 * Cause of an involuntary playback interruption.
 */
enum class InterruptionCause {
    PLAYBACK_ERROR,
    NETWORK_RECOVERY,
    AUDIO_FOCUS_LOSS,
    HEADPHONE_DISCONNECT,
    STALL_WATCHDOG_RELOAD,
}

/**
 * Classified outcome of a playback transition.
 */
sealed interface PlaybackOutcome {
    val mediaId: String?
    val tasteImpact: Float

    /**
     * Track played to natural completion at boundary or >= 80% duration.
     */
    data class NaturalCompletion(
        override val mediaId: String,
        val playTimeMs: Long,
        val durationMs: Long,
    ) : PlaybackOutcome {
        override val tasteImpact: Float = 1.0f
    }

    /**
     * User explicitly pressed Next (via UI, notification, or Bluetooth) while track was playing.
     */
    data class ExplicitUserSkip(
        override val mediaId: String,
        val playTimeMs: Long,
        val durationMs: Long,
        val isEarly: Boolean, // < 15s is early
        val source: CommandSource,
    ) : PlaybackOutcome {
        override val tasteImpact: Float = if (isEarly) -1.5f else -0.7f
    }

    /**
     * User explicitly picked another track from search, playlist, queue, or album.
     * Taste impact on the previous track is strictly NEUTRAL (0.0).
     */
    data class ManualTrackSelection(
        val oldMediaId: String?,
        val newMediaId: String,
        val targetIndex: Int,
        val source: CommandSource,
    ) : PlaybackOutcome {
        override val mediaId: String? = oldMediaId
        override val tasteImpact: Float = 0.0f
    }

    /**
     * Track was replayed (repeat mode or manual loop).
     */
    data class Replay(
        override val mediaId: String,
        val source: CommandSource = CommandSource.INTERNAL_SYSTEM,
    ) : PlaybackOutcome {
        override val tasteImpact: Float = 1.8f
    }

    /**
     * Track changed due to queue reordering, track removal, or playlist swap.
     */
    data class QueueModification(
        override val mediaId: String?,
        val details: String = "",
    ) : PlaybackOutcome {
        override val tasteImpact: Float = 0.0f
    }

    /**
     * Involuntary interruption caused by codec error, network failure, or focus loss.
     * Taste impact is strictly NEUTRAL (0.0).
     */
    data class SystemInterruption(
        override val mediaId: String,
        val cause: InterruptionCause,
        val details: String = "",
    ) : PlaybackOutcome {
        override val tasteImpact: Float = 0.0f
    }

    /**
     * Transition occurred without a matched explicit user command or error.
     * Must have strictly NEUTRAL taste impact to avoid unverified inferences.
     */
    data class UnclassifiedTransition(
        override val mediaId: String?,
        val playTimeMs: Long,
        val reasonCode: Int,
    ) : PlaybackOutcome {
        override val tasteImpact: Float = 0.0f
    }
}
