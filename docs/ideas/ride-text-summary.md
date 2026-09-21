# Ride Text Summary (issue #167)

## Problem Statement
How might we turn the numbers already sitting in a `CyclingSession` into a short, always-visible verdict on the ride — what kind of ride it was, and how it stacks up against the rider's own history of rides like it?

## Recommended Direction
Add a **tag** to every session (`Zone 2`, `Intervals`, `Race`, `Recovery`, `Endurance`) computed by a new rule-based `RideClassifier` domain service, reading `powerZoneDistribution`/`hrZoneDistribution` (zone-time majority), `intervalTotalTimeSec` share, `cardiacDriftPercent`, `fatEfficiencyScore`, and the NP:AP ratio (`normalizedPower / averagePower` — the standard "variability index" signal for steady/hard vs. spiky/easy effort). Rules run in priority order (Intervals → Race → Zone 2 → Recovery → Endurance-as-fallback) and emit exactly one tag, no blending, matching the "auto-detected only" scope already decided.

The tag is **persisted** as a new `tag: String?` column on `CyclingSessionEntity` (schema v13 → v14, following the existing migration pattern in `VelometricsDatabase.kt`), computed once at import time in `FitImportService`. This is what makes tag-scoped history cheap: `CyclingSessionDao` gains a sibling to `getSessionMetricSamplesBeforeDate` filtered by tag, so `SessionComparator` can answer "how does this compare to your last 5 Zone 2 rides" with an indexed query instead of loading and reclassifying the rider's whole ride history in memory on every screen open.

On screen, the tag shows as a chip inline in `RideSummaryGrid`'s header row (`SessionDetailScreen.kt`), next to the date — always visible, zero new navigation, matching the placement the rider preferred (idea #1). Tapping it expands a short narrative (2-3 sentences) built from whichever KPIs are most notable for *this specific ride* relative to its tag-scoped history — not always the same metric first. A low-drift Zone 2 ride leads with drift; a Race with a personal-best NP leads with power. This is the richer, "did they score well" ambition (idea #2), scoped so the default view stays as light as idea #1.

## Key Assumptions to Validate
- [ ] **Must be true:** The five KPIs above actually separate this rider's real ride history into clean tags — validate by running the classifier's rules (even as a throwaway script) against a sample of the rider's existing imported sessions before finalizing thresholds. A ride that straddles two tags (e.g. a commute with a few sprints) will otherwise get a tag the rider doesn't trust, and won't stop trusting it quietly — they'll notice.
- [ ] **Must be true:** Persisting the tag needs a reclassify/backfill path from day one — both for existing sessions imported before this ships, and for the (likely) case that thresholds need a v2 tweak. Plan: a `reclassifyAll()` migration step that runs `RideClassifier` over every existing `CyclingSessionEntity` row, callable again later without a new DB migration if thresholds change.
- [ ] **Should be true:** Fat efficiency score and the NP:AP ratio each add real separating signal beyond what zone-time distribution already gives — worth a quick check against real data (do they move independently, or does one just echo cardiac drift?) before locking both into the rule set.
- [ ] **Should be true:** A tag-scoped comparison pool won't be too thin to be meaningful for tags the rider rarely rides (e.g. one lifetime "Race"). Needs a floor — mirror `SessionComparator`'s existing rule of falling back to `null` medians below 2 samples — and the narrative should say "not enough history for this ride type yet" rather than compare against n=1.

## MVP Scope
**In:**
- `RideClassifier` domain service (+ unit tests, following the `BestEffortCalculatorTest`/`IntervalDetectorTest` pattern already in the codebase) producing one of: Zone 2 / Intervals / Race / Recovery / Endurance.
- `tag: String?` column on `CyclingSessionEntity`, Room migration 13→14, computed in `FitImportService` at import time.
- One-time `reclassifyAll()` backfill for sessions imported before this ships.
- `CyclingSessionDao.getSessionMetricSamplesBeforeDate(beforeEpochMs, limit, tag)` variant (tag-filtered sibling of the existing query) + `SessionComparator` extended to accept an optional tag scope.
- Tag chip inline in `RideSummaryGrid`'s header row, next to the date.
- Expandable 2-3 sentence narrative on tap, templated (not free text), picking whichever tag-scoped KPI deviates most from that pool's median to lead with.
- Graceful "not enough history yet" state when the tag-scoped pool has fewer than 2 prior sessions.

**Out:** see Not Doing below.

## Not Doing (and Why)
- **Elevation-based ("Climbing") and sprint-based ("Sprint session") tags** — considered, deliberately deferred; keeps the v1 rule set to five tags and one new comparison-query shape instead of widening both at once. Natural v2 addition once the five-tag classifier is validated against real rides.
- **User-editable/correctable tags** — decided earlier in this session: auto-detected only for v1. Means a misclassified ride has no correction path yet; acceptable for MVP, but the first thing to add if the classifier's accuracy turns out shakier than the backfill assumption above hopes.
- **Sharing/exporting the summary as text** (copy button, GPX-style share intent) — decided earlier: audience is the rider, in-app, not an external LLM/coach. No `FileProvider`/`ACTION_SEND` plumbing needed, unlike the GPX export precedent.
- **Tag visible on `HomeScreen`'s session list rows** — real follow-on value (browse ride history by type) but a separate UI surface with its own design questions; not needed to prove the detail-screen concept.
- **Multi-tag / blended output** ("Zone 2 with some intervals") — real rides are often mixed, but a single deterministic tag is far simpler to test, persist, and reason about for v1. Revisit only if single-tag output proves too lossy in practice.

## Open Questions
- Exact numeric thresholds for each rule (e.g. "% of time in Zone 2" cutoff, NP:AP cutoff for Race vs. Endurance) — deliberately left unresolved here; needs the data-driven validation pass called out in Key Assumptions before implementation, not a guess baked into this doc.
- Where `reclassifyAll()` lives and how it's triggered (part of the Room migration itself vs. a one-time app-start check) — an implementation detail to settle when writing the migration, not a product decision.
