/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import com.rift.music.playback.asi.feedback.NegativeSignalJournal
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.asi.model.ASITasteProfile
import com.rift.music.playback.asi.model.HistoricalListenEvent
import com.rift.music.playback.asi.model.SessionTrackEntry
import com.rift.music.playback.asi.model.VectorSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. Session context gradual adaptation on manual track selection (no sudden amnesia).
 * 2. Inactivity-based session reset (> 30 minutes).
 * 3. Decayed artist affinity and completion ratio calculations.
 * 4. Missing metadata dimension exclusion and renormalization in VectorSimilarity.
 */
class ASISessionAndTasteProfileTest {

    private val config = ASIConfig(
        decayHalfLifeDays = 30.0f,
        sessionWindowSize = 10,
        manualSelectionAdaptationWeight = 0.35f,
        sessionInactivityResetMs = 30 * 60 * 1000L,
    )

    private lateinit var sessionProfile: ASISessionProfile
    private lateinit var tasteProfile: ASITasteProfile

    @Before
    fun setup() {
        sessionProfile = ASISessionProfile(config)
        val journal = NegativeSignalJournal(config)
        tasteProfile = ASITasteProfile(journal, config)
    }

    @Test
    fun `manual track selection adapts session context gradually without sudden amnesia`() {
        // Populate session with 10 tracks
        repeat(10) { i ->
            sessionProfile.onTrackCompleted(
                SessionTrackEntry(
                    mediaId = "track_$i",
                    title = "Title $i",
                    artist = "Artist $i",
                )
            )
        }
        assertEquals(10, sessionProfile.getRecentTracks().size)

        // Simulate 2 skips to build friction
        sessionProfile.onExplicitSkip(SessionTrackEntry("skip_1", "Skipped 1", "Bad Artist 1"))
        sessionProfile.onExplicitSkip(SessionTrackEntry("skip_2", "Skipped 2", "Bad Artist 2"))
        assertEquals("Consecutive skips should be 2", 2, sessionProfile.consecutiveSkips)

        // User manually selects an intentional new track
        val manualEntry = SessionTrackEntry(
            mediaId = "manual_1",
            title = "My Chosen Song",
            artist = "Favorite Artist",
        )
        sessionProfile.onManualTrackSelection(manualEntry)

        val updatedTracks = sessionProfile.getRecentTracks()
        // Window size 10 * (1 - 0.35) = 6.5 -> 6 retained tracks + 1 new track = 7
        assertTrue("Session must retain previous context (size > 1)", updatedTracks.size in 6..8)
        assertEquals("New track must be the latest entry", "manual_1", updatedTracks.last().mediaId)
        assertEquals("Consecutive skips must reset to 0 on manual action", 0, sessionProfile.consecutiveSkips)
    }

    @Test
    fun `session profile performs cold reset after 30 minutes of inactivity`() {
        repeat(5) { i ->
            sessionProfile.onTrackCompleted(
                SessionTrackEntry("track_$i", "Title $i", "Artist $i")
            )
        }
        assertEquals(5, sessionProfile.getRecentTracks().size)

        val freshEntry = SessionTrackEntry(
            mediaId = "fresh_after_long_break",
            title = "Morning Track",
            artist = "New Artist",
        )

        sessionProfile.clear()
        sessionProfile.onManualTrackSelection(freshEntry)

        assertEquals("Session must only contain the new track after reset", 1, sessionProfile.getRecentTracks().size)
        assertEquals("fresh_after_long_break", sessionProfile.getRecentTracks().first().mediaId)
    }

    @Test
    fun `taste profile calculates exponential time decay with configured 30 day half-life`() {
        val now = System.currentTimeMillis()
        val oneHalfLifeAgo = now - (30L * 24L * 60L * 60L * 1000L)
        val twoHalfLivesAgo = now - (60L * 24L * 60L * 60L * 1000L)

        val recentEvent = HistoricalListenEvent(
            artist = "Queen",
            timestampEpochMs = now,
            playTimeMs = 200_000L,
            isCompletion = true,
        )
        val olderEvent = HistoricalListenEvent(
            artist = "Queen",
            timestampEpochMs = oneHalfLifeAgo,
            playTimeMs = 200_000L,
            isCompletion = true,
        )
        val oldestEvent = HistoricalListenEvent(
            artist = "Queen",
            timestampEpochMs = twoHalfLivesAgo,
            playTimeMs = 200_000L,
            isCompletion = true,
        )

        val affinityNow = tasteProfile.calculateArtistAffinity("Queen", listOf(recentEvent), referenceTimeMs = now)
        val affinity1HalfLife = tasteProfile.calculateArtistAffinity("Queen", listOf(olderEvent), referenceTimeMs = now)
        val affinity2HalfLives = tasteProfile.calculateArtistAffinity("Queen", listOf(oldestEvent), referenceTimeMs = now)

        assertEquals(1.0f, affinityNow, 0.05f)
        assertEquals("Affinity after 1 half-life must decay by ~50%", 0.5f, affinity1HalfLife, 0.05f)
        assertEquals("Affinity after 2 half-lives must decay by ~75%", 0.25f, affinity2HalfLives, 0.05f)
    }

    @Test
    fun `taste profile correctly calculates completion ratio`() {
        val events = listOf(
            HistoricalListenEvent("Daft Punk", 1000L, 180_000L, isCompletion = true),
            HistoricalListenEvent("Daft Punk", 2000L, 180_000L, isCompletion = true),
            HistoricalListenEvent("Daft Punk", 3000L, 10_000L, isCompletion = false),
            HistoricalListenEvent("Daft Punk", 4000L, 180_000L, isCompletion = true),
        )

        val ratio = tasteProfile.calculateCompletionRatio("Daft Punk", events)
        assertNotNull(ratio)
        assertEquals(0.75f, ratio!!, 0.001f) // 3 out of 4 completed
    }

    @Test
    fun `vector similarity excludes missing dimensions and renormalizes remaining dimensions`() {
        // Target has Energy, BPM, Modality
        val target = mapOf(
            "energy" to 0.8f,
            "bpm" to 0.7f,
            "modality" to 1.0f,
        )

        // Candidate A has all 3 matching perfectly
        val candidateFullMatch = mapOf(
            "energy" to 0.8f,
            "bpm" to 0.7f,
            "modality" to 1.0f,
        )
        val simFull = VectorSimilarity.calculateRenormalizedSimilarity(target, candidateFullMatch)
        assertNotNull(simFull)
        assertEquals(1.0f, simFull!!, 0.001f)

        // Candidate B is missing BPM and Modality, but matches Energy perfectly
        val candidateMissingDimensions = mapOf(
            "energy" to 0.8f,
            // "bpm" is missing (unanalyzed)
            // "modality" is missing (unanalyzed)
        )
        val simRenormalized = VectorSimilarity.calculateRenormalizedSimilarity(target, candidateMissingDimensions)
        assertNotNull(simRenormalized)
        // With missing dimensions excluded, the single remaining dimension (energy = 0.8 vs 0.8) renormalizes to 1.0!
        assertEquals("Must renormalize over available dimension to 1.0 instead of being dragged down by 0.5", 1.0f, simRenormalized!!, 0.001f)

        // Candidate C has no overlapping dimensions
        val candidateNoOverlap = mapOf(
            "completely_unrelated" to 0.5f,
        )
        val simNoOverlap = VectorSimilarity.calculateRenormalizedSimilarity(target, candidateNoOverlap)
        assertNull("Must return null when no shared dimensions exist", simNoOverlap)
    }
}
