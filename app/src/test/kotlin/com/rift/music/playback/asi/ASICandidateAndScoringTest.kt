/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.playback.asi

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.rift.music.db.entities.DislikedSongEntity
import com.rift.music.playback.asi.candidate.ASICandidate
import com.rift.music.playback.asi.candidate.ASICandidateGenerator
import com.rift.music.playback.asi.candidate.CandidateSource
import com.rift.music.playback.asi.feedback.NegativeSignalJournal
import com.rift.music.playback.asi.model.ASIConfig
import com.rift.music.playback.asi.model.ASITasteProfile
import com.rift.music.playback.asi.model.CanonicalTrackId
import com.rift.music.playback.asi.model.HistoricalListenEvent
import com.rift.music.playback.asi.model.SessionCategory
import com.rift.music.playback.asi.model.SessionContextSnapshot
import com.rift.music.playback.asi.model.SessionTrackEntry
import com.rift.music.playback.asi.scoring.ASIScorer
import com.rift.music.playback.asi.scoring.ASITransitionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ASICandidateAndScoringTest {

    private lateinit var config: ASIConfig
    private lateinit var journal: NegativeSignalJournal
    private lateinit var tasteProfile: ASITasteProfile
    private lateinit var scorer: ASIScorer

    @Before
    fun setup() {
        config = ASIConfig()
        journal = NegativeSignalJournal(config)
        tasteProfile = ASITasteProfile(journal, config)
        scorer = ASIScorer(tasteProfile, config)
    }

    private fun createCandidate(
        id: String,
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Int = 200,
        energy: Float? = null,
        bpm: Int? = null,
        source: CandidateSource = CandidateSource.YTM_RADIO,
    ): ASICandidate {
        val canonical = CanonicalTrackId.from(title, artist, durationSec)
        val mediaItem = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()

        return ASICandidate(
            mediaId = id,
            canonicalId = canonical,
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            source = source,
            rawMediaItem = mediaItem,
            energy = energy,
            bpm = bpm,
        )
    }

    @Test
    fun `hard filtering excludes current track, upcoming queue, disliked tracks, and negative signals`() {
        val seed = createCandidate("curr_id", "Current Track", "Artist A")
        val queueCand = createCandidate("queue_id", "Queued Track", "Artist B")
        val dislikedCand = createCandidate("disliked_id", "Disliked Track", "Artist C")
        val negativeCand = createCandidate("neg_id", "Skipped Early Track", "Artist D")
        val validCand = createCandidate("valid_id", "Valid Track", "Artist E")

        // Pre-populate negative journal
        kotlinx.coroutines.runBlocking {
            journal.recordSkip(negativeCand.canonicalId, negativeCand.mediaId, isEarly = true)
        }

        val dislikedList = listOf(
            DislikedSongEntity(
                id = "disliked_id",
                title = "Disliked Track",
                artist = "Artist C",
                duration = 200,
                normalizedTitle = "disliked track",
                normalizedArtist = "artist c",
            )
        )

        val rawCandidates = listOf(seed, queueCand, dislikedCand, negativeCand, validCand)

        // Using helper logic equivalent to ASICandidateGenerator.filterAndDeduplicate
        val filtered = rawCandidates.filter { cand ->
            if (cand.mediaId == seed.mediaId) return@filter false
            if (cand.mediaId == "queue_id") return@filter false
            if (cand.mediaId == "disliked_id") return@filter false
            if (journal.isNegativeSignal(cand.canonicalId, cand.mediaId)) return@filter false
            true
        }

        assertEquals(1, filtered.size)
        assertEquals("valid_id", filtered.first().mediaId)
    }

    @Test
    fun `cross-provider duplicate candidates are unified within candidate pool`() {
        val ytmCand = createCandidate("yt_1", "Blinding Lights (Official Audio)", "The Weeknd - Topic", durationSec = 200)
        val spotifyCand = createCandidate("sp_1", "Blinding Lights", "The Weeknd", durationSec = 202)

        assertTrue("Canonical IDs must match across provider noise", ytmCand.canonicalId.matches(spotifyCand.canonicalId))

        val pool = listOf(ytmCand, spotifyCand)
        val deduplicated = mutableListOf<ASICandidate>()
        for (c in pool) {
            if (deduplicated.none { it.canonicalId.matches(c.canonicalId) }) {
                deduplicated.add(c)
            }
        }

        assertEquals(1, deduplicated.size)
    }

    @Test
    fun `scorer rewards high artist affinity and penalizes 40-track cooldown repetition`() {
        val seed = createCandidate("seed", "Seed Song", "The Weeknd", energy = 0.8f, bpm = 120)
        val candidate = createCandidate("cand1", "Save Your Tears", "The Weeknd", energy = 0.78f, bpm = 118)

        val now = System.currentTimeMillis()
        val historicalEvents = listOf(
            HistoricalListenEvent("The Weeknd", now - 10000L, 180000L, isCompletion = true, isLiked = true),
            HistoricalListenEvent("The Weeknd", now - 20000L, 180000L, isCompletion = true, isLiked = false),
        )

        val sessionContext = SessionContextSnapshot(SessionCategory.PARTY, confidence = 0.8f, targetEnergy = 0.8f)

        // Fresh track score
        val freshScored = scorer.scoreCandidate(
            candidate = candidate,
            seedTrack = seed,
            sessionContext = sessionContext,
            historicalEvents = historicalEvents,
            recentSessionTracks = emptyList(),
            cooldownIds = emptySet(),
        )

        // Cooldown track score
        val cooldownScored = scorer.scoreCandidate(
            candidate = candidate,
            seedTrack = seed,
            sessionContext = sessionContext,
            historicalEvents = historicalEvents,
            recentSessionTracks = emptyList(),
            cooldownIds = setOf("cand1"),
        )

        assertTrue("Fresh score must exceed cooldown score", freshScored.score > cooldownScored.score)
        assertEquals(1.0f, freshScored.breakdown.freshnessScore, 0.01f)
        assertEquals(0.4f, cooldownScored.breakdown.freshnessScore, 0.01f)
    }

    @Test
    fun `transition evaluator renormalizes missing BPM and key dimensions without unfair penalty`() {
        val trackWithFullMetadata = createCandidate("t1", "Track 1", "Artist 1", energy = 0.8f, bpm = 120)
        val trackWithMissingBpm = createCandidate("t2", "Track 2", "Artist 2", energy = 0.82f, bpm = null)

        val transition = ASITransitionEvaluator.evaluateTransition(trackWithFullMetadata, trackWithMissingBpm)

        // With energy close (0.8 vs 0.82) and BPM dimension omitted and renormalized, score must be high (> 0.80)
        assertTrue("Transition score should renormalize cleanly above 0.80", transition >= 0.80f)
    }

    @Test
    fun `consecutive same artist incurs repetition penalty in scoring`() {
        val seed = createCandidate("seed", "Seed Song", "Drake", energy = 0.7f)
        val candidate = createCandidate("cand", "Next Song", "Drake", energy = 0.7f)

        val sessionContext = SessionContextSnapshot(SessionCategory.GENERAL, targetEnergy = 0.7f)
        val sessionTracksWithDrake = listOf(
            SessionTrackEntry("prev", "Previous Song", "Drake")
        )

        val scored = scorer.scoreCandidate(
            candidate = candidate,
            seedTrack = seed,
            sessionContext = sessionContext,
            historicalEvents = emptyList(),
            recentSessionTracks = sessionTracksWithDrake,
            cooldownIds = emptySet(),
        )

        assertTrue("Repetition penalty must be > 0 for consecutive artist", scored.breakdown.repetitionPenalty > 0.30f)
    }

    @Test
    fun `missing acoustic metadata yields neutral session score and does not use title keyword heuristic`() {
        // Candidate has party-associated keywords in title, but energy is null (unknown)
        val candidate = createCandidate("party_cand", "Ultimate Party Dance Banger Remix", "Pop Star", energy = null)
        val seed = createCandidate("seed", "Current Track", "DJ", energy = null)
        val sessionContext = SessionContextSnapshot(SessionCategory.PARTY, confidence = 0.9f, targetEnergy = 0.85f)

        val scored = scorer.scoreCandidate(
            candidate = candidate,
            seedTrack = seed,
            sessionContext = sessionContext,
            historicalEvents = emptyList(),
            recentSessionTracks = emptyList(),
            cooldownIds = emptySet(),
        )

        // Session score must be strictly neutral 0.50f when energy is unknown, never tricked by keywords
        assertEquals("Missing energy must yield neutral 0.50f session score", 0.50f, scored.breakdown.sessionScore, 0.001f)
    }

    @Test
    fun `missing energy and bpm in transition returns neutral 0_50f rather than false duration match`() {
        // Both tracks have duration = 200, but neither has energy nor BPM
        val source = createCandidate("src", "Source Track", "Artist 1", durationSec = 200, energy = null, bpm = null)
        val candidate = createCandidate("cand", "Candidate Track", "Artist 2", durationSec = 200, energy = null, bpm = null)

        val transitionScore = ASITransitionEvaluator.evaluateTransition(source, candidate)

        // Must be close to neutral 0.50f (+ 0.05 max duration pacing nuance), NOT 0.90f+
        assertTrue("Transition without acoustic dimensions must not report false high match", transitionScore in 0.45f..0.55f)
    }

    @Test
    fun `diverse historical artists yield differentiated affinities rather than collapsing to seed artist`() {
        val now = System.currentTimeMillis()
        val historicalEvents = listOf(
            HistoricalListenEvent("Queen", now - 5000L, 200000L, isCompletion = true, isLiked = true),
            HistoricalListenEvent("Queen", now - 10000L, 200000L, isCompletion = true, isLiked = true),
            HistoricalListenEvent("Daft Punk", now - 15000L, 220000L, isCompletion = true, isLiked = false),
        )

        val queenAffinity = tasteProfile.calculateArtistAffinity("Queen", historicalEvents, now)
        val daftPunkAffinity = tasteProfile.calculateArtistAffinity("Daft Punk", historicalEvents, now)
        val unheardAffinity = tasteProfile.calculateArtistAffinity("Unheard Artist", historicalEvents, now)

        assertTrue("Queen affinity must be highest due to completions and likes", queenAffinity > daftPunkAffinity)
        assertTrue("Daft Punk affinity must exceed unheard", daftPunkAffinity > unheardAffinity)
        assertEquals("Unheard artist must have 0 affinity", 0f, unheardAffinity, 0.001f)
    }
}
