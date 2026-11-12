/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.sequence

import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.scoring.ASITransitionEvaluator
import com.rift.music.playback.asi.scoring.ScoredCandidate
import com.rift.music.playback.resolver.addon.TrackMatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Bounded multi-step sequence optimizer for Project ASI 1.0.
 *
 * Rather than picking recommendations greedily one-by-one, uses a bounded beam search
 * (width K = 4, horizon H = 5) to optimize short listening trajectories.
 *
 * Evaluates:
 * 1. Immediate candidate quality (enjoyment, session fit, freshness).
 * 2. Pairwise transition continuity between track i and track i+1.
 * 3. Path-level diversity (preventing consecutive artist repeats and album clustering).
 * 4. Energy flow (permitting natural swells, plateaus, and gentle cooldowns without monotonic bias).
 */
@Singleton
class ASISequenceOptimizer @Inject constructor(
    private val config: ASIConfig,
) {
    constructor() : this(ASIConfig())

    data class Path(
        val tracks: List<ASICandidate>,
        val cumulativeScore: Float,
        val lastArtist: String,
        val lastAlbum: String?,
        val artistCounts: Map<String, Int>,
    ) {
        val averageScore: Float
            get() = if (tracks.isNotEmpty()) cumulativeScore / tracks.size else 0f
    }

    /**
     * Optimizes a sequence of recommendations from the scored candidate pool.
     *
     * @param seed Track currently playing or acting as the starting point.
     * @param scoredCandidates Pre-filtered, pre-scored candidates from [ASIScorer].
     * @param targetCount Number of recommendations to produce (default 15).
     * @return Ordered list of [ASICandidate]s optimized for the listening session.
     */
    fun optimizeSequence(
        seed: ASICandidate,
        scoredCandidates: List<ScoredCandidate>,
        targetCount: Int = 15,
    ): List<ASICandidate> {
        if (scoredCandidates.isEmpty()) return emptyList()
        if (scoredCandidates.size <= 1) return scoredCandidates.map { it.candidate }

        val seedArtist = TrackMatcher.primaryArtist(seed.artist).lowercase().trim()
        val seedAlbum = seed.album?.lowercase()?.trim()

        // Initial beam starting with an empty trajectory from seed
        var currentBeam = listOf(
            Path(
                tracks = emptyList(),
                cumulativeScore = 0f,
                lastArtist = seedArtist,
                lastAlbum = seedAlbum,
                artistCounts = emptyMap(),
            )
        )

        val pool = scoredCandidates.sortedByDescending { it.score }
        val horizon = targetCount.coerceAtMost(pool.size)

        for (step in 0 until horizon) {
            val candidatePaths = mutableListOf<Path>()

            for (path in currentBeam) {
                val usedIds = path.tracks.map { it.mediaId }.toSet()
                // Consider top unpicked candidates
                val availableCandidates = pool.filter { it.candidate.mediaId !in usedIds }.take(10)

                for (scored in availableCandidates) {
                    val cand = scored.candidate
                    val candArtist = TrackMatcher.primaryArtist(cand.artist).lowercase().trim()
                    val candAlbum = cand.album?.lowercase()?.trim()

                    // Transition from the last track in path (or seed if path is empty)
                    val lastTrack = path.tracks.lastOrNull() ?: seed
                    val transitionScore = ASITransitionEvaluator.evaluateTransition(lastTrack, cand)

                    // Path-level penalties
                    var pathPenalty = 0.0f

                    // Consecutive artist penalty
                    if (candArtist.isNotBlank() && candArtist == path.lastArtist) {
                        pathPenalty += 0.40f
                    }

                    // Clustered artist penalty (if artist appeared multiple times in path)
                    val priorCount = path.artistCounts[candArtist] ?: 0
                    if (priorCount >= 2) {
                        pathPenalty += 0.35f * (priorCount - 1)
                    }

                    // Consecutive album penalty
                    if (!candAlbum.isNullOrBlank() && candAlbum == path.lastAlbum) {
                        pathPenalty += 0.15f
                    }

                    // Excessive energy jerk (avoid jarring swings > 0.40f)
                    if (lastTrack.energy != null && cand.energy != null) {
                        val energyDelta = abs(lastTrack.energy - cand.energy)
                        if (energyDelta > 0.40f) {
                            pathPenalty += 0.50f
                        }
                    }

                    // Path diversity bonus for introducing an artist not yet in the sequence path
                    var pathBonus = 0.0f
                    if (candArtist.isNotBlank() && priorCount == 0 && candArtist != seedArtist) {
                        pathBonus += 0.05f
                    }

                    // Step score combining individual quality, transition, and path diversity
                    val stepScore = (scored.score * 0.65f + transitionScore * 0.35f + pathBonus - pathPenalty).coerceAtLeast(0.01f)

                    val newCounts = path.artistCounts.toMutableMap()
                    if (candArtist.isNotBlank()) {
                        newCounts[candArtist] = (newCounts[candArtist] ?: 0) + 1
                    }

                    candidatePaths.add(
                        Path(
                            tracks = path.tracks + cand,
                            cumulativeScore = path.cumulativeScore + stepScore,
                            lastArtist = candArtist,
                            lastAlbum = candAlbum,
                            artistCounts = newCounts,
                        )
                    )
                }
            }

            if (candidatePaths.isEmpty()) break

            // Prune to top beamWidth paths
            currentBeam = candidatePaths
                .sortedByDescending { it.averageScore }
                .take(config.beamWidth)
        }

        val bestPath = currentBeam.maxByOrNull { it.cumulativeScore }
        return bestPath?.tracks ?: scoredCandidates.take(targetCount).map { it.candidate }
    }
}
