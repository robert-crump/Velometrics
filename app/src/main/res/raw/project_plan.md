# Cycling App

---

### PROMPT START (WP1)

```
You are building an Android cycling analytics app. The app processes .fit files from a Wahoo Roam bike computer and provides training analysis, route visualization, interval detection, a road graph model for route planning, and POI lookup along GPX routes. The .fit files are synced to Dropbox ("Apps > Wahoo") and should be importable from there or from local storage.

The user rides 1–4 times per week, 25–50 km per ride (up to 400 km max). He uses two bikes: a race bike WITH a power meter, and a gravel bike WITHOUT a power meter. Data from both must be handled gracefully (power fields will be null for the gravel bike).

The user's home location is 50.78117°N, 6.07261°E (near Aachen). This should be the default start/end point for route planning and configurable in settings. The bounding box for his typical riding area is approximately: SW corner (50.57, 5.63), NE corner (50.96, 6.30).

=== TECH STACK ===
- Target: Android (minimum SDK 34 / Android 14)
- Language: Kotlin
- UI: Jetpack Compose
- Maps: MapLibre GL with OpenStreetMap vector tiles (e.g., via MapTiler free tier or self-hosted protomaps)
- POI data: Overpass API (OpenStreetMap) — free, no API key needed, good coverage in the Aachen/Maastricht/Liège region
- Local database: Room (SQLite) for structured data
- .fit parsing: Use the official FIT SDK for Java/Kotlin (available from Garmin: com.garmin.fit:fit-sdk) or ANT+ FIT library
- GPX parsing: Use a lightweight XML parser
- Background processing: Kotlin Coroutines + WorkManager for file imports
- DI: Hilt
- Architecture: MVVM with clean architecture layers (data / domain / presentation)
- Dropbox integration: For now, manual file picking via Android file picker (the user has the Dropbox app installed, so .fit files in "Apps > Wahoo" are accessible). Later, automatic sync via Dropbox SDK (OAuth) will be added.
- Offline capability: Not required for the first version. Internet is needed for map tiles and POI queries. Offline support may be added later.
- Sample data: The developer will place several .fit files into the project's `res/raw/` folder for testing during development. The import pipeline should also support reading from raw resources for automated testing.

=== CORE DATA MODEL ===

--- Datapoint (transient, not persisted individually) ---
Used during .fit file import only. After import, data is aggregated into CyclingSession metrics and MapEdge updates. Individual datapoints are NOT stored in the database (to keep DB size bounded).

Fields:
- lat: Double (converted from semicircles: value * 180 / 2^31)
- lon: Double (same conversion)
- speed_kmh: Double? (raw speed in m/s * 3.6; discard if > 100 km/h)
- power: Int? (discard if > 1500 W; will be null for gravel bike)
- timestamp: Instant
- vector: FloatArray(2)? — unit vector [dx_m, dy_m] from previous point, null if movement < 0.5 m
- angle: Double? — bearing in degrees [0, 360), computed as atan2(dy, dx)

Multi-bike handling: The gravel bike has no power meter. When a session has zero valid power readings, mark it as hasPower = false. For such sessions, skip ALL power-related computations: no power zones, no fat/carb burn, no normalized power, no interval detection. GPS, speed, and duration analysis still apply normally. The MapEdge graph should still be updated with speed data from powerless sessions, but avgPower on edges should only incorporate data from sessions that have power data.

--- CyclingSession (persisted in Room) ---
One row per .fit file. Fields:
- id: Long (auto-generated primary key)
- fileName: String (original .fit filename)
- fileSha1: String (SHA-1 hash for deduplication)
- sessionStart: Instant
- sessionEnd: Instant
- totalDurationSec: Int
- pauseDurationSec: Int
- netDurationSec: Int
- distanceKm: Double
- averagePower: Int? (null if no power meter)
- normalizedPower: Int? (30-second rolling window → 4th-power mean → 4th root)
- fatBurnedGrams: Double?
- carbsBurnedGrams: Double?
- powerZoneDistribution: String (JSON map: {"Zone 1": 230, "Zone 2": 500, ...})
- speedHistogram: String (JSON map: {"0-20 km/h": 45, "20-25 km/h": 120, ...})
- intervalCount: Int
- intervalTotalTimeSec: Int
- gpsQualityPercent: Double
- powerQualityPercent: Double?
- hasPower: Boolean (true if session has any valid power readings; false for gravel bike rides)

--- IntervalSession (persisted in Room) ---
One row per detected high-power effort. Fields:
- id: Long
- cyclingSessionId: Long (foreign key)
- startTimestamp: Instant
- durationSec: Int
- durationNormalizedSec: Int (= durationSec * avgPower / FTP)
- distanceM: Double
- avgPower: Int
- avgSpeedKmh: Double
- avgSpeedNormalizedKmh: Double (= avgSpeedKmh / avgPower * FTP)
- direction: String (compass: "north", "east", "south", "west")
- startLat: Double
- startLon: Double
- endLat: Double
- endLon: Double
- gpsTrack: String (JSON array of [lat, lon] pairs for map rendering)
- prototypeRouteId: Long? (foreign key, null if no match)

--- IntervalPrototypeRoute (persisted in Room) ---
Named interval segments where the user frequently does efforts. Fields:
- id: Long
- name: String (user-defined, e.g. "Vaalserquartier Hill")
- startLat: Double
- startLon: Double
- endLat: Double
- endLon: Double
- distanceM: Double?
- avgGpsTrack: String? (averaged GPS track from all matched intervals)

Matching logic: An IntervalSession matches a prototype if its start point is within 50 m of the prototype's start AND its GPS track touches within 50 m of the prototype's end.

--- MapEdge (persisted in Room — this is the graph model) ---
The road network graph. Each edge represents a ~10 m segment of road, for ONE direction of travel. On a typical two-lane road, the northbound and southbound edges will be separate entries with different angleDeg values (~0° vs ~180°) and slightly different lat/lon positions (offset by ~2–4 m, reflecting riding on the right side of the road). Fields:
- id: Long
- startLat: Double
- startLon: Double
- endLat: Double
- endLon: Double
- angleDeg: Double (bearing of the direction vector, 0–360°)
- lengthM: Double (~10 m)
- riddenCount: Int (how many times this edge has been traversed; edge is only "confirmed" when riddenCount >= 5)
- avgSpeedKmh: Double (running average, updated with each new traversal)
- avgPower: Double? (running average)
- medianSpeedKmh: Double? (if storing full distribution is too expensive, use running average instead)
- stopProbability: Double (fraction of traversals where speed dropped below 3 km/h)
- avgStopDurationSec: Double? (average stop time when a stop occurred)

Position refinement: Each time an edge is traversed and a GPS point snaps to it, the edge's start and end coordinates are refined using a weighted running average: newLat = oldLat + (pointLat - oldLat) / riddenCount. This ensures that with more data, the edge's geometry converges toward the true road position, averaging out GPS noise over time. The R-tree entry must be updated after each position refinement.

Building / updating edges:
When a new .fit file is imported, iterate through consecutive datapoints. For each pair, determine which MapEdge they fall on (by proximity to edge midpoint + angle similarity within ±45°). If a matching edge exists, update its running averages AND refine its position. If no matching edge exists and the point is > 5 m from any existing edge, buffer it. Once a buffer location has accumulated ≥ 5 datapoints from different sessions, create a new confirmed MapEdge.

The key insight: the database size grows only when new roads are discovered, not with each new ride.

Spatial model: Use a hybrid approach combining an R-tree spatial index with directed edges. When a new GPS point arrives during import: (1) query the R-tree for all edges within 15 m of the point, (2) among those candidates, find the edge whose angle matches the point's bearing within ±45°, (3) if a match is found, update the edge's running averages (speed, power, stop probability) and refine its position, (4) if no match is found, add the point to a "pending points" buffer table. Periodically (or after each import), cluster pending points: when ≥ 5 points from ≥ 3 different sessions fall within 10 m of each other with compatible bearings, create a new confirmed MapEdge and insert it into the R-tree. On Android, the R-tree can be implemented using Room's built-in R*Tree virtual table support (SQLite extension) or a simple in-memory R-tree library.

Map visualization of edges: On the map, opposite-direction edges on the same road (e.g., northbound and southbound) are displayed SEPARATELY — they are not merged. This is important because they carry different data (e.g., uphill vs. downhill speed, different stop probabilities at asymmetric intersections). Each edge is drawn as a thin line segment colored by its avgSpeedKmh. Because opposite-direction edges are offset by a few meters (reflecting riding on the right side of the road), they will appear as two parallel colored lines — similar to how traffic flow maps show both directions. At typical map zoom levels for cycling (zoom 13–15), this offset is clearly visible and informative.

--- MapNode (persisted in Room) ---
Intersection / endpoint of MapEdges. Fields:
- id: Long
- lat: Double
- lon: Double
- isConfirmed: Boolean (at least 5 traversals)

Nodes are created by clustering edge endpoints that are within 10 m of each other (use DBSCAN or simple nearest-neighbor merging).

--- StopSpot (derived view / query on MapEdge data) ---
Not a separate table — instead, query MapEdges where stopProbability > 0.3. Display on map with:
- Color coding by stop duration: red (>= 10 min, likely coffee/snack stop), orange (1–10 min, e.g. long red light), yellow (< 1 min, e.g. traffic light)
- Icon indicating stop type (inferred from duration and frequency)

[YOUR PROMPT MENTIONED: "In a T-shaped intersection, left turn may have long stop, right turn may not." This is already handled by the directional edge model — the left-turn edge and right-turn edge are separate MapEdges with different angleDeg and different stopProbability values.]

=== CONSTANTS AND FORMULAS ===

FTP (Functional Threshold Power): 300 W [make this user-configurable in settings]

Power Zones (fractions of FTP):
- Zone 1: 0 to 0.55 * FTP (0–165 W)
- Zone 2: 0.55 to 0.70 * FTP (165–210 W)
- Zone 3: 0.70 to 0.92 * FTP (210–276 W)
- Zone 4: 0.92 to 1.05 * FTP (276–315 W)
- Zone 5: 1.05 to 1.25 * FTP (315–375 W)
- Zone 6: > 1.25 * FTP (> 375 W)
- "0 W" zone for coasting / no power

Speed classes for histogram:
- 0–10 km/h, 10–20 km/h, 20–25 km/h, 25–30 km/h, 30–35 km/h, 35–40 km/h, > 40 km/h

Speed classes for map coloring (note: the user's typical max is 70–75 km/h on long descents; absolute max was ~100 km/h on the Nürburgring):
- 0–20 km/h (yellow), 20–25 km/h (orange), 25–30 km/h (dark orange), 30–40 km/h (red), 40–50 km/h (dark red), 50–60 km/h (light blue), 60–70 km/h (medium blue), > 70 km/h (dark blue), 0 km/h (black)

Fat burn (grams per second):
  fat_g_per_sec = (-0.0142156 * W² + 5.30361 * W - 83.0716) / 3600
  where W = power in watts. Clamp to >= 0.

Carb burn (grams per second):
  carb_g_per_sec = (-0.0000007556 * W⁴ + 0.0006540519 * W³ - 0.1720687649 * W² + 17.6082662802 * W - 409.747303849) / 3600
  where W = power in watts. Clamp to >= 0.

Normalized Power:
  1. Compute 30-second rolling average of power values
  2. Raise each averaged value to the 4th power
  3. Take the mean of all 4th-power values
  4. Take the 4th root of that mean

=== INTERVAL DETECTION ALGORITHM ===

Parameters:
- rollingWindow = 15 seconds
- powerThreshold = 280 W (INTERVAL_POWER_THRESHOLD)
- minDuration = 120 seconds
- allowedRestGap = 15 seconds (brief dips below threshold are tolerated)
- FTP = 300 W

Algorithm:
1. Compute rolling average of power with a window of 15 data points (~15 seconds at 1 Hz)
2. Iterate through the rolling averages:
   a. When rolling avg exceeds powerThreshold and no interval is active → start a new interval
   b. While rolling avg is above threshold → continue interval, update lastAboveIndex
   c. When rolling avg drops below threshold:
      - If gap since lastAboveIndex <= allowedRestGap → tolerate, keep interval open
      - If gap > allowedRestGap → end interval candidate
3. For each interval candidate, extract the datapoints from startIndex to (lastAboveIndex + rollingWindow - 1)
4. Compute normalizedDuration = actualDuration * (avgPower / FTP)
5. Only keep intervals where normalizedDuration >= minDuration
6. After detection, attempt to match each interval to known IntervalPrototypeRoutes

=== FEATURE 1: TRAINING ANALYSIS (per session) ===

For each imported CyclingSession, show a detail screen with:
- Summary: date, duration (total / net / pause), distance, avg speed
- Power: average power, normalized power, power zone distribution (bar chart, each zone as % of ride time). Only shown if power data available.
- Speed: speed histogram (bar chart with the bins defined above)
- Calories: fat burned (g), carbs burned (g), total kcal (fat: 9 kcal/g, carbs: 4 kcal/g)
- Intervals: list of detected intervals with duration, distance, avg power, direction. Tap on an interval to see it highlighted on a map.
- Comparison: compare latest session's key metrics against the median of the previous 5 sessions (show absolute + relative difference with +/- sign)
- Map: show the session's GPS track on a map, color-coded by speed class

=== FEATURE 2: STOP SPOTS & SPEED MAP ===

On a map view, overlay the MapEdge data:
- Each confirmed edge (riddenCount >= 5) is drawn as a short line segment, colored by avgSpeedKmh
- Edges with high stopProbability are highlighted with markers:
  - Short stops (< 1 min avg, high frequency): yellow traffic-light icon
  - Medium stops (1–10 min): orange pause icon
  - Long stops (>= 10 min): red coffee-cup icon
- The user can filter by direction (e.g., "show only northbound edges") to see directional speed differences (e.g., uphill vs downhill, left turn vs right turn at intersections)

=== FEATURE 3: INTERVAL MAP ===

A dedicated map showing all past IntervalSessions:
- Each interval is drawn as a polyline on the map
- Color coding by normalized duration: 2 min = light, 8+ min = dark (use a gradient)
- Intervals matched to the same IntervalPrototypeRoute are visually grouped (single thick polyline with a label showing: name, count, avg duration, avg power)
- Tap on a grouped interval to see individual efforts in a list
- Each interval shows: distance, direction (arrow), time_duration

=== FEATURE 4: NEW ROUTE SUGGESTION ===

Using the MapEdge/MapNode graph:
- User sets a start/end point (default: home location at 50.78117°N, 6.07261°E)
- User sets a target distance (e.g., 40 km) or duration (e.g., 1.5 hours)
- The algorithm finds a loop (start == end) that:
  a. Prefers confirmed edges (riddenCount >= 5)
  b. Avoids edges with high stopProbability
  c. Maximizes variety: penalizes edges the user has ridden many times recently
  d. Avoids using the same road twice (except near start/end where options are limited)
  e. Can estimate total ride time using per-edge avgSpeedKmh
- The target distance has a ±10% tolerance: a route for "40 km" may be 36–44 km. If no route is found within ±10%, relax to ±15%.
- Algorithm: Use a budget-constrained weighted random walk with backtracking and A* return. At each node, score candidate edges by a weighted formula:
  (1) Straight-road bonus: strongly prefer edges that continue in roughly the same direction as the current heading (angle difference < 30°). This avoids zig-zagging through residential streets when a fast straight road is available. Concretely: if continuing straight has avgSpeedKmh > 25, penalize turns heavily.
  (2) Speed preference: prefer edges with high avgSpeedKmh. High average speed usually means uninterrupted flow (no traffic lights, no yield signs). Low average speed often indicates frequent stops.
  (3) Low stop probability: penalize edges where stopProbability > 0.3.
  (4) Turn penalty: each turn onto a different road (angle change > 45°) incurs a cost. This discourages routes with many short zigzag segments. The penalty should be proportional to stopProbability at the turning node — a turn at a traffic light is worse than a turn onto an open road.
  (5) Novelty: mildly penalize edges the user has ridden many times recently (optional, lower weight).
  (6) Direction: heading roughly away from home in the first half of the budget, toward home in the second half.
  When the remaining budget drops below 1.3× the straight-line distance home (divided by the graph's average speed), switch to A* shortest-path-home mode using the same cost function.
  Generate up to 10 candidate routes and present the best one (highest total score).
- Display the suggested route on a map with estimated duration and distance
- Allow the user to export the route as a GPX file

=== FEATURE 5: POI ALONG GPX ROUTE ===

When the user loads a GPX file and taps "POIs nearby":
- Fetch the user's current GPS position (one-time location request, no continuous tracking needed)
- Along the GPX track (from current position in the direction of travel), find the nearest:
  - Gas stations (OSM tag: amenity=fuel)
  - Cafés / bakeries (amenity=cafe, shop=bakery)
  - Fast food / fries delis (amenity=fast_food, cuisine=friture)
- Use the Overpass API to query POIs. Build a bounding box query around the remaining GPX track (from current position to end, bbox + 500 m buffer) and cache the results for this session.
- Display as a sorted list: "In X.X km along your route, there is a [type] Y.Y km off-route (air distance)"
- The search should look at points along the GPX track, checking POIs within 500 m perpendicular distance of the track
- The list is refreshed only when the user manually taps the "POIs nearby" button again. There is no automatic background refresh or continuous GPS tracking for this feature.

=== IMPORT PIPELINE ===

When importing a .fit file:
1. Compute SHA-1 hash. If already in the database, skip (deduplication).
2. Parse all "record" messages: extract position_lat, position_long (semicircles → degrees), speed (m/s → km/h), power, timestamp.
3. Parse "event" messages: extract timer start/stop events for pause detection.
4. GPS quality filter — discard bad datapoints BEFORE any further processing:
   a. Discard points with speed > 100 km/h (the user's absolute max was ~100 km/h on a long Nürburgring descent; normal max is ~70–75 km/h on long hills)
   b. Discard points with power > 1500 W
   c. Compute haversine distance from previous point. If distance > 500 m and elapsed time < 5 seconds, this is a GPS leap — discard the point.
   d. Compute implied speed from (distance / elapsed time). If implied speed > 120 km/h, discard the point (even if the .fit file's speed field looks reasonable, a huge positional jump indicates GPS error).
   e. Discard points where lat or lon is exactly 0.0 (common GPS initialization artifact).
5. For each valid datapoint, compute the movement vector and angle relative to the previous valid point (skip discarded points in the chain).
6. Determine hasPower: if the session has fewer than 10% valid power readings, set hasPower = false.
7. For hasPower sessions only: interpolate missing power values (linear interpolation with forward/backward fill).
8. Build CyclingSession aggregate metrics (speed histogram, power zones, fat/carb burn — the latter three only if hasPower).
9. Run interval detection (only if hasPower).
10. Match intervals to prototype routes.
11. Update MapEdge graph with the new session's datapoints.
12. Persist CyclingSession, IntervalSessions, and updated MapEdges to Room.

=== STUB / SHORT-RIDE FILTERING ===

Sometimes the user briefly explores a new road, discovers it's gravel, and turns around after 50–200 m. These "stubs" should NOT pollute the map graph. Rules:
- MapEdge confirmation threshold remains at riddenCount >= 5 (from >= 3 different sessions). This naturally filters one-off stubs.
- Additionally, for the "pending points" buffer: if all buffered points for a candidate edge come from fewer than 3 distinct sessions, do not create the edge. This prevents a single back-and-forth ride (which might produce 2–4 data points in each direction) from ever becoming a confirmed edge.
- On the map, only display confirmed edges (riddenCount >= 5). Unconfirmed edges are invisible to the user but remain in the buffer for future confirmation.

=== UI SCREENS ===

1. **Dashboard / Home**: List of recent CyclingSessions (sorted by date desc). Each row shows: date, distance, duration, avg power (if available). Tap to open Training Analysis.
2. **Training Analysis** (per session): As described in Feature 1.
3. **Map View**: Tabbed or layered map with toggles for:
   - Speed overlay (MapEdges colored by speed)
   - Stop spots
   - Interval overlay
   - Raw session tracks
4. **Route Planner**: Feature 4 interface.
5. **Live Navigation**: Feature 5 interface (GPX + POIs).
6. **Settings**: FTP value, home location, Dropbox connection, import folder, speed class customization.

=== WHAT I DO NOT NEED ===
- No social features, no sharing, no cloud sync (beyond Dropbox import)
- No real-time ride recording (the Wahoo handles that)
- No heart rate analysis (I don't use a heart rate monitor)
- No elevation profile (nice to have later, not now)
- No offline mode for now (internet required for map tiles and POI queries)
- No migration from previous Python-based data (starting fresh)
```

### PROMPT END

---

## Resolved Decisions


| Topic | Decision |
|-------|----------|
| POI data source | Overpass API (OpenStreetMap) |
| Dropbox sync | Manual file picker for now; Dropbox SDK OAuth later |
| Map tiles | MapLibre GL + OpenStreetMap vector tiles |
| Offline capability | Not for v1; may be added later |
| Home coordinates | 50.78117°N, 6.07261°E |
| Multi-bike handling | hasPower flag; skip power analysis for gravel bike |
| Data migration | Start fresh, no Python pickle migration |
| Sample data | .fit files in res/raw/ for testing |
| Spatial model | Hybrid: R-tree index + directed edges |
| Route algorithm | Budget-constrained weighted random walk + A* return, with straight-road bonus and turn penalty |
| Speed cap | 100 km/h (user's max was ~100 on Nürburgring) |
| Stub filtering | Edges need ≥ 5 traversals from ≥ 3 sessions |

---


### Recommended Spatial Approach: The Hybrid Approach (R-Tree + Directed Edges)

This is what the prompt now specifies. Here is how it works in detail:

**Step 1 — R-Tree as spatial index.** The R-tree is a balanced tree structure optimized for spatial queries. Each edge's bounding box (a small rectangle around its 10 m line segment) is inserted into the R-tree. When you need to find "which edges are near this GPS point?", the R-tree answers in O(log n) time instead of scanning all edges.

**Step 2 — Point arrival during import.** For each new GPS datapoint, compute its bearing (angle from previous point). Query the R-tree: "give me all edges within 15 m of this point." Among the candidates, find the one whose `angleDeg` matches the point's bearing within ±45°. If found → snap to that edge, update running averages. If not found → add to the pending-points buffer.

**Step 3 — Edge creation from buffer.** After import, scan the buffer. Cluster nearby pending points (within 10 m, compatible bearing, from ≥ 3 different sessions). If a cluster has ≥ 5 points, compute the average start/end coordinates and bearing, create a new MapEdge, and insert it into the R-tree.

**Step 4 — Node creation.** When two edges share an endpoint (within 10 m), merge those endpoints into a shared MapNode. This happens naturally as the graph grows.

**Why this is better than a pure grid:** You get a routable graph. You get directional separation. You get GPS noise averaging. You get bounded database growth. The R-tree makes lookups fast even with tens of thousands of edges.

**Why this is better than pure edge-based without an index:** Without the R-tree, snapping a point to the nearest edge would require scanning all edges (O(n) per point, thousands of edges × thousands of points per ride = very slow). The R-tree reduces this to O(log n) per point.

---

## Route Creation Algorithm

### Recommendation

The prompt now specifies a **weighted random walk with straight-road bonus and turn penalty** as the v1 algorithm, combined with A* return-home when the budget is running low. The key additions compared to a naive random walk are: (1) the straight-road bonus ensures the route follows fast, uninterrupted roads rather than zigzagging through side streets, (2) the turn penalty discourages excessive direction changes (each turn is a potential stop), and (3) the ±10% distance tolerance gives the algorithm enough flexibility to find good loops without forcing awkward detours to hit the exact target distance. Together, these produce routes that feel like something an experienced cyclist would actually choose.

---

## Project Segmentation into Work Packages

Given Claude Pro's context limits (~50–100k tokens per conversation depending on plan), here is how I'd split this into independent work packages. Each package should be self-contained: Claude Code gets only the information it needs, produces working code, and the next package builds on the output.

### Work Package 1: Project Skeleton & Data Model
**Context needed:** Tech stack decision, Room entity definitions, project structure
**Deliverable:** Android project with Hilt setup, Room database with all entities (CyclingSession, IntervalSession, MapEdge, MapNode, IntervalPrototypeRoute), DAOs, repository interfaces, and a basic navigation shell (empty screens).
**Estimated effort:** 1 Claude Code session

### Work Package 2: .fit File Import Pipeline
**Context needed:** WP1's entity definitions, the FIT SDK dependency, the import pipeline steps (parsing, filtering, interpolation, session metric computation)
**Deliverable:** `FitImportService` that reads a .fit file, creates Datapoint objects in memory, computes all CyclingSession fields (including normalized power, fat/carb burn, speed histogram, power zones), and persists the CyclingSession to Room. File picker UI to select .fit files.
**Estimated effort:** 1–2 Claude Code sessions

### Work Package 3: Interval Detection & Prototype Matching
**Context needed:** WP2's CyclingSession structure, interval detection algorithm (give the full algorithm description), IntervalSession and IntervalPrototypeRoute entities
**Deliverable:** `IntervalDetector` class that runs the algorithm from the prompt, `IntervalMatcher` that matches against prototypes, persistence of IntervalSessions.
**Estimated effort:** 1 Claude Code session

### Work Package 4: Training Analysis UI
**Context needed:** WP2+WP3's data model, the metrics to display, comparison logic (latest vs median of last 5)
**Deliverable:** Session list screen (Dashboard), session detail screen with all charts (power zones bar chart, speed histogram, interval list, comparison section).
**Estimated effort:** 1–2 Claude Code sessions

### Work Package 5: Map Integration & Session Track Display
**Context needed:** MapLibre/Mapbox setup, CyclingSession GPS data (you'll need to also store the GPS track — either as a compressed polyline in the CyclingSession or in a separate table)
**Deliverable:** Map screen showing session tracks color-coded by speed. Layer toggles.
**Estimated effort:** 1 Claude Code session

### Work Package 6: MapEdge Graph Builder
**Context needed:** MapEdge/MapNode entities, the spatial snapping algorithm, running average update logic
**Deliverable:** `GraphBuilder` service that takes a session's datapoints and updates the MapEdge graph. R-tree or geohash-based spatial index for efficient lookup.
**Estimated effort:** 2 Claude Code sessions (this is the most complex algorithmic piece)

### Work Package 7: Speed & Stop Spot Map Overlay
**Context needed:** WP6's MapEdge data, the color coding and stop classification logic
**Deliverable:** Map layer drawing confirmed edges by speed color, stop spot markers with icons and filtering.
**Estimated effort:** 1 Claude Code session

### Work Package 8: Interval Map Overlay
**Context needed:** WP3's IntervalSession data with GPS tracks, prototype grouping logic, color gradient for duration
**Deliverable:** Map layer showing all intervals, grouped by prototype route, with tap interaction.
**Estimated effort:** 1 Claude Code session

### Work Package 9: Route Suggestion
**Context needed:** WP6's graph model (MapEdge + MapNode), the routing constraints (loop, avoid repetition, minimize stops, target distance)
**Deliverable:** `RoutePlanner` that generates a suggested loop, displays it on the map, and exports as GPX.
**Estimated effort:** 2 Claude Code sessions

### Work Package 10: GPX Navigation & POI Lookup
**Context needed:** GPX parsing, Overpass API integration (or chosen POI source), live GPS tracking, the "distance along track" calculation
**Deliverable:** GPX loader, POI search along track, live position display with nearest POI cards.
**Estimated effort:** 1–2 Claude Code sessions

### Tips for Efficient Claude Code Usage

- **Start each session with a brief context block** that describes only the entities and interfaces relevant to that WP. Don't paste the full prompt every time.
- **Reference file paths, not file contents.** Once WP1 produces the project, subsequent WPs can say "see the Room entities in `data/model/`" rather than pasting the code.
- **Use planning mode first** to get an implementation plan, then execute. This avoids wasted tokens on wrong approaches.
- **Keep a running CHANGELOG.md** in the repo. At the start of each new Claude Code session, paste the changelog to give Claude context of what's already done.
- **Test each WP independently** before moving on. This avoids cascading bugs that eat context in later sessions.
