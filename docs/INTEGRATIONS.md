# Integrations Architecture

This document describes all verified optional third-party integrations in Rift, including modular HTTP add-ons, scrobbling protocols, alternative stream resolution, canvas providers, lyrics engines, and release distribution.

---

## 1. Architectural Philosophy

All integrations are strictly optional augmentations to the core streaming experience. The following invariants govern all integration implementations:
1. **Zero Playback Disruption:** Failure, timeout, rate-limiting, or invalid credentials within any integration must never interrupt, delay, or terminate active playback.
2. **Graceful Fallback:** When a provider cannot fulfill a request, the system degrades silently or displays a non-blocking toast warning, reverting automatically to primary YouTube Music resolution.
3. **Local Independence:** The application maintains full offline and local library utility even when all external integration endpoints are unreachable.

---

## 2. Modular HTTP Streaming Add-ons (`AddonClient.kt`, `AddonSource.kt`)

Rift supports an open JSON streaming protocol allowing users to connect modular HTTP streaming add-on servers:
- **Specification:** Servers declare capabilities via `/manifest.json` (`search`, `stream`, quality tiers `LOSSLESS`, `HIGH`, `LOW`).
- **In-Flight Deduplication (`SharedCalls.kt`):** Concurrent queries for the same query string are coalesced into a single network call.
- **Rate-Limiting Compliance:** Fully parses HTTP 429 and `Retry-After` headers, enforcing client-side cooldowns without server flooding.
- **Silent Track Mismatch Guard (`TrackMatcher.kt`):** Uses multi-factor string normalization and duration comparison to reject live recordings, acoustic covers, or remixes when the user requested studio recordings.
- **Direct Stream URL Optimization:** Bypasses intermediate `/stream/{id}` calls if the search result payload directly supplies `streamURL`.

---

## 3. Scrobbling Integrations

### Last.fm (`lastfm/` module)
- **Protocol:** Standard Last.fm Web Services API v2.0.
- **Configuration Screen:** `settings/integrations/lastfm` (`LastFMSettings.kt`).
- **Data Flow:** Sends immediate "Now Playing" updates upon track commencement, followed by scrobble submission once a track plays for at least 50% of its duration or 4 minutes.
- **Credential Storage:** User session keys are persisted securely within AndroidX DataStore (`LastFmSessionKey`).

### ListenBrainz (`IntegrationScreen.kt`)
- **Protocol:** ListenBrainz JSON REST API (`/1/submit-listens`).
- **Configuration:** Accessible directly under `Settings > Integrations`.
- **Functionality:** Submits scrobbles to the community-driven MetaBrainz ecosystem.
- **Credential Storage:** User API tokens reside in `ListenBrainzTokenKey`.

---

## 4. Alternative Audio Stream Providers (`ExternalStreamProviderResolver.kt`)

Users can optionally configure alternative stream resolution providers for higher bitrate or alternate audio mastering:
- **Tidal / JioSaavn / Spine:** Optional provider clients queried according to user-configured priority rankings.
- **Scoped YouTube Metadata Sharing:** To avoid repeated network latency, metadata from YouTube is resolved once lazily (`getYtMetadata()`) and shared across all candidate fallback providers.
- **Resilient Fallback:** Any HTTP 403, network timeout, or metadata mismatch automatically diverts the playback request to the primary YouTube Music stream without stalling ExoPlayer.

---

## 5. Synchronised Lyrics Providers

Lyrics are queried asynchronously according to user-customised priority rankings across multiple independent modules:
- **KuGou (`kugou/`):** High-precision time-synchronised and translated lyrics.
- **LRCLIB (`lrclib/`):** Open-source, community-governed synchronised lyrics database.
- **Musixmatch (`musixmatchlyrics/`):** Fallback provider for mainstream commercial releases.
- **Better Lyrics (`betterlyrics/`):** TTML formatted synchronised lyrics.
- **Paxsenix (`paxsenixlyrics/`):** Additional open API fallback.
- **InnerTube:** Native timed and plain text lyrics extracted directly from YouTube Music video payloads.

---

## 6. Visual Canvas Providers

The player UI supports dynamic background video loops and canvas animations:
- **Apple Music Canvas (`applecanvas/`):** Fetches official artist video loops and motion artwork.
- **Tidal Canvas (`canvas/`):** Resolves animated track visuals from Tidal CDN endpoints.
- **Echo / Vivi Music Canvas (`vivimusiccanvas/`):** Dynamic canvas video playback.
- **Artist Video (`artistvideo/`):** YouTube-based looping artist background visualisers.

---

## 7. Release Distribution

- **GitHub Releases:** Official releases and APK artifacts are distributed via `github.com/slythnox/Rift/releases`.
- **Packaging:** Builds are produced as signed APKs via automated GitHub Actions CI workflows. In-app self-updating was removed in favour of standard platform installation and package managers (F-Droid / Obtainium / direct APK).
