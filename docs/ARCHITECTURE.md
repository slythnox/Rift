# Architecture

## Overview

Napster (now Rift) is an Android music player built with Jetpack Compose, Media3/ExoPlayer, AndroidX Room, AndroidX DataStore, and Dagger Hilt. It connects to YouTube Music via the unofficial InnerTube API and optionally integrates with Last.fm, ListenBrainz, Spotify (public playlists only), and several canvas and lyrics providers.

- **Product Name:** Rift
- **Application ID:** `com.napster.music` — permanently retained across all build variants to preserve device installation identity, Room database paths, and offline media storage.
- **Internal Kotlin namespace:** `com.rift.music` — migrated across all source sets, activities, services, receivers, and Hilt modules.
- **Gradle root project name:** `napster`

---

## Module Structure

| Module | Purpose |
|---|---|
| `app/` | Application module — UI, features, playback, Room, DataStore, Hilt |
| `innertube/` | Unofficial YouTube Music (InnerTube) API client |
| `canvas/` | Tidal and Apple Music canvas provider abstractions |
| `applecanvas/` | Apple Music canvas (artwork + video) provider |
| `vivimusiccanvas/` | Echo / Vivi Music canvas provider |
| `artistvideo/` | Artist video canvas provider |
| `betterlyrics/` | Better Lyrics API client |
| `kugou/` | KuGou lyrics client |
| `lrclib/` | LRCLib lyrics client |
| `paxsenixlyrics/` | Paxsenix lyrics client |
| `youlyplus/` | YouLyPlus lyrics client |
| `musixmatchlyrics/` | Musixmatch lyrics client |
| `simpmusic/` | SimpMusic integration helper |
| `lastfm/` | Last.fm and ListenBrainz scrobbling client |
| `spotify/` | Public Spotify metadata client (no auth) |
| `jiosaavn/` | JioSaavn optional stream provider |
| `spine/` | Spine optional stream provider |

---

## Package Layout (inside `app/`)

```
com.rift.music/
├── app/           – Application, MainActivity, WelcomeActivity, CrashActivity
├── constants/     – Preference keys, system dimensions, preset keys, feature flags
├── db/            – Room database, entities, DAOs, migrations
├── di/            – Hilt modules and component bindings
├── feature/       – One sub-package per screen/feature
│   ├── home/
│   ├── search/
│   ├── library/
│   ├── player/
│   ├── lyrics/
│   ├── settings/  – Settings, About, Privacy, Updates, Integrations
│   ├── local/
│   ├── download/
│   ├── history/
│   ├── spotify/   – Spotify public playlist import
│   └── wrapped/
├── download/      – FLAC file metadata tagging (FlacTagger) and offline export
├── integration/   – Last.fm, ListenBrainz, updater, Cast, Tidal, JioSaavn, Spine
├── models/        – Shared data models (MediaMetadata, QueueData, etc.)
├── playback/      – MusicService, PlayerConnection, queue, events, DJ
│   ├── asi/       – Project ASI 1.0 (Adaptive Session Intelligence: feedback collector, taste models, session adaptation)
│   ├── audio/     – Audio diagnostics, output route tracking, Bluetooth A2DP telemetry
│   ├── dislike/   – DislikeManager & persistent dislike storage coordination
│   ├── recommendation/ – RecommendationPostProcessor (cooldown, diversity, progressive selection)
│   ├── queue/         – Queue orchestration & RandomDiscoveryQueue (Speed Dial anti-repetition discovery)
│   └── resolver/  – Stream resolution pipeline & HTTP add-on sources (AddonClient, TrackMatcher)
├── ui/
│   ├── component/ – Reusable Compose components
│   ├── navigation/
│   ├── overlays/
│   ├── theme/
│   └── utils/
├── utils/         – DataStore helpers, YTPlayerUtils, SongExporter, cipher, potoken, preset
└── widget/        – App widgets (Dot Player, Lyrics Player, Vinyl Deck)
```

---

## Data Flow

### Playback

```
Feature screen / ViewModel
  → PlayerConnection (Compose ↔ Service bridge)
    → MusicService (Media3 ExoPlayer, MediaSession, notifications)
      → playback/resolver/ → YTPlayerUtils / ExternalStreamProviderResolver
        → innertube/ (InnerTube API) or external provider (Spine, Tidal, JioSaavn)
      → queue/ (ListenTogether-free queue state)
      → events/ → PlaybackEventDispatcher → Last.fm / ListenBrainz
```

`MusicService` owns the ExoPlayer instance, MediaSession, audio focus, notification, background wakelock, cache coordination, download management, and the optional DJ mixer. Preference values consumed inside player callbacks are cached in-memory (`cachedRepeatMode`, `cachedPersistentQueue`, etc.) and updated reactively via a single DataStore flow collector — no `runBlocking` calls remain on the main thread.

### Spotify Public Playlist Import

```
Public Spotify playlist URL
  → playlist ID extraction
  → PublicSpotifyMetadataClient (spotify/ module)
      ├─► Extract anonymous guest token from public embed HTML
      └─► Pathfinder GraphQL pagination (1,000+ tracks; Retry-After backoff & 401 refresh)
  → Preserves duplicate tracks & exact playlist order
  → DeterministicTrackResolutionEngine
      └─► YouTube search via innertube/ with duration matching
  → resolved Song entities
  → Room database (playlist + song + playlist_song_map persistence)
```

No Spotify account login, WebView cookie harvesting, or private API access is involved. The importer relies exclusively on public embed tokens and internal partner endpoints as an unauthenticated, best-effort client-side utility.

---

## Key Design Decisions

### Namespace migrated to `com.rift.music` while retaining `applicationId = com.napster.music`

The Kotlin package namespace was migrated from `com.beatwave.music` to `com.rift.music`. To maintain 100% update compatibility and prevent data loss:
- The Android application ID permanently remains `com.napster.music` across all variants.
- Room schemas versions 1–38 were mirrored to `app/schemas/com.rift.music.db.InternalDatabase/`, maintaining the identical `identityHash` (`de260a1f8fcef65830e287325ab3b697`).
- The Room database file remains `song.db` (openable seamlessly without migration).
- All ContentProvider authorities and DataStore preference keys remain untouched.
- Submodules (`canvas`, `applecanvas`, etc.) retain their standalone namespaces.

### Optional integrations and add-ons must not break core playback

Every optional provider (HTTP add-ons, Spine, Tidal, JioSaavn, canvas providers, lyrics sources) is wired to fail silently. `ExternalStreamProviderResolver` falls back to YouTube if all external providers fail. `PlaybackEventDispatcher` catches exceptions from scrobbling clients individually. HTTP add-ons enforce client-side cooldowns (HTTP 429 Retry-After) and deduplicate in-flight requests (`SharedCalls`) to prevent cascading failures.

### Truth-first audio telemetry without unverified bit-perfect claims

Audio diagnostics monitor the actual decoder format via Media3 `AnalyticsListener.onAudioInputFormatChanged()` and physical routes via Android `AudioManager` and `BluetoothProfile`. Telemetry highlights downstream downgrades honestly. The app avoids unsupported promises of "bit-perfect" playback, acknowledging that audio output routes through standard Android AudioTrack/AudioFlinger system pipelines.

### Non-destructive FLAC tagging and export

`FlacTagger` manipulates FLAC metadata blocks (Vorbis comments, pictures, padding) while copying raw audio frames byte-for-byte untouched. Non-FLAC sources are exported as M4A/AAC rather than falsely relabeled as FLAC.

### Recognition history retained despite feature removal

The `RecognitionHistory` Room entity and its DAO methods remain in the schema to preserve database migration continuity. The user-facing recognition UI and microphone permission were removed in Phase 8. Dropping the entity would require a schema migration that either drops the table (losing history data) or performs a no-op migration — neither is automatically safe.

---

## Removed Features

The following were removed in Phases 7–8 and no longer exist in source or navigation:

| Feature | Status |
|---|---|
| Listen Together / collaborative rooms | Completely removed (source, DI, UI, strings, tests) |
| Discord Rich Presence (Kizzy) | Completely removed |
| Spotify private account APIs / login WebView | Completely removed |
| AI lyrics translation / AI playlist modification | Completely removed |
| Shazam / microphone recognition (UI) | Removed; DB entity retained |
| Supabase / custom backend services | Completely removed |
| Legacy Account screen | Completely removed |
| Legacy Story page | Completely removed |

---

## Adaptive Session Intelligence (ASI 1.0) Architecture

- **ASIManager.kt:** Central orchestrator bridging recommendation queries from `MusicService` to the ASI recommendation engine, enforcing 5-second overall timeouts and 3.5-second candidate generation timeouts.
- **Multi-Source Candidate Pooling (`ASICandidateGenerator`):** Concurrently gathers candidates across YouTube Music radio continuations, artist discovery endpoints, and local library favorites, applying hard exclusions (dislikes, negative journal, current track, queue) and canonical cross-provider deduplication.
- **Dynamic Session Context (`SessionContextSnapshot`, `ASISessionProfile`):** Tracks session energy moving averages, skips, and classified atmospheres (Party, Workout, Relaxed, Romantic, Focus, Discovery, Commute, General) with gradual adaptation on manual track selection.
- **Explainable Multi-Dimension Scoring (`ASIScorer`, `ASITransitionEvaluator`):** Computes normalized 5-dimension scores (enjoyment, session fit, transition smoothness, freshness, discovery) with harmonic BPM matching, acoustic delta gating, and dimension renormalization for sparse metadata.
- **Bounded Beam Search Sequence Optimization (`ASISequenceOptimizer`):** Employs width $K=4$, horizon $H=5$ trajectory optimization balancing candidate quality, pairwise continuity, energy flow, and path diversity without monotonic bias.
- **Diagnostics & Safe Fallback (`ASIDiagnostics`):** In-memory ring buffer (last 50 runs) preserving complete explainability audit logs; automatic, zero-stutter fallback to `RecommendationPostProcessor` whenever ASI is disabled or encounters a timeout/error.
- Full specification: [ASI.md](ASI.md).

---


---

## Speed Dial Discovery & Performance Telemetry Architecture

### Random Discovery Queue (RandomDiscoveryQueue.kt)
- **Instant Preload:** Prepares seedSong.toMediaMetadata() instantly on user tap, enabling zero-latency playback startup without waiting for network recommendations.
- **Genuine History Seeding:** Randomly samples a track from database.recentEvents(100) to anchor the listening session in the user's authentic taste context.
- **Strict Anti-Fatigue Filtering:** Filters out the user's top 30 most played songs (mostPlayedSongs(0L).take(30)), recent 50 history events, and disliked songs, guaranteeing non-repetitive exploration.
- **Atmosphere & Genre Alignment:** Queries YouTube Related and Radio endpoints seeded by the chosen track, matching genre, mood, and acoustic energy.
- **Novelty & Diversity Guardrails:** Boosts newer deep-catalog releases (2023+), enforces a strict artist cap (maximum 2 tracks per artist across the entire queue), and supports continuous background pagination.

### App Usage & Telemetry (TrackUsageScreen.kt)
- **Real-Time Performance Graphs:** Captures and displays time-series performance metrics for CPU load, GPU frame rendering duration, and Network consumption (Cellular and Wi-Fi) without background polling overhead.
- **Smooth Bézier Rendering:** Utilizes cubic Bézier curve paths for flicker-free data visualization across all display refresh rates.

## Known Complexity Hotspots

The following files are intentionally large and are not candidates for refactoring in a cleanup phase:

- `MusicService.kt` — broad Android service, ExoPlayer, MediaSession, notifications, audio focus, cache, DJ.
- `DatabaseDao.kt` — single DAO for the entire Room database; reflects a deliberate monolithic DAO design for query locality.
- `YTPlayerUtils.kt` — InnerTube stream resolution with 12-client fallback, SABR/cipher deobfuscation, po-token generation.
- `MainActivity.kt` — Compose host, navigation, global state, and many integration hooks.

---

## Playback Reliability and Diagnostics Architecture

- **PlaybackDiagnostics.kt:** In-memory, thread-safe circular event buffer (ConcurrentLinkedDeque, capacity: 100) capturing structured playback telemetry (PlaybackDiagnosticEvent). Records timestamps, session IDs, media metadata, streaming providers, Media3 playback states, and recovery attempts without disk I/O overhead.
- **Resilient Player Lifecycle:** State management distinguishes active user intent (playWhenReady) from the transient Media3 playback state (isPlaying), ensuring uninterrupted resumption across audio sink recoveries, transient audio focus losses, network quality changes, and expired stream refreshes.
- **Streaming Provider Isolation:** ResolvingDataSource applies provider-specific data spec policies: YouTube itag streams utilize chunked subrange requests (CHUNK_LENGTH = 10MB), while direct static streams (Tidal, Spine, JioSaavn, HTTP add-ons) are fetched via open-ended requests to prevent premature truncation.
