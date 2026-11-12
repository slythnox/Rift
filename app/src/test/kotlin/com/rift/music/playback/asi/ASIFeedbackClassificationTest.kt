/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.rift.music.playback.asi.feedback.ASIFeedbackCollector
import com.rift.music.playback.asi.feedback.CommandOriginRegistry
import com.rift.music.playback.asi.feedback.CommandSource
import com.rift.music.playback.asi.feedback.CommandSourceType
import com.rift.music.playback.asi.feedback.InterruptionCause
import com.rift.music.playback.asi.feedback.NegativeSignalJournal
import com.rift.music.playback.asi.feedback.PlaybackOutcome
import com.rift.music.playback.asi.feedback.UserPlaybackCommand
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying playback feedback instrumentation and outcome classification:
 * 1. Explicit user skips (UI, Notification, Bluetooth AVRCP) are classified correctly.
 * 2. Natural completion (boundary or >= 80% duration) is never classified as a skip.
 * 3. Manual track selection does not penalize the previous track (tasteImpact = 0.0).
 * 4. Playback errors and stream recoveries are classified as SystemInterruption (tasteImpact = 0.0).
 * 5. Unclassified transitions without user commands have strictly neutral taste impact (0.0).
 * 6. Duplicate event suppression prevents double-counting identical transitions.
 */
class ASIFeedbackClassificationTest {

    private lateinit var commandOriginRegistry: CommandOriginRegistry
    private lateinit var sessionProfile: ASISessionProfile
    private lateinit var feedbackCollector: ASIFeedbackCollector
    private lateinit var journal: NegativeSignalJournal
    private val config = ASIConfig()

    @Before
    fun setup() {
        PlaybackDiagnostics.clear()
        commandOriginRegistry = CommandOriginRegistry()
        sessionProfile = ASISessionProfile(config)
        journal = NegativeSignalJournal(config)
        feedbackCollector = ASIFeedbackCollector(
            commandOriginRegistry = commandOriginRegistry,
            negativeSignalJournal = journal,
            sessionProfile = sessionProfile,
            config = config,
        )
    }

    private fun createPositionInfo(
        mediaId: String,
        title: String,
        artist: String,
        index: Int,
        positionMs: Long,
    ): Player.PositionInfo {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadata)
            .build()

        return Player.PositionInfo(
            /* windowUid = */ null,
            /* mediaItemIndex = */ index,
            /* mediaItem = */ item,
            /* periodUid = */ null,
            /* periodIndex = */ index,
            /* positionMs = */ positionMs,
            /* contentPositionMs = */ positionMs,
            /* adGroupIndex = */ -1,
            /* adIndexInAdGroup = */ -1
        )
    }

    @Test
    fun `natural completion at track boundary is classified as NaturalCompletion with positive impact`() {
        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 180_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            knownDurationMs = 180_000L,
        )

        assertNotNull(outcome)
        assertTrue("Must be NaturalCompletion", outcome is PlaybackOutcome.NaturalCompletion)
        val completion = outcome as PlaybackOutcome.NaturalCompletion
        assertEquals("track_1", completion.mediaId)
        assertTrue("Taste impact must be positive", completion.tasteImpact > 0f)
        assertEquals("Consecutive skips must reset to 0", 0, sessionProfile.consecutiveSkips)
    }

    @Test
    fun `playback past 80 percent duration is classified as NaturalCompletion even if seek occurred`() {
        // Track duration 100,000ms; played for 85,000ms (85%)
        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 85_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 100_000L,
        )

        assertNotNull(outcome)
        assertTrue("Must be NaturalCompletion due to >=80% duration played", outcome is PlaybackOutcome.NaturalCompletion)
        assertEquals(1.0f, outcome!!.tasteImpact, 0.001f)
    }

    @Test
    fun `explicit user skip via UI under 15s is classified as early ExplicitUserSkip with strong negative impact`() {
        commandOriginRegistry.recordCommand(
            UserPlaybackCommand.SeekNext(CommandSource.IN_APP_UI)
        )

        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 8_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 180_000L,
        )

        assertNotNull(outcome)
        assertTrue("Must be ExplicitUserSkip", outcome is PlaybackOutcome.ExplicitUserSkip)
        val skip = outcome as PlaybackOutcome.ExplicitUserSkip
        assertTrue("Must be early skip (<15s)", skip.isEarly)
        assertEquals(CommandSourceType.IN_APP_UI, skip.source.type)
        assertEquals(-1.5f, skip.tasteImpact, 0.001f)
        assertEquals("Consecutive skips counter must increment", 1, sessionProfile.consecutiveSkips)
    }

    @Test
    fun `explicit user skip via Bluetooth AVRCP is attributed to Bluetooth origin`() {
        commandOriginRegistry.recordCommand(
            UserPlaybackCommand.SeekNext(CommandSource.BLUETOOTH)
        )

        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 20_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 180_000L,
        )

        assertNotNull(outcome)
        val skip = outcome as PlaybackOutcome.ExplicitUserSkip
        assertFalse("20s is not an early skip (<15s)", skip.isEarly)
        assertEquals(CommandSourceType.BLUETOOTH, skip.source.type)
        assertEquals(-0.7f, skip.tasteImpact, 0.001f)
    }

    @Test
    fun `manual track selection has strictly neutral taste impact on previous track`() {
        commandOriginRegistry.recordCommand(
            UserPlaybackCommand.ManualSelectTrack(targetIndex = 5, source = CommandSource.IN_APP_UI)
        )

        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 12_000L)
        val newPos = createPositionInfo("track_6", "Song Six", "Artist F", index = 5, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 180_000L,
        )

        assertNotNull(outcome)
        assertTrue("Must be ManualTrackSelection", outcome is PlaybackOutcome.ManualTrackSelection)
        val manual = outcome as PlaybackOutcome.ManualTrackSelection
        assertEquals(0.0f, manual.tasteImpact, 0.0001f)
        assertEquals("track_1", manual.oldMediaId)
        assertEquals("track_6", manual.newMediaId)
        assertEquals("Consecutive skips must reset to 0 on intentional selection", 0, sessionProfile.consecutiveSkips)
    }

    @Test
    fun `playback error is classified as SystemInterruption with zero taste penalty`() {
        // Simulate error recorded in PlaybackDiagnostics
        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.ERROR,
            mediaId = "track_err",
            title = "Failing Track",
            playbackState = Player.STATE_IDLE,
            playWhenReady = true,
            isPlaying = false,
            details = "HTTP 410 Gone"
        )

        val oldPos = createPositionInfo("track_err", "Failing Track", "Artist X", index = 0, positionMs = 5_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 200_000L,
        )

        assertNotNull(outcome)
        assertTrue("Must be classified as SystemInterruption", outcome is PlaybackOutcome.SystemInterruption)
        val interruption = outcome as PlaybackOutcome.SystemInterruption
        assertEquals(InterruptionCause.PLAYBACK_ERROR, interruption.cause)
        assertEquals("Taste impact for system error must be strictly 0.0", 0.0f, interruption.tasteImpact, 0.0001f)
    }

    @Test
    fun `unclassified transition without recorded user command has strictly neutral taste impact`() {
        // No command in registry, no error in diagnostics
        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 10_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 200_000L,
        )

        assertNotNull(outcome)
        assertTrue("Must be UnclassifiedTransition", outcome is PlaybackOutcome.UnclassifiedTransition)
        assertEquals("Unclassified transition must have 0.0 taste impact", 0.0f, outcome!!.tasteImpact, 0.0001f)
    }

    @Test
    fun `queue removal is classified as QueueModification with neutral taste impact`() {
        val oldPos = createPositionInfo("track_rem", "Removed Song", "Artist R", index = 2, positionMs = 50_000L)
        val newPos = createPositionInfo("track_next", "Next Song", "Artist N", index = 2, positionMs = 0L)

        val outcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_REMOVE,
            knownDurationMs = 200_000L,
        )

        assertNotNull(outcome)
        assertTrue(outcome is PlaybackOutcome.QueueModification)
        assertEquals(0.0f, outcome!!.tasteImpact, 0.0001f)
    }

    @Test
    fun `duplicate transition event within cooldown window is suppressed`() {
        commandOriginRegistry.recordCommand(
            UserPlaybackCommand.SeekNext(CommandSource.IN_APP_UI)
        )

        val oldPos = createPositionInfo("track_1", "Song One", "Artist A", index = 0, positionMs = 5_000L)
        val newPos = createPositionInfo("track_2", "Song Two", "Artist B", index = 1, positionMs = 0L)

        val firstOutcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 200_000L,
        )
        assertNotNull(firstOutcome)

        // Rapid duplicate trigger with identical parameters
        val duplicateOutcome = feedbackCollector.onPositionDiscontinuity(
            oldPosition = oldPos,
            newPosition = newPos,
            reason = Player.DISCONTINUITY_REASON_SEEK,
            knownDurationMs = 200_000L,
        )
        assertNull("Duplicate transition must be suppressed", duplicateOutcome)
    }
}
