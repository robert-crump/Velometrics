# ScoreGpxTrack (GPX Analysis Overlay)

## Problem Statement
How might we give the rider a quick, evidence-based read on whether a loaded .gpx route is worth riding — how new it is, how hard it is, and what's along the way — without leaving the Map tab?

## Recommended Direction
Add a ".gpx analysis" button to the Map tab toolbar, next to the existing "POIs along .gpx" chip and POI list button, enabled whenever a .gpx track is loaded. Tapping it opens a full-screen scrollable overlay (dialog-style, single "X" close at the top, no bottom CTAs) containing a stack of analysis sections.

The overlay leans entirely on infrastructure that already exists or needs only small extensions:
- **Discovery score** and **speed/power estimate** come from `MapMatcher` matching the .gpx to graph edges, then reading `is_traversed` and the per-edge `metadata` JSON (speed_mean/power_mean).
- **POI density per 5km** reuses the along-track corridor logic that already powers "POIs along .gpx", bucketed via `TrackIndex.distanceAlongTrack`.
- **Elevation profile** comes from the .gpx file itself (not the DB — `GpxParser.kt` currently discards `<ele>` tags and needs to start capturing them).

Surface-type distribution is dropped entirely — see Not Doing.

### Overlay sections (top to bottom)
1. **Discovery score** (0–100): % of route length where matched edges have `is_traversed=0`.
2. **Elevation profile**: chart + total gain/loss (smoothed) + gain per 100km, derived from GPX `<ele>` with basic noise smoothing.
3. **Speed/power estimate**: average from matched edges' historical metadata, shown alongside the % of route distance that has ride history (e.g. "24 km/h, 192W — based on 8% of this route").
4. **POI density**: bar chart of POI counts per 5km segment along the track.

## Key Assumptions to Validate
- [ ] `MapMatcher` matches arbitrary external .gpx files (downloaded/shared routes, not just your own recorded rides) well enough to drive discovery score and speed/power — test against a few non-Velometrics .gpx exports (Strava, Komoot).
- [ ] A simple smoothing pass (moving average or minimum-delta threshold) on GPX `<ele>` produces sane gain/loss numbers — validate against a route with a known elevation profile.
- [ ] Extending `GpxTrack`/`GpxParser` with elevation doesn't break existing consumers (Home import flow, `GpxSharedViewModel`, Map tab loading) — needs a quick audit of call sites before implementation.
- [ ] Coverage-% framing for speed/power ("based on X% of route you've ridden") is clear enough as a standalone signal, even when X is very low or zero.

## MVP Scope
**In:**
- New ".gpx analysis" toolbar button in Map tab, visible/enabled only when a .gpx is loaded
- Full-screen scrollable overlay, "X" to close, no other actions
- Discovery score via MapMatcher + `is_traversed`
- Elevation profile chart, gain/loss (smoothed), gain per 100km — requires extending GpxParser to capture `<ele>`
- Speed/power estimate with coverage % from matched-edge metadata
- POI density per 5km, reusing existing along-track corridor search

**Out:** everything in Not Doing below.

## Not Doing (and Why)
- **Surface-type distribution** — the `surface` column is 100% NULL across all 511,597 edges in `cycling_graph.db`. Per the original criterion, this is dropped; revisit only if surface data gets populated upstream by a different process.
- **Batch-scoring multiple .gpx files** — v1 analyzes only the single currently-loaded track. A "rank my downloaded routes by discovery score" feature is a natural follow-up but adds file-management UI that isn't needed to validate the core idea.
- **Comparing the route to existing RoutePlanner clusters** — "this is 80% similar to your usual commute" is a useful idea but a distinct feature with its own UI; cross-referencing against saved clusters can come later.
- **Saving/editing the analyzed .gpx as a route or session** — the overlay is read-only analysis. Any "import this as a real route" flow already exists via Home and doesn't need duplicating here.

## Open Questions
- What exactly needs to change in `GpxParser.kt` / `GpxTrack` to carry elevation through, and which screens consume `GpxTrack` today? (audit before implementation)
- What smoothing window/threshold gives good elevation-gain results on typical .gpx exports — needs testing against a couple of real files with known stats.
- Should "based on 0% of route" for speed/power show "N/A" instead of a 0%-coverage stat, or is showing 0% itself useful information (ties into discovery score)?
