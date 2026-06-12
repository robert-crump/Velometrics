package com.velometrics.app.util

object CyclingConstants {
    // Power thresholds
    const val DEFAULT_FTP = 300                      // Watts — default Functional Threshold Power
    const val SPRINT_THRESHOLD_FACTOR = 1.33         // Sprint detected at FTP × 1.33
    const val INTERVAL_THRESHOLD_FACTOR = 0.95       // Interval detected at FTP × 0.95
    const val MAX_REALISTIC_POWER = 1500             // Watts

    // Interval detection
    const val INTERVAL_MIN_DURATION_SEC = 120
    const val INTERVAL_ALLOWED_REST_SEC = 15
    const val INTERVAL_ROLLING_WINDOW = 15           // seconds

    // Speed thresholds
    const val MAX_REALISTIC_SPEED_KMH = 110.0

    // GPS quality
    const val GPS_LEAP_MAX_DISTANCE_M = 500.0
    const val GPS_LEAP_MAX_TIME_SEC = 5.0
    const val GPS_IMPLIED_MAX_SPEED_KMH = 120.0

    // GPS / location acquisition
    const val GPS_ROUGH_FIX_ACCURACY_M = 200f       // acceptable accuracy for a rough position
    const val LOCATION_UPDATE_MIN_TIME_MS = 1_000L  // min interval between location callbacks
    const val LOCATION_DISPLAY_THROTTLE_MS = 5_000L // blue-dot update throttle

    // Physics / calculation
    const val MTS_PER_SEC_TO_KMH = 3.6             // m/s → km/h conversion factor
    const val KCAL_PER_GRAM_FAT = 9.3              // dietary fat energy density (kcal/g)
    const val KCAL_PER_GRAM_CARB = 4.1             // dietary carbohydrate energy density (kcal/g)
    const val NORMALIZED_POWER_WINDOW = 29          // rolling window size for normalized power (30-sec)
    const val MINIMUM_GPS_DATAPOINTS = 60           // min GPS points required to import a ride
    const val POWER_DATA_COVERAGE_THRESHOLD = 0.10  // fraction of points needing power to count as power ride

    // Home location (Aachen, Germany)
    const val HOME_LAT = 50.78117
    const val HOME_LON = 6.07261

    // Dropbox sync
    const val DEFAULT_DROPBOX_SYNC_FOLDER = "/Apps/WahooFitness"

    // Bounding box (approximate cycling area)
    const val BBOX_SW_LAT = 50.57
    const val BBOX_SW_LON = 5.63
    const val BBOX_NE_LAT = 50.96
    const val BBOX_NE_LON = 6.30

    // Fat burn polynomial coefficients
    // fat_g_per_sec = (a*W² + b*W + c) / 3600, clamped >= 0
    const val FAT_A = -0.0142156
    const val FAT_B = 5.30361
    const val FAT_C = -83.0716

    // Carb burn polynomial coefficients
    // carb_g_per_sec = (a*W⁴ + b*W³ + c*W² + d*W + e) / 3600, clamped >= 0
    const val CARB_A = -0.0000007556
    const val CARB_B = 0.0006540519
    const val CARB_C = -0.1720687649
    const val CARB_D = 17.6082662802
    const val CARB_E = -409.747303849

    // Speed histogram bins (label → [lower, upper) in km/h)
    val SPEED_HISTOGRAM_BINS = listOf(
        "0-5 km/h" to (0.0 to 5.0),
        "5-10 km/h" to (5.0 to 10.0),
        "10-20 km/h" to (10.0 to 20.0),
        "20-25 km/h" to (20.0 to 25.0),
        "25-30 km/h" to (25.0 to 30.0),
        "30-35 km/h" to (30.0 to 35.0),
        "35-40 km/h" to (35.0 to 40.0),
        ">40 km/h" to (40.0 to Double.MAX_VALUE)
    )

    // Power zones (label → [lower, upper) as fraction of FTP)
    val POWER_ZONES = listOf(
        "Zone 1" to (0.0 to 0.55),
        "Zone 2" to (0.55 to 0.70),
        "Zone 3" to (0.70 to 0.92),
        "Zone 4" to (0.92 to 1.05),
        "Zone 5" to (1.05 to 1.25),
        "Zone 6" to (1.25 to Double.MAX_VALUE)
    )

    // Interval overlay
    const val INTERVAL_OVERLAY_LINE_WIDTH = 6f
    const val INTERVAL_GROUPED_LINE_WIDTH = 8f
    const val INTERVAL_HIGHLIGHT_LINE_WIDTH = 8f
    const val INTERVAL_COLOR_MIN_DURATION_SEC = 120   // 2 min = lightest color
    const val INTERVAL_COLOR_MAX_DURATION_SEC = 480   // 8 min = darkest color
    const val INTERVAL_HIGHLIGHT_COLOR = "#00E5FF" // Cyan for highlighted interval

    // Warm color ramp: light yellow → orange → deep red → dark crimson
    // 5 evenly spaced stops from 120s to 480s
    val INTERVAL_DURATION_COLOR_RAMP = listOf(
        "#FFFFB2",  // 120s - light yellow
        "#FECC5C",  // 210s - yellow-orange
        "#FD8D3C",  // 300s - orange
        "#F03B20",  // 390s - red-orange
        "#BD0026"   // 480s - dark crimson
    )

    // Speed overlay
    const val SPEED_OVERLAY_LINE_WIDTH = 5f

    // Map view
    const val DEFAULT_MAP_ZOOM = 12.0
    const val TRACK_LINE_WIDTH = 4f
    const val TRACK_FIT_PADDING = 80 // px padding for camera bounds fitting

    val TRACK_COLORS = listOf(
        "#2196F3", // Blue
        "#4CAF50", // Green
        "#FF9800", // Orange
        "#E91E63", // Pink
        "#9C27B0", // Purple
        "#00BCD4"  // Cyan
    )

    const val CARTO_DARK_STYLE_URL =
        "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"

    // Legacy alias kept for any callers not yet migrated
    val OSM_RASTER_STYLE_JSON = CARTO_DARK_STYLE_URL

    const val ROUTE_START_RADIUS_M = 50.0

    // Map-matching (snapping GPS tracks to road-graph edge sequences)
    const val INTERVAL_EDGE_SNAP_RADIUS_M = 20.0
    const val INTERVAL_MATCH_MAX_REPAIR_DEPTH = 6
    const val INTERVAL_MATCH_MAX_OUTLIER_RUN_POINTS = 1
    // Max bearing difference between GPS heading and edge direction for a snap to be preferred.
    // Among in-radius candidates, the nearest one within this bearing of the GPS heading is
    // chosen; if none qualify, falls back to the nearest candidate by distance so a point is
    // never dropped purely due to heading noise.
    const val INTERVAL_SNAP_BEARING_MAX_DIFF_DEG = 45.0

    // RepeatedInterval clustering / matching (grouping raw intervals into deduped archetypes)
    const val INTERVAL_LENGTH_TOLERANCE_M = 100.0
    const val INTERVAL_POINT_SIMILARITY_THRESHOLD = 0.8
    const val INTERVAL_POINT_MATCH_RADIUS_M = 20.0
    const val INTERVAL_SUBSET_OVERLAP_THRESHOLD = 0.8

    // Navigation / POI
    const val POI_CIRCLE_COLOR = "#37474F"
    const val POI_MARKER_RADIUS = 16f
    const val POI_MARKER_STROKE_WIDTH = 4f
    const val NAV_TRACK_COLOR = "#2979FF"
    const val NAV_TRACK_WIDTH = 5f
    const val NAV_USER_MARKER_COLOR = "#2196F3"
    const val NAV_USER_MARKER_RADIUS = 10f
    const val USER_HEADING_ARROW_ICON_SIZE = 0.9f
    const val FAST_WAY_HOME_TRACK_COLOR = "#FF6F00"
    const val FAST_WAY_HOME_TRACK_WIDTH = 5f
    const val FAST_WAY_HOME_BBOX_MARGIN_M = 2000.0
    const val FAST_WAY_HOME_GPS_ACCURACY_THRESHOLD_M = 20f
    const val FAST_WAY_HOME_GPS_WAIT_TIMEOUT_MS = 5_000L

    // Speed color map for visualization
    val SPEED_COLOR_MAP = mapOf(
        "0 km/h" to "#000000",
        "0-20 km/h" to "#FFEDA0",
        "20-25 km/h" to "#FEB24C",
        "25-30 km/h" to "#FD8D3C",
        "30-40 km/h" to "#F03B20",
        "40-50 km/h" to "#BD0026",
        "50-60 km/h" to "#6BAED6",
        "60-70 km/h" to "#2171B5",
        ">70 km/h" to "#08306B"
    )
}
