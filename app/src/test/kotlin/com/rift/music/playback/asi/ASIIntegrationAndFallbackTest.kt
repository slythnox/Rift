/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import androidx.media3.common.MediaItem
import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.candidate.ASICandidateGenerator
import com.rift.music.playback.asi.candidate.CandidateSource
import com.rift.music.playback.asi.diagnostics.ASIDiagnostics
import com.rift.music.playback.asi.feedback.NegativeSignalJournal
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.asi.model.ASITasteProfile
import com.rift.music.playback.asi.model.CanonicalTrackId
import com.rift.music.playback.asi.scoring.ASIScorer
import com.rift.music.playback.asi.sequence.ASISequenceOptimizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ASIIntegrationAndFallbackTest {

    private lateinit var config: ASIConfig
    private lateinit var sessionProfile: ASISessionProfile
    private lateinit var journal: NegativeSignalJournal
    private lateinit var tasteProfile: ASITasteProfile
    private lateinit var scorer: ASIScorer
    private lateinit var sequenceOptimizer: ASISequenceOptimizer
    private lateinit var diagnostics: ASIDiagnostics

    // Stub candidate generator that returns configurable candidates
    private class TestCandidateGenerator(
        config: ASIConfig,
        var candidatesToReturn: List<ASICandidate> = emptyList(),
        var throwException: Boolean = false,
        var throwCancellation: Boolean = false,
        var lastReceivedInitialRadioItems: List<MediaItem>? = null,
    ) : ASICandidateGenerator(
        database = null,
        negativeSignalJournal = NegativeSignalJournal(config),
        config = config,
    ) {
        override suspend fun generateCandidates(
            seedMediaItem: MediaItem,
            existingQueueIds: Set<String>,
            failedSongIds: Set<String>,
            initialRadioItems: List<MediaItem>,
            targetCount: Int,
        ): List<ASICandidate> {
            lastReceivedInitialRadioItems = initialRadioItems
            if (throwCancellation) throw kotlin.coroutines.cancellation.CancellationException("Test coroutine cancelled")
            if (throwException) throw RuntimeException("Simulated remote discovery timeout")
            return candidatesToReturn
        }
    }

    @Before
    fun setup() {
        config = ASIConfig(asiEnabled = true)
        sessionProfile = ASISessionProfile(config)
        journal = NegativeSignalJournal(config)
        tasteProfile = ASITasteProfile(journal, config)
        scorer = ASIScorer(tasteProfile, config)
        sequenceOptimizer = ASISequenceOptimizer(config)
        diagnostics = ASIDiagnostics()
    }

    @Test
    fun `feature flag disable returns null immediately for instant fallback`() = runBlocking {
        val disabledConfig = ASIConfig(asiEnabled = false)
        val generator = TestCandidateGenerator(disabledConfig)

        val disabledManager = ASIManager(
            candidateGenerator = generator,
            sessionProfile = sessionProfile,
            tasteProfile = tasteProfile,
            scorer = scorer,
            sequenceOptimizer = sequenceOptimizer,
            database = null,
            diagnostics = diagnostics,
            config = disabledConfig,
        )

        val seed = MediaItem.Builder().setMediaId("seed_1").build()
        val result = disabledManager.getRecommendations(seed)

        assertNull("Disabled ASI must return null to trigger transparent fallback", result)
    }

    @Test
    fun `candidate generator failure returns null and logs fallback diagnostic`() = runBlocking {
        val generator = TestCandidateGenerator(config, candidatesToReturn = emptyList())

        val manager = ASIManager(
            candidateGenerator = generator,
            sessionProfile = sessionProfile,
            tasteProfile = tasteProfile,
            scorer = scorer,
            sequenceOptimizer = sequenceOptimizer,
            database = null,
            diagnostics = diagnostics,
            config = config,
        )

        val seed = MediaItem.Builder().setMediaId("seed_fail").build()
        val result = manager.getRecommendations(seed)
        assertNull("Empty candidates must return null for legacy fallback", result)

        val recentRuns = diagnostics.getRecentRuns()
        assertTrue("Diagnostics must record the fallback run", recentRuns.isNotEmpty())
        assertTrue("Run must be marked as fallback", recentRuns.first().isFallback)
    }

    @Test
    fun `diagnostics records explainability breakdown for successful recommendation run`() = runBlocking {
        val candMediaItem = MediaItem.Builder().setMediaId("cand_ok").build()
        val cand = ASICandidate(
            mediaId = "cand_ok",
            canonicalId = CanonicalTrackId.from("Title", "Artist"),
            title = "Title",
            artist = "Artist",
            source = CandidateSource.YTM_RADIO,
            rawMediaItem = candMediaItem,
        )

        val generator = TestCandidateGenerator(config, candidatesToReturn = listOf(cand))

        val manager = ASIManager(
            candidateGenerator = generator,
            sessionProfile = sessionProfile,
            tasteProfile = tasteProfile,
            scorer = scorer,
            sequenceOptimizer = sequenceOptimizer,
            database = null,
            diagnostics = diagnostics,
            config = config,
        )

        val seed = MediaItem.Builder().setMediaId("seed_ok").build()
        val result = manager.getRecommendations(seed)

        assertNotNull(result)
        assertEquals(1, result!!.size)
        assertEquals("cand_ok", result.first().mediaId)

        val run = diagnostics.getRecentRuns().last()
        assertFalse("Run must succeed without fallback", run.isFallback)
        assertEquals("seed_ok", run.seedMediaId)
    }

    @Test
    fun `coroutine cancellation propagates out of ASIManager without being swallowed`() = runBlocking {
        val generator = TestCandidateGenerator(config, throwCancellation = true)
        val manager = ASIManager(
            candidateGenerator = generator,
            sessionProfile = sessionProfile,
            tasteProfile = tasteProfile,
            scorer = scorer,
            sequenceOptimizer = sequenceOptimizer,
            database = null,
            diagnostics = diagnostics,
            config = config,
        )

        val seed = MediaItem.Builder().setMediaId("seed_cancel").build()
        var caughtCancellation = false
        try {
            manager.getRecommendations(seed)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            caughtCancellation = true
        }

        assertTrue("CancellationException must propagate out to caller", caughtCancellation)
    }

    @Test
    fun `initial radio items are forwarded to candidate generator to eliminate duplicate requests`() = runBlocking {
        val generator = TestCandidateGenerator(config)
        val manager = ASIManager(
            candidateGenerator = generator,
            sessionProfile = sessionProfile,
            tasteProfile = tasteProfile,
            scorer = scorer,
            sequenceOptimizer = sequenceOptimizer,
            database = null,
            diagnostics = diagnostics,
            config = config,
        )

        val seed = MediaItem.Builder().setMediaId("seed_1").build()
        val preFetched = listOf(MediaItem.Builder().setMediaId("pre_1").build())

        manager.getRecommendations(
            seedMediaItem = seed,
            initialRadioItems = preFetched,
        )

        assertEquals("Generator must receive pre-fetched radio items", preFetched, generator.lastReceivedInitialRadioItems)
    }
}
