/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.candidate

import androidx.media3.common.MediaItem
import com.rift.music.core.extensions.metadata
import com.rift.music.core.extensions.toMediaItem
import com.rift.music.db.MusicDatabase
import com.rift.music.db.entities.SongAnalysisEntity
import com.rift.music.playback.asi.feedback.NegativeSignalJournal
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.CanonicalTrackId
import com.rift.music.playback.dislike.DislikeManager
import com.rift.music.playback.queue.YouTubeQueue
import com.rift.music.playback.resolver.addon.TrackMatcher
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified multi-source candidate generator for Project ASI 1.0.
 *
 * Gathers, hard-filters, and deduplicates recommendation candidates from:
 * 1. YouTube Music related/radio streams (`RDAMVM`).
 * 2. Artist discovery endpoints (`YouTube.related`).
 * 3. Local library favorites and listening history.
 *
 * Guarantees:
 * - Structured concurrency with timeout protection.
 * - Independent source failure isolation.
 * - Strict hard exclusions (current track, upcoming queue, dislikes, negative skip signals).
 * - Preservation of playback-ready [MediaItem] objects for authoritative player queueing.
 */
@Singleton
open class ASICandidateGenerator @Inject constructor(
    private val database: MusicDatabase?,
    private val negativeSignalJournal: NegativeSignalJournal,
    private val config: ASIConfig,
) {
    constructor(
        negativeSignalJournal: NegativeSignalJournal,
        config: ASIConfig = ASIConfig(),
    ) : this(null, negativeSignalJournal, config)

    constructor(
        database: MusicDatabase?,
        negativeSignalJournal: NegativeSignalJournal,
    ) : this(database, negativeSignalJournal, ASIConfig())

    /**
     * Generates a unified pool of playable candidates for the given seed track.
     */
    open suspend fun generateCandidates(
        seedMediaItem: MediaItem,
        existingQueueIds: Set<String> = emptySet(),
        failedSongIds: Set<String> = emptySet(),
        initialRadioItems: List<MediaItem> = emptyList(),
        targetCount: Int = 40,
    ): List<ASICandidate> = withContext(Dispatchers.IO) {
        val seedMediaId = seedMediaItem.mediaId
        val seedMeta = seedMediaItem.metadata
        val seedTitle = seedMeta?.title.orEmpty().ifBlank { seedMediaItem.mediaMetadata.title?.toString().orEmpty() }
        val seedArtist = seedMeta?.artists?.joinToString(" ") { it.name }.orEmpty()
            .ifBlank { seedMediaItem.mediaMetadata.artist?.toString().orEmpty() }

        val dislikedList = try {
            database?.getDislikedSongs().orEmpty()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }

        val rawPool = coroutineScope {
            // Source 1: YouTube Music Radio / Watch Queue (reuses initialRadioItems if already fetched)
            val ytmDeferred = async {
                fetchYtmCandidates(seedMediaId, seedArtist, initialRadioItems)
            }

            // Source 2: Artist Discovery / Related Tracks
            val artistDeferred = async {
                fetchArtistDiscoveryCandidates(seedMediaId, seedArtist)
            }

            // Source 3: Local Library Favorites
            val localDeferred = async {
                fetchLocalCandidates(seedArtist)
            }

            // Wait for all with bounded timeout
            val ytmCandidates = withTimeoutOrNull(config.candidateTimeoutMs) { ytmDeferred.await() } ?: emptyList()
            val artistCandidates = withTimeoutOrNull(config.candidateTimeoutMs) { artistDeferred.await() } ?: emptyList()
            val localCandidates = withTimeoutOrNull(config.candidateTimeoutMs) { localDeferred.await() } ?: emptyList()

            // Merge pools with balanced provenance
            val pool = mutableListOf<ASICandidate>()
            pool.addAll(ytmCandidates)
            pool.addAll(artistCandidates)
            pool.addAll(localCandidates)
            pool
        }

        // Apply hard exclusions and deduplication
        val deduplicated = filterAndDeduplicate(
            rawCandidates = rawPool,
            seedMediaId = seedMediaId,
            existingQueueIds = existingQueueIds,
            failedSongIds = failedSongIds,
            dislikedList = dislikedList,
            targetCount = targetCount,
        )

        // Enrich candidates with cached SongAnalysisEntity (BPM, key, energy) if available in database
        enrichCandidatesWithAnalysis(deduplicated)
    }

    private suspend fun fetchYtmCandidates(
        seedMediaId: String,
        seedArtist: String,
        initialRadioItems: List<MediaItem>,
    ): List<ASICandidate> {
        // Reuse pre-fetched items if provided to avoid duplicate network calls
        if (initialRadioItems.isNotEmpty()) {
            return initialRadioItems.mapNotNull { mediaItem: MediaItem ->
                toCandidate(mediaItem, CandidateSource.YTM_RADIO, seedMediaId, seedArtist)
            }
        }
        if (seedMediaId.isBlank()) return emptyList()
        return try {
            val queue = YouTubeQueue(
                endpoint = WatchEndpoint(
                    videoId = seedMediaId,
                    playlistId = "RDAMVM$seedMediaId",
                )
            )
            val status = queue.getInitialStatus()
            status.items.mapNotNull { mediaItem: MediaItem ->
                toCandidate(mediaItem, CandidateSource.YTM_RADIO, seedMediaId, seedArtist)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("ASICandidateGen").w(e, "Failed to fetch YTM radio candidates")
            emptyList()
        }
    }

    private suspend fun fetchArtistDiscoveryCandidates(seedMediaId: String, seedArtist: String): List<ASICandidate> {
        if (seedMediaId.isBlank()) return emptyList()
        return try {
            val nextResult = YouTube.next(WatchEndpoint(videoId = seedMediaId)).getOrNull()
            val relatedEndpoint = nextResult?.relatedEndpoint ?: return emptyList()
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return emptyList()
            relatedPage.songs.mapNotNull { songItem ->
                val mediaItem = songItem.toMediaItem()
                toCandidate(mediaItem, CandidateSource.ARTIST_DISCOVERY, seedMediaId, seedArtist)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("ASICandidateGen").w(e, "Failed to fetch artist discovery candidates")
            emptyList()
        }
    }

    private suspend fun fetchLocalCandidates(seedArtist: String): List<ASICandidate> {
        if (database == null) return emptyList()
        return try {
            val liked = database.likedSongsByCreateDateAsc().firstOrNull().orEmpty()
            val primarySeed = TrackMatcher.primaryArtist(seedArtist).lowercase().trim()

            // Select up to 10 local favorites, prioritizing same or related artists
            val prioritized = liked.sortedByDescending { song ->
                val candArtist = TrackMatcher.primaryArtist(song.artists.joinToString(" ") { it.name }).lowercase().trim()
                if (primarySeed.isNotBlank() && candArtist == primarySeed) 2 else 1
            }.take(10)

            prioritized.mapNotNull { song ->
                val mediaItem = song.toMediaItem()
                toCandidate(mediaItem, CandidateSource.LOCAL_FAVORITE, null, seedArtist)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("ASICandidateGen").w(e, "Failed to fetch local candidates")
            emptyList()
        }
    }

    private fun toCandidate(
        mediaItem: MediaItem,
        source: CandidateSource,
        seedTrackId: String?,
        seedArtist: String?,
    ): ASICandidate? {
        val mediaId = mediaItem.mediaId.ifBlank { return null }
        val meta = mediaItem.metadata
        val title = meta?.title.orEmpty().ifBlank { mediaItem.mediaMetadata.title?.toString().orEmpty() }
        if (title.isBlank()) return null
        val artist = meta?.artists?.joinToString(" ") { it.name }.orEmpty()
            .ifBlank { mediaItem.mediaMetadata.artist?.toString().orEmpty() }
        val album = meta?.album?.title ?: mediaItem.mediaMetadata.albumTitle?.toString()
        val durationSec = meta?.duration ?: -1
        val artworkUri = mediaItem.mediaMetadata.artworkUri?.toString()
        val canonical = CanonicalTrackId.from(title, artist, durationSec)

        return ASICandidate(
            mediaId = mediaId,
            canonicalId = canonical,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            artworkUri = artworkUri,
            source = source,
            seedTrackId = seedTrackId,
            seedArtist = seedArtist,
            rawMediaItem = mediaItem,
        )
    }

    /**
     * Pure filtering and deduplication logic:
     * Enforces hard exclusions and cross-provider deduplication.
     */
    fun filterAndDeduplicate(
        rawCandidates: List<ASICandidate>,
        seedMediaId: String?,
        existingQueueIds: Set<String>,
        failedSongIds: Set<String>,
        dislikedList: List<com.rift.music.db.entities.DislikedSongEntity>,
        targetCount: Int = 40,
    ): List<ASICandidate> {
        val deduplicated = mutableListOf<ASICandidate>()

        for (cand in rawCandidates) {
            // 1. Current track exclusion
            if (seedMediaId != null && cand.mediaId == seedMediaId) continue

            // 2. Upcoming queue exclusion
            if (cand.mediaId in existingQueueIds) continue

            // 3. Failed song exclusion
            if (cand.mediaId in failedSongIds) continue

            // 4. Disliked song exclusion (cross-provider aware)
            val isDisliked = DislikeManager.matchesAnyDisliked(
                candId = cand.mediaId,
                candTitle = cand.title,
                candArtist = cand.artist,
                candDurationSec = cand.durationSec,
                dislikedList = dislikedList,
            )
            if (isDisliked) continue

            // 5. Persistent negative skip journal exclusion
            if (negativeSignalJournal.isNegativeSignal(cand.canonicalId, cand.mediaId)) continue

            // 6. Cross-provider deduplication within the pool
            val isDuplicate = deduplicated.any { existing ->
                existing.canonicalId.matches(cand.canonicalId)
            }
            if (!isDuplicate) {
                deduplicated.add(cand)
            }

            if (deduplicated.size >= targetCount) break
        }

        return deduplicated
    }

    private suspend fun enrichCandidatesWithAnalysis(candidates: List<ASICandidate>): List<ASICandidate> {
        if (database == null || candidates.isEmpty()) return candidates
        return try {
            val candidateIds = candidates.map { it.mediaId }
            val analyses = database.songAnalyses(candidateIds, SongAnalysisEntity.CURRENT_ANALYSIS_VERSION)
                .associateBy { it.id }
            if (analyses.isEmpty()) return candidates

            candidates.map { cand ->
                val analysis = analyses[cand.mediaId] ?: return@map cand
                val energy = if (analysis.energyProfile.isNotEmpty()) {
                    val bytes = analysis.energyProfile
                    val sum = bytes.fold(0L) { acc, b -> acc + (b.toInt() and 0xFF) }
                    (sum.toDouble() / (bytes.size * 255.0)).toFloat().coerceIn(0f, 1f)
                } else null

                val bpm = if (analysis.bpmConfidence >= 0.40f && analysis.bpm > 0f) {
                    analysis.bpm.roundToInt()
                } else null

                val key = if (analysis.keyConfidence >= 0.40f && analysis.keyIndex in 0..11) {
                    pitchClassToString(analysis.keyIndex, analysis.keyIsMinor)
                } else null

                cand.copy(
                    energy = energy,
                    bpm = bpm,
                    key = key,
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            candidates
        }
    }

    companion object {
        private val PITCH_CLASSES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun pitchClassToString(keyIndex: Int, isMinor: Boolean): String {
            val note = PITCH_CLASSES.getOrNull(keyIndex) ?: return ""
            return if (isMinor) "${note}m" else note
        }
    }
}
