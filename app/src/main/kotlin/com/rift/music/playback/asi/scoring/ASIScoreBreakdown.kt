/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.scoring

import com.rift.music.playback.asi.candidate.ASICandidate

/**
 * Explainable decomposition of candidate recommendation scoring.
 */
data class ASIScoreBreakdown(
    val enjoymentScore: Float,
    val sessionScore: Float,
    val transitionScore: Float,
    val freshnessScore: Float,
    val discoveryScore: Float,
    val repetitionPenalty: Float,
    val totalScore: Float,
)

data class ScoredCandidate(
    val candidate: ASICandidate,
    val breakdown: ASIScoreBreakdown,
    val score: Float,
)
