# CycleGraph

![License](https://img.shields.io/badge/license-MIT-blue.svg)
![Android](https://img.shields.io/badge/platform-Android%2014%2B-green.svg)
![Kotlin](https://img.shields.io/badge/language-Kotlin-purple.svg)

An Android app for tracking, analyzing, and visualizing cycling sessions. Import rides from Garmin FIT files, explore performance metrics, compare repeated routes, and navigate with offline maps.

## Features

- **FIT file import** — Import rides from Garmin devices; supports power, heart rate, cadence, and GPS data
- **Session analysis** — Power zones, fat efficiency histogram, sprint/interval detection, normalized power, training stress
- **Repeated routes** — Automatically clusters similar routes; compare performance across rides with scatter plots and averaged speed distributions
- **Offline map** — MapLibre-based map with speed overlay, interval segments, heatmap, and stop markers
- **Navigation** — POI discovery along GPS tracks or near current location; Google Maps handoff for turn-by-turn
- **GPS tracking** — Live location with accuracy circle; progressive fix acquisition with timeout feedback
- **User settings** — Configurable FTP; home location via address search or map tap; on-demand recalculation

## Requirements

- Android 14+ (API 34)
- Android Studio Hedgehog or later
- JDK 11 (JDK 25 is not supported by the current Gradle/AGP version)

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/robert-crump/cyclegraph.git
   cd cyclegraph
   ```

2. Open in Android Studio via **File → Open**.

3. Let Gradle sync complete.

4. Run on a device or emulator (API 34+).

> **Note:** The app bundles `cycling_graph.db` (~58 MB) as an asset for offline map routing. Cloning will include this file.

## Built With

- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI
- [MapLibre Android](https://maplibre.org/) — Offline maps
- [Room](https://developer.android.com/training/data-storage/room) — Local database
- [Hilt](https://dagger.dev/hilt/) — Dependency injection
- [Garmin FIT SDK](https://developer.garmin.com/fit/) — FIT file parsing
- [Retrofit](https://square.github.io/retrofit/) — Nominatim geocoding
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) — User preferences

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.

## Development

This project was developed with assistance from [Claude Code](https://claude.ai/code) by Anthropic.
