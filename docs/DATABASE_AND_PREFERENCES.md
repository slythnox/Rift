# Database and Preferences Architecture

This document describes the local persistence layer of Rift, including Room SQLite schemas, migrations, Data Access Objects (DAOs), transaction safety, and Jetpack DataStore preferences.

---

## 1. Room SQLite Database (`com.rift.music.db`)

Rift relies on AndroidX Room for all structured local persistence. The database definition and configuration are maintained in `MusicDatabase.kt` / `InternalDatabase` and injected via Dagger Hilt in `AppModule.kt`.

- **Current Schema Version:** 38
- **Primary Schema Export Directory:** `app/schemas/com.rift.music.db.InternalDatabase/` (with `app/schemas/com.beatwave.music.db.InternalDatabase/` retained for historical discovery).
- **Database File:** `song.db` within the application private storage directory (`/data/data/com.napster.music/databases/`).
- **Compiled Identity Hash:** `de260a1f8fcef65830e287325ab3b697` (identical across old and new namespace schema 38 exports, ensuring non-destructive opening).

### Core Database Entities

| Entity | Primary Purpose |
|---|---|
| `Song` | Persists track metadata, including YouTube video ID, track title, artists, duration, thumbnail URLs, and play counts. |
| `Album` | Stores album metadata, release year, artist relationships, and cover art references. |
| `Artist` | Stores artist profiles, thumbnail references, and followed status. |
| `Playlist` & `PlaylistSongMap` | Stores custom user playlists and ordered relational mappings of songs to playlist entries. |
| `Event` | Stores timestamped playback events utilised for Daily Discover algorithms, Quick Picks, and listening statistics. |
| `SearchHistory` | Caches recent search queries locally on device. |
| `DownloadEntity` / `FormatEntity` | Tracks offline media segments, formats, file paths, and download completion states. |
| `RecognitionHistory` | **Compatibility entity:** Preserved in the Room schema to ensure that database migrations from earlier application versions remain continuous without requiring schema destruction or complex table migrations. |
| `DislikedSongEntity` | **Schema v38 Entity:** Persists disliked tracks (`disliked_song` table) with YouTube/Spotify IDs, titles, artists, albums, duration, and normalized title/artist tokens for instant cross-provider exclusion. |

### Room Database Migration 37 → 38 (`DatabaseMigration37To38Test.kt`)

Room schema version 38 introduces the `disliked_song` table along with a composite index:
- **Table Definition:** `disliked_song (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT, duration INTEGER NOT NULL, thumbnailUrl TEXT, normalizedTitle TEXT NOT NULL, normalizedArtist TEXT NOT NULL, dislikedAt TEXT NOT NULL)`
- **Composite Index:** `index_disliked_song_normalizedTitle_normalizedArtist` on `(normalizedTitle, normalizedArtist)` for fast cross-provider dislike lookups.
- **Auto-Migration Verification:** Validated via an in-memory SQLite fixture test (`DatabaseMigration37To38Test`) that applies `InternalDatabase_AutoMigration_37_38_Impl` directly to a populated version-37 schema fixture, proving all 18 existing tables and representative user records survive intact.

### Data Access Object Optimisations (`DatabaseDao.kt`)

1. **Atomic Play-Count Transactions:** The `incrementPlayCount(songId)` operation is an atomic `@Transaction`. It combines `insert(PlayCountEntity(..., 0))` using `OnConflictStrategy.IGNORE` with `incrementPlayCount(songId, year, month)`, entirely eliminating previous `runBlocking` calls and thread starvation risks.
2. **Strictly Bounded Seed Queries:** Queries feeding recommendation algorithms (`recentEvents(limit: Int)`, `mostRecentLikedSong()`, `mostRecentLibrarySong()`) enforce strict SQL `LIMIT` clauses directly in SQLite, avoiding full-table memory dumps.
3. **Lightweight Identification Projections:** List item rows query targeted ID projections (`albumSongIds(albumId)`, `playlistSongIds(playlistId)`) returning `List<String>` rather than constructing thousands of heavyweight `Song` entities just to display an offline badge.

---

## 2. Jetpack DataStore Preferences

Reactive user configuration is maintained via AndroidX DataStore Preferences, defined primarily across `com.rift.music.constants.PreferenceKeys`.

### Core Preference Domains

- **Audio & Playback:**
  - Audio quality level (`AudioQualityKey`)
  - Volume normalisation (`AudioNormalizationKey`)
  - Crossfade transition duration (`CrossfadeDurationKey`)
  - Persistent queue state (`PersistentQueueKey`)
  - Sleep timer interval and auto-load more recommendations (`AutoLoadMoreKey`)
  - Persistent Autoplay queue continuation (`AutoPlayKey`)
- **Appearance & Theme:**
  - Dark mode preference (`DarkModeKey`)
  - Pure Black OLED theme toggle (`PureBlackKey`)
  - Dynamic artwork colour derivation (`DynamicColorKey`)
  - Liquid glass backdrop blur enable (`LiquidGlassKey`)
  - Active theme and layout presets (`ThemePresetKey`)
- **Content & Localization:**
  - Content language (`ContentLanguageKey`) and regional market country code (`ContentCountryKey`)
  - Explicit content filter toggle (`HideExplicitKey`)
- **Privacy & Security:**
  - Pause listening history recording (`PauseListenHistoryKey`)
  - Pause search query caching (`PauseSearchHistoryKey`)
  - Secure window flag / screenshot prevention (`DisableScreenshotKey`)
- **Integrations:**
  - Last.fm session credentials and scrobble state (`LastFmSessionKey`, `LastFmScrobbleKey`)
  - ListenBrainz user token and scrobble enable (`ListenBrainzTokenKey`, `ListenBrainzEnabledKey`)

### Bounded Negative Feedback Journal (si_negative_journal)

In addition to standard user preferences, AndroidX DataStore is leveraged for the Project ASI negative feedback journal (si_negative_journal):
- **Atomic Operations:** Stores serialized JSON records capturing negative user feedback signals (intentional early skips, disliked entries) with monotonic sequence tokens.
- **Bounding & Expiration:** Automatically pruned to a hard ceiling of 200 items, with records older than 14 days expired on each update transaction.
- **Zero SQLite Schema Churn:** Persisting negative signals within DataStore avoids continuous database writes or schema modifications on frequent track transitions.

---

### Concurrency and Thread Safety

Preferences read within `MusicService` callbacks are mirrored in thread-safe in-memory variables and updated via a background coroutine flow collector. Direct reads from ViewModels execute via non-blocking Kotlin flows (`dataStore.data.first()`), preventing UI frame drops and main-thread deadlocks.
