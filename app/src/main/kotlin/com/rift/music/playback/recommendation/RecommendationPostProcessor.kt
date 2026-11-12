/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.recommendation

import androidx.media3.common.MediaItem
import com.rift.music.core.extensions.metadata
import com.rift.music.db.MusicDatabase
import com.rift.music.db.entities.DislikedSongEntity
import com.rift.music.playback.dislike.DislikeManager
import com.rift.music.playback.resolver.addon.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class RecommendationPostProcessor @Inject constructor(
    private val database: MusicDatabase,
    private val dislikeManager: DislikeManager,
) {

    data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationSec: Int = -1,
        val originalIndex: Int = 0,
        val rawItem: MediaItem? = null,
    )

    /**
     * Filters, deduplicates, and ranks recommendation candidates deterministically.
     *
     * @param candidates The raw list of recommended MediaItems (from YouTube / Radio / Automix)
     * @param currentMediaId The ID of the currently playing song to exclude
     * @param existingQueueIds Set of IDs already in the upcoming queue to exclude
     * @param targetCount Desired number of items (default 25)
     * @param cooldownWindow Number of recent tracks from playback history to check (default 40)
     */
    suspend fun process(
        candidates: List<MediaItem>,
        currentMediaId: String? = null,
        existingQueueIds: Set<String> = emptySet(),
        targetCount: Int = 25,
        cooldownWindow: Int = 40,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        if (candidates.isEmpty()) return@withContext emptyList()

        val parsedCandidates = candidates.mapIndexed { index, mediaItem ->
            val meta = mediaItem.metadata
            Candidate(
                id = mediaItem.mediaId,
                title = meta?.title.orEmpty().ifBlank { mediaItem.mediaMetadata.title?.toString().orEmpty() },
                artist = meta?.artists?.joinToString(" ") { it.name }.orEmpty()
                    .ifBlank { mediaItem.mediaMetadata.artist?.toString().orEmpty() },
                album = meta?.album?.title ?: mediaItem.mediaMetadata.albumTitle?.toString(),
                durationSec = meta?.duration ?: -1,
                originalIndex = index,
                rawItem = mediaItem,
            )
        }

        val dislikedList = database.getDislikedSongs()
        val recentPlayedIds = try {
            database.getRecentEventSongIds(cooldownWindow).toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val processedCandidates = filterAndRank(
            candidates = parsedCandidates,
            currentMediaId = currentMediaId,
            existingQueueIds = existingQueueIds,
            dislikedList = dislikedList,
            recentPlayedIds = recentPlayedIds,
            targetCount = targetCount,
        )

        processedCandidates.mapNotNull { it.rawItem }
    }

    companion object {
        /**
         * Pure deterministic filter and rank algorithm, easily testable in unit tests.
         */
        fun filterAndRank(
            candidates: List<Candidate>,
            currentMediaId: String?,
            existingQueueIds: Set<String>,
            dislikedList: List<DislikedSongEntity>,
            recentPlayedIds: Set<String>,
            targetCount: Int = 25,
        ): List<Candidate> {
            if (candidates.isEmpty()) return emptyList()

            // 1. Strict Exclusions:
            // - Currently playing ID
            // - Already in upcoming queue
            // - Disliked by user (matching exact ID or cross-provider track identity)
            val nonDisliked = candidates.filter { cand ->
                if (currentMediaId != null && cand.id == currentMediaId) return@filter false
                if (cand.id in existingQueueIds) return@filter false
                val isDisliked = DislikeManager.matchesAnyDisliked(
                    candId = cand.id,
                    candTitle = cand.title,
                    candArtist = cand.artist,
                    candDurationSec = cand.durationSec,
                    dislikedList = dislikedList,
                )
                !isDisliked
            }

            if (nonDisliked.isEmpty()) return emptyList()

            // 2. Cross-provider deduplication within the candidate list
            val deduplicated = mutableListOf<Candidate>()
            for (cand in nonDisliked) {
                val isDuplicate = deduplicated.any { existing ->
                    isEquivalentTrack(existing, cand)
                }
                if (!isDuplicate) {
                    deduplicated.add(cand)
                }
            }

            // Check if this is an explicit Artist Radio:
            // If >= 70% of candidates have the same primary artist, relax artist constraint
            val primaryArtists = deduplicated.map { TrackMatcher.primaryArtist(it.artist).lowercase().trim() }
            val mostCommonArtistCount = primaryArtists.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
            val isArtistRadioMode = deduplicated.isNotEmpty() && (mostCommonArtistCount.toFloat() / deduplicated.size >= 0.70f)

            // 3. Progressive Selection Strategy:
            // Partition into fresh candidates (not recently played) and recent cooldown candidates.
            // Fresh candidates are always prioritized first. Only if the fresh candidate pool
            // cannot satisfy targetCount do we progressively expand to include recent candidates.
            // Disliked tracks remain 100% strictly excluded (filtered out above).
            val freshPool = deduplicated.filter { it.id !in recentPlayedIds }
            val recentPool = deduplicated.filter { it.id in recentPlayedIds }

            val result = mutableListOf<Candidate>()

            // Step 3a: Select up to targetCount from the fresh pool
            val freshSelected = orderWithDiversity(
                pool = freshPool,
                isArtistRadioMode = isArtistRadioMode,
                targetCount = targetCount,
            )
            result.addAll(freshSelected)

            // Step 3b: If targetCount is not met, progressively expand to recent candidates
            if (result.size < targetCount && recentPool.isNotEmpty()) {
                val needed = targetCount - result.size
                val recentSelected = orderWithDiversity(
                    pool = recentPool,
                    isArtistRadioMode = isArtistRadioMode,
                    targetCount = needed,
                    initialLastArtist = result.lastOrNull()?.let { TrackMatcher.primaryArtist(it.artist).lowercase().trim() },
                    initialLastAlbum = result.lastOrNull()?.album?.lowercase()?.trim(),
                    initialWindowArtists = result.takeLast(5).map { TrackMatcher.primaryArtist(it.artist).lowercase().trim() },
                )
                result.addAll(recentSelected)
            }

            return result
        }

        /**
         * Checks whether two tracks are equivalent (even if from different providers).
         */
        fun isEquivalentTrack(a: Candidate, b: Candidate): Boolean {
            if (a.id == b.id) return true
            val cleanA = TrackMatcher.cleanTitle(a.title, a.artist).lowercase().trim()
            val cleanB = TrackMatcher.cleanTitle(b.title, b.artist).lowercase().trim()
            val artistA = TrackMatcher.primaryArtist(a.artist).lowercase().trim()
            val artistB = TrackMatcher.primaryArtist(b.artist).lowercase().trim()

            if (cleanA.isEmpty() || cleanB.isEmpty()) return false

            val titlesMatch = cleanA == cleanB || cleanA.startsWith(cleanB) || cleanB.startsWith(cleanA)
            val artistsMatch = artistA.isEmpty() || artistB.isEmpty() || artistA == artistB ||
                    artistA.contains(artistB) || artistB.contains(artistA)

            if (titlesMatch && artistsMatch) {
                // If duration is present for both, check 15 second tolerance
                if (a.durationSec > 0 && b.durationSec > 0) {
                    if (abs(a.durationSec - b.durationSec) > 15) return false
                }
                // Check version keyword mismatch
                val target = TrackMatcher.Target(title = a.title, artist = a.artist, durationSec = a.durationSec)
                val score = TrackMatcher.matchScore(
                    candidateTitle = b.title,
                    candidateArtist = b.artist,
                    candidateDurationSec = if (b.durationSec > 0) b.durationSec else null,
                    target = target,
                )
                return score >= 0
            }
            return false
        }

        /**
         * Arranges candidates so consecutive tracks avoid identical primary artists and albums,
         * unless in artist radio mode.
         */
        private fun orderWithDiversity(
            pool: List<Candidate>,
            isArtistRadioMode: Boolean,
            targetCount: Int,
            initialLastArtist: String? = null,
            initialLastAlbum: String? = null,
            initialWindowArtists: List<String> = emptyList(),
        ): List<Candidate> {
            val remaining = pool.toMutableList()
            val result = mutableListOf<Candidate>()

            var lastArtist: String? = initialLastArtist
            var lastAlbum: String? = initialLastAlbum
            val artistCountsInWindow = initialWindowArtists.groupingBy { it }.eachCount().toMutableMap()
            val slidingWindow = initialWindowArtists.toMutableList()

            while (remaining.isNotEmpty() && result.size < targetCount) {
                var bestIndex = -1
                var bestPenalty = Int.MAX_VALUE

                for (i in remaining.indices) {
                    val cand = remaining[i]
                    val primaryArtist = TrackMatcher.primaryArtist(cand.artist).lowercase().trim()
                    val album = cand.album?.lowercase()?.trim()

                    var penalty = cand.originalIndex // Base relevance score

                    if (!isArtistRadioMode) {
                        // Consecutive artist penalty
                        if (primaryArtist.isNotEmpty() && primaryArtist == lastArtist) {
                            penalty += 1000
                        }
                        // Clustered artist penalty in recent 5 selections
                        val windowCount = artistCountsInWindow[primaryArtist] ?: 0
                        if (windowCount >= 2) {
                            penalty += 400 * windowCount
                        }
                    }

                    // Consecutive album penalty
                    if (!album.isNullOrBlank() && album == lastAlbum) {
                        penalty += 200
                    }

                    if (penalty < bestPenalty) {
                        bestPenalty = penalty
                        bestIndex = i
                    }
                }

                if (bestIndex == -1) {
                    bestIndex = 0
                }

                val chosen = remaining.removeAt(bestIndex)
                result.add(chosen)

                val chosenArtist = TrackMatcher.primaryArtist(chosen.artist).lowercase().trim()
                lastArtist = chosenArtist
                lastAlbum = chosen.album?.lowercase()?.trim()

                slidingWindow.add(chosenArtist)
                artistCountsInWindow[chosenArtist] = (artistCountsInWindow[chosenArtist] ?: 0) + 1
                if (slidingWindow.size > 5) {
                    val oldArtist = slidingWindow.removeAt(0)
                    val count = artistCountsInWindow[oldArtist] ?: 1
                    if (count <= 1) artistCountsInWindow.remove(oldArtist) else artistCountsInWindow[oldArtist] = count - 1
                }
            }

            return result
        }
    }
}
