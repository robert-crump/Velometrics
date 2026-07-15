# Repeated Route — .gpx Export (issue #140)

## Problem Statement
How might we let a rider get a Repeated Route onto their GPS device or bike computer as a GPX file, the same way they already can with Plan a Ride (#WP9) and Fast Way Home (#139)?

## Recommended Direction
Add a share/export icon to `RepeatedRouteDetailScreen`'s `TopAppBar`, next to the existing rename icon, wired to `RepeatedRouteDetailViewModel.exportGpx()` following the `shareIntent: SharedFlow<Intent>` + `FileProvider` + `ACTION_SEND` chooser pattern from `PlanARideViewModel`/`FastWayHomeViewModel`.

The wrinkle that makes this different from #139: `GpxExporter` today only knows how to write `List<MapEdge>` (routed edges with encoded polylines). `RepeatedRoute.representativeTrack` is a raw `List<List<Double>>` — the actual recorded GPS track of the median-length session (`RepeatedRouteRepositoryImpl.kt:94-95`), not a routed path. `GpxExporter` needs a second entry point that writes raw `[lat, lon]` pairs directly as `<trkpt>` elements, sharing the surrounding GPX/XML scaffolding (header, metadata, `escapeXml`) with the existing `export(List<MapEdge>, ...)` via a private shared writer, but keeping the two public methods distinct rather than forcing raw coords through a fake `MapEdge`.

The representative track is exported as-is — same data already rendered on the detail screen's preview map (`RepeatedRouteDetailScreen.kt:136-152`), so no new filtering/smoothing logic, and no risk of the export looking different from what the rider already reviewed.

## Key Assumptions to Validate
- [ ] Exporting the raw recorded track (potentially including stops, GPS noise/drift) is good enough for getting a repeated route onto a device — confirmed acceptable; it's the same data already shown in the app's own preview, so no separate quality bar to hit.
- [ ] Route names are free text (`renameRoute`) and must become a filesystem-safe filename — needs a small inline sanitizer (e.g. strip everything but alphanumerics/space/dash/underscore) since, unlike Fast Way Home's fixed `"home"` literal, this is the first GPX filename in the codebase built from user input.
- [ ] A route with no sessions/no `representativeTrack` (edge case: repository returns `null`) should hide or disable the export action rather than crash — mirrors how the screen already guards `route == null`.

## MVP Scope
**In:**
- Export/share icon in `RepeatedRouteDetailScreen`'s `TopAppBar` actions, enabled whenever `uiState.route?.representativeTrack` is non-null and non-empty.
- `GpxExporter` gains a new method for raw coordinates (e.g. `exportTrack(coords: List<List<Double>>, routeName: String, outputStream: OutputStream)`), refactored to share XML scaffolding with the existing `MapEdge` path via a private helper.
- `RepeatedRouteDetailViewModel.exportGpx()` mirrors `PlanARideViewModel.exportGpx()`: write to `context.cacheDir`, filename `${date}_${sanitizedRouteName}.gpx` (`yyMMdd` prefix, matching Fast Way Home's date-first convention), GPX `<name>` set to the route's actual (unsanitized) name.
- `RepeatedRouteDetailViewModel` gains `@ApplicationContext private val context: Context` and a `shareIntent: SharedFlow<Intent>`, matching `FastWayHomeViewModel`'s existing pattern (it's currently a plain `ViewModel` with no `Context`).
- `RepeatedRouteDetailScreen` collects `shareIntent` via `LaunchedEffect` + `context.startActivity(it)`, same as `MapViewScreen.kt:207-213`.
- Share via existing `FileProvider` authority (`${context.packageName}.fileprovider`) and `ACTION_SEND` chooser, `type = "application/gpx+xml"`.
- Same-day repeat export overwrites the previous file for that route — accepted, consistent with #139's precedent.

**Out:** see Not Doing below.

## Not Doing (and Why)
- **Filtering/smoothing the recorded track before export** — would make the exported file diverge from what the rider already reviewed on the detail screen's preview map; no existing precedent for this kind of cleanup in the codebase.
- **Per-session export (choosing a specific ride's track instead of the representative one)** — a distinct feature (export any session, not just repeated-route summaries); `RepeatedRoute` only carries the one representative track today.
- **Export entry point on `RepeatedRoutesScreen`'s list rows** — saves a tap but clutters a list row with a new affordance that has no precedent elsewhere in the list UI; the detail screen already opens per-route and already shows the preview map.
- **Direct-to-device sync (Garmin Connect IQ / Wahoo APIs)** — same reasoning as #139: real payoff, much bigger scope, separate issue if ever wanted.
- **General-purpose filename-sanitizing utility in `FormatUtils`** — this is the first and only place a GPX filename is built from free-text user input; a small inline sanitizer in the ViewModel is enough, no need to build an abstraction for one caller.

## Open Questions
None remaining — resolved during refinement: `GpxExporter` gets a separate method sharing a private writer (not a fake-`MapEdge` hack), the recorded track exports unfiltered, the action lives on the detail screen's top bar, and the filename is `${date}_${routeName}.gpx`.
