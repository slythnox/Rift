/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.scoring

import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.candidate.CandidateSource
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.asi.model.ASITasteProfile
import com.rift.music.playback.asi.model.HistoricalListenEvent
import com.rift.music.playback.asi.model.SessionCategory
import com.rift.music.playback.asi.model.SessionContextSnapshot
import com.rift.music.playback.asi.model.SessionTrackEntry
import com.rift.music.playback.asi.model.VectorSimilarity
import com.rift.music.playback.resolver.addon.TrackMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Multi-dimensional personalized recommendation scorer for Project ASI 1.0.
 *
 * Evaluates candidate tracks across 5 independent normalized dimensions:
 * 1. Personal Enjoyment Prediction (decayed artist affinity, completion ratios, likes)
 * 2. Session Context Compatibility (target energy, session category fit)
 * 3. Transition Quality (acoustic distance, harmonic tempo, pacing)
 * 4. Freshness (penalizing 40-track cooldown and recent session repeats)
 * 5. Discovery Value (calibrated introduction of unfamiliar artists)
 * Plus explicit repetition penalties for clustered artists and albums.
 */
@Singleton
class ASIScorer @Inject constructor(
    private val tasteProfile: ASITasteProfile,
    private val config: ASIConfig,
) {
    constructor(tasteProfile: ASITasteProfile) : this(tasteProfile, ASIConfig())

    /**
     * Scores a candidate track against the seed and session context.
     */
    fun scoreCandidate(
        candidate: ASICandidate,
        seedTrack: ASICandidate,
        sessionContext: SessionContextSnapshot,
        historicalEvents: List<HistoricalListenEvent>,
        recentSessionTracks: List<SessionTrackEntry>,
        cooldownIds: Set<String>,
    ): ScoredCandidate {
        val primaryArtist = TrackMatcher.primaryArtist(candidate.artist).lowercase().trim()

        // 1. Personal Enjoyment Prediction [0.0, 1.0]
        val artistAffinity = tasteProfile.calculateArtistAffinity(candidate.artist, historicalEvents)
        val completionRatio = tasteProfile.calculateCompletionRatio(candidate.artist, historicalEvents)

        var enjoyment = when {
            completionRatio != null -> (completionRatio * 0.70f + (artistAffinity / 10f).coerceIn(0f, 0.30f))
            artistAffinity > 0f -> (0.50f + (artistAffinity / 10f).coerceIn(0f, 0.40f))
            else -> 0.45f // Neutral baseline for unheard artist
        }
        if (candidate.source == CandidateSource.LOCAL_FAVORITE) {
            enjoyment = (enjoyment + 0.25f).coerceAtMost(1.0f)
        }
        enjoyment = enjoyment.coerceIn(0.0f, 1.0f)

        // 2. Session Compatibility [0.0, 1.0]
        val sessionScore = evaluateSessionCompatibility(candidate, sessionContext)

        // 3. Transition Quality [0.0, 1.0]
        val transitionScore = ASITransitionEvaluator.evaluateTransition(seedTrack, candidate)

        // 4. Freshness [0.0, 1.0]
        var freshness = 1.0f
        if (candidate.mediaId in cooldownIds) {
            freshness -= 0.60f
        }
        if (recentSessionTracks.any { it.mediaId == candidate.mediaId }) {
            freshness -= 0.35f
        }
        freshness = freshness.coerceIn(0.0f, 1.0f)

        // 5. Discovery Value [0.0, 1.0]
        val isUnfamiliar = historicalEvents.none {
            TrackMatcher.primaryArtist(it.artist).lowercase().trim() == primaryArtist
        }
        val discoveryScore = if (isUnfamiliar) {
            (sessionContext.discoveryTolerance * 1.5f).coerceIn(0.2f, 1.0f)
        } else {
            0.30f
        }

        // 6. Repetition Penalties [0.0, 1.0]
        var repetitionPenalty = 0.0f
        val lastTrack = recentSessionTracks.lastOrNull()
        if (lastTrack != null) {
            val lastArtist = TrackMatcher.primaryArtist(lastTrack.artist).lowercase().trim()
            if (primaryArtist.isNotBlank() && primaryArtist == lastArtist) {
                repetitionPenalty += 0.35f // Consecutive same artist
            }
        }
        val recentArtistsInWindow = recentSessionTracks.takeLast(5).map {
            TrackMatcher.primaryArtist(it.artist).lowercase().trim()
        }
        val artistClusterCount = recentArtistsInWindow.count { it == primaryArtist }
        if (artistClusterCount >= 2) {
            repetitionPenalty += 0.25f * artistClusterCount
        }

        // Calculate weighted composite score
        val totalScore = (
            config.weightEnjoyment * enjoyment +
            config.weightSession * sessionScore +
            config.weightTransition * transitionScore +
            config.weightFreshness * freshness +
            config.weightDiscovery * discoveryScore -
            repetitionPenalty
        ).coerceIn(0.0f, 1.0f)

        val breakdown = ASIScoreBreakdown(
            enjoymentScore = enjoyment,
            sessionScore = sessionScore,
            transitionScore = transitionScore,
            freshnessScore = freshness,
            discoveryScore = discoveryScore,
            repetitionPenalty = repetitionPenalty,
            totalScore = totalScore,
        )

        return ScoredCandidate(candidate, breakdown, totalScore)
    }

    private fun evaluateSessionCompatibility(
        candidate: ASICandidate,
        context: SessionContextSnapshot,
    ): Float {
        val targetEnergy = context.targetEnergy
        val candEnergy = candidate.energy

        // Unknown metadata remains strictly unknown; returns neutral 0.50f without title keyword heuristics
        if (candEnergy == null) {
            return 0.50f
        }

        // Energy distance with penalty scaled by session classification confidence
        val energyDelta = abs(candEnergy - targetEnergy)
        val energyFit = (1.0f - energyDelta * 1.5f).coerceIn(0f, 1f)

        // Category specific alignment scaled by classification confidence
        val rawCategoryBonus = when (context.category) {
            SessionCategory.PARTY -> if (candEnergy >= 0.70f) 0.20f else if (candEnergy < 0.40f) -0.50f else -0.15f
            SessionCategory.WORKOUT -> if (candEnergy >= 0.65f) 0.20f else if (candEnergy < 0.40f) -0.50f else -0.10f
            SessionCategory.RELAXED -> if (candEnergy <= 0.40f) 0.20f else if (candEnergy > 0.70f) -0.50f else -0.15f
            SessionCategory.FOCUS -> if (candEnergy in 0.25f..0.55f) 0.20f else -0.20f
            SessionCategory.ROMANTIC -> if (candEnergy in 0.30f..0.60f) 0.15f else 0.0f
            else -> 0.0f
        }
        val scaledCategoryBonus = rawCategoryBonus * context.confidence.coerceIn(0.2f, 1.0f)

        return (energyFit + scaledCategoryBonus).coerceIn(0.0f, 1.0f)
    }
}
