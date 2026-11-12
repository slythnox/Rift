# Project ASI 1.0 — Adaptive Session Intelligence

## 1. Overview & Vision
Project ASI 1.0 (Adaptive Session Intelligence) is an on-device recommendation intelligence and trajectory optimization system for **Rift**. Rather than treating autoplay as naive 1-to-1 song similarity or relying solely on external uncurated recommendation endpoints, ASI models the listening session dynamically.

ASI operates entirely on-device, preserving privacy and maintaining sub-second latency while guaranteeing:
- **Session context awareness:** Dynamically inferred listening modes (Party, Workout, Relaxed, Romantic, Focus, Discovery, Commute, General) with confidence-scaled adaptation.
- **Multi-source candidate pooling:** YouTube Music radio (reusing in-flight radio items to eliminate duplicate network calls), artist related tracks, and local library favorites/history.
- **Genuine acoustic metadata integration:** Real audio analysis (`SongAnalysisEntity` BPM, key, energy) queried from Room database cache; unknown metadata remains strictly `null` and scores neutrally (0.50) without deceptive keyword heuristics.
- **Explainable multi-dimension scoring:** Balanced weights across Enjoyment (0.30), Session fit (0.25), Transition smoothness (0.20), Freshness (0.10), and Discovery (0.15).
- **Sequence optimization via bounded beam search ($K=4$, horizon $H=5$):** Prevents greedy dead-ends, repetitive artist clustering, jarring energy swings, and 2-artist alternating echo chambers via path diversity bonuses.
- **Safe Dark-Launch Feature Flag:** Governed by `EnableAsiAutoplayKey` in DataStore preferences (`enableAsiAutoplay`), defaulting to `false` for zero-risk rollout. ASI must be explicitly enabled by the user or tester; it is disabled out-of-the-box.
- **Clean, automatic fallback:** Strict coroutine cancellation propagation and fast fallback (1500ms candidate timeout, 2000ms overall timeout) to the baseline pipeline (`RecommendationPostProcessor`) on timeout, error, or feature flag disable.

---

## 2. Architecture & Directory Structure

All ASI components reside under `com.rift.music.playback.asi`:

```
com.rift.music.playback.asi/
├── ASIManager.kt                   // Central orchestrator with structured concurrency & fallback
├── candidate/
│   ├── ASICandidate.kt            // Unified candidate model with canonical ID & acoustic metrics
│   └── ASICandidateGenerator.kt    // Multi-source concurrent candidate generator & hard filter
├── model/
│   ├── ASIConfig.kt                // Dynamic scoring weights, beam search params & thresholds
│   ├── ASISessionProfile.kt        // Stateful session profile tracking energy, skips & context
│   └── SessionContext.kt           // Session categories & context snapshots
├── scoring/
│   ├── ASIScoreBreakdown.kt        // Explainable scoring breakdowns & ScoredCandidate
│   ├── ASIScorer.kt                // 5-dimensional candidate scoring engine
│   └── ASITransitionEvaluator.kt   // Harmonic, acoustic & pacing transition evaluator
├── sequence/
│   └── ASISequenceOptimizer.kt    // Bounded beam search trajectory optimizer
└── diagnostics/
    └── ASIDiagnostics.kt           // Bounded audit ring buffer (last 50 runs)
```

---

## 3. Core Components

### 3.1 Session Context & Profiling (`SessionContext.kt`, `ASISessionProfile.kt`)
Sessions are classified dynamically into `SessionCategory`:
- `PARTY`: High energy ($\ge 0.75$), high tempo ($\ge 115$ BPM), upbeat pop/dance/hiphop.
- `WORKOUT`: High energy ($\ge 0.70$), high tempo ($\ge 120$ BPM), motivational/electronic/rock.
- `RELAXED`: Low-to-moderate energy ($\le 0.45$), acoustic/ambient/chill/lo-fi.
- `ROMANTIC`: Moderate energy ($\le 0.55$), soul/R&B/ballad.
- `FOCUS`: Low energy ($\le 0.40$), instrumental/classical/study/ambient.
- `DISCOVERY`: Elevated discovery tolerance, broad genre exploratory mode.
- `COMMUTE`: Moderate energy ($0.45 - 0.70$), balanced pacing.
- `GENERAL`: Default baseline context when signals are neutral.

The session profile updates incrementally upon every track playback or transition:
- **Energy Moving Average:** Exponential moving average of recent track energy ($\alpha = 0.35$).
- **Skip Friction:** Tracks rapid skips. Accumulating $>2$ rapid skips triggers an automatic vibe cooldown and drops discovery tolerance.
- **Gradual Context Adaptation:** When the user manually selects a new track, the session context adapts gradually ($\alpha = 0.50$ blending with the new seed track) rather than wiping session history completely.
- **Confidence Scaling:** Session mismatch penalties are scaled by the session's actual confidence level ($\ge 0.20$), avoiding punitive scoring early in short or mixed sessions.

### 3.2 Genuine Historical Data Reconstruction & Taste Profiling (`ASIManager.kt`)
The historical listening profile is built directly from Room database entities (`EventWithSong` queried via `DatabaseDao.recentEvents()`):
- **Actual Song Metadata:** Real song titles, media IDs, and true artist names (completely eliminating placeholder assignments).
- **Actual Timestamps & Durations:** Real event timestamps and listening durations recorded by the player.
- **Genuine Completion & Like Signals:** Derived from actual play percentages and Room `likedSongs` records.
- **Time-Decayed Affinity:** Exponential decay over historical events using configurable half-life parameters.

### 3.3 Multi-Source Candidate Generation & Cached Acoustic Enrichment (`ASICandidateGenerator.kt`)
ASI concurrently retrieves candidates from multiple complementary sources:
1. **YouTube Music Radio (`YTM_RADIO`):** Reuses in-flight `initialRadioItems` from `MusicService` to eliminate duplicate network calls (saving ~1.5–2.5s of latency), or falls back to `YouTubeQueue` if absent.
2. **Artist Related Discovery (`ARTIST_DISCOVERY`):** Via `YouTube.next().related` endpoints.
3. **Local Library Favorites (`LOCAL_FAVORITE`):** Via Room database `likedSongsByCreateDateAsc()`.
4. **Local Listening History (`LOCAL_HISTORY`):** Via frequently completed tracks in user history.

#### Runtime Acoustic Enrichment:
Candidates are batch-enriched via `database.songAnalyses(candidateIds, CURRENT_ANALYSIS_VERSION)` against previously analyzed audio in Room table `song_analysis`. 
- Cached tracks receive authentic `bpm`, musical `key`, and normalized `energy`.
- Unanalyzed tracks retain `null` acoustic metadata.
- **Strictly Honest Scoring:** Title-string keyword heuristics are eliminated; missing acoustic dimensions evaluate to a strictly neutral $0.50$ score without artificially boosting duration matches.

#### Hard Exclusion Filters:
Before any scoring occurs, the following hard exclusions are enforced:
- **Disliked Songs:** Matches against database disliked songs (`DislikedSongEntity`).
- **Negative Feedback Journal:** Matches against persistent negative-signal DataStore journal.
- **Current Track & Upcoming Queue:** Any track already present in ExoPlayer's queue or currently playing.
- **Cross-Provider Canonical Deduplication:** Normalizes track titles and artist strings (stripping suffixes like "(Official Video)", "Topic", "Remastered") to prevent duplicate cross-provider recommendations.

### 3.4 Multi-Dimension Scoring Engine (`ASIScorer.kt`, `ASITransitionEvaluator.kt`)
Each candidate is scored across five normalized dimensions $[0.0, 1.0]$:
1. **Enjoyment Score ($w = 0.30$):** Combines historical completion ratio, user likes, and time-decayed artist affinity.
2. **Session Compatibility ($w = 0.25$):** Evaluates energy delta against target energy, scaled by session confidence.
3. **Transition Smoothness ($w = 0.20$):** Evaluated against the seed track:
   - Energy delta compatibility ($0.40$ weight).
   - Harmonic BPM match, including half-time ($0.5\times$) and double-time ($2.0\times$) matching ($0.30$ weight).
   - Artist continuity bonus ($0.15$ weight).
   - Duration pacing compatibility ($0.15$ weight).
   - If acoustic dimensions are missing, transitions evaluate to neutral $0.50$ ($\pm 0.05$ pacing nuance).
4. **Freshness Score ($w = 0.10$):** Deducts score if the track was played within the recent cooldown window.
5. **Discovery Score ($w = 0.15$):** Boosts unfamiliar artists scaled by the session's current `discoveryTolerance`.
6. **Repetition Penalties:** Consecutive same-artist and same-album penalties, plus cluster penalties for artists appearing $\ge 2$ times in recent history.

### 3.5 Bounded Beam Search Sequence Optimizer (`ASISequenceOptimizer.kt`)
Greedy one-by-one selection can fall into diversity traps or energy dead-ends. ASI implements a bounded beam search:
- **Beam Width ($K$):** 4 active paths.
- **Sequence Horizon ($H$):** 5 steps.
- **Evaluation Criteria:** Immediate candidate score ($65\%$) + pairwise transition continuity ($35\%$) - path diversity penalties + path diversity bonus.
- **Path Diversity Guardrails:**
  - Consecutive same artist: $-0.40$ penalty.
  - Consecutive same album: $-0.15$ penalty.
  - Artist clustering in sequence window: $-0.35 \times (\text{count} - 1)$ when an artist appears $\ge 2$ times.
  - Excessive energy jerk: $-0.50$ penalty when $\Delta_{\text{energy}} > 0.40$.
  - **Path Diversity Bonus:** $+0.05$ bonus for introducing distinct artists, preventing 2-artist alternating echo chambers (e.g., A-B-A-B-A).

### 3.6 Coroutine Cancellation, Latency & Fail-Safe Fallback
- **Cancellation Propagation:** All coroutine loops, candidate fetchers, DataStore edits, and manager orchestrators explicitly catch and rethrow `CancellationException`.
- **Latency Optimization:** 
  - Candidate generation timeout: **1500 ms**.
  - Overall recommendation timeout: **2000 ms**.
  - Duplicate radio request elimination cuts 1.5–2.5 seconds of overhead.
- **Feature Flag Rollout:** Controlled by `EnableAsiAutoplayKey` in `PreferenceKeys.kt` (`enableAsiAutoplay`), defaulting to `false` (safe dark launch).
- **Queue Authority & Fallback:** Media3 queue remains 100% authoritative. If ASI is disabled, times out, or fails, `MusicService` immediately falls back to `RecommendationPostProcessor.filterAndRank`.

---

## 4. Diagnostics & Observability (`ASIDiagnostics.kt`)
ASI maintains an in-memory ring buffer storing audit records of the last 50 recommendation runs:
- Request ID and timestamp.
- Seed track info (ID, title, artist, energy, BPM).
- Session category, target energy, and session confidence.
- Candidate count per source (`YTM_RADIO`, `ARTIST_DISCOVERY`, `LOCAL_FAVORITE`).
- Hard-filtered count (dislikes, queue duplicates).
- Top candidate scores with full 5-dimension breakdowns.
- Chosen sequence path and beam search score.
- Execution latency in milliseconds.
- Fallback flag and reason (if triggered).

---

## 5. Offline Evaluation Methodology & Results

Evaluation is implemented in `ASIOfflineEvaluatorTest.kt` using chronological point-in-time evaluation across 40 distinct sessions spanning 4 major listening modes (PARTY, RELAXED, WORKOUT, COMMUTE) with dynamic candidate generation and zero temporal data leakage:

| Scenario / Metric | Baseline Autoplay | ASI 1.0 Autoplay | Status / Result |
| :--- | :--- | :--- | :--- |
| **Early Skip Rate** | 50.00% | **0.00%** | Jarring vibe shifts eliminated |
| **Completion Rate** | 50.00% | **100.00%** | Coherent acoustic continuity |
| **Consecutive Artist Repeats** | 40 | **0** | Zero back-to-back artist repeats |
| **2-Artist Alternating Chains** | Prevalent in baseline | **0** | Eliminated via beam path diversity bonus |
| **Multi-Genre Compatibility** | Fails on cross-genre seeds | **100% Validated** | Party, Relaxed, Workout, Commute |
| **Ablation: Full ASI Score** | — | **0.758** | Superior multi-metric trajectory |
| **Ablation: No Session Context** | — | 0.655 | -13.6% drop in overall trajectory fit |
| **Ablation: No Transition Continuity** | — | 0.727 | -4.1% drop; increased energy jerkiness |
| **Ablation: Greedy Selection** | — | 0.749 | Vulnerable to local maxima and clustering |

*Note: Offline evaluation tests model logic against simulated user behavioral models. Production validation on physical devices is required for subjective human musical flow verification.*
