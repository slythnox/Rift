<div align="center">

<img src="assets/logo.png" width="160" alt="Rift Logo" />

# R I F T

### Precision Audio Architecture for Android

<p align="center">
  An open-source, serverless Android music client engineered with Jetpack Compose, AndroidX Media3, and a multi-endpoint streaming pipeline.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/Licence-GPL--3.0-E50914?style=for-the-badge&labelColor=0E1015" alt="Licence GPL-3.0" /></a>
  <a href="version.properties"><img src="https://img.shields.io/badge/Version-1.0.4--XO-E50914?style=for-the-badge&labelColor=0E1015" alt="Version 1.0.4" /></a>
  <a href="docs/BUILD_AND_TEST.md"><img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%E2%80%9336)-E50914?style=for-the-badge&labelColor=0E1015" alt="Android 8.0+" /></a>
  <a href="docs/ARCHITECTURE.md"><img src="https://img.shields.io/badge/Architecture-Clean%20%2F%20MVI%20%2B%20Media3-E50914?style=for-the-badge&labelColor=0E1015" alt="Architecture Clean Media3" /></a>
  <a href="docs/PRIVACY_AND_PERMISSIONS.md"><img src="https://img.shields.io/badge/Telemetry-Zero%20Tracking-E50914?style=for-the-badge&labelColor=0E1015" alt="Zero Telemetry" /></a>
</p>

<p align="center">
  <a href="#1-project-introduction"><b>OVERVIEW</b></a> &nbsp;&bull;&nbsp;
  <a href="#2-feature-catalogue"><b>FEATURES</b></a> &nbsp;&bull;&nbsp;
  <a href="#3-technology-stack"><b>TECH STACK</b></a> &nbsp;&bull;&nbsp;
  <a href="#4-system-architecture"><b>ARCHITECTURE</b></a> &nbsp;&bull;&nbsp;
  <a href="#5-playback-pipeline"><b>PLAYBACK PIPELINE</b></a> &nbsp;&bull;&nbsp;
  <a href="#6-search-and-playlist-import"><b>SEARCH & IMPORT</b></a> &nbsp;&bull;&nbsp;
  <a href="#7-persistence-and-storage"><b>PERSISTENCE</b></a> &nbsp;&bull;&nbsp;
  <a href="#8-integrations-matrix"><b>INTEGRATIONS</b></a> &nbsp;&bull;&nbsp;
  <a href="#9-privacy-and-permissions"><b>PRIVACY</b></a> &nbsp;&bull;&nbsp;
  <a href="#10-developer-quickstart"><b>QUICKSTART</b></a> &nbsp;&bull;&nbsp;
  <a href="#11-repository-structure"><b>STRUCTURE</b></a> &nbsp;&bull;&nbsp;
  <a href="#12-engineering-decisions"><b>DESIGN DECISIONS</b></a> &nbsp;&bull;&nbsp;
  <a href="#13-documentation-index"><b>DOCUMENTATION</b></a> &nbsp;&bull;&nbsp;
  <a href="#14-licensing-and-provenance"><b>PROVENANCE</b></a>
</p>

</div>

---

> [!IMPORTANT]
> **Independent Open-Source Notice:** Rift is an independent, community-driven open-source software project distributed under the GNU General Public Licence v3.0. It is **not** affiliated with, sponsored by, or endorsed by Google LLC, YouTube, Spotify AB, or any past or present commercial entity associated with historic media brands. This software operates entirely client-side without proprietary backend infrastructure or commercial subscriptions.

---

## 1. Project Introduction

### The Rationale
Modern mobile streaming clients increasingly prioritise algorithmic promotional feeds, tracking beacons, and walled gardens over user control, audio quality, and battery efficiency. **Rift** was engineered to return complete autonomy to the listener:
- **Serverless Operation:** Runs 100% on-device. No central server stores your listening history, account identifiers, or playback queries.
- **Resilient Media Pipeline:** Decouples playback logic from proprietary client locks by consuming open streams via a cascading, multi-client InnerTube resolver.
- **Tactile Material 3 Interface:** Employs Jetpack Compose with custom liquid-glass backdrop shaders, adaptive palette matching, and dedicated landscape/tablet adaptations.
- **Universal Bridge:** Enables users to import public Spotify and YouTube Music playlists directly into their local on-device database without requiring account credentials or API tokens.

### Application Identity vs Architecture Namespace
- **User-Facing Product Name:** **Rift**
- **Android Application ID:** `com.napster.music` — permanently retained across all build variants to ensure zero disruption to existing device installations, update paths, downloaded media storage directories, and app signing identity.
- **Kotlin Package Namespace:** `com.rift.music` — all application source sets, components, ViewModels, and services are fully migrated to this modern Kotlin namespace.
- **Room Database Schema Continuity:** Schema version 38 with full migration history (schemas 1–38) preserved in both `com.rift.music.db.InternalDatabase` and historical paths, ensuring non-destructive database opening.

### Visual Rebrand Status & Screenshots Placeholder
> [!NOTE]
> The application code, Kotlin namespace, and architecture have completed full stabilization. A final visual rebrand pass for new logo assets, splash art, and updated theme graphics is queued. Screenshots will be refreshed upon completion of the asset rebrand.

---

## 2. Feature Catalogue

### Core Audio & Playback
- **Adaptive Session Intelligence (ASI 1.0):** Autonomous, on-device recommendation engine modeling listening session momentum, atmosphere profiles (Party, Workout, Relaxed, Focus, etc.), harmonic BPM matching, and beam search trajectory optimization ($K=4$, $H=5$) with authentic Room DB historical event reconstruction. Disabled by default via feature flag (`enableAsiAutoplay`).
- **Speed Dial Random Discovery & Anti-Repetition:** Matrix-aligned 5-dot Random action preloading genuine recent events and generating genre/vibe-matched queues while strictly filtering out top 30 most-played and recent 50 tracks to eliminate repetition loops.
- **Persistent Song Dislike System:** Dedicated Room schema v38 table and Settings management screen permanently excluding disliked tracks from playback, queues, and automix across providers.
- **AndroidX Media3 / ExoPlayer Engine:** Built upon ExoPlayer with customized audio sinks, continuous buffer management, and automatic audio focus handling.
- **Truth-First Audio Diagnostics:** Real-time telemetry monitoring Media3 decoder inputs (actual codec, bitrate, sample rate, bit depth), detecting audio chain downgrades, and displaying transparent UI badges without unverified bit-perfect claims.
- **Bluetooth Output-Route Telemetry:** Non-blocking tracking of connected hardware sinks (Wired, USB DAC, HDMI, Internal Speaker) and Bluetooth A2DP transport codecs (LDAC, aptX, AAC, SBC, Samsung Seamless) via Android audio device callbacks.
- **Per-Network Quality Preferences:** Independent audio quality selectors for Wi-Fi and Cellular connections, respecting Data Saver mode and transitioning smoothly upon network handover.
- **HTTP Streaming Add-ons:** Modular support for external HTTP streaming add-on servers using an open JSON manifest specification, with search-probe fallbacks, request deduplication, and 429 Retry-After backoff.
- **Lossless FLAC File Export:** Export cached lossless tracks directly to user-selected storage via Android SAF, embedding Vorbis comment tags, UTF-8 Unicode metadata, album art, and synchronized lyrics without altering raw audio frames (non-FLAC sources exported cleanly as M4A).
- **Direct Queue Reordering:** Long-press drag-and-drop reordering of queue items with visual elevation, haptic feedback, edge auto-scrolling, and full TalkBack accessibility actions.
- **Shuffle-Aware Media3 Queue Synchronization:** Seamless reordering in shuffle mode updates the Media3 `DefaultShuffleOrder` permutation in-place, keeping active playback uninterrupted and maintaining accurate timeline indices.
- **Progressive Recommendation Selection:** Deterministic recommendation post-processor with a 40-track recency cooldown, two-phase progressive selection (fresh pool strictly prioritized before recent candidates), artist and album separation, and preserved artist radio mode.
- **Seamless Autoplay & Radio Continuation:** Persistent Autoplay infinity toggle chip in Queue and Player controls, automatically transitioning to algorithmic radio recommendations with fallback to official YouTube Radio stations.
- **High-Capacity Spotify Public Playlist Import:** Paginated import supporting 1,000+ tracks via unauthenticated embed guest tokens and Pathfinder GraphQL, respecting server `Retry-After` rate-limiting and preserving duplicate tracks in exact sequence.
- **Audio Equalisation & DSP Effects:** Integrated graphical equaliser, bass boost, virtualiser, and system DSP intent forwarding.
- **DJ Mode & Beat-Matched Transitions:** Gapless playback, configurable crossfade durations, and automated beat-matching energy transitions.
- **Off-Screen & Background Playback:** Fully decoupled Android Foreground Service (`MusicService`) managing hardware media buttons, lock-screen notifications, and Bluetooth transport controls.
- **Local Audio Library:** Real-time scanning and merging of on-device audio files stored in device storage alongside cloud streams.
- **Offline Download & Cache Controls:** Background downloading managed by `ExoDownloadService` with granular cache eviction and storage budget management.

### Interface & Experience
- **Liquid Glass & Dynamic Blur:** Hardware-accelerated backdrop blur (`Kyant0/backdrop`) creating frosted glass aesthetics behind playback bars and navigation headers.
- **Synchronised Lyrics:** Multi-source lyrics engine querying LRCLIB, KuGou, Paxsenix, Better Lyrics, and local embedded LRC/TTML tags with word-level and line-level karaoke synchronisation.
- **Visual Canvas:** High-definition animated album canvas rendering supporting Apple Music canvas streams, Tidal canvas streams, and artist video loops.
- **Android Auto Integration:** First-class vehicle media browsing and dashboard playback support, including cold-start playlist resumption, styled content lists, and vehicle transport controls via AndroidX Media3.
- **Multiple Form Factors:** Dedicated responsive layouts for smartphones, tablets, foldables, and car displays with smooth 120Hz scrolling and zero-recomposition chrome animations.
- **Interactive Widgets:** Redesigned interactive widgets including the minimalist Rift Dot Player (2×2), split-panel Rift Lyrics Player (4×2), and animated Rift Vinyl Deck (4×2).
- **App Usage Telemetry:** Settings > App Usage displays interactive, real-time and historical graphs for CPU load, GPU frame rendering duration, and Network data consumption rendered with smooth cubic Bézier curves and zero background tracking overhead.

### Discovery & Integrations
- **Multi-Endpoint InnerTube Search:** Search songs, albums, artists, community playlists, and mood stations with instant client-side debouncing (300 ms).
- **Public Spotify & YouTube Music Import:** Resolve and import any public Spotify or YouTube Music playlist URL into native Room playlists using client-side scrapers and InnerTube resolution without account credentials.
- **Audioscrobbling:** Native asynchronous scrobbling support for **Last.fm** and **ListenBrainz** via official REST endpoints.
- **Backup & Portability:** One-click encrypted JSON/SQLite database export and restoration for library entries, custom playlists, and playback settings.

### Intentionally Absent Features
To maintain uncompromising privacy, codebase stability, and architecture integrity, several non-essential modules and unsupported promises are explicitly excluded:
- No real-time collaborative rooms or proprietary multi-user sync protocols (Listen Together).
- No private Spotify user authentication, cookies, or GraphQL account token extractors.
- No microphone-based ambient audio recognition (Shazam clones).
- No Discord Rich Presence sockets or external RPC daemons.
- No analytics SDKs, advertising frameworks, or crash telemetry endpoints.
- No unsupported promises of "bit-exact" or guaranteed end-to-end hardware bit-perfect reproduction (playback routes through standard Android audio sinks).
- No Wear OS, Android TV, desktop applications (Windows/macOS/Linux), or browser extensions.

---

## 3. Technology Stack

Rift uses modern, production-grade Android open-source technologies:

| Layer | Technology | Version | Architectural Responsibility |
|---|---|---|---|
| **Language** | Kotlin | `2.1.0` | Primary codebase language with Coroutines and Flow |
| **UI Framework** | Jetpack Compose | BOM `2024.12.01` | Declarative UI, Material 3, animations, and adaptive layouts |
| **Audio Engine** | AndroidX Media3 / ExoPlayer | `1.5.1` | Audio decoding, buffering, caching, and MediaSession |
| **Local Database** | AndroidX Room | `2.6.1` | SQLite persistence across 38 schema migrations |
| **Preferences** | AndroidX DataStore | `1.1.1` | Asynchronous key-value settings storage |
| **Dependency Injection** | Dagger Hilt | `2.54` | Application-wide compile-time dependency inversion |
| **Image Loading** | Coil 3 | `3.0.4` | Asynchronous image decoding, memory caching, and disk cache |
| **Networking & HTTP** | Ktor Client / OkHttp | `3.0.3` / `5.0.0-alpha.14` | HTTP/2, TLS connection pooling, and payload parsing |
| **Serialization** | Kotlinx Serialization | `1.7.3` | Multiplatform JSON serialization for models and APIs |
| **Backdrop Blur** | Kyant0/backdrop | Source | Hardware-accelerated frosted glass surface rendering |
| **Cast Extension** | Google Cast SDK | `22.0.0` | Optional Chromecast protocol routing (`gms` variant only) |

---

## 4. System Architecture

The repository enforces a clean, modular architecture separating presentation, domain, and data layers:

```mermaid
graph TD
    subgraph UI ["Presentation Layer (Jetpack Compose)"]
        A[Navigation Graph] --> B[Feature Screens & ViewModels]
        B --> C[PlayerConnection State Flow]
        B --> D[Design System & Shaders]
    end

    subgraph Service ["Audio Subsystem (Android Service)"]
        E[MusicService] --> F[MediaSession & MediaLibrarySessionCallback]
        E --> G[AndroidX ExoPlayer Instance]
        G --> H[MediaSourceFactory / Caching Pipeline]
    end

    subgraph Domain ["Resolution & Stream Cascade"]
        H --> I[InnerTube Multi-Client Resolver]
        I --> J["Client: WEB_REMIX"]
        I --> K["Client: ANDROID_MUSIC"]
        I --> L["Fallback: Spine / JioSaavn / Addons"]
    end

    subgraph Data ["Persistence & Network"]
        M[(Room Database: 38 Schemas)]
        N[(AndroidX DataStore: Preferences)]
        O[ExoDownloadService: Disk Cache]
    end

    C <==>|MediaSession RPC| F
    B --> M
    B --> N
    H --> O
```

### Module Responsibilities
- **`:app`**: Application root containing Compose screens, Navigation graph, ViewModels, Hilt modules, Room DAOs (`com.rift.music.db`), and the foreground `MusicService`.
- **`:innertube`**: Dedicated YouTube Music / InnerTube client engine handling request signing, protobuf payloads, and JSON stream models.
- **`:canvas` / `:applecanvas` / `:vivimusiccanvas` / `:artistvideo`**: Specialised visual stream renderers providing animated video album canvas art.
- **`:betterlyrics` / `:kugou` / `:lrclib` / `:paxsenixlyrics` / `:simpmusic`**: Modular lyric extractors queried concurrently via Kotlin Coroutines.
- **`:lastfm`**: Authenticated Last.fm REST API client with scrobble queueing and MD5 authentication hashing.
- **`:spotify`**: Public Spotify playlist scraper resolving web metadata into standard Song track queries.
- **`:jiosaavn` / `:spine`**: Secondary and regional media resolvers utilised when primary YouTube audio tracks are geo-restricted.

---

## 5. Playback Pipeline

Every audio playback event follows a deterministic, resilient lifecycle from user interaction to the hardware audio sink:

```mermaid
sequenceDiagram
    autonumber
    actor User as Listener
    participant UI as Feature Screen / ViewModel
    participant Conn as PlayerConnection
    participant Svc as MusicService
    participant Res as Stream Resolver Pipeline
    participant Cache as SimpleCache (Disk)
    participant Sink as ExoPlayer AudioSink

    User->>UI: Select Track / Album / Playlist
    UI->>Conn: playMediaItem(MediaMetadata)
    Conn->>Svc: Send Transport Command (MediaBrowser)
    Svc->>Cache: Check rift://cache/ for Cached Audio
    alt Track Cached
        Cache-->>Svc: Provide Local File DataSource
    else Network Stream Required
        Svc->>Res: Resolve Stream URL (VideoId)
        Res->>Res: Try Client 1: WEB_REMIX
        opt Resolution Fails (HTTP 403 / PO-Token Required)
            Res->>Res: Fallback Client 2: ANDROID_MUSIC
        end
        Res-->>Svc: Direct Audio Stream URL (HTTPS / Opus / AAC)
    end
    Svc->>Sink: Prepare MediaSource & Feed AudioTrack Sink
    Sink-->>User: Gapless Hardware Audio Output
    Svc->>UI: Emit PlaybackState (Playing, Position, Buffering)
```

---

## 6. Search and Playlist Import

### Debounced Multi-Type Search
The search architecture integrates a 300 ms debounce operator across Kotlin Flows. As characters are entered, queries are directed through InnerTube's suggest engine, yielding instantaneous suggestions alongside faceted category filters:
`All` &bull; `Songs` &bull; `Albums` &bull; `Artists` &bull; `Playlists` &bull; `Featured Stations`

### Public Spotify Playlist Importer
Users can paste any public Spotify playlist URL (e.g. `https://open.spotify.com/playlist/...`). Rift processes the request client-side without authenticating with Spotify:

```
[Spotify Public URL] 
        │
        ▼ (Client-side HTML/Metadata Scraper)
[Extract: Track Title, Primary Artist, Duration, Album]
        │
        ▼ (Coroutines Async Resolution Batch)
[Query InnerTube Engine per Track with Fuzzy Matching]
        │
        ▼ (Relational Insertion)
[Insert Resolved Songs into Local Room Playlist]
```

### Public YouTube Music Playlist Importer
Users can paste any public or unlisted YouTube / YouTube Music playlist URL (or playlist ID). Rift parses the playlist ID, fetches track metadata directly via InnerTube without account credentials, and inserts the playlist and songs into the local Room database with real-time import progress.

---

## 7. Persistence and Storage

### Room Relational Database
Rift maintains an SQLite database (`song.db`) managed via AndroidX Room with 38 sequential migration stages. Strict schema integrity guarantees that existing user data is preserved across application updates without table drops or data loss.

| Table Entity | Primary Role |
|---|---|
| `SongEntity` | Track metadata, duration, local file paths, and thumbnail references |
| `ArtistEntity` | Artist biographies, subscriber counts, and thumbnail artwork |
| `AlbumEntity` | Release year, artist relations, track counts, and cover images |
| `PlaylistEntity` & `PlaylistSongMap` | Custom user playlists and relational song mappings |
| `SearchHistory` | Local user search terms with chronological indexing |
| `Event` | Timestamped playback events utilised for recommendations and stats |
| `FormatEntity` | Audio stream encoding, bitrate, and mime types for offline playback |
| `LyricsEntity` | Cached LRC and plain-text lyrics indexed by track identifier |
| `SongAnalysisEntity` | Cached acoustic metrics (BPM, key, energy) for DJ transitions |
| `DislikedSongEntity` | Schema v38 table permanently excluding disliked tracks from queues and radio |

### DataStore Preferences
Non-relational preferences (equaliser band gains, theme selection, dynamic colours, download quality, and crossfade duration) are stored asynchronously using AndroidX DataStore (`com.rift.music.constants.PreferenceKeys`).

---

## 8. Integrations Matrix

| Service | Mechanism | Authentication Required | Purpose | Failure Behaviour |
|---|---|---|---|---|
| **YouTube Music** | InnerTube Client | No (Client-side requests) | Primary audio streaming, search, feeds | Cascades to alternative client headers |
| **Spotify** | Public Web Scraper | **No** (Public lists only) | Playlist tracklist extraction | Displays parsing error alert to user |
| **Last.fm** | REST API | Yes (API Key + User Session) | Asynchronous audioscrobbling | Caches scrobble offline, retries later |
| **ListenBrainz** | REST API | Yes (User API Token) | Open scrobbling protocol | Drops non-critical telemetry packet |
| **LRCLIB** | REST API | No | Synchronised LRC lyric provider | Falls back to KuGou or Paxsenix |
| **Apple Music** | CDN Endpoint | No | High-resolution animated canvas video | Falls back to static album artwork |
| **HTTP Add-ons** | Open JSON Manifest | No / Server-defined | Modular alternative streaming | Falls back to next candidate in cascade |

---

## 9. Privacy and Permissions

Rift adheres to a strict zero-telemetry policy. The application requires only the minimal set of Android runtime permissions necessary to deliver local and streaming audio playback:

```xml
<!-- Network Communications -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Background Playback -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- Local Storage Audio Library -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

<!-- System Integration -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- **Zero Analytics:** Contains no Firebase Analytics, Mixpanel, Segment, or proprietary metrics SDKs.
- **Zero Crash Telemetry:** No Sentry, Crashlytics, or Bugsnag libraries. Crash logs remain purely on-device in `adb logcat`.
- **No Account Required:** Operates immediately after installation without mandatory registration, email, or telephone verification.

---

## 10. Developer Quickstart

### Prerequisites
- **Java Development Kit (JDK):** Version 21 recommended.
- **Android SDK:** Installed with Build-Tools `36.0.0` and Platform API `36` / `37`.
- **Android NDK:** Version `27.0.12077973` (configured via `local.properties` or `ANDROID_NDK_HOME`).
- **Gradle:** Wrapper included (`gradlew` / `gradlew.bat`).

### Build Variants
- **`arm64Foss`**: Primary development flavour. Excludes proprietary Google Mobile Services (GMS) binaries and includes full 64-bit ARM optimizations.
- **`universalFoss`**: 100% Free and Open Source variant bundled for all CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- **`universalGms`**: Universal build containing Google Cast SDK bindings for Chromecast streaming.

### Compilation Commands

```powershell
# 1. Build the primary ARM64 FOSS Debug APK
.\gradlew.bat :app:assembleArm64FossDebug

# 2. Execute all unit tests across the application and submodules
.\gradlew.bat :canvas:test :innertube:testDebugUnitTest :spine:testDebugUnitTest :app:testArm64FossDebugUnitTest

# 3. Assemble signed release APK
.\gradlew.bat :app:assembleArm64FossRelease

# 4. Install directly onto a connected physical device or emulator
.\gradlew.bat :app:installArm64FossDebug
```

---

## 11. Repository Structure

```
Rift/
├── app/                         # Primary Android application module
│   ├── src/main/kotlin/com/rift/music/
│   │   ├── app/                 # Application class & Activity lifecycle
│   │   ├── db/                  # Room Database, migrations (1-38), DAOs, entities
│   │   ├── feature/             # UI features (home, player, search, library, settings)
│   │   ├── playback/            # MusicService, PlayerConnection, MediaSession, ASI
│   │   └── ui/                  # Compose theme, components, Liquid Glass shaders
│   └── src/main/res/            # Android resources, vector drawables, layouts
├── innertube/                   # Independent YouTube Music / InnerTube client engine
├── canvas/                      # Abstract animated canvas decoding infrastructure
├── applecanvas/                 # Apple Music canvas video resolution module
├── vivimusiccanvas/             # Community canvas service bridge
├── artistvideo/                 # Artist video canvas playback integration
├── lrclib/                      # LRCLIB lyrics API consumer
├── betterlyrics/                # BetterLyrics provider module
├── kugou/                       # KuGou multi-language lyrics parser
├── paxsenixlyrics/              # Paxsenix lyrics engine client
├── simpmusic/                   # Auxiliary lyrics and music metadata parser
├── spotify/                     # Public Spotify playlist importer (no authentication)
├── lastfm/                      # Last.fm and ListenBrainz REST scrobbling client
├── jiosaavn/                    # Secondary regional audio resolution provider
├── spine/                       # Fallback audio streaming client
├── docs/                        # Complete technical documentation index
└── gradle/                      # Gradle wrapper configuration and dependency catalogs
```

---

## 12. Engineering Decisions

### Architectural Invariants
1. **Application ID vs Kotlin Namespace:**
   The Android application ID is permanently retained as `applicationId = "com.napster.music"`. This guarantees that existing user app data, SQLite databases, downloaded offline tracks, and system permissions are retained without needing an app uninstall or manual data migration. The internal Kotlin namespace was cleanly migrated to `com.rift.music`.
2. **Room Schema Parity:**
   Room schema version 38 exports are maintained under `app/schemas/com.rift.music.db.InternalDatabase/` with backwards schema discovery retained under `app/schemas/com.beatwave.music.db.InternalDatabase/`. In-memory SQLite migration tests confirm zero schema drift.
3. **Multi-Module Lyrics & Canvas Isolation:**
   Each external provider is isolated in its own Gradle subproject. Breaking API changes in upstream third-party endpoints never block core playback compilation.
4. **Decoupled Playback via `PlayerConnection`:**
   UI components never hold direct references to `ExoPlayer` or `MusicService`. Communication is handled through an IPC bridge powered by AndroidX Media3's `MediaBrowser` and Kotlin StateFlows, preventing memory leaks during Activity recreation.

---

## 13. Documentation Index

For exhaustive technical references, inspect the comprehensive engineering documentation in the [`docs/`](docs/) directory:

| Document | Description |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Detailed architectural patterns, Clean Architecture, and module graph |
| [`docs/SETUP.md`](docs/SETUP.md) | Complete workstation environment, IDE, and SDK configuration instructions |
| [`docs/BUILD_AND_TEST.md`](docs/BUILD_AND_TEST.md) | Gradle compilation, test suites, ProGuard/R8 rules, and release signing |
| [`docs/FEATURES.md`](docs/FEATURES.md) | Comprehensive inventory of active features, UI components, and limitations |
| [`docs/PLAYBACK.md`](docs/PLAYBACK.md) | Audio pipeline lifecycle, MediaSession callbacks, and caching logic |
| [`docs/ASI.md`](docs/ASI.md) | Project ASI 1.0 architecture, session profiling, beam search, and evaluation |
| [`docs/SEARCH_AND_SPOTIFY_IMPORT.md`](docs/SEARCH_AND_SPOTIFY_IMPORT.md) | Debouncing, InnerTube search filters, and public Spotify scraping logic |
| [`docs/DATABASE_AND_PREFERENCES.md`](docs/DATABASE_AND_PREFERENCES.md) | Room SQLite schema definitions, 38 migrations, and DataStore structures |
| [`docs/INTEGRATIONS.md`](docs/INTEGRATIONS.md) | Third-party service configuration, API keys, and graceful degradation |
| [`docs/PRIVACY_AND_PERMISSIONS.md`](docs/PRIVACY_AND_PERMISSIONS.md) | Comprehensive audit of device permissions, network calls, and data flows |
| [`docs/APK_UPDATE_AND_SIGNING.md`](docs/APK_UPDATE_AND_SIGNING.md) | Release signing lifecycle, keystore security, certificate fingerprint verification, and safe in-place APK upgrades |
| [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) | Code styling guidelines, pull request protocols, and review standards |
| [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Common compilation issues, audio focus bugs, and runtime diagnostics |
| [`docs/RELEASE_CHECKLIST.md`](docs/RELEASE_CHECKLIST.md) | Quality assurance, automated test verification, and APK signing steps |

---

## 14. Licensing and Provenance

### Software Licence
Rift is licensed under the **GNU General Public Licence v3.0 (GPL-3.0)**. Permissions of this strong copyleft licence are conditioned on making available complete source code of licensed works and modifications under the same licence. See the [`LICENSE`](LICENSE) file for the full legal text.

### Upstream Open-Source Lineage
This application represents an evolution of open-source music playback on Android, built upon foundational reverse-engineering and local-first media playback architecture under GPL-3.0.
- **InnerTune** *(by Malopieds)*: Original InnerTube API reverse-engineering implementation and Room schema foundation.

### AI-Assisted Development Disclosure
Portions of the codebase refactoring, architectural auditing, test suite authoring, and documentation generation were performed with the assistance of advanced artificial intelligence models (Google Gemini and Anthropic Claude). All machine-assisted outputs were rigorously inspected, validated, compiled, and verified by human maintainers. In adherence to open-source software governance, AI systems are not granted authorship status; all copyright and legal responsibilities remain with the human contributors and respective upstream licence holders.

---

<div align="center">
  <sub>Engineered for uncompromising listening freedom. Free software for everyone.</sub>
</div>
