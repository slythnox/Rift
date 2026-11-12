/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi.model

import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class SessionTrackEntry(
    val mediaId: String,
    val title: String,
    val artist: String,
    val energyLevel: Float? = null,
    val tempoBpm: Int? = null,
    val scriptCluster: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * In-memory model representing the active listening session.
 *
 * Implements:
 * 1. Rolling window of recent tracks with gradual adaptation on manual selection.
 * 2. Inferred [SessionContextSnapshot] (party, workout, relaxed, focus, etc.) with confidence.
 * 3. Skip friction and completion monitoring.
 * 4. 30-minute inactivity cold reset.
 */
@Singleton
class ASISessionProfile @Inject constructor(
    private val config: ASIConfig,
) {
    constructor() : this(ASIConfig())

    private val recentTracks = ArrayDeque<SessionTrackEntry>()

    var consecutiveSkips: Int = 0
        private set

    var lastActivityTimeMs: Long = System.currentTimeMillis()
        private set

    @Synchronized
    fun onTrackCompleted(entry: SessionTrackEntry) {
        lastActivityTimeMs = System.currentTimeMillis()
        consecutiveSkips = max(0, consecutiveSkips - 1)
        addTrack(entry)
    }

    @Synchronized
    fun onExplicitSkip(entry: SessionTrackEntry) {
        lastActivityTimeMs = System.currentTimeMillis()
        consecutiveSkips++
        addTrack(entry)
    }

    /**
     * When the user manually selects another track, session context adapts gradually
     * rather than resetting completely.
     */
    @Synchronized
    fun onManualTrackSelection(newEntry: SessionTrackEntry) {
        val now = System.currentTimeMillis()
        if (now - lastActivityTimeMs > config.sessionInactivityResetMs) {
            recentTracks.clear()
        } else {
            val retainCount = ((config.sessionWindowSize * (1f - config.manualSelectionAdaptationWeight)).toInt()).coerceAtLeast(1)
            while (recentTracks.size > retainCount) {
                recentTracks.removeFirst()
            }
        }
        lastActivityTimeMs = now
        consecutiveSkips = 0 // User took intentional control, clear skip friction
        addTrack(newEntry)
    }

    @Synchronized
    private fun addTrack(entry: SessionTrackEntry) {
        if (recentTracks.size >= config.sessionWindowSize) {
            recentTracks.removeFirst()
        }
        recentTracks.addLast(entry)
    }

    @Synchronized
    fun getRecentArtists(): List<String> = recentTracks.map { it.artist }.distinct()

    @Synchronized
    fun getRecentTracks(): List<SessionTrackEntry> = recentTracks.toList()

    /**
     * Infers the active session context based on recent tracks, energy, and user feedback.
     */
    @Synchronized
    fun inferSessionContext(): SessionContextSnapshot {
        val now = System.currentTimeMillis()
        if (now - lastActivityTimeMs > config.sessionInactivityResetMs) {
            recentTracks.clear()
            consecutiveSkips = 0
            return SessionContextSnapshot(SessionCategory.GENERAL, confidence = 0.5f, targetEnergy = 0.5f)
        }

        if (recentTracks.isEmpty()) {
            return SessionContextSnapshot(SessionCategory.GENERAL, confidence = 0.5f, targetEnergy = 0.5f)
        }

        val tracks = recentTracks.toList()
        val knownEnergies = tracks.mapNotNull { it.energyLevel }
        val avgEnergy = if (knownEnergies.isNotEmpty()) knownEnergies.average().toFloat() else 0.5f

        // Keyword analysis across recent track titles
        val textCorpus = tracks.joinToString(" ") { "${it.title} ${it.artist}" }.lowercase()

        val (category, confidence) = when {
            textCorpus.containsAny("party", "club", "dance", "remix", "bass", "banger") ->
                SessionCategory.PARTY to 0.85f
            textCorpus.containsAny("workout", "gym", "running", "pump", "training", "fitness") ->
                SessionCategory.WORKOUT to 0.80f
            textCorpus.containsAny("chill", "relax", "ambient", "sleep", "acoustic", "piano", "meditation") ->
                SessionCategory.RELAXED to 0.85f
            textCorpus.containsAny("romantic", "love", "valentine", "heart", "slowed", "r&b") ->
                SessionCategory.ROMANTIC to 0.75f
            textCorpus.containsAny("focus", "study", "lofi", "lo-fi", "coding", "reading", "instrumental") ->
                SessionCategory.FOCUS to 0.85f
            avgEnergy > 0.75f ->
                SessionCategory.PARTY to 0.65f
            avgEnergy < 0.35f ->
                SessionCategory.RELAXED to 0.65f
            else ->
                SessionCategory.GENERAL to 0.50f
        }

        // Discovery tolerance increases when user is actively completing tracks without skips
        val discoveryTol = if (consecutiveSkips == 0 && tracks.size >= 3) 0.5f else 0.25f

        return SessionContextSnapshot(
            category = category,
            confidence = confidence,
            targetEnergy = avgEnergy,
            discoveryTolerance = discoveryTol,
            timestampMs = now,
        )
    }

    private fun String.containsAny(vararg words: String): Boolean =
        words.any { this.contains(it) }

    @Synchronized
    fun clear() {
        recentTracks.clear()
        consecutiveSkips = 0
        lastActivityTimeMs = System.currentTimeMillis()
    }
}
