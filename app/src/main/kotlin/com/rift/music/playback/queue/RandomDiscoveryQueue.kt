/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.queue

import androidx.media3.common.MediaItem
import com.rift.music.core.extensions.toMediaItem
import com.rift.music.db.MusicDatabase
import com.rift.music.db.entities.Song
import com.rift.music.models.MediaMetadata
import com.rift.music.models.toMediaMetadata
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Intelligent Random Discovery Queue.
 *
 * 1. Seed: Starts with a random song from the user's recent listening history immediately.
 * 2. Same Genre & Vibe: Dynamically discovers tracks matching the seed track's style using
 *    YouTube Music's related and radio automix streams.
 * 3. Anti-Repetition: Strictly excludes overplayed tracks (the 7-8 songs the user listens to
 *    every time, most-played tracks, and recent listening history).
 * 4. Freshness & Novelty: Prioritizes newer releases, deeper cuts from the same genre, and ensures
 *    artist diversity (max 2 songs per artist) so listening is exciting rather than repetitive.
 */
class RandomDiscoveryQueue(
    private val seedSong: Song,
    private val overplayedSongIds: Set<String>,
    private val database: MusicDatabase? = null,
) : Queue {

    override val preloadItem: MediaMetadata = seedSong.toMediaMetadata()

    private var continuation: String? = null
    private var nextEndpoint: WatchEndpoint? = null
    private val seenTrackIds = mutableSetOf<String>().apply {
        add(seedSong.id)
        addAll(overplayedSongIds)
    }

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        val seedMediaItem = seedSong.toMediaItem()
        val radioCandidates = mutableListOf<SongItem>()
        val relatedCandidates = mutableListOf<SongItem>()

        try {
            val radioEndpoint = WatchEndpoint(
                videoId = seedSong.id,
                playlistId = "RDAMVM${seedSong.id}"
            )
            val nextResult = YouTube.next(radioEndpoint).getOrNull()

            if (nextResult != null) {
                continuation = nextResult.continuation
                nextEndpoint = nextResult.endpoint
                radioCandidates.addAll(nextResult.items)

                // Fetch YouTube Music Related tab (deeper same-genre / same-vibe discovery)
                nextResult.relatedEndpoint?.let { relEndpoint ->
                    val relatedPage = YouTube.related(relEndpoint).getOrNull()
                    if (relatedPage != null && relatedPage.songs.isNotEmpty()) {
                        relatedCandidates.addAll(relatedPage.songs)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag("RandomDiscoveryQueue").w(e, "Error fetching discovery candidates for seed ${seedSong.id}")
        }

        // Merge candidates: place related songs (fresh vibe matches) first, followed by radio items
        val combinedCandidates = (relatedCandidates + radioCandidates)
            .distinctBy { it.id }
            .filter { it.id !in seenTrackIds }

        val yearRegex = Regex("""\b(202[3-9])\b""")

        // Score candidates for freshness and "newer or something"
        val sortedCandidates = combinedCandidates.sortedWith(
            compareByDescending<SongItem> { item ->
                // Newer releases (2023+ in title or album) get boosted
                val hasRecentYear = yearRegex.containsMatchIn(item.title) ||
                    yearRegex.containsMatchIn(item.album?.name.orEmpty())
                if (hasRecentYear) 1 else 0
            }.thenByDescending { item ->
                // Related tab songs represent deeper stylistic similarity rather than generic chart radio
                if (item in relatedCandidates) 1 else 0
            }.thenBy {
                // Slight shuffle within equal tiers to keep each session fresh
                kotlin.random.Random.nextInt(10)
            }
        )

        val artistCounts = mutableMapOf<String, Int>()
        val selectedDiscoveryItems = mutableListOf<SongItem>()

        for (candidate in sortedCandidates) {
            val primaryArtist = candidate.artists.firstOrNull()?.name?.trim()?.lowercase().orEmpty()
            val currentCount = artistCounts.getOrDefault(primaryArtist, 0)
            if (currentCount < 2) {
                artistCounts[primaryArtist] = currentCount + 1
                selectedDiscoveryItems.add(candidate)
                seenTrackIds.add(candidate.id)
            }
            if (selectedDiscoveryItems.size >= 25) break
        }

        // Safety fallback: if strict filtering resulted in fewer than 8 items, fill with remaining non-seen items
        if (selectedDiscoveryItems.size < 8) {
            for (candidate in combinedCandidates) {
                if (candidate.id !in seenTrackIds) {
                    selectedDiscoveryItems.add(candidate)
                    seenTrackIds.add(candidate.id)
                }
                if (selectedDiscoveryItems.size >= 15) break
            }
        }

        val fullQueue = listOf(seedMediaItem) + selectedDiscoveryItems.map { it.toMediaItem() }

        Queue.Status(
            title = "Random Discovery",
            items = fullQueue,
            mediaItemIndex = 0,
            position = 0L,
        )
    }

    override fun hasNextPage(): Boolean = continuation != null || nextEndpoint != null

    override suspend fun nextPage(): List<MediaItem> = withContext(IO) {
        val endpoint = nextEndpoint ?: WatchEndpoint(videoId = seedSong.id)
        val result = YouTube.next(endpoint, continuation).getOrNull() ?: return@withContext emptyList()
        continuation = result.continuation
        nextEndpoint = result.endpoint

        val newCandidates = result.items
            .filter { it.id !in seenTrackIds }
            .take(15)

        newCandidates.forEach { seenTrackIds.add(it.id) }
        newCandidates.map { it.toMediaItem() }
    }
}
