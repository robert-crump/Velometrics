# Heart Rate Recovery (HRR60/HRR30) per interval

*Refines issue #177.*

## Problem Statement

How might we surface how quickly a rider's heart rate recovers after each detected interval — and later, whether that recovery rate is influenced by post-interval power, gets worse across reps in a session, or improves over months of training?

## Recommended Direction

Extend interval detection to look *past* the end of each interval into the trailing datapoints already sitting in memory during FIT import, and compute two standard sports-science metrics — **HRR60** and **HRR30** (heart-rate drop, in bpm, over the 60s/30s immediately following interval end) — plus **average power over the 60s after the interval**, which is the direct measurement needed to test the "does coasting recover faster than soft-pedaling" hypothesis from the issue.

This isn't a new subsystem. `IntervalDetector.detect()` already walks the full-resolution `datapoints` list (power, HR, timestamps) before anything gets downsampled or persisted — it currently just stops looking the moment `avgPower` drops back below threshold. The natural extension is: once an interval's `endIdx` is found, keep reading forward into the same list for up to 60 more seconds (bounded by session end or the start of the next detected interval) and compute the HR/power stats from that slice. The result lands as new nullable fields on `IntervalSession`, flows through the existing `intervalRepository.insertIntervals()` persistence path, and surfaces in the `IntervalListCard` on `SessionDetailScreen` — the same card that already shows duration, power, and (since #174) the repeated-interval name.

"Is HRR the right term?" — yes. It's the standard sports-science term, almost universally reported as HRR60 (sometimes HRR30/HRR120), attributed to parasympathetic reactivation. The literature on active-vs-passive recovery is mixed and mostly about *subsequent* interval intensity, not the decay curve itself — so the power-vs-recovery-rate hypothesis in the issue is a legitimately open question that this feature lets you test on your own data rather than something already answered in a paper.

## Key Assumptions to Validate

- [ ] **A single HR sample at t=end and t=end+60s is representative enough.** Real HR straps/optical sensors can spike or drop out right at the moment of interest. If early real-ride data looks noisy, revisit with a short rolling average (e.g. last 3–5 samples) instead of a raw single-sample read — don't over-build smoothing before seeing whether it's actually needed.
- [ ] **Trailing datapoints are reliably available and clean.** Validate on a handful of real rides that the post-interval window isn't full of GPS/HR gaps that would make hrr60/avgPower60sAfter meaningless.
- [ ] **Rides with power also tend to have HR.** Interval detection already requires `hasPower`; confirm this doesn't leave HRR60 null on most of your own interval-heavy rides (if your power meter and HR strap are usually paired, this is probably fine).

## MVP Scope

**In:**
- New nullable fields on `IntervalSession`: `hrr60: Int?`, `hrr30: Int?`, `avgPower60sAfter: Int?` (average power over the 60s window after interval end — the direct measurement for the coasting-vs-soft-pedaling hypothesis).
- A way to know whether the 60s/30s windows were full or cut short by the next interval starting early (e.g. a `restBeforeNextIntervalSec: Int?` — null for the last interval in a session — that consumers compare against 60/30 to know if a window was truncated). Compute the metric over the fixed window regardless of truncation; don't null it out — a rider hammering back-to-back reps with <60s rest is exactly the case hypothesis #3 in the issue is about.
- New `hasHR: Boolean` field on `CyclingSession`, mirroring the existing `hasPower` coverage-threshold pattern (`POWER_DATA_COVERAGE_THRESHOLD`) — computed at import time from HR sample coverage across the ride's datapoints.
- Computed only for **new rides going forward** (no backfill of existing `IntervalSession`/`CyclingSession` rows).
- Missing HR data → `hrr60`/`hrr30`/`avgPower60sAfter` null for that interval specifically (same nullable-field pattern as `avgHeartRate` elsewhere), not skipped for the whole ride — `hasHR` is informational coverage on the session, it doesn't gate the per-interval computation.
- Displayed per-interval in the existing `IntervalListCard`, with a visual distinction (e.g. a marker/asterisk/different styling) when `hrr60` was computed over a truncated window (`restBeforeNextIntervalSec` < 60).
- Room schema migration for the new columns.

**Out (see below):**
- Cross-ride HRR trend view.
- Backfilling historical rides.
- Any smoothing/outlier-rejection logic beyond raw samples, until real data shows it's needed.
- Session-level HRR average.

## Not Doing (and Why)

- **Backfilling existing intervals** — you explicitly want new-rides-only; re-deriving HRR for historical FIT files would need re-parsing every stored file, and isn't needed to test the hypotheses in the issue going forward.
- **Cross-ride HRR trend chart** — fast-follow. There's nothing to trend until new rides with the field accumulate; designing the aggregation/chart now would be speculative.
- **HRR for sprints** (separate `SprintDetector`, not `IntervalDetector`) — out of scope; sprints are much shorter efforts with different recovery dynamics, revisit separately if useful.
- **Smoothing/outlier rejection algorithm** — start with the simplest correct thing (raw samples at t=end, t=end+30s, t=end+60s); only add complexity if real ride data shows it's noisy.
- **Session-level HRR average** — not for now; per-interval + the future global trend chart is enough.

## Decisions

- `IntervalListCard` visually distinguishes a truncated HRR60 (rest < 60s before the next rep) from a clean one.
- The fast-follow trend chart aggregates **globally across all intervals ever**, not per-`RepeatedInterval` archetype.
- No session-level HRR average for now.

## Open Questions

- None remaining — ready for issue breakdown.
