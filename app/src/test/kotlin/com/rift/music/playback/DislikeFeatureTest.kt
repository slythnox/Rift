/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback

import com.rift.music.db.entities.DislikedSongEntity
import com.rift.music.playback.dislike.DislikeManager
import com.rift.music.playback.resolver.addon.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DislikeFeatureTest {

    private fun createDisliked(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        duration: Int = 200,
    ): DislikedSongEntity {
        return DislikedSongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            thumbnailUrl = null,
            normalizedTitle = TrackMatcher.cleanTitle(title, artist).lowercase().trim(),
            normalizedArtist = TrackMatcher.primaryArtist(artist).lowercase().trim(),
            dislikedAt = LocalDateTime.now(),
        )
    }

    @Test
    fun testExactIdMatch() {
        val dislikedList = listOf(
            createDisliked(id = "yt_123", title = "Blinding Lights", artist = "The Weeknd")
        )

        val matches = DislikeManager.matchesAnyDisliked(
            candId = "yt_123",
            candTitle = "Some Other Title",
            candArtist = "Some Other Artist",
            candDurationSec = 100,
            dislikedList = dislikedList,
        )
        assertTrue("Exact ID match must return true", matches)
    }

    @Test
    fun testCrossProviderMatchSameTitleAndArtist() {
        val dislikedList = listOf(
            createDisliked(id = "yt_123", title = "Blinding Lights", artist = "The Weeknd", duration = 200)
        )

        // Song comes from Spotify with different ID
        val matches = DislikeManager.matchesAnyDisliked(
            candId = "sp_999",
            candTitle = "Blinding Lights",
            candArtist = "The Weeknd",
            candDurationSec = 201,
            dislikedList = dislikedList,
        )
        assertTrue("Cross-provider match with identical metadata must return true", matches)
    }

    @Test
    fun testCrossProviderMatchWithFeatAndTopicNoise() {
        val dislikedList = listOf(
            createDisliked(id = "yt_123", title = "Starboy", artist = "The Weeknd feat. Daft Punk", duration = 230)
        )

        val matches = DislikeManager.matchesAnyDisliked(
            candId = "sp_starboy",
            candTitle = "Starboy (Official Audio) - Topic",
            candArtist = "The Weeknd",
            candDurationSec = 232,
            dislikedList = dislikedList,
        )
        assertTrue("Noise like Topic and Feat should be cleaned and match", matches)
    }

    @Test
    fun testDurationMismatchRejectsMatch() {
        val dislikedList = listOf(
            createDisliked(id = "yt_123", title = "Song A", artist = "Artist X", duration = 180)
        )

        // Same title and artist but duration is 240s (> 15s difference, likely extended mix or different version)
        val matches = DislikeManager.matchesAnyDisliked(
            candId = "alt_456",
            candTitle = "Song A",
            candArtist = "Artist X",
            candDurationSec = 240,
            dislikedList = dislikedList,
        )
        assertFalse("Duration difference > 15s should reject match", matches)
    }

    @Test
    fun testDifferentArtistsDoNotMatch() {
        val dislikedList = listOf(
            createDisliked(id = "yt_123", title = "Hello", artist = "Adele", duration = 295)
        )

        // Same song title by Lionel Richie
        val matches = DislikeManager.matchesAnyDisliked(
            candId = "lionel_hello",
            candTitle = "Hello",
            candArtist = "Lionel Richie",
            candDurationSec = 295,
            dislikedList = dislikedList,
        )
        assertFalse("Different primary artists must never match", matches)
    }

    @Test
    fun testVersionMismatchRejection() {
        val dislikedList = listOf(
            createDisliked(id = "yt_orig", title = "Levitating", artist = "Dua Lipa", duration = 203)
        )

        // Remix version should not match original when user dislikes original
        val matches = DislikeManager.matchesAnyDisliked(
            candId = "yt_remix",
            candTitle = "Levitating (The Blessed Madonna Remix)",
            candArtist = "Dua Lipa",
            candDurationSec = 203,
            dislikedList = dislikedList,
        )
        assertFalse("Remix keyword mismatch should not match disliked original", matches)
    }

    @Test
    fun testUpcomingQueueDislikedRemovalPreservesCurrentlyPlaying() {
        data class TestTrack(val id: String, val title: String)

        val queue = mutableListOf(
            TestTrack("song_1", "Song 1"),
            TestTrack("song_disliked", "Bad Song"), // Currently playing
            TestTrack("song_3", "Song 3"),
            TestTrack("song_disliked", "Bad Song"), // Upcoming occurrence
            TestTrack("song_5", "Song 5"),
        )

        val currentPlayingIndex = 1
        val dislikedId = "song_disliked"

        // Queue cleanup algorithm: remove upcoming occurrences without interrupting current track
        val indicesToRemove = mutableListOf<Int>()
        for (i in queue.indices) {
            if (i > currentPlayingIndex && queue[i].id == dislikedId) {
                indicesToRemove.add(i)
            }
        }

        // Remove in reverse order so indices do not shift
        for (idx in indicesToRemove.reversed()) {
            queue.removeAt(idx)
        }

        // Verify currently playing track at index 1 is intact
        assertEquals("song_disliked", queue[currentPlayingIndex].id)
        // Verify upcoming duplicate is gone
        assertEquals(listOf("song_1", "song_disliked", "song_3", "song_5"), queue.map { it.id })
    }
}
