/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.scoring

import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.model.VectorSimilarity
import com.rift.music.playback.resolver.addon.TrackMatcher
import kotlin.math.abs

/**
 * Evaluates musical and acoustic transition compatibility between two tracks.
 *
 * Utilizes missing-dimension renormalization so unknown BPM or key dimensions
 * never unfairly penalize candidate tracks.
 */
object ASITransitionEvaluator {

    /**
     * Calculates transition compatibility score in [0.0, 1.0] from [source] to [candidate].
     */
    fun evaluateTransition(
        source: ASICandidate,
        candidate: ASICandidate,
    ): Float {
        // Build feature maps for dimension-renormalized comparison
        val sourceFeatures = mutableMapOf<String, Float>()
        val candidateFeatures = mutableMapOf<String, Float>()

        // 1. Energy dimension
        if (source.energy != null && candidate.energy != null) {
            sourceFeatures["energy"] = source.energy
            candidateFeatures["energy"] = candidate.energy
        }

        // 2. Tempo / BPM dimension (normalized to [0.0, 1.0] across range 60 - 180 BPM)
        if (source.bpm != null && candidate.bpm != null && source.bpm > 0 && candidate.bpm > 0) {
            val normSourceBpm = ((source.bpm - 60f) / 120f).coerceIn(0f, 1f)
            val normCandBpm = ((candidate.bpm - 60f) / 120f).coerceIn(0f, 1f)

            // Check harmonic tempo (half-time / double-time)
            val ratio = source.bpm.toFloat() / candidate.bpm.toFloat()
            val isHarmonicTempo = abs(ratio - 2.0f) < 0.1f || abs(ratio - 0.5f) < 0.05f

            if (isHarmonicTempo) {
                sourceFeatures["bpm"] = 0.5f
                candidateFeatures["bpm"] = 0.5f // Neutralize distance for half-time/double-time
            } else {
                sourceFeatures["bpm"] = normSourceBpm
                candidateFeatures["bpm"] = normCandBpm
            }
        }

        // 3. Duration pacing dimension
        if (source.durationSec > 0 && candidate.durationSec > 0) {
            val normSourceDur = (source.durationSec / 600f).coerceIn(0f, 1f)
            val normCandDur = (candidate.durationSec / 600f).coerceIn(0f, 1f)
            sourceFeatures["duration"] = normSourceDur
            candidateFeatures["duration"] = normCandDur
        }

        val dimensionWeights = mapOf(
            "energy" to 2.0f,
            "bpm" to 1.5f,
            "duration" to 0.5f,
        )

        // When genuine acoustic metadata (energy/bpm) is absent, duration alone
        // must NOT produce a falsely high match. Provide neutral baseline (0.50f)
        // with subtle pacing nuance (max +/- 0.05f).
        val hasAcousticDimensions = sourceFeatures.containsKey("energy") || sourceFeatures.containsKey("bpm")
        val acousticSimilarity = if (hasAcousticDimensions) {
            VectorSimilarity.calculateRenormalizedSimilarity(
                targetFeatures = sourceFeatures,
                candidateFeatures = candidateFeatures,
                dimensionWeights = dimensionWeights,
            ) ?: 0.50f
        } else {
            val durationNuance = if (sourceFeatures.containsKey("duration") && candidateFeatures.containsKey("duration")) {
                val durDelta = abs(sourceFeatures["duration"]!! - candidateFeatures["duration"]!!)
                (0.05f * (1.0f - durDelta * 2f)).coerceIn(-0.05f, 0.05f)
            } else 0.0f
            0.50f + durationNuance
        }

        // 4. Relational context (same artist or collaboration)
        val sourceArtist = TrackMatcher.primaryArtist(source.artist).lowercase().trim()
        val candidateArtist = TrackMatcher.primaryArtist(candidate.artist).lowercase().trim()

        val artistContinuityBonus = when {
            sourceArtist.isNotBlank() && sourceArtist == candidateArtist -> 0.10f
            sourceArtist.isNotBlank() && candidate.artist.contains(sourceArtist, ignoreCase = true) -> 0.05f
            else -> 0.0f
        }

        return (acousticSimilarity * 0.90f + artistContinuityBonus).coerceIn(0.0f, 1.0f)
    }
}
