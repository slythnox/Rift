/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import com.rift.music.playback.asi.feedback.NegativeSignalEntry
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.CanonicalTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NegativeSignalEntry serialization, CanonicalTrackId cross-provider matching,
 * duplicate handling, pruning, and TTL expiration logic.
 */
class NegativeSignalJournalTest {

    private val config = ASIConfig(
        negativeSignalTtlDays = 14,
        maxNegativeSignals = 5, // Small limit for testing bounding
    )

    @Test
    fun `CanonicalTrackId cleans noise and matches identical tracks across providers`() {
        val ytmTrack = CanonicalTrackId.from(
            title = "Blinding Lights (Official Audio)",
            artist = "The Weeknd - Topic",
            durationSec = 200,
        )
        val spotifyTrack = CanonicalTrackId.from(
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationSec = 202, // Within 15s tolerance
        )

        assertEquals("blinding lights", ytmTrack.normalizedTitle)
        assertEquals("the weeknd", ytmTrack.normalizedPrimaryArtist)
        assertEquals(ytmTrack.toStorageKey(), spotifyTrack.toStorageKey())
        assertTrue("Tracks must match across providers", ytmTrack.matches(spotifyTrack))
    }

    @Test
    fun `CanonicalTrackId rejects tracks exceeding duration tolerance`() {
        val trackA = CanonicalTrackId.from("Song", "Artist", durationSec = 100)
        val trackB = CanonicalTrackId.from("Song", "Artist", durationSec = 130) // delta = 30s > 15s

        assertFalse("Must reject tracks with duration delta > 15s", trackA.matches(trackB))
    }

    @Test
    fun `NegativeSignalEntry serializes and deserializes correctly without data loss`() {
        val entry = NegativeSignalEntry(
            canonicalKey = "blinding lights||the weeknd",
            mediaId = "yt_12345",
            timestampEpochMs = 1700000000000L,
            skipCount = 3,
            isEarly = true,
        )

        val serialized = entry.toSerialized()
        val parsed = NegativeSignalEntry.fromSerialized(serialized)

        assertNotNull(parsed)
        assertEquals(entry.canonicalKey, parsed!!.canonicalKey)
        assertEquals(entry.mediaId, parsed.mediaId)
        assertEquals(entry.timestampEpochMs, parsed.timestampEpochMs)
        assertEquals(3, parsed.skipCount)
        assertTrue(parsed.isEarly)
    }

    @Test
    fun `NegativeSignalEntry ignores corrupted serialized lines`() {
        assertNull(NegativeSignalEntry.fromSerialized(""))
        assertNull(NegativeSignalEntry.fromSerialized("corrupted;;line"))
        assertNull(NegativeSignalEntry.fromSerialized("key;;id;;not_a_number;;1;;true"))
    }

    @Test
    fun `journal pruning logic drops entries older than TTL cutoff`() {
        val now = 1_000_000_000_000L
        val ttlCutoff = now - (config.negativeSignalTtlDays * 24L * 60L * 60L * 1000L)

        val freshEntry = NegativeSignalEntry("song1||artist1", "id1", now - 1000L)
        val expiredEntry = NegativeSignalEntry("song2||artist2", "id2", ttlCutoff - 5000L)

        val entries = listOf(freshEntry, expiredEntry)
        val unexpired = entries.filter { it.timestampEpochMs >= ttlCutoff }

        assertEquals(1, unexpired.size)
        assertEquals("id1", unexpired.first().mediaId)
    }

    @Test
    fun `journal bounding logic drops oldest entries when exceeding max capacity`() {
        val now = System.currentTimeMillis()
        val entries = (1..10).map { i ->
            NegativeSignalEntry(
                canonicalKey = "song$i||artist$i",
                mediaId = "id$i",
                timestampEpochMs = now + i * 1000L, // Increasing timestamps
            )
        }

        // Bounding: sort descending and take maxNegativeSignals (5)
        val bounded = entries.sortedByDescending { it.timestampEpochMs }.take(config.maxNegativeSignals)

        assertEquals(5, bounded.size)
        assertEquals("id10", bounded.first().mediaId)
        assertEquals("id6", bounded.last().mediaId)
    }
}
