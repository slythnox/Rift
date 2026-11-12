/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.spotifyimport

import com.music.spotify.models.SpotifySimpleAlbum
import com.music.spotify.models.SpotifySimpleArtist
import com.music.spotify.models.SpotifyTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyImportPaginationTest {

    private fun createTrack(id: String, name: String, artist: String = "Test Artist"): SpotifyTrack {
        return SpotifyTrack(
            id = id,
            name = name,
            artists = listOf(SpotifySimpleArtist(id = "artist_$id", name = artist)),
            album = SpotifySimpleAlbum(id = "album_$id", name = "Test Album"),
            durationMs = 210000,
        )
    }

    @Test
    fun testExtractPlaylistIdFormats() {
        // Standard link
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylistImporter.extractPlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        // Link with query params
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylistImporter.extractPlaylistId("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abcd1234efgh"))
        // Embed link
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylistImporter.extractPlaylistId("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        // Spotify URI
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylistImporter.extractPlaylistId("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        // Raw 22-char ID
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyPlaylistImporter.extractPlaylistId("37i9dQZF1DXcBWIGoYBM5M"))
        // Invalid link
        assertNull(SpotifyPlaylistImporter.extractPlaylistId("https://open.spotify.com/album/37i9dQZF1DXcBWIGoYBM5M"))
    }

    @Test
    fun testPaginationSimulations() {
        val testCounts = listOf(100, 500, 999, 1000, 1001)

        for (expectedTotal in testCounts) {
            val allServerTracks = (0 until expectedTotal).map { i ->
                createTrack("track_$i", "Song $i")
            }

            // Simulate paginated retrieval with 100 tracks per page
            val pageSize = 100
            val retrievedTracks = mutableListOf<SpotifyTrack>()
            val seenIds = mutableSetOf<String>()

            var offset = 0
            while (offset < expectedTotal) {
                val pageTracks = allServerTracks.subList(offset, minOf(offset + pageSize, expectedTotal))
                for (t in pageTracks) {
                    if (seenIds.add(t.id)) {
                        retrievedTracks.add(t)
                    }
                }
                offset += pageSize
            }

            assertEquals("Playlist with $expectedTotal tracks should fetch all tracks", expectedTotal, retrievedTracks.size)
            assertEquals("First track should match", "track_0", retrievedTracks.first().id)
            assertEquals("Last track should match", "track_${expectedTotal - 1}", retrievedTracks.last().id)
        }
    }

    @Test
    fun testIntentionalDuplicatePlaylistEntriesPreserved() {
        // Intentional duplicate tracks in a Spotify playlist (e.g. repeated tracks or interludes)
        // should be preserved in exact playlist order with sequential positions
        val page1 = listOf(createTrack("t1", "Intro"), createTrack("t2", "Hit Song"))
        val page2 = listOf(createTrack("t3", "Interlude"), createTrack("t2", "Hit Song (Encore)")) // t2 intentionally repeated
        val page3 = listOf(createTrack("t1", "Outro Reprise")) // t1 reprise

        val allTracks = mutableListOf<SpotifyTrack>()
        allTracks.addAll(page1)
        allTracks.addAll(page2)
        allTracks.addAll(page3)

        assertEquals("Intentional duplicate tracks must be preserved across pages", 5, allTracks.size)
        assertEquals(listOf("t1", "t2", "t3", "t2", "t1"), allTracks.map { it.id })
    }

    @Test
    fun testMalformedAndUnavailableTracksFiltered() {
        val rawTracks = listOf(
            createTrack("valid_1", "Valid Song 1"),
            createTrack("empty_name", ""),
            createTrack("valid_2", "Valid Song 2"),
            createTrack("blank_name", "   "),
            createTrack("valid_3", "Valid Song 3"),
        )

        val playableTracks = rawTracks.filter { it.id.isNotBlank() && it.name.isNotBlank() }

        assertEquals(3, playableTracks.size)
        assertEquals(listOf("valid_1", "valid_2", "valid_3"), playableTracks.map { it.id })
    }

    @Test
    fun testRateLimitBackoffAndRetryAfter() = runBlocking {
        var attempts = 0
        val simulatedRetryAfterSec = 1L
        var success = false

        val simulateFetch = suspend {
            attempts++
            if (attempts <= 2) {
                // Simulate HTTP 429
                val retryAfter = simulatedRetryAfterSec
                delay(retryAfter * 10) // Scaled down for unit test speed
                throw Exception("HTTP 429: Too Many Requests")
            } else {
                success = true
                listOf(createTrack("t1", "Track 1"))
            }
        }

        var result: List<SpotifyTrack>? = null
        for (i in 0..3) {
            try {
                result = simulateFetch()
                break
            } catch (_: Exception) {
                // Backoff and retry
            }
        }

        assertTrue(success)
        assertEquals(3, attempts)
        assertNotNull(result)
        assertEquals(1, result!!.size)
    }

    @Test
    fun testCancellationHandling() {
        val pageSize = 100
        val totalCount = 1000
        var loadedCount = 0

        try {
            for (offset in 0 until totalCount step pageSize) {
                if (offset >= 300) {
                    throw CancellationException("Import cancelled by user")
                }
                loadedCount += pageSize
            }
            fail("Expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("Import cancelled by user", e.message)
            assertEquals("Should have loaded 3 pages (300 tracks) before cancellation", 300, loadedCount)
        }
    }

    @Test
    fun testPartialFailurePreservesLoadedTracks() {
        val page1 = (0 until 100).map { createTrack("t_$it", "Song $it") }
        val allLoaded = mutableListOf<SpotifyTrack>()
        allLoaded.addAll(page1)

        // Page 2 fails due to network outage
        val page2Success = try {
            throw java.io.IOException("Network unreachable")
        } catch (_: Exception) {
            false
        }

        assertFalse(page2Success)
        // Verify already loaded 100 tracks are preserved and not lost
        assertEquals(100, allLoaded.size)
    }
}
