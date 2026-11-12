/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback

import com.rift.music.db.entities.DislikedSongEntity
import com.rift.music.playback.recommendation.RecommendationPostProcessor
import com.rift.music.playback.recommendation.RecommendationPostProcessor.Candidate
import com.rift.music.playback.resolver.addon.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RecommendationPostProcessorTest {

    private fun makeCandidate(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Int = 200,
        originalIndex: Int = 0,
    ) = Candidate(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationSec = durationSec,
        originalIndex = originalIndex,
    )

    private fun makeDisliked(
        id: String,
        title: String,
        artist: String,
        duration: Int = 200,
    ) = DislikedSongEntity(
        id = id,
        title = title,
        artist = artist,
        album = null,
        duration = duration,
        thumbnailUrl = null,
        normalizedTitle = TrackMatcher.cleanTitle(title, artist).lowercase().trim(),
        normalizedArtist = TrackMatcher.primaryArtist(artist).lowercase().trim(),
        dislikedAt = LocalDateTime.now(),
    )

    @Test
    fun testExcludesCurrentMediaIdAndQueueIds() {
        val candidates = listOf(
            makeCandidate("playing_1", "Currently Playing", "Artist A", originalIndex = 0),
            makeCandidate("queue_1", "Already In Queue", "Artist B", originalIndex = 1),
            makeCandidate("cand_1", "Fresh Track 1", "Artist C", originalIndex = 2),
            makeCandidate("cand_2", "Fresh Track 2", "Artist D", originalIndex = 3),
        )

        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = "playing_1",
            existingQueueIds = setOf("queue_1"),
            dislikedList = emptyList(),
            recentPlayedIds = emptySet(),
            targetCount = 10,
        )

        val resultIds = result.map { it.id }
        assertFalse(resultIds.contains("playing_1"))
        assertFalse(resultIds.contains("queue_1"))
        assertTrue(resultIds.contains("cand_1"))
        assertTrue(resultIds.contains("cand_2"))
    }

    @Test
    fun testExcludesDislikedTracksCrossProvider() {
        val candidates = listOf(
            makeCandidate("yt_disliked", "Bad Blood", "Taylor Swift", durationSec = 210, originalIndex = 0),
            makeCandidate("sp_good", "Cruel Summer", "Taylor Swift", durationSec = 178, originalIndex = 1),
        )

        // Disliked entry stored with different ID (e.g. from Spotify)
        val dislikedList = listOf(
            makeDisliked("sp_bad_blood", "Bad Blood", "Taylor Swift", duration = 210)
        )

        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = dislikedList,
            recentPlayedIds = emptySet(),
            targetCount = 10,
        )

        val resultIds = result.map { it.id }
        assertFalse("Cross-provider disliked track must be excluded", resultIds.contains("yt_disliked"))
        assertTrue("Non-disliked track must be kept", resultIds.contains("sp_good"))
    }

    @Test
    fun testRecencyCooldownExclusion() {
        val candidates = listOf(
            makeCandidate("recent_song", "Recent Track", "Artist A", originalIndex = 0),
            makeCandidate("fresh_song", "Fresh Track", "Artist B", originalIndex = 1),
        )

        val recentPlayedIds = setOf("recent_song")

        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = emptyList(),
            recentPlayedIds = recentPlayedIds,
            targetCount = 10,
        )

        // Fresh track should be selected first; recent track only if relaxed
        assertEquals("fresh_song", result.first().id)
    }

    @Test
    fun testArtistDiversityAvoidsConsecutiveSameArtist() {
        val candidates = listOf(
            makeCandidate("w1", "Blinding Lights", "The Weeknd", originalIndex = 0),
            makeCandidate("w2", "Starboy", "The Weeknd", originalIndex = 1),
            makeCandidate("d1", "One Dance", "Drake", originalIndex = 2),
            makeCandidate("w3", "Save Your Tears", "The Weeknd", originalIndex = 3),
            makeCandidate("d2", "God's Plan", "Drake", originalIndex = 4),
        )

        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = emptyList(),
            recentPlayedIds = emptySet(),
            targetCount = 5,
        )

        // Verify no two consecutive tracks have the exact same primary artist
        for (i in 0 until result.size - 1) {
            val a1 = TrackMatcher.primaryArtist(result[i].artist).lowercase().trim()
            val a2 = TrackMatcher.primaryArtist(result[i + 1].artist).lowercase().trim()
            assertFalse(
                "Adjacent tracks at indices $i and ${i + 1} should have different artists: $a1 vs $a2",
                a1 == a2
            )
        }
    }

    @Test
    fun testArtistRadioModeRelaxesArtistConstraint() {
        // When 70%+ candidates belong to the seed artist (e.g. Radio for The Weeknd)
        val candidates = listOf(
            makeCandidate("w1", "Song 1", "The Weeknd", originalIndex = 0),
            makeCandidate("w2", "Song 2", "The Weeknd", originalIndex = 1),
            makeCandidate("w3", "Song 3", "The Weeknd", originalIndex = 2),
            makeCandidate("w4", "Song 4", "The Weeknd", originalIndex = 3),
            makeCandidate("feat1", "Song 5", "Daft Punk feat. The Weeknd", originalIndex = 4),
        )

        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = emptyList(),
            recentPlayedIds = emptySet(),
            targetCount = 5,
        )

        // In artist radio mode, songs from the seed artist should all be preserved
        assertEquals(5, result.size)
        assertTrue(result.all { it.artist.contains("The Weeknd") })
    }

    @Test
    fun testProgressiveRelaxationNeverIncludesDisliked() {
        val candidates = listOf(
            makeCandidate("disliked_1", "Disliked Track", "Artist A", originalIndex = 0),
            makeCandidate("recent_1", "Recently Played Track", "Artist B", originalIndex = 1),
        )

        val dislikedList = listOf(
            makeDisliked("disliked_1", "Disliked Track", "Artist A")
        )
        val recentIds = setOf("recent_1")

        // Target count is 2, but only 1 candidate can be valid after relaxation
        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = dislikedList,
            recentPlayedIds = recentIds,
            targetCount = 2,
        )

        // Recent track can be relaxed and included
        assertTrue(result.any { it.id == "recent_1" })
        // Disliked track must NEVER be included under any circumstances
        assertFalse(result.any { it.id == "disliked_1" })
    }

    @Test
    fun testCompleteQueueGenerationRespectsAllInvariants() {
        val candidates = listOf(
            makeCandidate("current_play", "Playing Song", "Artist Seed", album = "Album S", originalIndex = 0),
            makeCandidate("queue_next", "Upcoming Song", "Artist A", album = "Album A1", originalIndex = 1),
            makeCandidate("disliked_exact", "Disliked Exact", "Artist D", album = "Album D", originalIndex = 2),
            makeCandidate("disliked_cross", "Disliked Cross", "Artist E", album = "Album E", durationSec = 210, originalIndex = 3),
            makeCandidate("fresh_1", "Song F1", "Artist A", album = "Album A1", originalIndex = 4),
            makeCandidate("fresh_2", "Song F2", "Artist B", album = "Album B1", originalIndex = 5),
            makeCandidate("fresh_3", "Song F3", "Artist A", album = "Album A2", originalIndex = 6),
            makeCandidate("fresh_4", "Song F4", "Artist C", album = "Album C1", originalIndex = 7),
            makeCandidate("recent_1", "Recent R1", "Artist B", album = "Album B2", originalIndex = 8),
        )

        val disliked = listOf(
            makeDisliked("disliked_exact", "Disliked Exact", "Artist D"),
            makeDisliked("other_id_cross", "Disliked Cross", "Artist E", duration = 210),
        )

        val recent = setOf("recent_1")

        val result = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = "current_play",
            existingQueueIds = setOf("queue_next"),
            dislikedList = disliked,
            recentPlayedIds = recent,
            targetCount = 5,
        )

        val resultIds = result.map { it.id }

        // Invariant 1: Currently playing track is excluded
        assertFalse("Current media ID must be excluded", resultIds.contains("current_play"))

        // Invariant 2: Tracks already in queue are excluded
        assertFalse("Existing queue ID must be excluded", resultIds.contains("queue_next"))

        // Invariant 3: Disliked tracks are 100% excluded (both exact and cross-provider)
        assertFalse("Exact disliked track must be excluded", resultIds.contains("disliked_exact"))
        assertFalse("Cross-provider disliked track must be excluded", resultIds.contains("disliked_cross"))

        // Invariant 4: Fresh tracks are preferred before cooldown tracks
        val freshCount = resultIds.count { it.startsWith("fresh_") }
        assertEquals("All 4 fresh tracks must be chosen", 4, freshCount)

        // Invariant 5: No consecutive identical artists among fresh tracks
        for (i in 0 until result.size - 1) {
            val a1 = TrackMatcher.primaryArtist(result[i].artist).lowercase().trim()
            val a2 = TrackMatcher.primaryArtist(result[i + 1].artist).lowercase().trim()
            assertFalse("Adjacent tracks at $i and ${i + 1} must not repeat artist $a1", a1 == a2)
        }
    }

    @Test
    fun testProgressiveSelectionGuaranteesFreshBeforeRecentEvenWithHighOriginalIndex() {
        // High index fresh candidate vs low index recent candidate
        val candidates = listOf(
            makeCandidate("recent_early", "Recent Early Track", "Artist A", originalIndex = 0),
            makeCandidate("fresh_late", "Fresh Late Track", "Artist B", originalIndex = 5000),
        )

        val recent = setOf("recent_early")

        // If targetCount is 1, only the fresh candidate must be picked
        val resultTarget1 = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = emptyList(),
            recentPlayedIds = recent,
            targetCount = 1,
        )
        assertEquals(1, resultTarget1.size)
        assertEquals("fresh_late", resultTarget1[0].id)

        // If targetCount is 2, fresh candidate MUST come first, then recent candidate
        val resultTarget2 = RecommendationPostProcessor.filterAndRank(
            candidates = candidates,
            currentMediaId = null,
            existingQueueIds = emptySet(),
            dislikedList = emptyList(),
            recentPlayedIds = recent,
            targetCount = 2,
        )
        assertEquals(2, resultTarget2.size)
        assertEquals("fresh_late", resultTarget2[0].id)
        assertEquals("recent_early", resultTarget2[1].id)
    }
}
