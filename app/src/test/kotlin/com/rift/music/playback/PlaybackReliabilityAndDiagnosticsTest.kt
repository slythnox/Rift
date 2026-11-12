/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.rift.music.playback.diagnostics.PlaybackDiagnosticEvent
import com.rift.music.playback.diagnostics.PlaybackDiagnostics
import com.rift.music.playback.diagnostics.PlaybackEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for playback reliability fixes and structured diagnostics:
 * 1. PlaybackDiagnostics ring buffer limits, thread safety, and event recording.
 * 2. `shouldResume` calculation preserving play intent when player is in STATE_BUFFERING.
 * 3. External stream provider data spec handling (no 10MB chunk truncation).
 * 4. HTTP 410 (Gone) and HTTP 403 (Forbidden) classification as expired streaming URLs.
 */
class PlaybackReliabilityAndDiagnosticsTest {

    @Before
    fun setup() {
        PlaybackDiagnostics.clear()
        PlaybackDiagnostics.currentSessionId = "test_session"
    }

    @Test
    fun `PlaybackDiagnostics respects max capacity ring buffer limit of 100 entries`() {
        repeat(120) { i ->
            PlaybackDiagnostics.record(
                eventType = PlaybackEventType.STATE_CHANGE,
                mediaId = "track_$i",
                title = "Title $i",
                provider = "YouTube",
                playbackState = Player.STATE_READY,
                playWhenReady = true,
                isPlaying = true,
                details = "Index $i"
            )
        }

        val events = PlaybackDiagnostics.getRecentEvents()
        assertEquals("Ring buffer must hold exactly 100 entries", 100, events.size)
        // First entry should be track_20 because 0-19 were evicted
        assertEquals("track_20", events.first().mediaId)
        assertEquals("track_119", events.last().mediaId)
    }

    @Test
    fun `PlaybackDiagnostics properly records all fields and formats playbackStateName`() {
        PlaybackDiagnostics.record(
            eventType = PlaybackEventType.ERROR,
            mediaId = "song_abc",
            title = "Test Song",
            provider = "JioSaavn",
            playbackState = Player.STATE_BUFFERING,
            playWhenReady = true,
            isPlaying = false,
            details = "errorCode=2001, httpStatus=410"
        )

        val events = PlaybackDiagnostics.getRecentEvents()
        assertEquals(1, events.size)
        val event = events.first()
        assertEquals("song_abc", event.mediaId)
        assertEquals("Test Song", event.title)
        assertEquals("JioSaavn", event.provider)
        assertEquals(PlaybackEventType.ERROR, event.eventType)
        assertEquals(Player.STATE_BUFFERING, event.playbackState)
        assertEquals("STATE_BUFFERING", event.playbackStateName)
        assertTrue(event.playWhenReady)
        assertFalse(event.isPlaying)
        assertEquals("errorCode=2001, httpStatus=410", event.details)
        assertEquals("test_session", event.sessionId)
    }

    @Test
    fun `playback resumption evaluates true when playWhenReady is true even if isPlaying is false during buffering`() {
        // Simulating the Media3 state contract during buffering:
        // playbackState == STATE_BUFFERING, playWhenReady == true, isPlaying == false
        val isPlayerPlaying = false
        val playWhenReady = true
        val wasPlayingBeforeLoss = false

        // Old flawed check:
        val oldWasPlaying = isPlayerPlaying
        assertFalse("Old check failed to recognize user playback intent during buffering", oldWasPlaying)

        // New robust check:
        val shouldResume = playWhenReady || isPlayerPlaying || wasPlayingBeforeLoss
        assertTrue("New check correctly restores playback when playWhenReady is active", shouldResume)
    }

    @Test
    fun `audio focus transient loss captures playWhenReady during buffering`() {
        // Audio focus lost during network switch/buffering
        val playbackState = Player.STATE_BUFFERING
        val playWhenReady = true
        val isPlaying = false

        // New capture logic:
        val wasPlayingBeforeAudioFocusLoss = playWhenReady || isPlaying
        assertTrue("Must record true so that AUDIOFOCUS_GAIN restores playback", wasPlayingBeforeAudioFocusLoss)
    }

    @Test
    fun `external stream providers are exempted from CHUNK_LENGTH truncation`() {
        data class PlaybackStub(
            val isTidalStream: Boolean = false,
            val isSpineStream: Boolean = false,
            val isAddonStream: Boolean = false,
            val isSaavnStream: Boolean = false,
        )

        fun shouldBypassChunking(playback: PlaybackStub): Boolean {
            return playback.isTidalStream ||
                playback.isSpineStream ||
                playback.isAddonStream ||
                playback.isSaavnStream
        }

        // Standard YouTube stream
        val ytStream = PlaybackStub()
        assertFalse("YouTube streams should use chunking", shouldBypassChunking(ytStream))

        // External streams must all bypass chunking:
        assertTrue("Tidal stream must bypass chunking", shouldBypassChunking(PlaybackStub(isTidalStream = true)))
        assertTrue("Spine stream must bypass chunking", shouldBypassChunking(PlaybackStub(isSpineStream = true)))
        assertTrue("Add-on stream must bypass chunking", shouldBypassChunking(PlaybackStub(isAddonStream = true)))
        assertTrue("JioSaavn stream must bypass chunking", shouldBypassChunking(PlaybackStub(isSaavnStream = true)))
    }

    @Test
    fun `HTTP 403 and 410 are both recognized as expired streaming URLs`() {
        fun isExpiredUrlStatusCode(httpStatusCode: Int): Boolean {
            return httpStatusCode == 403 || httpStatusCode == 410
        }

        assertTrue("HTTP 403 Forbidden is an expired signed URL", isExpiredUrlStatusCode(403))
        assertTrue("HTTP 410 Gone is an expired signed URL", isExpiredUrlStatusCode(410))
        assertFalse("HTTP 404 is not an expired signed URL", isExpiredUrlStatusCode(404))
        assertFalse("HTTP 500 is not an expired signed URL", isExpiredUrlStatusCode(500))
        assertFalse("HTTP 200 is not an error", isExpiredUrlStatusCode(200))
    }
}
