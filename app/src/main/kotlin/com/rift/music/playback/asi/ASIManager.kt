/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import androidx.media3.common.MediaItem
import com.rift.music.core.extensions.metadata
import com.rift.music.db.MusicDatabase
import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.candidate.ASICandidateGenerator
import com.rift.music.playback.asi.candidate.CandidateSource
import com.rift.music.playback.asi.diagnostics.ASIDiagnostics
import com.rift.music.playback.asi.diagnostics.ASIRunDiagnostic
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASISessionProfile
import com.rift.music.playback.asi.model.ASITasteProfile
import com.rift.music.playback.asi.model.CanonicalTrackId
import com.rift.music.playback.asi.model.HistoricalListenEvent
import com.rift.music.playback.asi.scoring.ASIScorer
import com.rift.music.playback.asi.sequence.ASISequenceOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central orchestrator for Project ASI 1.0 (Adaptive Session Intelligence).
 *
 * Integrates:
 * 1. Unified candidate generation across YTM, artist discovery, and local library.
 * 2. Real-time session context understanding.
 * 3. Personalized multi-dimensional scoring.
 * 4. Bounded beam-search sequence optimization.
 * 5. Bounded diagnostics and explainability.
 * 6. Non-blocking fallback to legacy recommendation logic on failure or timeout.
 */
@Singleton
class ASIManager @Inject constructor(
    private val candidateGenerator: ASICandidateGenerator,
    private val sessionProfile: ASISessionProfile,
    private val tasteProfile: ASITasteProfile,
    private val scorer: ASIScorer,
    private val sequenceOptimizer: ASISequenceOptimizer,
    private val database: MusicDatabase?,
    private val diagnostics: ASIDiagnostics,
    private val config: ASIConfig,
) {
    /**
     * Generates a session-optimized recommendation sequence for active autoplay/radio.
     *
     * Returns `null` if ASI is disabled, times out, or fails, signaling the player
     * service to execute its existing recommendation fallback transparently.
     */
    suspend fun getRecommendations(
        seedMediaItem: MediaItem,
        existingQueueIds: Set<String> = emptySet(),
        failedSongIds: Set<String> = emptySet(),
        initialRadioItems: List<MediaItem> = emptyList(),
        targetCount: Int = 15,
        cooldownWindow: Int = 40,
    ): List<MediaItem>? = withContext(Dispatchers.IO) {
        if (!config.asiEnabled) {
            Timber.tag("ASIManager").d("ASI is disabled by config; triggering fallback")
            return@withContext null
        }

        val startTime = System.currentTimeMillis()
        val requestId = "asi_${UUID.randomUUID().toString().take(8)}"
        val seedMediaId = seedMediaItem.mediaId

        val seedMeta = seedMediaItem.metadata
        val seedTitle = seedMeta?.title.orEmpty().ifBlank { seedMediaItem.mediaMetadata.title?.toString().orEmpty() }
        val seedArtist = seedMeta?.artists?.joinToString(" ") { it.name }.orEmpty()
            .ifBlank { seedMediaItem.mediaMetadata.artist?.toString().orEmpty() }
        val seedDuration = seedMeta?.duration ?: -1

        val seedCandidate = ASICandidate(
            mediaId = seedMediaId,
            canonicalId = CanonicalTrackId.from(seedTitle, seedArtist, seedDuration),
            title = seedTitle,
            artist = seedArtist,
            album = seedMeta?.album?.title ?: seedMediaItem.mediaMetadata.albumTitle?.toString(),
            durationSec = seedDuration,
            source = CandidateSource.YTM_RADIO,
            rawMediaItem = seedMediaItem,
        )

        try {
            withTimeoutOrNull(config.overallTimeoutMs) {
                // 1. Infer active session context
                val sessionContext = sessionProfile.inferSessionContext()

                // 2. Fetch candidates from multiple sources (reusing initialRadioItems if present)
                val candidates = candidateGenerator.generateCandidates(
                    seedMediaItem = seedMediaItem,
                    existingQueueIds = existingQueueIds,
                    failedSongIds = failedSongIds,
                    initialRadioItems = initialRadioItems,
                    targetCount = 40,
                )

                if (candidates.isEmpty()) {
                    diagnostics.recordRun(
                        ASIRunDiagnostic(
                            requestId = requestId,
                            timestampEpochMs = startTime,
                            seedMediaId = seedMediaId,
                            seedTitle = seedTitle,
                            seedArtist = seedArtist,
                            sessionContext = sessionContext,
                            sourceCounts = emptyMap(),
                            filteredCount = 0,
                            scoredCount = 0,
                            topScores = emptyList(),
                            selectedTrackIds = emptyList(),
                            durationMs = System.currentTimeMillis() - startTime,
                            isFallback = true,
                            fallbackReason = "Candidate generation yielded empty pool",
                        )
                    )
                    return@withTimeoutOrNull null
                }

                // 3. Reconstruct genuine historical events from Room database
                val recentEventsWithSong = try {
                    database?.recentEvents(cooldownWindow)?.firstOrNull().orEmpty()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    emptyList()
                }

                val cooldownIds = if (recentEventsWithSong.isNotEmpty()) {
                    recentEventsWithSong.map { it.event.songId }.toSet()
                } else {
                    try {
                        database?.getRecentEventSongIds(cooldownWindow)?.toSet().orEmpty()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        emptySet()
                    }
                }

                val zoneId = try { ZoneId.systemDefault() } catch (_: Exception) { ZoneId.of("UTC") }
                val historicalEvents = recentEventsWithSong.mapNotNull { ews ->
                    val songEntity = ews.song.song
                    val artistName = ews.song.artists.joinToString(" ") { it.name }
                        .ifBlank { songEntity.title }
                    if (artistName.isBlank()) return@mapNotNull null
                    val durMs = if (songEntity.duration > 0) songEntity.duration * 1000L else -1L
                    val isCompletion = if (durMs > 0) ews.event.playTime >= (durMs * 0.80f) else ews.event.playTime >= 30_000L
                    val timestampMs = try {
                        ews.event.timestamp.atZone(zoneId).toInstant().toEpochMilli()
                    } catch (_: Exception) {
                        System.currentTimeMillis()
                    }
                    HistoricalListenEvent(
                        artist = artistName,
                        timestampEpochMs = timestampMs,
                        playTimeMs = ews.event.playTime,
                        isCompletion = isCompletion,
                        isLiked = songEntity.liked,
                    )
                }

                // 4. Score all candidates
                val recentSessionTracks = sessionProfile.getRecentTracks()
                val scoredCandidates = candidates.map { candidate ->
                    scorer.scoreCandidate(
                        candidate = candidate,
                        seedTrack = seedCandidate,
                        sessionContext = sessionContext,
                        historicalEvents = historicalEvents,
                        recentSessionTracks = recentSessionTracks,
                        cooldownIds = cooldownIds,
                    )
                }

                // 5. Sequence Optimization via Beam Search
                val optimizedSequence = sequenceOptimizer.optimizeSequence(
                    seed = seedCandidate,
                    scoredCandidates = scoredCandidates,
                    targetCount = targetCount,
                )

                val duration = System.currentTimeMillis() - startTime
                val sourceCounts = candidates.groupingBy { it.source.name }.eachCount()

                // Acoustic coverage metrics for bounded diagnostics (no personal history logged)
                val candidateIds = candidates.map { it.mediaId }
                val matchedLocalSongs = try {
                    if (database != null && candidateIds.isNotEmpty()) {
                        database.getSongsByIds(candidateIds).size
                    } else {
                        candidates.count { it.source == CandidateSource.LOCAL_FAVORITE || it.source == CandidateSource.LOCAL_HISTORY }
                    }
                } catch (_: Exception) {
                    candidates.count { it.source == CandidateSource.LOCAL_FAVORITE || it.source == CandidateSource.LOCAL_HISTORY }
                }
                val cachedBpmCount = candidates.count { it.bpm != null }
                val cachedEnergyCount = candidates.count { it.energy != null }
                val cachedKeyCount = candidates.count { it.key != null }
                val noAcousticDataCount = candidates.count { it.bpm == null && it.energy == null && it.key == null }

                // Record explainability diagnostics
                diagnostics.recordRun(
                    ASIRunDiagnostic(
                        requestId = requestId,
                        timestampEpochMs = startTime,
                        seedMediaId = seedMediaId,
                        seedTitle = seedTitle,
                        seedArtist = seedArtist,
                        sessionContext = sessionContext,
                        sourceCounts = sourceCounts,
                        filteredCount = candidates.size,
                        scoredCount = scoredCandidates.size,
                        topScores = scoredCandidates.sortedByDescending { it.score }.take(5).map {
                            it.candidate.title to it.score
                        },
                        selectedTrackIds = optimizedSequence.map { it.mediaId },
                        durationMs = duration,
                        isFallback = false,
                        totalCandidates = candidates.size,
                        candidatesMatchedToLocalSongs = matchedLocalSongs,
                        candidatesWithCachedBpm = cachedBpmCount,
                        candidatesWithCachedEnergy = cachedEnergyCount,
                        candidatesWithCachedKey = cachedKeyCount,
                        candidatesWithNoAcousticData = noAcousticDataCount,
                    )
                )

                optimizedSequence.mapNotNull { it.rawMediaItem }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag("ASIManager").e(e, "ASI recommendation cycle failed; falling back to legacy pipeline")
            diagnostics.recordRun(
                ASIRunDiagnostic(
                    requestId = requestId,
                    timestampEpochMs = startTime,
                    seedMediaId = seedMediaId,
                    seedTitle = seedTitle,
                    seedArtist = seedArtist,
                    sessionContext = sessionProfile.inferSessionContext(),
                    sourceCounts = emptyMap(),
                    filteredCount = 0,
                    scoredCount = 0,
                    topScores = emptyList(),
                    selectedTrackIds = emptyList(),
                    durationMs = System.currentTimeMillis() - startTime,
                    isFallback = true,
                    fallbackReason = "Exception: ${e.message}",
                )
            )
            null
        }
    }
}
