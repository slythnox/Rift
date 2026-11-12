/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.diagnostics

import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.model.SessionContextSnapshot
import com.rift.music.playback.asi.scoring.ScoredCandidate
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

data class ASIRunDiagnostic(
    val requestId: String,
    val timestampEpochMs: Long,
    val seedMediaId: String,
    val seedTitle: String,
    val seedArtist: String,
    val sessionContext: SessionContextSnapshot,
    val sourceCounts: Map<String, Int>,
    val filteredCount: Int,
    val scoredCount: Int,
    val topScores: List<Pair<String, Float>>,
    val selectedTrackIds: List<String>,
    val durationMs: Long,
    val isFallback: Boolean = false,
    val fallbackReason: String? = null,
    val totalCandidates: Int = 0,
    val candidatesMatchedToLocalSongs: Int = 0,
    val candidatesWithCachedBpm: Int = 0,
    val candidatesWithCachedEnergy: Int = 0,
    val candidatesWithCachedKey: Int = 0,
    val candidatesWithNoAcousticData: Int = 0,
)

/**
 * Thread-safe, bounded, privacy-conscious diagnostic logger for Project ASI 1.0.
 *
 * Retains the 50 most recent recommendation cycles in memory for inspection,
 * testing, and operational verification.
 */
@Singleton
class ASIDiagnostics @Inject constructor() {

    private val history = ConcurrentLinkedDeque<ASIRunDiagnostic>()

    fun recordRun(diagnostic: ASIRunDiagnostic) {
        history.addLast(diagnostic)
        while (history.size > 50) {
            history.pollFirst()
        }
    }

    fun getRecentRuns(limit: Int = 20): List<ASIRunDiagnostic> =
        history.toList().takeLast(limit)

    fun clear() {
        history.clear()
    }
}
