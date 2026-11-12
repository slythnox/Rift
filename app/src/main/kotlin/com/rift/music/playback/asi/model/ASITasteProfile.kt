/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.model

import com.rift.music.playback.asi.feedback.NegativeSignalJournal
import com.rift.music.playback.resolver.addon.TrackMatcher
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import javax.inject.Inject
import javax.inject.Singleton

data class HistoricalListenEvent(
    val artist: String,
    val timestampEpochMs: Long,
    val playTimeMs: Long,
    val isCompletion: Boolean,
    val isLiked: Boolean = false,
)

/**
 * Local taste model calculating decayed artist affinities and completion ratios
 * using reliable available signals.
 *
 * All thresholds and half-life parameters are configurable via [ASIConfig].
 */
@Singleton
class ASITasteProfile @Inject constructor(
    private val negativeSignalJournal: NegativeSignalJournal,
    private val config: ASIConfig,
) {
    constructor(negativeSignalJournal: NegativeSignalJournal) : this(negativeSignalJournal, ASIConfig())

    /**
     * Calculates time-decayed affinity for an artist given a list of historical events.
     *
     * @param artist The artist name to query
     * @param events Chronological historical events available up to the evaluation point
     * @param referenceTimeMs Current time point (defaults to now)
     * @return Decayed affinity score (higher is better, <= 0 means net negative or unheard)
     */
    fun calculateArtistAffinity(
        artist: String,
        events: List<HistoricalListenEvent>,
        referenceTimeMs: Long = System.currentTimeMillis(),
    ): Float {
        val primaryTarget = TrackMatcher.primaryArtist(artist).lowercase().trim()
        if (primaryTarget.isBlank()) return 0f

        val halfLifeMs = config.decayHalfLifeDays * 24f * 60f * 60f * 1000f
        var totalAffinity = 0.0

        for (event in events) {
            val eventArtist = TrackMatcher.primaryArtist(event.artist).lowercase().trim()
            if (eventArtist != primaryTarget) continue

            val ageMs = (referenceTimeMs - event.timestampEpochMs).coerceAtLeast(0L)
            val decay = (2.0).pow(-ageMs.toDouble() / halfLifeMs.toDouble())

            val baseSignal = when {
                event.isLiked -> 2.5
                event.isCompletion -> 1.0
                else -> 0.2
            }
            totalAffinity += baseSignal * decay
        }

        return totalAffinity.toFloat()
    }

    /**
     * Calculates the historical completion ratio for an artist:
     * (Completed tracks) / (Total attempted tracks)
     */
    fun calculateCompletionRatio(
        artist: String,
        events: List<HistoricalListenEvent>,
    ): Float? {
        val primaryTarget = TrackMatcher.primaryArtist(artist).lowercase().trim()
        if (primaryTarget.isBlank()) return null

        val artistEvents = events.filter {
            TrackMatcher.primaryArtist(it.artist).lowercase().trim() == primaryTarget
        }

        if (artistEvents.isEmpty()) return null

        val completions = artistEvents.count { it.isCompletion }
        return completions.toFloat() / artistEvents.size.toFloat()
    }
}
