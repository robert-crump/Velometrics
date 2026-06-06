# Velometrics

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Android](https://img.shields.io/badge/platform-Android%2014%2B-green.svg)
![Kotlin](https://img.shields.io/badge/language-Kotlin-purple.svg)

An Android app for tracking, analyzing, and visualizing cycling sessions. Import rides from Garmin FIT files, explore performance metrics, compare repeated routes, plan new routes, and navigate with offline maps.

*README last refreshed: 2026-06-06*

## Features

- **FIT file import** — Import rides from Garmin devices; supports power, heart rate, cadence, and GPS data; SHA-1 duplicate detection prevents re-importing the same file
- **Session analysis** — Power zones, fat/carb efficiency histogram, sprint/interval detection, normalized power, training stress score, calorie breakdown
- **Repeated routes** — Automatically clusters similar routes using single-linkage clustering; compare performance across rides with scatter plots and averaged speed distributions
- **Route planner** — Suggest routes matching a target distance and duration using random-walk + A* pathfinding over the bundled road graph; export results as GPX
- **Offline map** — MapLibre-based map with speed overlay, interval segments, heatmap, stop markers, and road-graph edge visualization
- **Navigation** — Load a GPX track and discover POIs (fuel, cafés, bakeries, fast food) along the route via Overpass API; Google Maps handoff for turn-by-turn; handles GPX share intents
- **GPS tracking** — Live location with accuracy circle; progressive fix acquisition with timeout feedback
- **User settings** — Configurable FTP; home location via address search or map tap; on-demand metric recalculation

## Requirements

- Android 14+ (API 34)
- Android Studio Hedgehog or later
- JDK 11 (JDK 25 is not supported by the current Gradle/AGP version)

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/robert-crump/Velometrics.git
   cd Velometrics
   ```

2. Open in Android Studio via **File → Open**.

3. Let Gradle sync complete.

4. Run on a device or emulator (API 34+).

> **Note:** The app bundles `cycling_graph.db` (~58 MB) as an asset for offline map routing and route planning. Cloning will include this file.

## Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI (BOM 2025.01.01)
- [MapLibre Android](https://maplibre.org/) — Offline maps (v11.5.1)
- [Room](https://developer.android.com/training/data-storage/room) — Local database (v2.7.1)
- [Hilt](https://dagger.dev/hilt/) — Dependency injection (v2.55)
- [Garmin FIT SDK](https://developer.garmin.com/fit/) — FIT file parsing (v21.188.0)
- [Retrofit](https://square.github.io/retrofit/) — Nominatim geocoding + Overpass API (v2.11.0)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) — User preferences (v1.1.1)
- [RTree2](https://github.com/davidmoten/rtree2) — Spatial indexing for edge graph (v0.9.3)

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

## Development

This project was developed with assistance from [Claude Code](https://claude.ai/code) by Anthropic.
