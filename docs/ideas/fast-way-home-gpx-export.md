# Fast Way Home — GPX Export (issue #139)

## Problem Statement
How might we let a rider get the Fast Way Home route onto their GPS device or bike computer as a GPX file, the same way they already can with a planned route?

## Recommended Direction
Add a share/export button to `FastWayHomeCard`, wired to the same `GpxExporter` + `FileProvider` share-sheet pattern already proven in `PlanARideViewModel.exportGpx()`. `FastWayHomeResult.path` is already a `List<MapEdge>`, so `GpxExporter` needs no changes — this is pure wiring, not new capability.

Motivation: prompted by an actual ride where the route couldn't be gotten onto a device. Plan a Ride has had export since GPX support was built (WP9); Fast Way Home has been missing the same affordance since it shipped (#47). This closes that gap rather than introducing a new pattern.

`FastWayHomeViewModel` is a plain `ViewModel`, unlike `PlanARideViewModel` which is an `AndroidViewModel` and gets `Context` via `getApplication<Application>()`. Resolved: `HomeViewModel.kt:68` already injects `@ApplicationContext private val context: Context` into a plain `@HiltViewModel : ViewModel()` — that's the codebase's actual precedent (`PlanARideViewModel`'s `AndroidViewModel` is the outlier). `FastWayHomeViewModel` will follow `HomeViewModel`'s pattern.

## Key Assumptions to Validate
- [ ] Share-sheet UX (same `ACTION_SEND` chooser as Plan a Ride) is good enough for getting a route onto a Garmin/Wahoo device — already true for Plan a Ride, so low risk, but not explicitly confirmed for this flow.
- [ ] Filename `yymmdd_home.gpx` (per issue title) is fine to diverge from Plan a Ride's `"${distanceKm}k_Velometrics_$date"` convention — confirmed with user, going with the issue title's naming.
- [ ] `FastWayHomeService.findFastWayHome` already works mid-ride from live GPS, not just from a stationary starting point — verified by reading `FastWayHomeService.kt`; no work needed here, just confirm behavior in testing.

## MVP Scope
**In:**
- Export/share button on `FastWayHomeCard`, visible whenever a `FastWayHomeResult` is present.
- `FastWayHomeViewModel.exportGpx()` mirroring `PlanARideViewModel.exportGpx()`: write to `context.cacheDir`, filename `${date}_home.gpx` (`yyMMdd`), GPX `<name>` something human-readable like `"Fast Way Home – ${date}"`.
- Share via existing `FileProvider` authority (`${context.packageName}.fileprovider`) and `ACTION_SEND` chooser, `type = "application/gpx+xml"`.
- `FastWayHomeViewModel` gains `@ApplicationContext private val context: Context`, matching `HomeViewModel`'s existing pattern.
- Same-day repeat export overwrites the previous `${date}_home.gpx` — accepted, no distinct-filename handling needed.

**Out:** see Not Doing below.

## Not Doing (and Why)
- **Direct-to-device sync (Garmin Connect IQ / Wahoo APIs)** — real payoff (skip the share-sheet detour) but a much bigger scope: new SDK dependencies, device-specific auth. Separate issue if it's ever wanted.
- **Multiple same-day exports with distinct filenames** — `yymmdd_home.gpx` will silently overwrite if tapped twice in a day. Confirmed acceptable; not building distinct-filename handling.
- **Clipboard/maps-link export for riders without a GPS device** — legitimate alternative workflow, but a different feature with a different UI, not part of closing this gap.
- **Auto-export without a tap** — an explicit share button matches the existing Plan a Ride pattern and avoids silently writing files to cache on every calculation.

## Open Questions
None remaining — both were resolved: `FastWayHomeViewModel` uses `@ApplicationContext` injection (matching `HomeViewModel`), and same-day overwrite is acceptable.
