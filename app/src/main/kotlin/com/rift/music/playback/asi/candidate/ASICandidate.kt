/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.candidate

import androidx.media3.common.MediaItem
import com.rift.music.playback.asi.model.CanonicalTrackId

enum class CandidateSource {
    YTM_RADIO,
    ARTIST_DISCOVERY,
    LOCAL_FAVORITE,
    LOCAL_HISTORY,
}

/**
 * Unified candidate contract containing all acoustic, provenance, and playback metadata
 * necessary to evaluate, score, and transition cleanly into the ExoPlayer queue.
 */
data class ASICandidate(
    val mediaId: String,
    val canonicalId: CanonicalTrackId,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSec: Int = -1,
    val artworkUri: String? = null,
    val source: CandidateSource,
    val seedTrackId: String? = null,
    val seedArtist: String? = null,
    val rawMediaItem: MediaItem? = null,
    val energy: Float? = null,
    val bpm: Int? = null,
    val key: String? = null,
    val retrievalTimestampMs: Long = System.currentTimeMillis(),
)
