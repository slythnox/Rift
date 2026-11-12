/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import androidx.media3.common.MediaItem
import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.candidate.CandidateSource
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.CanonicalTrackId
import com.rift.music.playback.asi.scoring.ASIScoreBreakdown
import com.rift.music.playback.asi.scoring.ScoredCandidate
import com.rift.music.playback.asi.sequence.ASISequenceOptimizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ASISequenceOptimizerTest {

    private lateinit var config: ASIConfig
    private lateinit var optimizer: ASISequenceOptimizer

    @Before
    fun setup() {
        config = ASIConfig(beamWidth = 4, sequenceHorizon = 5)
        optimizer = ASISequenceOptimizer(config)
    }

    private fun createScoredCandidate(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        energy: Float? = null,
        bpm: Int? = null,
        score: Float = 0.8f,
    ): ScoredCandidate {
        val canonical = CanonicalTrackId.from(title, artist, 200)
        val candidate = ASICandidate(
            mediaId = id,
            canonicalId = canonical,
            title = title,
            artist = artist,
            album = album,
            durationSec = 200,
            source = CandidateSource.YTM_RADIO,
            rawMediaItem = MediaItem.Builder().setMediaId(id).build(),
            energy = energy,
            bpm = bpm,
        )
        val breakdown = ASIScoreBreakdown(
            enjoymentScore = score,
            sessionScore = score,
            transitionScore = score,
            freshnessScore = 1f,
            discoveryScore = 0.3f,
            repetitionPenalty = 0f,
            totalScore = score,
        )
        return ScoredCandidate(candidate, breakdown, score)
    }

    @Test
    fun `sequence optimizer avoids consecutive identical artists despite high individual scores`() {
        val seed = createScoredCandidate("seed", "Seed Track", "Artist X", energy = 0.7f).candidate

        // Suppose Candidate 1 (Artist X) has slightly higher score 0.95, but Candidate 2 (Artist Y) has 0.90
        val cand1 = createScoredCandidate("c1", "Song 1", "Artist X", energy = 0.7f, score = 0.95f)
        val cand2 = createScoredCandidate("c2", "Song 2", "Artist Y", energy = 0.72f, score = 0.90f)
        val cand3 = createScoredCandidate("c3", "Song 3", "Artist Z", energy = 0.69f, score = 0.88f)

        val optimized = optimizer.optimizeSequence(
            seed = seed,
            scoredCandidates = listOf(cand1, cand2, cand3),
            targetCount = 3,
        )

        assertTrue(optimized.isNotEmpty())
        // Seed was Artist X; consecutive Artist X should be penalized so first track chosen is Artist Y or Artist Z
        assertNotEquals("Must not pick identical artist immediately after seed", "Artist X", optimized.first().artist)
    }

    @Test
    fun `sequence optimizer respects natural energy flow without monotonic bias`() {
        val seed = createScoredCandidate("seed", "Seed Track", "Artist A", energy = 0.8f).candidate

        // Pool containing tracks with steady energy (0.78), slightly relaxing energy (0.72), and hyper energy (0.98)
        val steadyCand = createScoredCandidate("c1", "Steady Track", "Artist B", energy = 0.78f, score = 0.90f)
        val coolCand = createScoredCandidate("c2", "Relaxing Track", "Artist C", energy = 0.70f, score = 0.88f)
        val hyperCand = createScoredCandidate("c3", "Hyper Jerk Track", "Artist D", energy = 0.10f, score = 0.75f)

        val optimized = optimizer.optimizeSequence(
            seed = seed,
            scoredCandidates = listOf(steadyCand, coolCand, hyperCand),
            targetCount = 3,
        )

        // Jerk track (0.10 against 0.80) should be placed last due to transition continuity penalty
        assertEquals("Hyper jerk track should rank last", "c3", optimized.last().mediaId)
    }

    @Test
    fun `empty and single element pools return safely without crash`() {
        val seed = createScoredCandidate("seed", "Seed", "Artist").candidate
        val emptyResult = optimizer.optimizeSequence(seed, emptyList(), 5)
        assertTrue(emptyResult.isEmpty())

        val single = createScoredCandidate("single", "Single Track", "Solo Artist")
        val singleResult = optimizer.optimizeSequence(seed, listOf(single), 5)
        assertEquals(1, singleResult.size)
        assertEquals("single", singleResult.first().mediaId)
    }
}
