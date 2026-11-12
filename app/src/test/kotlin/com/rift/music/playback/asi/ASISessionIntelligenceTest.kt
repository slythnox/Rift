/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.asi.model.SessionCategory
import com.rift.music.playback.asi.model.SessionTrackEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ASISessionIntelligenceTest {

    private lateinit var config: ASIConfig
    private lateinit var sessionProfile: ASISessionProfile

    @Before
    fun setup() {
        config = ASIConfig(
            sessionWindowSize = 10,
            manualSelectionAdaptationWeight = 0.35f,
            sessionInactivityResetMs = 30 * 60 * 1000L,
        )
        sessionProfile = ASISessionProfile(config)
    }

    @Test
    fun `session profile infers PARTY context from high energy and dance keywords`() {
        sessionProfile.onTrackCompleted(
            SessionTrackEntry("t1", "Club Banger Party", "DJ Snake", energyLevel = 0.85f)
        )
        sessionProfile.onTrackCompleted(
            SessionTrackEntry("t2", "Festival Dance Bass", "Martin Garrix", energyLevel = 0.90f)
        )

        val context = sessionProfile.inferSessionContext()
        assertEquals(SessionCategory.PARTY, context.category)
        assertTrue("Confidence should be high (> 0.7)", context.confidence > 0.70f)
        assertTrue("Target energy should be high (> 0.8)", context.targetEnergy > 0.80f)
    }

    @Test
    fun `session profile infers RELAXED context from low energy and acoustic keywords`() {
        sessionProfile.onTrackCompleted(
            SessionTrackEntry("t1", "Acoustic Piano Chill", "Ludovico", energyLevel = 0.25f)
        )
        sessionProfile.onTrackCompleted(
            SessionTrackEntry("t2", "Sleep Meditation Ambient", "Brian Eno", energyLevel = 0.20f)
        )

        val context = sessionProfile.inferSessionContext()
        assertEquals(SessionCategory.RELAXED, context.category)
        assertTrue("Target energy should be low (< 0.35)", context.targetEnergy < 0.35f)
    }

    @Test
    fun `gradual adaptation on manual track selection preserves session continuity without amnesia`() {
        // Build an established session of 8 tracks
        for (i in 1..8) {
            sessionProfile.onTrackCompleted(
                SessionTrackEntry("id$i", "Track $i", "Artist $i", energyLevel = 0.5f)
            )
        }
        assertEquals(8, sessionProfile.getRecentTracks().size)

        // User manually picks another track
        sessionProfile.onManualTrackSelection(
            SessionTrackEntry("manual_1", "New Manual Track", "New Artist", energyLevel = 0.7f)
        )

        val remaining = sessionProfile.getRecentTracks()
        // With adaptation weight 0.35, retainCount = 10 * 0.65 = 6.
        // It must NOT reset to 1 track! It retains prior momentum + the new track.
        assertTrue("Session must adapt gradually, retaining prior tracks", remaining.size in 6..7)
        assertEquals("manual_1", remaining.last().mediaId)
    }

    @Test
    fun `skip friction accumulates on skips and resets on track completion or manual selection`() {
        sessionProfile.onExplicitSkip(SessionTrackEntry("s1", "Song 1", "Artist 1"))
        sessionProfile.onExplicitSkip(SessionTrackEntry("s2", "Song 2", "Artist 2"))
        assertEquals(2, sessionProfile.consecutiveSkips)

        // Natural completion reduces skip friction
        sessionProfile.onTrackCompleted(SessionTrackEntry("s3", "Song 3", "Artist 3"))
        assertEquals(1, sessionProfile.consecutiveSkips)

        // Manual selection clears skip friction
        sessionProfile.onManualTrackSelection(SessionTrackEntry("s4", "Song 4", "Artist 4"))
        assertEquals(0, sessionProfile.consecutiveSkips)
    }
}
