/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.model

import com.rift.music.playback.resolver.addon.TrackMatcher
import kotlin.math.abs

/**
 * Stable, provider-agnostic track identity for cross-provider matching, negative signals,
 * and duplicate detection.
 */
data class CanonicalTrackId(
    val normalizedTitle: String,
    val normalizedPrimaryArtist: String,
    val durationSec: Int = -1,
) {
    /**
     * Compact delimiter-separated representation suitable for bounded string storage.
     */
    fun toStorageKey(): String = "$normalizedTitle||$normalizedPrimaryArtist"

    /**
     * Checks if another canonical track matches this one with duration tolerance.
     */
    fun matches(other: CanonicalTrackId, durationToleranceSec: Int = 15): Boolean {
        if (normalizedTitle != other.normalizedTitle) return false
        if (normalizedPrimaryArtist.isNotBlank() && other.normalizedPrimaryArtist.isNotBlank()) {
            if (normalizedPrimaryArtist != other.normalizedPrimaryArtist) return false
        }
        if (durationSec > 0 && other.durationSec > 0) {
            if (abs(durationSec - other.durationSec) > durationToleranceSec) return false
        }
        return true
    }

    companion object {
        fun from(title: String, artist: String, durationSec: Int = -1): CanonicalTrackId {
            val cleanTitle = TrackMatcher.cleanTitle(title, artist).lowercase().trim()
            val rawArtist = artist.replace(Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE), "").trim()
            val primaryArtist = TrackMatcher.primaryArtist(rawArtist).lowercase().trim()
            return CanonicalTrackId(
                normalizedTitle = cleanTitle,
                normalizedPrimaryArtist = primaryArtist,
                durationSec = durationSec,
            )
        }

        fun fromStorageKey(key: String, durationSec: Int = -1): CanonicalTrackId {
            val parts = key.split("||", limit = 2)
            return CanonicalTrackId(
                normalizedTitle = parts.getOrNull(0).orEmpty(),
                normalizedPrimaryArtist = parts.getOrNull(1).orEmpty(),
                durationSec = durationSec,
            )
        }
    }
}
