/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.model

/**
 * Recognized acoustic and behavioral listening session categories.
 */
enum class SessionCategory {
    PARTY,
    WORKOUT,
    RELAXED,
    ROMANTIC,
    FOCUS,
    DISCOVERY,
    COMMUTE,
    GENERAL,
}

/**
 * Point-in-time snapshot of the active listening session's context,
 * energy level, and user tolerance parameters.
 */
data class SessionContextSnapshot(
    val category: SessionCategory = SessionCategory.GENERAL,
    val confidence: Float = 0.5f,
    val targetEnergy: Float = 0.5f,
    val discoveryTolerance: Float = 0.3f,
    val acousticPurity: Float = 0.5f,
    val timestampMs: Long = System.currentTimeMillis(),
)
