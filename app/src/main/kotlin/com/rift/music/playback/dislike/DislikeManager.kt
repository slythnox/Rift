/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.dislike

import com.rift.music.db.MusicDatabase
import com.rift.music.db.entities.DislikedSongEntity
import com.rift.music.models.MediaMetadata
import com.rift.music.playback.resolver.addon.TrackMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class DislikeManager @Inject constructor(
    private val database: MusicDatabase,
) {
    val dislikedSongs: Flow<List<DislikedSongEntity>> = database.dislikedSongs()

    suspend fun dislike(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        duration: Int = -1,
        thumbnailUrl: String? = null,
    ): DislikedSongEntity = withContext(Dispatchers.IO) {
        val normTitle = TrackMatcher.cleanTitle(title, artist).lowercase().trim()
        val normArtist = TrackMatcher.primaryArtist(artist).lowercase().trim()

        val entity = DislikedSongEntity(
            id = id,
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            normalizedTitle = normTitle,
            normalizedArtist = normArtist,
            dislikedAt = LocalDateTime.now(),
        )
        database.insert(entity)
        entity
    }

    suspend fun removeDislike(id: String) = withContext(Dispatchers.IO) {
        database.deleteDislikedSong(id)
    }

    suspend fun removeDislike(entity: DislikedSongEntity) = withContext(Dispatchers.IO) {
        database.delete(entity)
    }

    fun isDislikedFlow(id: String): Flow<Boolean> = database.isSongDisliked(id)

    suspend fun isDisliked(
        id: String,
        title: String? = null,
        artist: String? = null,
        durationSec: Int = -1,
    ): Boolean = withContext(Dispatchers.IO) {
        // Fast path 1: check exact ID in DB
        if (database.isSongDislikedSync(id)) {
            return@withContext true
        }

        // If title or artist missing, exact ID check is all we can do
        if (title.isNullOrBlank()) {
            return@withContext false
        }

        val allDisliked = database.getDislikedSongs()
        if (allDisliked.isEmpty()) {
            return@withContext false
        }

        matchesAnyDisliked(
            candId = id,
            candTitle = title,
            candArtist = artist.orEmpty(),
            candDurationSec = durationSec,
            dislikedList = allDisliked,
        )
    }

    suspend fun isDisliked(metadata: MediaMetadata): Boolean =
        isDisliked(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(" ") { it.name },
            durationSec = metadata.duration,
        )

    companion object {
        /**
         * Cross-provider matching: checks if a candidate matches any disliked entry using:
         * 1. Exact ID match
         * 2. Normalized title + primary artist match
         * 3. Version keyword mismatch guard (e.g. remix vs acoustic vs original)
         * 4. Duration tolerance check (within 15 seconds)
         */
        fun matchesAnyDisliked(
            candId: String,
            candTitle: String,
            candArtist: String,
            candDurationSec: Int,
            dislikedList: List<DislikedSongEntity>,
        ): Boolean {
            val candCleanTitle = TrackMatcher.cleanTitle(candTitle, candArtist).lowercase().trim()
            val candPrimaryArtist = TrackMatcher.primaryArtist(candArtist).lowercase().trim()

            for (disliked in dislikedList) {
                if (disliked.id == candId) {
                    return true
                }

                // If primary artists differ significantly, not a match
                val disArtist = disliked.normalizedArtist
                if (candPrimaryArtist.isNotEmpty() && disArtist.isNotEmpty()) {
                    val artistsMatch = candPrimaryArtist == disArtist ||
                            candPrimaryArtist.contains(disArtist) ||
                            disArtist.contains(candPrimaryArtist)
                    if (!artistsMatch) continue
                }

                // Title check
                val disTitle = disliked.normalizedTitle
                if (candCleanTitle.isNotEmpty() && disTitle.isNotEmpty()) {
                    val titlesMatch = candCleanTitle == disTitle ||
                            candCleanTitle.startsWith(disTitle) ||
                            disTitle.startsWith(candCleanTitle)
                    if (!titlesMatch) continue

                    // Target matcher version keyword check
                    val target = TrackMatcher.Target(
                        title = disliked.title,
                        artist = disliked.artist,
                        durationSec = if (disliked.duration > 0) disliked.duration else null,
                    )
                    val score = TrackMatcher.matchScore(
                        candidateTitle = candTitle,
                        candidateArtist = candArtist,
                        candidateDurationSec = if (candDurationSec > 0) candDurationSec else null,
                        target = target,
                    )
                    if (score >= 0) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
