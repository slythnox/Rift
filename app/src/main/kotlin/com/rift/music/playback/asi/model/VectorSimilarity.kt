/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.model

import kotlin.math.abs

/**
 * Utility for dimension-renormalized vector similarity calculations.
 *
 * Missing or unavailable metadata dimensions are strictly excluded from both the
 * similarity numerator and total weight denominator, renormalizing the score
 * across only the dimensions that are genuinely present.
 *
 * This avoids the mathematical distortion of arbitrarily imputing 0.5 to missing values.
 */
object VectorSimilarity {

    /**
     * Calculates the normalized similarity between two feature maps.
     *
     * @param targetFeatures Map of feature dimension name to normalized value in [0.0, 1.0]
     * @param candidateFeatures Map of feature dimension name to normalized value in [0.0, 1.0]
     * @param dimensionWeights Relative importance weights for each dimension (must be non-negative)
     * @return Normalized similarity in [0.0, 1.0], or `null` if no overlapping dimensions exist.
     */
    fun calculateRenormalizedSimilarity(
        targetFeatures: Map<String, Float>,
        candidateFeatures: Map<String, Float>,
        dimensionWeights: Map<String, Float> = emptyMap(),
    ): Float? {
        if (targetFeatures.isEmpty() || candidateFeatures.isEmpty()) return null

        var weightedSimilaritySum = 0f
        var totalAvailableWeight = 0f

        for ((dimension, targetVal) in targetFeatures) {
            val candidateVal = candidateFeatures[dimension] ?: continue // Exclude missing dimension

            val weight = dimensionWeights[dimension] ?: 1.0f
            if (weight <= 0f) continue

            // Linear similarity: 1.0 when identical, 0.0 when maximum distance
            val similarity = (1.0f - abs(targetVal.coerceIn(0f, 1f) - candidateVal.coerceIn(0f, 1f))).coerceIn(0f, 1f)
            weightedSimilaritySum += similarity * weight
            totalAvailableWeight += weight
        }

        if (totalAvailableWeight <= 0.0001f) {
            return null // No shared dimensions available
        }

        return (weightedSimilaritySum / totalAvailableWeight).coerceIn(0f, 1f)
    }
}
