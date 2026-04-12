# Changelog

## WP10 — GPX Navigation & POI Lookup

### Context

WP1–WP9 built the full CyclingData app with .FIT import, interval detection, dashboard/detail UI, MapLibre maps, graph builder, speed/stop overlay, interval overlay, and route planner with GPX export. WP10 adds a 6th "Navigation" screen to the bottom nav bar: load a GPX file (or receive a route from the Route Planner), display the track on a map, fetch the user's GPS position once, and find POIs (fuel, cafes, bakeries, fast food, friture) along the remaining track via the Overpass API.

### New Files

- **`domain/model/GpxTrack.kt`** — `GpxTrack` data class with nullable name and `List<LatLng>` points.
- **`domain/model/Poi.kt`** — `PoiType` enum (FUEL, CAFE, BAKERY, FAST_FOOD, FRITURE) and `Poi` data class with id, lat/lon, name, type, distance-along-route, and off-route distance.
- **`data/gpx/GpxParser.kt`** — XmlPullParser-based GPX parser handling `<trk><trkseg><trkpt>` track format, multiple `<trkseg>` segments (concatenated), `<rte><rtept>` route format, and `<name>` extraction from `<trk>` or `<metadata>`. Returns `Result<GpxTrack>` for graceful error handling. Skips points with missing lat/lon.
- **`data/poi/OverpassApiService.kt`** — OkHttp-based HTTP client for the Overpass API. Single POST to `https://overpass-api.de/api/interpreter` with Overpass QL querying all POI types within a bounding box. Parses JSON response with Gson. Classifies POIs: `amenity=fuel` → FUEL, `amenity=cafe` → CAFE, `shop=bakery` → BAKERY, `amenity=fast_food` + `cuisine=friture` → FRITURE, other `amenity=fast_food` → FAST_FOOD. 25-second timeout. Returns `Result<List<RawPoi>>`.
- **`data/poi/PoiRepository.kt`** — Combines Overpass data with track geometry. Projects user position onto track to get remaining track, projects each POI onto remaining track to compute distance-along-route and off-route distance, filters POIs beyond 500m off-route, sorts by distance-along-route ascending. In-memory cache: caches raw Overpass response per GPX track; `refreshDistances()` reuses cached POIs with new user position; cache invalidated on new GPX load.
- **`domain/service/TrackGeometryUtils.kt`** — Pure geometric functions (no Android dependencies): `projectPointOntoTrack` (point-to-line-segment projection across all segments), `remainingTrackFrom` (sub-track from projected position to end), `computeDistanceAlongTrack` (cumulative haversine between two projections), `computeBoundingBox` (with meter buffer using `GeoUtils.metersToLat/metersToLon`), `projectPoiOntoTrack` (distance-along + off-route for a single POI). All O(n×m), fine for typical track/POI sizes.
- **`domain/service/NavigationRouteHolder.kt`** — Hilt singleton holding `pendingRoute: List<LatLng>?` and `pendingRouteName: String?` for passing route data from Route Planner to Navigation screen without large nav arguments or temp files.
- **`ui/screens/navigation/NavigationViewModel.kt`** — `@HiltViewModel` managing: GPX loading from URI (via ContentResolver + GpxParser) or from LatLng points (Route Planner integration), one-shot GPS position via `LocationManager.getCurrentLocation()` (API 30+, minSdk=34), Overpass POI fetching with distance computation, POI type filtering, selected POI for map centering, loading/error states. On init, checks `NavigationRouteHolder` for a pending route from Route Planner.
- **`ui/screens/navigation/NavigationScreen.kt`** — Full navigation UI: TopAppBar with "Load GPX" action (opens `OpenDocument` file picker for GPX/XML), map (weight 0.6) showing track polyline + user position blue circle + POI markers, POI list (weight 0.4) with filter chips (All, Fuel, Cafe/Bakery, Fast Food) and scrollable LazyColumn of POI rows (colored dot, name, distance-along, off-route distance, tap to pan map), bottom action area with "POIs nearby" button (triggers location permission + fetch) and refresh button. Handles `ACCESS_FINE_LOCATION` runtime permission with fallback to track start if denied.
- **`ui/components/MapPoiRenderer.kt`** — Renders POI markers on MapLibre using a single GeoJSON FeatureCollection + CircleLayer with data-driven colors via `Expression.get("color")`. Colors: FUEL = red (#E53935), CAFE/BAKERY = brown (#795548), FAST_FOOD/FRITURE = orange (#FF9800). Circle radius 8f, white stroke 2f. `addPois`/`removePois` API.

### Modified Files

- **`AndroidManifest.xml`** — Added `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>` for one-shot GPS positioning.
- **`util/Constants.kt`** — Added 14 constants: `OVERPASS_API_URL`, `OVERPASS_TIMEOUT_SEC` (25), `POI_OFF_ROUTE_THRESHOLD_M` (500), `POI_BBOX_BUFFER_M` (500), POI marker colors (FUEL red, CAFE/BAKERY brown, FAST_FOOD/FRITURE orange), `POI_MARKER_RADIUS` (8f), `POI_MARKER_STROKE_WIDTH` (2f), `NAV_TRACK_COLOR` (#2979FF), `NAV_TRACK_WIDTH` (5f), `NAV_USER_MARKER_COLOR` (#2196F3), `NAV_USER_MARKER_RADIUS` (10f).
- **`ui/navigation/Screen.kt`** — Added `Navigation` screen object (`"navigation"`, `"Navigate"`, `Icons.Default.Navigation`). Inserted into `bottomNavItems` between RoutePlanner and Settings.
- **`ui/navigation/CycleGraphNavHost.kt`** — Added `NavigationScreen` composable route. Updated `RoutePlannerScreen` call to pass `onNavigateToNavigation` lambda.
- **`di/GraphBuilderModule.kt`** — Added `provideOverpassApiService()`, `providePoiRepository(OverpassApiService)`, and `provideNavigationRouteHolder()` singleton providers.
- **`ui/screens/routeplanner/RoutePlannerScreen.kt`** — Added `onNavigateToNavigation` parameter. Added "Navigate" `OutlinedButton` in the action row (visible when route exists), calling `viewModel.prepareNavigation()` then navigating.
- **`ui/screens/routeplanner/RoutePlannerViewModel.kt`** — Injected `NavigationRouteHolder`. Added `prepareNavigation()` that converts route edges to `List<LatLng>` and sets on holder with route name.
- **`app/build.gradle.kts`** — Added `testOptions.unitTests.isReturnDefaultValues = true` for Android stub compatibility in unit tests. Added `testImplementation("net.sf.kxml:kxml2:2.3.0")` for XmlPullParser availability in JVM tests.

### Test Files

- **`data/gpx/GpxParserTest.kt`** — 8 unit tests: single track segment, multiple segments concatenation, `<rte>` format, empty file failure, malformed XML failure, missing lat/lon skip, track name from `<trk>`, name from `<metadata>` fallback.
- **`data/poi/OverpassQueryBuilderTest.kt`** — 4 unit tests: query contains all POI types, correct bbox format, JSON output format, `parseResponse` classifies all 5 POI types correctly (including friture detection via cuisine tag).
- **`domain/service/TrackGeometryUtilsTest.kt`** — 10 unit tests: point-on-segment projection, exact vertex projection (~0m distance), remaining track from midpoint, remaining track from start, distance along full track, distance along single segment, bounding box with buffer, POI on-route (small off-route), POI far off-route (large off-route), bounding box with single point.
- **`data/poi/PoiRepositoryTest.kt`** — 5 unit tests: POI within 500m kept, POI beyond 500m discarded, POIs sorted by distance-along-route, `refreshDistances` reuses cached POIs, `invalidateCache` clears cached data.

### Key Implementation Details

- **One-shot GPS** — Uses `LocationManager.getCurrentLocation()` (API 30+, available since minSdk=34), no Play Services dependency. Single read, not continuous tracking.
- **Overpass query** — Single POST fetching all POI types in one call within the bounding box of the remaining track (from user position to end). Parsed inline with Gson.
- **Distance computation** — Point-to-line-segment projection across all track segments. Off-route distance = haversine from POI to nearest projected point on track. Distance-along-route = cumulative haversine from user projection to POI projection.
- **Caching strategy** — Raw Overpass response cached per GPX track identity. `refreshDistances()` reuses cached POIs but recomputes projections with new user position. Cache invalidated when a new GPX file is loaded.
- **Route Planner integration** — Shared `NavigationRouteHolder` singleton avoids large data in nav arguments. Route Planner populates it, Navigation screen consumes and clears it on init.
- **Map rendering** — Reuses existing `MapTrackRenderer.addTrack()` for the GPX polyline. New `MapPoiRenderer` follows the same GeoJSON FeatureCollection + data-driven styling pattern as `MapOverlayRenderer`.

---

## WP9 — Route Suggestion

### Context

WP1–WP8 built the full app foundation including the MapEdge graph (WP6), speed/stop overlay (WP7), and interval overlay (WP8). The graph has ~10m directed edge segments with traversal statistics but no explicit connectivity — edges and nodes exist independently. WP9 adds a route suggestion feature: the user specifies a target distance, and the algorithm generates a loop route using confirmed edges, displays it on the map, and supports GPX export.

### New Files

- **`domain/service/RoutePlanner.kt`** — Application-scoped singleton implementing the full route generation algorithm. Builds connectivity at runtime from `RTreeSpatialIndex.queryEdgesNear()` (no schema migration needed). Algorithm phases: (1) build adjacency graph from confirmed edges within 10m radius, filtering U-turns (>150° angle diff); (2) scored random walk outbound using weighted edge selection (straight bonus, speed preference, stop penalty, turn penalty, novelty, direction-awareness that flips between outbound/return halves); (3) A* return path when remaining budget < 1.3× distance to home, using g-cost weighted by stop probability and admissible haversine heuristic; (4) generate up to 10 candidates, filter by ±10% then ±15% distance tolerance, return highest-scoring. Exposes `RouteResult` data class with edges, distance, estimated duration, turn count, avg speed, and score.
- **`domain/service/GpxExporter.kt`** — Singleton that converts route edges to GPX 1.1 XML. Writes `<trk><trkseg>` with start point of first edge + end points of all edges as `<trkpt>` elements. Includes `<metadata><time>` with ISO timestamp and `<name>` with XML-escaped route name. Provides both `export(OutputStream)` and `toGpxString()` APIs.
- **`test/.../domain/service/RoutePlannerTest.kt`** — 10 unit tests covering: scoring (straight fast > slow turning, stop penalty reduces score, revisit gets 0.1× penalty, score never below MIN_SCORE, direction score flips between halves), connectivity (edges connect within radius, U-turns excluded, disconnected edges have no successors), and candidate selection (tight/relaxed tolerance filtering, closest fallback).
- **`test/.../domain/service/GpxExporterTest.kt`** — 6 unit tests covering: valid GPX 1.1 structure, empty edge list produces valid empty GPX, start point included as first trkpt, correct lat/lon values, metadata time element, XML escaping of route name.

### Modified Files

- **`data/local/dao/MapEdgeDao.kt`** — Added `getConfirmedEdgesList()` suspend query returning `List<MapEdgeEntity>` for one-shot connectivity building (complements existing `getConfirmedEdges()` Flow).
- **`domain/repository/MapGraphRepository.kt`** — Added `getConfirmedEdgesList(): List<MapEdge>` method signature.
- **`data/repository/MapGraphRepositoryImpl.kt`** — Implemented `getConfirmedEdgesList()` delegating to DAO with `.toDomain()` mapping.
- **`util/Constants.kt`** — Added 26 routing constants: connectivity (`ROUTE_SUCCESSOR_RADIUS_M`, `ROUTE_MAX_ANGLE_DIFF_DEG`, `ROUTE_START_RADIUS_M`), scoring weights (straight bonus, speed, stop penalty, turn penalty, novelty, direction), thresholds (straight angle/speed, turn angle, speed cap), algorithm parameters (min score, revisit penalty, return budget factor, max candidates, distance tolerances), UI defaults (default/min/max distance, step, polyline color/width).
- **`di/GraphBuilderModule.kt`** — Added `provideRoutePlanner(MapGraphRepository, RTreeSpatialIndex)` and `provideGpxExporter()` singleton providers. Updated `provideGraphBuilder` to accept `dagger.Lazy<RoutePlanner>` to avoid circular initialization.
- **`domain/service/GraphBuilder.kt`** — Added `dagger.Lazy<RoutePlanner>` constructor parameter. Calls `routePlanner.get().invalidate()` at end of `processSession()` when edges are updated or created, so the connectivity cache refreshes after new graph data.
- **`ui/screens/routeplanner/RoutePlannerViewModel.kt`** — Full rewrite replacing placeholder. Injects `RoutePlanner` and `GpxExporter`. Manages `RoutePlannerUiState` with target distance/duration, generating flag, route result, and error message. Actions: `setTargetDistance`, `setTargetDuration`, `toggleDurationMode`, `generateRoute` (launches coroutine calling `routePlanner.generateBestRoute()` from home coordinates), `tryAnother` (re-generates for different random walk), `exportGpx(uri, contentResolver)`.
- **`ui/screens/routeplanner/RoutePlannerScreen.kt`** — Full rewrite replacing placeholder. Layout: `DistanceControlBar` (slider 10–100km step 5, or duration slider 0.5–4h step 0.25, with mode toggle), full-screen `ComposableMapView` with `CircularProgressIndicator` overlay during generation, `RouteSummaryCard` (distance, est. time, turns, avg speed), action buttons (Generate Route / Try Another + Export GPX). Route rendered as bright green polyline via `MapTrackRenderer.addTrack("planned-route")` with camera fit to bounds. GPX export via `ActivityResultContracts.CreateDocument`.

### Key Implementation Details

- **Runtime connectivity** — Adjacency built from `queryEdgesNear()` at edge endpoints rather than relying on `startNodeId`/`endNodeId` fields (which are inconsistently populated). Cached as application-scoped singleton, invalidated after imports.
- **Scored random walk** — Weighted edge selection using 6 scoring factors: straight bonus (angle < 30° AND speed > 25 km/h), speed preference (normalized to 40 km/h cap), stop penalty, turn penalty (angle-proportional × stop probability), novelty (penalizes frequently ridden edges), and direction (cosine of angle to home, sign-flipped in first half to prefer outbound).
- **A* return** — Switches from random walk when remaining distance budget < 1.3× haversine distance to start. Uses edge cost = lengthM × (1 + stopProbability) with admissible haversine heuristic. Goal: any edge ending within 50m of start.
- **Candidate selection** — Generates up to 10 routes (each with different random seed), filters by ±10% distance, falls back to ±15%, falls back to closest. Returns highest-scoring from filtered pool.
- **Map reuse** — Route polyline rendered via existing `MapTrackRenderer.addTrack()` with trackId `"planned-route"` — no new renderer needed.
- **GPX export** — Uses `ActivityResultContracts.CreateDocument` for file picker, writes via `ContentResolver.openOutputStream`.

---

## WP8 — Interval Map Overlay

### Context

WP1–WP7 built the full app foundation including interval detection/matching (WP3), MapLibre map integration (WP5), and the speed/stop spot overlay (WP7). WP8 adds an **Interval Map Overlay** — drawing past interval sessions as polylines colored by normalized duration, grouping intervals matched to the same prototype route, with tap-to-inspect interaction.

### New Files

- **`ui/components/MapIntervalRenderer.kt`** — Renders interval polylines on MapLibre using 4 layers: ungrouped intervals (colored by normalized duration), grouped prototype routes (thicker lines using prototype avgGpsTrack), prototype labels (SymbolLayer at track midpoints), and highlight layer (cyan for selected interval). Uses the same GeoJSON FeatureCollection + data-driven styling pattern as `MapOverlayRenderer`.
- **`test/.../util/MapOverlayUtilsIntervalTest.kt`** — 14 unit tests covering `normalizedDurationToColor` (boundary values, clamping, interpolation), `groupIntervals` (empty, ungrouped, grouped, mixed, average computation), and `formatDurationMinSec` (various durations).

### Modified Files

- **`util/Constants.kt`** — Added interval overlay constants: line widths (`INTERVAL_OVERLAY_LINE_WIDTH`, `INTERVAL_GROUPED_LINE_WIDTH`, `INTERVAL_HIGHLIGHT_LINE_WIDTH`), duration range (`INTERVAL_MIN/MAX_DURATION_SEC`), highlight color (`INTERVAL_HIGHLIGHT_COLOR`), and 5-stop warm color ramp (`INTERVAL_DURATION_COLOR_RAMP`) from light yellow to dark crimson.
- **`util/MapOverlayUtils.kt`** — Added `IntervalGroup` data class (top-level), `normalizedDurationToColor` (linear RGB interpolation across 5-stop color ramp with clamping), `groupIntervals` (partitions intervals by prototype route, computes average duration/power), `formatDurationMinSec` (M:SS format). Private helpers: `interpolateColor`, `parseHexColor`.
- **`ui/screens/mapview/MapViewViewModel.kt`** — Added `IntervalRepository` constructor injection. New state: `showIntervalOverlay` toggle, `allIntervals` and `allPrototypeRoutes` StateFlows from repository, tap interaction state (`selectedInterval`, `selectedGroup`, `highlightedIntervalId`) with select/clear functions.
- **`ui/screens/mapview/MapViewScreen.kt`** — Added interval overlay `LaunchedEffect` (remove-then-render pattern), highlight sync `LaunchedEffect`, map click listener with `rememberUpdatedState` for stale-capture prevention (queries grouped then ungrouped layers). Updated bottom sheet with "Intervals" toggle row. Updated `LegendCard` with interval duration gradient (5 colored boxes, "2 min" to "8+ min" labels). New `IntervalDetailCard` composable (date, duration, distance, power, speed, direction with close button). New `PrototypeGroupSheet` composable (header with route name/count, average summary, sorted interval list with tap-to-highlight).

### Key Implementation Details

- **4 MapLibre layers** — Ungrouped intervals, grouped prototype routes, prototype labels, and highlight — each with separate GeoJSON source for independent lifecycle management.
- **Normalized duration coloring** — Linear RGB interpolation across a 5-stop warm color ramp (120s–480s), clamped at boundaries.
- **Prototype grouping** — Intervals with `prototypeRouteId` grouped together; uses `avgGpsTrack` from prototype or longest interval's track as fallback.
- **Stale capture prevention** — `rememberUpdatedState` for values accessed in the map click listener lambda (registered once on map ready).
- **No data layer changes** — All data from existing `IntervalRepository` Flows.

---

## WP7 — Speed & Stop Spot Map Overlay

### Context

WP1–WP6 built the app foundation: .FIT import, interval detection, dashboard/session detail UI, MapLibre map integration with session GPS track display (WP5), and the GraphBuilder that populates `MapEdge` rows during import with traversal statistics (WP6). WP7 adds a **Speed & Stop Spot overlay** — drawing confirmed edges colored by average speed, stop spot markers at high-stop-probability edges, and a direction filter. This is the first visual payoff of the graph builder.

### New Files

- **`util/MapOverlayUtils.kt`** — Pure utility functions for overlay logic: `speedToColor` (maps speed to hex color from `SPEED_COLOR_MAP` bins), `Direction` enum with `edgeMatchesDirection` (90° sector matching using `GeoUtils.angleDifference`), `filterEdgesByDirection`, `StopType` enum with `classifyStop` (short/medium/long by duration thresholds), `stopTypeToColor`.
- **`ui/components/MapOverlayRenderer.kt`** — Renders speed overlay and stop spots on MapLibre. Speed overlay uses a single GeoJSON FeatureCollection with data-driven `lineColor(Expression.get("color"))` for efficient rendering of thousands of edges. Stop spots use a CircleLayer with data-driven circle colors. Each layer has add/remove methods for clean toggling.
- **`test/.../util/MapOverlayUtilsTest.kt`** — 18 unit tests covering all utility functions: speedToColor bins (7 tests), edgeMatchesDirection with wrap-around (6 tests), filterEdgesByDirection (1 test), classifyStop cases (4 tests).

### Modified Files

- **`util/Constants.kt`** — Added `SPEED_OVERLAY_LINE_WIDTH`, `STOP_SHORT_THRESHOLD_SEC`, `STOP_LONG_THRESHOLD_SEC`, stop marker colors (`STOP_COLOR_SHORT/MEDIUM/LONG`), and stop spot circle sizing constants (`STOP_SPOT_RADIUS`, `STOP_SPOT_STROKE_WIDTH`, `STOP_SPOT_STROKE_COLOR`).
- **`ui/screens/mapview/MapViewViewModel.kt`** — Added `showSpeedOverlay`, `showStopSpots`, `directionFilter` state flows with toggle/set functions. Added `filteredEdges` derived StateFlow combining `confirmedEdges` with `directionFilter` via `MapOverlayUtils.filterEdgesByDirection`.
- **`ui/screens/mapview/MapViewScreen.kt`** — Added two `LaunchedEffect` blocks for speed overlay and stop spots sync (remove-then-render pattern). Updated bottom sheet with "Overlays" section containing speed/stop toggle switches, direction filter chips (visible when either overlay is on), and a `LegendCard` composable showing speed scale and stop type key. Session list moved below a `HorizontalDivider`.

### Key Implementation Details

- **Single FeatureCollection per layer** — All edges rendered in one GeoJSON source + one layer (not one per edge) for GPU-efficient rendering of thousands of segments.
- **Data-driven styling** — `Expression.get("color")` reads the color property from each Feature, avoiding multiple layers.
- **Direction filtering** — 90° sectors using `GeoUtils.angleDifference` with proper 0°/360° wrap-around handling.
- **Stop classification** — Three tiers based on `avgStopDurationSec`: Short (<60s), Medium (60–600s), Long (>600s).
- **No data layer changes** — All data sourced from existing `MapGraphRepository.getConfirmedEdges()` Flow.

---

## WP6 — MapEdge Graph Builder

### Context

WP1–WP5 established the full app foundation with .FIT import, interval detection, dashboard UI, and MapLibre map integration showing session GPS tracks. The `MapEdgeEntity`, `MapNodeEntity`, and `PendingPointEntity` tables exist but remain unpopulated. WP6 implements the **GraphBuilder** service — the core algorithm that converts raw GPS traces into a directed road graph of ~10m segments with traversal statistics. This graph is the foundation for the speed/stop overlay (WP7) and route planner (WP9).

### Algorithm Summary

**Phase 1: R-Tree Init** — Load all MapEdge entries into an in-memory R-tree spatial index on first use.

**Phase 2: Point Processing** — For each datapoint with valid angle:
1. R-tree query: edges within 15m
2. Filter by angle: angleDifference ≤ 45°
3. Best match = closest to point
4. Match found → update edge (running-average speed, power if hasPower, stop probability, persist)
5. No match → insert PendingPoint

**Phase 3: Edge Creation from Buffer** — After processing all datapoints:
1. Cluster pending points (within 10m, angle ≤ 45°)
2. Qualify: ≥5 points from ≥3 sessions
3. Create MapEdge: position = cluster average, angle = circular mean, stats from cluster
4. Insert to Room + R-tree
5. Delete consumed pending points

**Phase 4: Node Creation** — For new edges:
1. Query MapNode within 10m of endpoints
2. Snap to existing node or create new
3. Node confirmed if ≥2 confirmed edges connect

### New Files

- **`domain/service/RTreeSpatialIndex.kt`** — Thread-safe wrapper around rtree2 library for spatial indexing of MapEdge entities. Provides fast lookups for edges within a radius of a point. Uses Mutex for thread safety. API: `rebuildIndex()`, `insertEdge()`, `updateEdge()`, `removeEdge()`, `queryEdgesNear()`.
- **`domain/service/GraphBuilder.kt`** — Main graph building service implementing the 4-phase algorithm. Application-scoped singleton. Processes datapoints during import, updates edge statistics with running averages, creates new edges from pending point clusters, and manages node creation. Returns `BuildResult` with counts of updated/created edges and pending points added.
- **`di/GraphBuilderModule.kt`** — Hilt DI module providing `RTreeSpatialIndex` and `GraphBuilder` as application-scoped singletons.

### Modified Files

- **`gradle/libs.versions.toml`** — Added `rtree2 = "0.9.3"` version and library declaration (`com.github.davidmoten:rtree2`).
- **`app/build.gradle.kts`** — Added `implementation(libs.rtree2)` dependency for spatial indexing.
- **`data/local/dao/MapEdgeDao.kt`** — Added `getAllList()` (suspend, returns List) and `updateAll(List<MapEdgeEntity>)` for batch operations required by graph builder.
- **`domain/repository/MapGraphRepository.kt`** — Added `getAllEdgesList()` and `updateEdges(List<MapEdge>)` method signatures.
- **`data/repository/MapGraphRepositoryImpl.kt`** — Implemented new repository methods with entity-to-domain mapping.
- **`data/fitimport/FitImportService.kt`** — Added `GraphBuilder` injection and step 11 in import pipeline (after interval detection, before summary building). Calls `graphBuilder.processSession(datapoints, id, hasPower)` and logs result.

### Test Files

- **`domain/service/GraphBuilderTest.kt`** — 10 unit tests covering pure functions: circular mean angle (with wrap-around), angle difference, haversine distance, meters/lat/lon conversions, running average formula, stop probability calculation, cluster qualification logic, end point computation from start position and angle.

### Key Implementation Details

- **No schema changes** — All required fields already exist in `MapEdgeEntity`, `MapNodeEntity`, `PendingPointEntity`.
- **Thread safety** — R-tree access protected by Mutex since imports may theoretically overlap.
- **Batch persistence** — Collects all edge updates and pending points, persists in single transaction for performance.
- **Running averages** — Speed/power/stop duration updated incrementally: `newAvg = oldAvg + (newValue - oldAvg) / count`.
- **Stop probability** — Tracks stops (speed < 3 km/h) and updates probability: `stopProbability = stopCount / riddenCount`.
- **Circular mean angle** — Uses atan2 of mean sin/cos components to handle 0/360 wrap-around correctly.
- **Position refinement** — Placeholder for future enhancement (weighted average of traversal positions).
- **Node confirmation** — Placeholder for future enhancement (mark nodes with ≥2 confirmed edges).

### Performance Characteristics

- **R-tree query**: O(log n) per datapoint
- **Pending point clustering**: O(n²) but n is small (hundreds, not millions) — acceptable for expected data volume
- **Batch writes**: Single transaction for all edge updates per session
- **Expected session processing time**: Under a few seconds for typical 2000–4000 datapoint sessions

### What This WP Does NOT Include

- Map visualization of edges (WP7 — speed/stop overlay)
- Node connectivity / routing (WP9 — route planner)
- R-tree persistence (in-memory only, rebuilt from Room on startup)
- Position refinement implementation (deferred)
- Node confirmation status implementation (deferred)

---

## WP5 — Map Integration & Session Track Display

### New Files

- **`util/GpsTrackParser.kt`** — Parses GPS track JSON (`[[lat,lon],...]`) to `List<LatLng>`, computes `LatLngBounds` for camera fitting. Handles null, blank, and malformed input gracefully.
- **`ui/components/ComposableMapView.kt`** — Reusable Compose wrapper for MapLibre `MapView` with full lifecycle management (start/resume/pause/stop/destroy), configurable center/zoom/gestures, OSM raster tile style, and `onMapReady` callback delivering both `MapLibreMap` and `Style`.
- **`ui/components/MapTrackRenderer.kt`** — GPU-accelerated polyline rendering via GeoJSON source + LineLayer. Supports add/remove/removeAll operations with round line joins/caps and configurable color/width.

### Modified Files

- **`AndroidManifest.xml`** — Added `INTERNET` and `ACCESS_NETWORK_STATE` permissions for tile loading.
- **`util/Constants.kt`** — Added `OSM_RASTER_STYLE_JSON` (inline style JSON for OSM raster tiles), `DEFAULT_MAP_ZOOM`, `TRACK_LINE_WIDTH`, `TRACK_FIT_PADDING`, and `TRACK_COLORS` (6-color palette for session tracks).
- **`ui/screens/sessiondetail/SessionDetailScreen.kt`** — Replaced `MapPlaceholderCard` with `SessionMapCard`: renders GPS track as a blue polyline on a static (gestures disabled) 300dp MapLibre map, or shows "No GPS data available" for sessions without GPS.
- **`ui/screens/mapview/MapViewViewModel.kt`** — Added `CyclingSessionRepository` injection, `sessions` StateFlow (sorted by date DESC), `visibleSessionIds` toggle state with `toggleSession()`, `showAll()`, `hideAll()`.
- **`ui/screens/mapview/MapViewScreen.kt`** — Full rewrite: full-screen MapLibre map with OSM tiles, edge count chip overlay, Layers FAB opening a `ModalBottomSheet` with session toggle list (color dots, date/distance/duration, switches), diff-based track rendering via `LaunchedEffect`.

### Test Files

- **`util/GpsTrackParserTest.kt`** — 10 unit tests covering valid parsing, null/empty/blank/malformed input, empty array, single point, sub-array validation, bounds computation edge cases.

---

## WP4 — Dashboard & Session Detail UI

### New Files

- **`util/FormatUtils.kt`** — Pure formatting utilities for durations, distances, speeds, power, dates, and comparison strings. No Android dependencies, fully unit-testable.
- **`domain/service/SessionComparator.kt`** — Computes median-based comparisons against recent sessions. Includes `SessionComparison` data class with median values for duration, distance, speed, power, normalized power, and interval time.
- **`ui/components/SpeedHistogramChart.kt`** — Compose Canvas horizontal bar chart showing speed distribution across 7 bins with color gradient and time/percentage labels.
- **`ui/components/PowerZoneChart.kt`** — Compose Canvas horizontal bar chart showing power zone distribution (Zones 1–6 plus "0 W") with zone-specific colors.
- **`ui/components/ComparisonCard.kt`** — Card displaying current session metrics vs. median of recent rides with color-coded diff indicators (green=better, red=worse, gray=neutral).
- **`ui/components/IntervalListCard.kt`** — Card listing detected intervals with duration, distance, power, direction arrows, pause times between intervals, and a summary line.
- **`ui/components/CaloriesCard.kt`** — Card showing fat/carb burn in grams and kcal with a stacked bar visualization of the ratio.

### Modified Files

- **`data/local/dao/CyclingSessionDao.kt`** — Added `getRecentSessionsList` suspend query returning a plain `List` for one-shot reads.
- **`domain/repository/CyclingSessionRepository.kt`** — Added `getRecentSessionsList(limit)` method.
- **`data/repository/CyclingSessionRepositoryImpl.kt`** — Implemented `getRecentSessionsList` delegation to DAO.
- **`ui/screens/dashboard/DashboardViewModel.kt`** — Added `sessionCount` StateFlow derived from sessions list.
- **`ui/screens/dashboard/DashboardScreen.kt`** — Added TopAppBar with ride count subtitle, enhanced SessionCard with avg speed, power indicator dot (green/gray), "No power" text for non-power rides, DirectionsBike icon in empty state. All formatting via FormatUtils.
- **`ui/screens/sessiondetail/SessionDetailViewModel.kt`** — Fixed SavedStateHandle type from String to Long, added SessionComparator injection, comparison StateFlow, isLoading state.
- **`ui/screens/sessiondetail/SessionDetailScreen.kt`** — Full rewrite with 7 scrollable sections: Summary Card (2-column grid), Comparison Card, Speed Histogram, Power Zones (power only), Calories (power only), Interval List (power only), Map Placeholder.

### Test Files

- **`FakeCyclingSessionRepository.kt`** — In-memory test fake implementing CyclingSessionRepository with all methods including `getRecentSessionsList`.
- **`FormatUtilsTest.kt`** — Unit tests for all formatting functions including duration, distance, speed, power, comparison with/without median, duration comparison.
- **`SessionComparatorTest.kt`** — 5 unit tests covering: 0 previous sessions (null), 1 previous (null), 3 previous with power (medians), mixed power/no-power (power-only medians), all no-power (null power medians).

---

## WP3 — Interval Detection & Matching

### New Files

- **`domain/service/IntervalDetector.kt`** — Detects high-power intervals from datapoint streams using rolling-average power thresholds, rest-gap tolerance, and FTP-normalized duration acceptance. Produces `IntervalSession` objects with GPS track, power, speed, and direction metadata.
- **`domain/service/IntervalMatcher.kt`** — Matches detected intervals to prototype routes by proximity of start/end coordinates (50 m radius), selecting the highest-scoring match. Exposes both a suspend `matchToPrototypes` (Room-backed) and a pure `matchIntervals` function.

### Modified Files

- **`data/fitimport/FitImportService.kt`** — Added interval detection & matching pipeline step after session persistence (step 10). Power rides trigger `IntervalDetector.detect` → `IntervalMatcher.matchToPrototypes` → `IntervalRepository.insertIntervals` → `CyclingSessionRepository.updateIntervalStats`. Import summary now includes interval count.
- **`data/local/dao/CyclingSessionDao.kt`** — Added `updateIntervalStats` query to update `intervalCount` and `intervalTotalTimeSec` columns.
- **`domain/repository/CyclingSessionRepository.kt`** — Added `updateIntervalStats(sessionId, count, totalSec)` method.
- **`data/repository/CyclingSessionRepositoryImpl.kt`** — Implemented `updateIntervalStats` delegation to DAO.

### Test Files

- **`IntervalDetectorTest.kt`** — 7 unit tests covering: low-power rejection, single interval detection, short-dip merge, long-dip split, FTP-normalized duration acceptance, null-power handling, end-of-session intervals.
- **`IntervalMatcherTest.kt`** — 4 unit tests covering: empty prototypes, start+end proximity match, start-too-far rejection, highest-score selection among multiple prototypes.

---

## WP2 — .FIT File Import Pipeline

### New Files

- **`data/fitimport/ImportResult.kt`** — Sealed class modelling import outcomes: `Success`, `AlreadyImported`, `Error`
- **`data/fitimport/SessionMetricsCalculator.kt`** — Stateless singleton that computes all session metrics from parsed datapoints:
  - Session timestamps, total/pause/net durations (from timer stop/start events)
  - Distance via cumulative haversine between consecutive GPS points
  - Speed histogram classified into 7 bins (0–10, 10–20, 20–25, 25–30, 30–35, 35–40, >40 km/h)
  - Power zone distribution (Zones 1–6 plus "0 W") as fraction of FTP
  - Average power and normalized power (30 s rolling average raised to 4th power)
  - Fat and carbohydrate burn estimates via polynomial models
  - GPS and power quality percentages
  - GPS track downsampled to ~1000 points, serialized as `[[lat,lon],...]` JSON
- **`data/fitimport/FitImportService.kt`** — Orchestrator singleton driving the full import pipeline:
  1. SHA-1 duplicate detection
  2. Garmin FIT SDK parsing (`RecordMesgListener` for GPS/speed/power, `EventMesgListener` for timer events)
  3. GPS quality filter (zero coords, unrealistic speed/power, GPS leaps, implied speed checks)
  4. Vector and angle computation for each datapoint
  5. Power availability determination (>=10% threshold)
  6. Linear power interpolation with forward/backward fill for gaps
  7. Metrics computation via `SessionMetricsCalculator`
  8. Room persistence and result reporting

### Modified Files

- **`ui/screens/dashboard/DashboardViewModel.kt`** — Added `FitImportService` injection, `ImportUiState` sealed class, `importFromUri(uri)` for importing from device storage via `ContentResolver`, filename extraction via cursor query
- **`ui/screens/dashboard/DashboardScreen.kt`** — Added `OpenDocument` file picker on FAB, `SnackbarHost` for import feedback, `LinearProgressIndicator` during loading, improved session cards showing formatted date, distance, duration, average power, and a "PWR" badge for power-equipped rides
- **`ui/screens/settings/SettingsViewModel.kt`** — Added `FitImportService` injection, `TestImportState` data class, `importTestFiles()` iterating the 7 bundled `res/raw/c1–c7.fit` files with per-file progress updates
- **`ui/screens/settings/SettingsScreen.kt`** — Added "Test Data Import" card with import button (disabled during import), circular progress indicator, per-file result display with status icons, vertical scroll for the settings column

---

## WP1 — Project Skeleton

### Architecture

Clean Architecture with three layers (domain, data, UI) and Hilt dependency injection throughout.

### Domain Layer — Models

- **`CyclingSession`** — Core ride model with timestamps, durations, distance, power metrics, speed histogram, power zone distribution, GPS track, and quality indicators
- **`Datapoint`** — Transient per-record structure used during import (lat, lon, speed, power, timestamp, vector, angle)
- **`IntervalSession`** — High-power interval detected within a ride
- **`IntervalPrototypeRoute`** — Template route that interval sessions are matched against
- **`MapEdge`** / **`MapNode`** — Graph representation of ridden road segments
- **`PendingPoint`** — GPS point awaiting graph integration

### Domain Layer — Repository Interfaces

- **`CyclingSessionRepository`** — CRUD + SHA-1 lookup + Flow-based queries
- **`IntervalRepository`** — Interval session management with foreign-key scoping
- **`MapGraphRepository`** — Edge/node/pending-point operations for the map graph

### Data Layer — Room Database

- **`CycleGraphDatabase`** — Room database with 6 entities
- **6 Entity classes** — `CyclingSessionEntity`, `IntervalSessionEntity`, `IntervalPrototypeRouteEntity`, `MapEdgeEntity`, `MapNodeEntity`, `PendingPointEntity`
- **6 DAO interfaces** — Full CRUD, Flow-based list queries, specialized lookups (SHA-1, spatial, foreign key)
- **`Converters`** — Room type converters

### Data Layer — Repository Implementations

- **`CyclingSessionRepositoryImpl`** — Maps between domain models and entities using extension functions
- **`IntervalRepositoryImpl`** — Interval session persistence
- **`MapGraphRepositoryImpl`** — Map graph persistence

### Dependency Injection

- **`DatabaseModule`** — Provides Room database and all 6 DAOs
- **`RepositoryModule`** — Binds repository interfaces to implementations

### Utilities

- **`Constants.kt`** (`CyclingConstants`) — FTP, thresholds, histogram bins, power zones, polynomial coefficients, GPS parameters, home location, bounding box
- **`GeoUtils.kt`** — Semicircle conversion, haversine distance, bearing, compass direction, angle difference, meters-per-degree, fat/carb burn polynomials
- **`EntityMappers.kt`** — Bidirectional extension functions for all entity/domain pairs, Gson-based JSON serialization for maps

### UI Layer — Navigation

- **`Screen.kt`** — Route definitions for 5 screens (Dashboard, Session Detail, Map View, Route Planner, Settings)
- **`CycleGraphNavHost.kt`** — NavHost wiring all screens with argument passing
- **`BottomNavBar.kt`** — Bottom navigation component

### UI Layer — Screens

- **Dashboard** — Session list with FAB for import, empty state placeholder
- **Session Detail** — Placeholder for detailed ride view
- **Map View** — Placeholder for map visualization
- **Route Planner** — Placeholder for route planning
- **Settings** — Constants display, test data import

### UI Layer — Theme

- **`Theme.kt`** / **`Color.kt`** / **`Type.kt`** — Material 3 theming

### Infrastructure

- **`CycleGraphApplication.kt`** — Hilt application class
- **`MainActivity.kt`** — Single-activity Compose host
- **`res/raw/c1.fit–c7.fit`** — 7 bundled Garmin .FIT test files

### Key Dependencies

Kotlin 2.0, Jetpack Compose (BOM 2025.01.01), Hilt 2.55, Room 2.7.1, Navigation Compose 2.8.9, Garmin FIT SDK 21.188.0, MapLibre 11.5.1, Gson 2.11.0, Coroutines 1.10.2 — targeting minSdk 34 / compileSdk 36.
