# Ride Reveal

## Problem Statement

How might we turn the moment a new ride finishes syncing into a small, earned moment of recognition — Strava's post-upload "nice work" beat — without dragging in the social/edit-screen baggage Strava built around it?

Today, a completed import batch (via Dropbox auto-sync or pull-to-refresh) only ever produces a plain Snackbar summary ("Imported 3 new rides, 1 failed..."). There's no moment that treats an actual new ride as an event worth noticing.

## Recommended Direction

**Ride Reveal**: a dedicated hero sheet, shown once at the end of an import batch, but only for a ride that is genuinely new — not a historical backfill.

**Trigger condition:** Fires only for the single newest file in the batch, and only if that file's parsed ride start-time is later than the start-time of every ride already persisted in the database *before this sync began*. This must be computed from the parsed FIT start-time, not filename or processing order (`DropboxSyncService` sorts files by filename before import, which doesn't reliably track ride chronology), and must be compared against pre-existing DB state, not sibling files in the same batch — otherwise a first-time Dropbox connection or a fresh install that backfills hundreds of historical rides will incorrectly fire the reveal for whichever backfilled file happens to be chronologically last in that batch.

**Content — two-tier fallback in the hero slot:**

- **Tier 1 — Achievement.** Fires if this ride ranks **1st–3rd all-time**, or **1st–3rd within the current calendar year**, on any of:
  - Power-curve bests: 5s / 1min / 5min / 20min power (`BestEffortCalculator`)
  - Ride-level milestones: longest ride (by distance), most elevation climbed, fastest average speed
  
  If multiple qualify, tie-break in this order: all-time beats this-year → rank 1 beats 2 beats 3 → ride-level milestones beat power-curve bests.

- **Tier 2 — Fallback.** If nothing in Tier 1 applies, show plain absolute stats for the ride (distance, duration, elevation gained). Always available, since it's already computed synchronously by `SessionMetricsCalculator` during import — guarantees the sheet never has nothing to say.

Ride finalizes automatically with no review/edit step (fits a private, single-user, local-first app with no social stakes to protect).

**Why this shape:** every input the hero sheet needs is already computed *synchronously* inside `FitImportService.importFile` before `HomeViewModel.recluster()` even runs — raw session stats and best-effort records are both in hand by the time a batch finishes. Route/interval clustering (`RouteClusteringService`, `IntervalClusteringService`) is deliberately excluded from the hero slot because it runs afterward on a separate `@ApplicationScope` coroutine with no guaranteed completion time relative to the reveal — including it would mean either adding latency to wait on it, or risking stale/missing content.

## Key Assumptions to Validate

- [ ] The rank check ("how many rides beat this one on metric X, scoped to all-time / this year") is a cheap, correct query against existing tables — sketch the actual query (likely `COUNT` of rows with a greater value, or `ORDER BY ... LIMIT`) against `bestEffortRepository` and session stats before committing to the design.
- [ ] The backfill/cold-start edge case is worth guarding against in practice — confirm whether reconnecting Dropbox or reinstalling is something that actually happens in your usage, since the guard is a real (if small) piece of added logic.
- [ ] "Newest ride ever" is a reliable enough proxy for "this just happened" for a single-rider setup — holds for your actual usage pattern (sync same-day/next-day), would not hold for a shared Dropbox folder across multiple riders, which isn't your setup.

## MVP Scope

**In:**
- New `ImportUiState` case for the reveal sheet, gated by the "newest file, newer than all prior DB rides" rule
- Tier 1 rank check across the 4 power-curve durations + 3 ride-level milestones, each checked both all-time and current-year
- Tie-break priority logic when multiple achievements qualify
- Tier 2 plain-stats fallback card
- Bottom sheet UI replacing (or supplementing) the current Snackbar for this one specific case

**Out (see Not Doing):** rolling-average comparisons, route/interval clustering content in the hero slot, review/edit step, anything social.

## Not Doing (and Why)

- **Route/interval clustering tie-in** ("6th lap of Route X") — the most narratively interesting content, but it runs on an async coroutine with no completion guarantee relative to the reveal moment. Revisit once/if the reveal sheet can be shown or updated independently of the import call.
- **Rolling-average delta** ("+12% avg power vs. last 10 rides") — superseded by the rank-based approach, which avoids the "is up actually good?" ambiguity a raw percentage delta has, and needs a similar new query anyway so there's no feasibility win to doing both.
- **Review/edit step before finalizing** — no social stakes to protect in a private single-user app; auto-finalize is strictly simpler and was an explicit choice.
- **Multi-format support** — stay scoped to `.fit`, the only format the pipeline ingests today.
- **Dedup/conflict resolution** — existing SHA-1-based duplicate detection in `FitImportService` is assumed sufficient; not touching it here.
- **Social/feed features** — out of scope for Velometrics per the app's split from Route IQ/Ride-Graph; no kudos, comments, or activity feed.

## Open Questions

- Should the hero sheet be dismissible/persistent (does it need to be re-viewable from history), or is it strictly a one-time, ephemeral moment at import time?
- Does a Tier 1 achievement need any additional per-metric threshold (e.g. is a "3rd place this year" out of only 4 rides this year still worth celebrating), or is raw rank sufficient regardless of sample size?
- At what ride-history volume does the rank query need an index or precomputed leaderboard table rather than a live scan? (Likely a non-issue at personal scale, but worth a note if the DB grows into the thousands of sessions.)
