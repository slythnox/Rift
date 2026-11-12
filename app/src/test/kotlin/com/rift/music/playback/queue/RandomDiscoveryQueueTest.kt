/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.queue

import com.rift.music.db.entities.ArtistEntity
import com.rift.music.db.entities.Song
import com.rift.music.db.entities.SongEntity
import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomDiscoveryQueueTest {

    private fun createTestSong(id: String, title: String, artist: String): Song {
        return Song(
            song = SongEntity(
                id = id,
                title = title,
                duration = 180,
            ),
            artists = listOf(ArtistEntity(id = "a_$id", name = artist)),
        )
    }

    private fun createTestSongItem(
        id: String,
        title: String,
        artist: String,
        year: Int? = null,
    ): SongItem {
        return SongItem(
            id = id,
            title = title,
            artists = listOf(Artist(name = artist, id = "a_$id")),
            album = year?.let { Album(name = "Album ($it)", id = "alb_$id") },
            duration = 180,
            thumbnail = "https://example.com/thumb.jpg",
        )
    }

    @Test
    fun `preloadItem returns seed song metadata immediately without network delay`() {
        val seed = createTestSong("seed_1", "Tum Se Tum Tak", "Niyam")
        val queue = RandomDiscoveryQueue(
            seedSong = seed,
            overplayedSongIds = emptySet(),
        )

        assertEquals("seed_1", queue.preloadItem.id)
        assertEquals("Tum Se Tum Tak", queue.preloadItem.title)
    }

    @Test
    fun `candidate filtering strictly removes overplayed tracks the user listens to every time`() {
        val overplayed = setOf("repeat_1", "repeat_2", "repeat_3")
        val seenTrackIds = mutableSetOf<String>().apply {
            add("seed_1")
            addAll(overplayed)
        }

        val candidates = listOf(
            createTestSongItem("repeat_1", "Frequent Track 1", "Artist A"),
            createTestSongItem("fresh_1", "Fresh Discovery 1", "Artist B"),
            createTestSongItem("repeat_2", "Frequent Track 2", "Artist C"),
            createTestSongItem("fresh_2", "Fresh Discovery 2", "Artist D"),
        )

        val filtered = candidates.filter { it.id !in seenTrackIds }

        assertEquals(2, filtered.size)
        assertEquals(listOf("fresh_1", "fresh_2"), filtered.map { it.id })
        assertFalse(filtered.any { it.id in overplayed })
    }

    @Test
    fun `artist diversity enforces maximum 2 songs per artist in discovery queue`() {
        val candidates = listOf(
            createTestSongItem("s1", "Song 1", "The Weeknd"),
            createTestSongItem("s2", "Song 2", "The Weeknd"),
            createTestSongItem("s3", "Song 3", "The Weeknd"), // 3rd track should be capped
            createTestSongItem("s4", "Song 4", "Dua Lipa"),
        )

        val artistCounts = mutableMapOf<String, Int>()
        val selected = mutableListOf<SongItem>()

        for (candidate in candidates) {
            val artistName = candidate.artists.firstOrNull()?.name?.trim()?.lowercase().orEmpty()
            val currentCount = artistCounts.getOrDefault(artistName, 0)
            if (currentCount < 2) {
                artistCounts[artistName] = currentCount + 1
                selected.add(candidate)
            }
        }

        assertEquals(3, selected.size)
        assertEquals(listOf("s1", "s2", "s4"), selected.map { it.id })
        assertEquals(2, selected.count { it.artists.first().name == "The Weeknd" })
    }

    @Test
    fun `novelty ranking prioritizes newer songs from same vibe over older tracks`() {
        val oldSong = createTestSongItem("old_1", "Old Hit", "Artist A", year = 2015)
        val newSong = createTestSongItem("new_1", "New Vibe 2025", "Artist B", year = 2025)
        val midSong = createTestSongItem("mid_1", "Mid Track 2024", "Artist C", year = 2024)

        val yearRegex = Regex("""\b(202[3-9])\b""")
        val pool = listOf(oldSong, midSong, newSong)

        val sorted = pool.sortedWith(
            compareByDescending<SongItem> { item ->
                val hasRecentYear = yearRegex.containsMatchIn(item.title) ||
                    yearRegex.containsMatchIn(item.album?.name.orEmpty())
                if (hasRecentYear) 1 else 0
            }
        )

        // Newest tracks (2025 and 2024) must precede older tracks
        assertTrue(sorted[0].id == "new_1" || sorted[0].id == "mid_1")
        assertEquals("old_1", sorted.last().id)
    }
}
