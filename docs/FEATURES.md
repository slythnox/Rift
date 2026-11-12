# Rift Feature Catalogue & Functional Inventory

This document provides a comprehensive inventory of features available in **Rift** (Kotlin namespace `com.rift.music`, Android application ID `com.napster.music`), categorized across the 8 primary functional domains. Each entry is explicitly tagged with its verification status:

- **[Implemented & tested]**: Automated unit tests execute and pass in CI/local Gradle runs.
- **[Implemented but not device-tested]**: Feature is fully implemented in production code; automated tests exist or code builds cleanly, but physical device/hardware validation is pending connection.
- **[Experimental]**: Feature is implemented behind a user opt-in toggle or flag and subject to external provider availability.
- **[Partially implemented]**: Core flow is functional, but secondary auxiliary paths or edge cases are stubbed or pending expansion.
- **[Unsupported/deprecated]**: Explicitly retired or excluded legacy functionality (see Section 9).

---

## 1. Domain 1: Core Playback Engine

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **AndroidX Media3 / ExoPlayer Core** | `MusicService.kt`, `PlayerConnection.kt` | Background playback, foreground service | **Implemented & tested** | Background foreground service with MediaSession, Android Auto, lockscreen media controls. |
| **Direct Queue Long-Press Reordering** | `Queue.kt`, `QueueReorderingHelper.kt` | Queue sheet long-press drag | **Implemented & tested** | Direct drag without edit mode, haptic feedback, auto-scroll at bounds, TalkBack custom actions. |
| **Shuffle-Aware Timeline Coordination** | `MusicService.kt`, `QueueReorderingTest.kt` | Queue reorder while shuffle active | **Implemented & tested** | Permutes `DefaultShuffleOrder` in-place; uninterrupted timeline without audio restarts. |
| **Gapless Playback & Crossfade** | `MusicService.kt`, `CoilBitmapLoader.kt` | Settings > Playback > Crossfade | **Implemented but not device-tested** | Soft volume interpolation and buffer pre-loading. |
| **DJ Mode & Transitions** | `BeatTracker.kt`, `KeyDetector.kt`, `EnergyProfile.kt` | Settings > Playback > DJ Transitions | **Implemented & tested** | Harmonic key and tempo matching between adjacent tracks. |
| **Audio Equalizer & Presets** | `EqualizerViewModel.kt`, `EqualizerScreen.kt` | Settings > Audio > Equalizer | **Implemented but not device-tested** | Multi-band graphic equalizer with Bass Boost and custom curves. |
| **Persistent Queue Restoration** | `MusicService.kt`, `QueueDao.kt` | Application cold start | **Implemented & tested** | Rebuilds timeline state and last played track across app restarts. |
| **Audio Focus Handling** | `MusicService.kt` | System audio interruptions | **Implemented & tested** | Preserves `playWhenReady` intent; ducks on transient loss, resumes on focus gain. |

---

## 2. Domain 2: Adaptive Session Intelligence (ASI) & Autoplay

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **Project ASI 1.0 Feedback Engine** | `ASIManager.kt`, `ASIFeedbackCollector.kt` | Automated session tracking | **Implemented & tested** | Centralized classifier: natural completion (+), skip (-), replay (+1.8), manual pick (0.0). |
| **Negative Signal Journal** | `NegativeSignalJournal.kt`, `CanonicalTrackId.kt` | Track skips < 30s | **Implemented & tested** | Atomic Jetpack DataStore journal (`asi_negative_journal`), bounded to 200 items, 14-day TTL. |
| **Session & Taste Profiling** | `ASISessionProfile.kt`, `ASITasteProfile.kt` | Real-time listening analysis | **Implemented & tested** | 8 atmosphere profiles, exponential half-life decay ($t_{1/2}=30$ days), 65% session momentum carryover. |
| **Candidate Generation & Beam Search** | `ASICandidateGenerator.kt`, `ASISequenceOptimizer.kt` | Queue continuation | **Implemented & tested** | Multi-source pooling, explainable 5-dimension scoring, width $K=4$, horizon $H=5$. |
| **Autoplay Infinity Continuation** | `MusicService.kt`, `AutoPlayKey` | Queue / Player infinity chip | **Implemented & tested** | InnerTube automix fallback with YouTube Radio stations (`RDAMVM...`). |
| **Two-Phase Progressive Selection** | `RecommendationPostProcessor.kt` | Continuation pipeline | **Implemented & tested** | Fresh candidates strictly precede 40-track cooldown window pool. |
| **Persistent Disliked Songs** | `DislikeManager.kt`, `DislikedSongDao.kt` | Player/Queue 3-dot menu, Settings | **Implemented & tested** | Schema v38 table `disliked_song`; instant skip, recommendation blacklist, restore screen. |

---

## 3. Domain 3: Stream Providers & Add-ons

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **YouTube Music InnerTube Client** | `YTPlayerUtils.kt`, `YouTube.kt` | Default playback source | **Implemented & tested** | Resolves WebM/Opus and M4A/AAC streams via multi-client rotation. |
| **Terminal Error Fast-Abort** | `BotDetectionMitigator.kt` | Expired / private video streams | **Implemented & tested** | Instantly aborts rotation on permanent 403/private/copyright errors. |
| **HTTP Streaming Add-on Protocol** | `AddonClient.kt`, `AddonSource.kt` | Settings > Add-ons | **Implemented & tested** | Standard `/manifest.json`, `/search`, `/stream/{id}` JSON specification. |
| **Add-on In-Flight Deduplication** | `SharedCalls.kt` | Concurrent queries | **Implemented & tested** | Prevents redundant network queries to self-hosted add-on servers. |
| **Add-on Rate-Limit & Backoff** | `AddonClient.kt` | HTTP 429 response | **Implemented & tested** | Honors server `Retry-After` headers and applies client-side cooldowns. |
| **Silent Track Mismatch Guard** | `TrackMatcher.kt` | Add-on result resolution | **Implemented & tested** | Multi-tier scoring strictly rejects live recordings/remixes for album tracks. |
| **Secondary Hi-Fi Providers (Spine, Tidal, JioSaavn)** | `ExternalStreamProviderResolver.kt` | Settings > Audio > External Providers | **Experimental** | User opt-in; subject to third-party availability. Fallback to YouTube on failure. |
| **Per-Network Quality Resolution** | `MusicService.kt`, `NetworkConnectivityObserver.kt` | Settings > Audio Quality | **Implemented & tested** | Independent Wi-Fi and Cellular bitrate selectors; seamless handover without stutter. |

---

## 4. Domain 4: Search & Discovery

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **Debounced Instant Search** | `SearchViewModel.kt`, `SearchScreen.kt` | Top search bar | **Implemented & tested** | 300 ms debounce prevents redundant network calls during rapid keystrokes. |
| **Category Filter Chips** | `SearchFilterChip.kt` | Search results screen | **Implemented & tested** | Fast filtering across Songs, Videos, Albums, Artists, and Playlists. |
| **Public Spotify Playlist Import** | `SpotifyPlaylistParser.kt`, `SpotifyImportViewModel.kt` | Settings > Import Spotify Playlist | **Implemented & tested** | Unauthenticated embed/GraphQL pagination (1,000+ tracks); background notification support. |
| **Speed Dial Random Discovery** | `HomeViewModel.kt`, `SpeedDialDiceButton.kt` | Home screen speed dial matrix | **Implemented & tested** | 5-dot rolling dice button; samples recent events, excludes top-30 most played and recent-50. |
| **Artist Discovery Carousels** | `HomeFeedBuilder.kt` | Home feed | **Implemented & tested** | "More From This Artist" dynamic carousels derived from frequent listening history. |

---

## 5. Domain 5: Library, Persistence & Downloads

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **Room Schema v38 Database** | `InternalDatabase.kt`, `DatabaseDao.kt` | Core persistence (`song.db`) | **Implemented & tested** | 18 tables, SQLite identityHash `de260a1f8fcef65830e287325ab3b697`. |
| **Local Media Indexing** | `LocalMediaScanner.kt`, `MediaStore` | Library > Local Files | **Implemented but not device-tested** | Scans device audio via `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`. |
| **Offline Downloads Manager** | `DownloadService.kt`, ExoPlayer `DownloadManager` | Track / Album download button | **Implemented & tested** | Persistent offline media storage within app-private directory. |
| **Lossless FLAC Export** | `SongExporter.kt`, `FlacTagger.kt` | Downloaded track > Export | **Implemented & tested** | SAF export; byte-exact audio frame copying, Vorbis comments and cover art embedding. |
| **Non-FLAC Clean Remux** | `SongExporter.kt` | Lossy stream > Export | **Implemented & tested** | Lossy Opus/AAC streams are cleanly packaged as `.m4a` rather than mislabeled as FLAC. |

---

## 6. Domain 6: Lyrics & Canvas

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **Synchronized Karaoke Lyrics** | `LyricsViewModel.kt`, `LyricsScreen.kt` | Player screen lyrics tab | **Implemented & tested** | Real-time word-by-word highlighted lyrics with auto-scroll. |
| **Multi-Provider Lyrics Cascade** | `LyricsProviderResolver.kt` | Lyrics resolution pipeline | **Implemented & tested** | Fallback cascade: KuGou, LRCLIB, Musixmatch, Better Lyrics, Paxsenix. |
| **Video Canvas Playback** | `CanvasPlayer.kt`, `CanvasSubmodule` | Player background | **Experimental** | Short looping background canvas visuals for supported tracks. |

---

## 7. Domain 7: Audio Diagnostics & Output Route Telemetry

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **Decoder Input Monitoring** | `AudioDiagnostics.kt` | Player screen media info | **Implemented & tested** | Inspects `AnalyticsListener.onAudioInputFormatChanged()`: codec, sample rate, bit depth, bitrate. |
| **Downgrade Detection** | `AudioDiagnostics.kt`, `AudioQualityBadge.kt` | Player quality badge | **Implemented & tested** | Dynamically flags streams when lossless claim is fed lossy Opus/AAC or downsampled. |
| **Physical Route Classification** | `OutputRouteTracker.kt`, `AudioDeviceCallback` | Player media info dialog | **Implemented but not device-tested** | Identifies Speaker, Wired Headset, USB DAC, HDMI/eARC sinks. |
| **Bluetooth A2DP Codec Telemetry** | `BluetoothAudioTracker.kt` | Bluetooth connected state | **Implemented but not device-tested** | Interrogates Android `BluetoothA2dp` for LDAC, aptX HD, AAC, SBC negotiated codec info. |

---

## 8. Domain 8: User Interface, Theming & Widgets

| Feature | Primary Classes | Entry Point | Status | Limitations / Notes |
|---|---|---|---|---|
| **Liquid Glass Styling** | `Modifier.liquidGlass`, `Kyant0/backdrop` | Global Compose UI | **Implemented & tested** | Hardware-accelerated backdrop blur and refractive frosted glass surfaces. |
| **Dynamic Palette Theming** | `ColorPalette.kt`, `MaterialTheme` | Settings > Appearance | **Implemented & tested** | Derives color palette dynamically from active album art or Material You system accent. |
| **Pure Black OLED Mode** | `ThemeViewModel.kt` | Settings > Appearance > Pure Black | **Implemented & tested** | Configures pure `#000000` background for maximum OLED battery savings. |
| **Preset Export & Import** | `PresetManager.kt` | Settings > Appearance > Presets | **Implemented & tested** | Archive import/export via `.napsterpreset` files (retained for backward compatibility). |
| **Streamlined Top Header** | `HomeScreen.kt` | Home screen top bar | **Implemented & tested** | Displays clean brand wordmark; redundant settings icon removed (accessed via bottom dock). |
| **App Usage Telemetry Graphs** | `AppUsageScreen.kt`, `AppUsageTelemetry.kt` | Settings > App Usage | **Implemented & tested** | Real-time cubic Bézier curves for CPU load, GPU frame duration, and data usage with 0 tracking daemons. |
| **Homescreen Widgets (3 variants)** | `MetrolistWidgetManager.kt`, `MusicWidgetReceiver.kt`, `TurntableWidgetReceiver.kt`, `LyricsWidgetReceiver.kt` | Android launcher widget picker & home screen | **Implemented & tested** | 2×2 Rift Dot Player, 4×2 Rift Lyrics Player, and 4×2 Rift Vinyl Deck with smooth 25 FPS animations, partial RemoteViews updates without full re-inflation, dedicated launcher preview drawables/layouts, and clean turntable styling without stray outlines or badges. |

---

## 9. Intentionally Excluded Legacy Features

The following capabilities were present in ancestral or upstream repositories and are **permanently removed**:

| Excluded Feature | Engineering Rationale |
|---|---|
| **Donation / In-App Support Prompts** | Personal payment handles (UPI IDs) and donation dialog prompts were completely excised for user privacy, neutrality, and zero-commercialization integrity. |
| **Listen Together / Social Rooms** | Required external WebSocket infrastructure and user identity servers. Rift is a serverless client player. |
| **Private Spotify Account Login** | Required fragile WebView scraping and session hijacking. Replaced by unauthenticated public playlist parsing. |
| **Microphone / Shazam Audio Recognition** | Required `RECORD_AUDIO` permission and remote daemons. Schema entity `RecognitionHistory` retained solely for migration compatibility. |
| **Discord Rich Presence (Kizzy)** | Deprecated daemon requiring background IPC and third-party tokens. |
| **AI Playlist Modification & Translation** | Proprietary remote LLM endpoints with high latency and external dependencies. |
| **Supabase / Cloud Account Sync** | Cloud accounts eliminated in favor of 100% on-device local SQLite data ownership. |
| **Obsolete Developer Diagnostics** | Stale playback probe screens removed in favor of clean production user preferences. |
| **Bit-Perfect / DAC Direct Bypass Claims** | Unachievable on standard Android audio architectures without proprietary OEM kernel drivers. Audio routes cleanly through standard Android AudioTrack. |
| **Wear OS, Android TV & Desktop Builds** | Scope is strictly focused on high-performance Android mobile and Android Auto form factors. |
