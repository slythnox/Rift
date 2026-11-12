/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.model

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Configuration parameters for Project ASI 1.0 (Adaptive Session Intelligence).
 *
 * NOTE: All default parameter values (e.g. 30-day half-life, 15-second skip threshold,
 * scoring weights) represent initial experimental hypotheses. They are exposed as
 * configurable properties so they can be calibrated and evaluated empirically.
 */
@Singleton
data class ASIConfig(
    /** Master enable/disable flag for ASI recommendation intelligence. Defaults to false for safe rollout. */
    val asiEnabled: Boolean = false,

    /** Half-life in days for exponential time-decay of historical taste events. */
    val decayHalfLifeDays: Float = 30.0f,

    /** Maximum continuous playback duration (ms) below which an intentional user skip is marked early. */
    val earlySkipThresholdMs: Long = 15_000L,

    /** Playback duration window (ms) for casual skips. */
    val casualSkipThresholdMs: Long = 30_000L,

    /** Fraction of total track duration required to treat an uncompleted track as naturally completed. */
    val completionRatioThreshold: Float = 0.80f,

    /** Maximum time-to-live in days for entries in the persistent negative-signal journal. */
    val negativeSignalTtlDays: Int = 14,

    /** Maximum number of records retained in the negative-signal journal. */
    val maxNegativeSignals: Int = 200,

    /** Maximum number of recent tracks tracked in the active session profile sliding window. */
    val sessionWindowSize: Int = 10,

    /**
     * Adaptation weight [0.0, 1.0] applied when the user manually selects another track.
     * Higher values discard more prior momentum; lower values preserve more session continuity.
     */
    val manualSelectionAdaptationWeight: Float = 0.35f,

    /** Inactivity duration (ms) after which an active session profile undergoes a cold reset. */
    val sessionInactivityResetMs: Long = 30 * 60 * 1000L,

    /** Expiration window (ms) for matching an explicit user command to a Media3 transition. */
    val commandExpiryWindowMs: Long = 3_000L,

    /** Minimum duration (ms) between processing identical media transition events to prevent duplicates. */
    val duplicateEventCooldownMs: Long = 2_000L,

    // === Scoring Weights (Normalized to 1.0) ===
    val weightEnjoyment: Float = 0.30f,
    val weightSession: Float = 0.25f,
    val weightTransition: Float = 0.20f,
    val weightFreshness: Float = 0.10f,
    val weightDiscovery: Float = 0.15f,

    // === Sequence Optimization Parameters ===
    val beamWidth: Int = 4,
    val sequenceHorizon: Int = 5,
    val candidateTimeoutMs: Long = 1_500L,
    val overallTimeoutMs: Long = 2_000L,
) {
    @Inject
    constructor() : this(
        asiEnabled = false,
        decayHalfLifeDays = 30.0f,
        earlySkipThresholdMs = 15_000L,
        casualSkipThresholdMs = 30_000L,
        completionRatioThreshold = 0.80f,
        negativeSignalTtlDays = 14,
        maxNegativeSignals = 200,
        sessionWindowSize = 10,
        manualSelectionAdaptationWeight = 0.35f,
        sessionInactivityResetMs = 30 * 60 * 1000L,
        commandExpiryWindowMs = 3_000L,
        duplicateEventCooldownMs = 2_000L,
        weightEnjoyment = 0.30f,
        weightSession = 0.25f,
        weightTransition = 0.20f,
        weightFreshness = 0.10f,
        weightDiscovery = 0.15f,
        beamWidth = 4,
        sequenceHorizon = 5,
        candidateTimeoutMs = 1_500L,
        overallTimeoutMs = 2_000L,
    )
}
