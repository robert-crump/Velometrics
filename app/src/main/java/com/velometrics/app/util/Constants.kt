package com.velometrics.app.util

object CyclingConstants {
    // Power thresholds
    const val DEFAULT_FTP = 300                      // Watts — default Functional Threshold Power
    const val SPRINT_THRESHOLD_FACTOR = 1.33         // Sprint detected at FTP × 1.33
    const val INTERVAL_THRESHOLD_FACTOR = 0.95       // Interval detected at FTP × 0.95
    const val MAX_REALISTIC_POWER = 1500             // Watts

    // Shared with RideClassifier's Recovery upper power bound (RECOVERY_MAX_POWER_FTP_FRACTION):
    // also the threshold behind CyclingSession.timeBelowSixtyPercentFtpSec, the metric the
    // Recovery tag-comparison narrative leads with.
    const val RECOVERY_TIME_BELOW_FTP_FRACTION = 0.60

    // Interval detection
    const val INTERVAL_MIN_DURATION_SEC = 120
    const val INTERVAL_ALLOWED_REST_SEC = 15
    const val INTERVAL_ROLLING_WINDOW = 15           // seconds

    // Import-time HR recovery metrics (#178): fixed clinical HRR windows, read forward from each
    // interval's endIdx regardless of when the next interval starts (see IntervalDetector).
    const val INTERVAL_HRR60_WINDOW_SEC = 60L
    const val INTERVAL_HRR30_WINDOW_SEC = 30L

    // Speed thresholds
    const val MAX_REALISTIC_SPEED_KMH = 110.0

    // GPS quality
    const val GPS_LEAP_MAX_DISTANCE_M = 500.0
    const val GPS_LEAP_MAX_TIME_SEC = 5.0
    const val GPS_IMPLIED_MAX_SPEED_KMH = 120.0
    const val GPS_MAX_ACCURACY_M = 15.0              // FIT gps_accuracy above this is discarded (#233)
    const val GPS_MAX_ACCELERATION_MPS2 = 5.0        // |a| from GPS-implied speed above this is discarded
    const val GPS_ACCELERATION_MAX_GAP_SEC = 10.0    // no acceleration check across longer gaps

    // GPS / location acquisition
    const val GPS_ROUGH_FIX_ACCURACY_M = 200f       // acceptable accuracy for a rough position
    const val LOCATION_UPDATE_MIN_TIME_MS = 1_000L  // min interval between location callbacks
    const val LOCATION_DISPLAY_THROTTLE_MS = 5_000L // blue-dot update throttle
    const val LOCATION_CACHE_MAX_AGE_MS = 120_000L  // reject cached last-known fixes older than this

    // Physics / calculation
    const val MTS_PER_SEC_TO_KMH = 3.6             // m/s → km/h conversion factor
    const val KCAL_PER_GRAM_FAT = 9.3              // dietary fat energy density (kcal/g)
    const val KCAL_PER_GRAM_CARB = 4.1             // dietary carbohydrate energy density (kcal/g)
    const val NORMALIZED_POWER_WINDOW = 29          // rolling window size for normalized power (30-sec)
    const val MINIMUM_GPS_DATAPOINTS = 60           // min GPS points required to import a ride
    const val POWER_DATA_COVERAGE_THRESHOLD = 0.10  // fraction of points needing power to count as power ride
    const val ELEVATION_GAIN_THRESHOLD_M = 3.0       // hysteresis threshold for cumulative elevation gain

    // Cardiac drift (#154): trailing-bucket efficiency-factor (avg power / avg HR) decoupling
    const val CARDIAC_DRIFT_BUCKET_SEC = 600           // ~10-minute buckets
    const val CARDIAC_DRIFT_MIN_RIDE_SEC = 3600         // gate: ride must be >= 60 min (6 buckets)
    const val CARDIAC_DRIFT_POWER_EXCLUSION_FTP_FRACTION = 0.25  // exclude samples below 25% FTP from bucket averages
    const val CARDIAC_DRIFT_BUCKET_DROP_FRACTION = 0.5  // drop a bucket if more than half its samples are excluded
    // Cardiac drift advice (#222): cause rules for MEDIUM/HIGH drift
    const val CARDIAC_DRIFT_HEAT_MIN_TEMP_C = 25.0          // avg ride temperature that counts as hot
    const val CARDIAC_DRIFT_LATE_START_HOUR = 11            // hot ride starting after this local hour -> "start riding earlier"
    const val CARDIAC_DRIFT_INTENSITY_MIN_IF = 0.75         // NP / FTP above endurance pace
    const val CARDIAC_DRIFT_DURATION_FACTOR = 1.3           // net duration vs. the usual long-ride median
    const val CARDIAC_DRIFT_BASELINE_WINDOW_DAYS = 42L      // usual long ride = rides >= 60 min in the previous 6 weeks
    const val CARDIAC_DRIFT_BASELINE_MIN_RIDES = 3
    const val CARDIAC_DRIFT_FUELING_MIN_SEC = 7200          // rides >= 2 h always get the eating advice
    const val CARDIAC_DRIFT_MAX_CAUSES = 2

    // Session Detail recap prose (#224): "comparable" bands around the pool median; a value on the boundary is comparable
    const val RECAP_BAND_DURATION_DISTANCE = 0.20       // relative; also interval time, time below 60% FTP, fat burned
    const val RECAP_BAND_SPEED = 0.05                   // relative
    const val RECAP_BAND_POWER_HR = 0.05                // relative; avg power, interval power, heart rate
    const val RECAP_BAND_CARDIAC_DRIFT_PP = 1.0         // absolute, percentage points
    const val RECAP_BAND_FAT_EFFICIENCY = 5.0           // absolute, score points
    const val RECAP_BAND_INTERVAL_COUNT = 2.0           // absolute, intervals
    const val RECAP_MAX_METRICS = 3

    // Home location (Aachen, Germany)
    const val HOME_LAT = 50.78117
    const val HOME_LON = 6.07261

    // Dropbox sync
    const val DEFAULT_DROPBOX_SYNC_FOLDER = "/Apps/WahooFitness"

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
        "0-10 km/h" to (0.0 to 10.0),
        "10-20 km/h" to (10.0 to 20.0),
        "20-30 km/h" to (20.0 to 30.0),
        "30-40 km/h" to (30.0 to 40.0),
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

    // Heart rate zones (label → [lower, upper) as fraction of max HR)
    // Boundaries follow Strava's zone formula (#173), expressed as a percentage of max HR:
    // bpm thresholds 109/144/161/179 scaled against Strava's reference max HR of 185.
    const val DEFAULT_MAX_HR = 190
    val HR_ZONES = listOf(
        "Zone 1" to (0.0 to 109.0 / 185.0),
        "Zone 2" to (109.0 / 185.0 to 144.0 / 185.0),
        "Zone 3" to (144.0 / 185.0 to 161.0 / 185.0),
        "Zone 4" to (161.0 / 185.0 to 179.0 / 185.0),
        "Zone 5" to (179.0 / 185.0 to Double.MAX_VALUE)
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

    // Flow segments overlay
    // Minimum combined pedal-flow + gravity-flow run count for an edge to qualify as a
    // "flow segment" (a stretch ridden with sustained controlled pedaling or coasting).
    const val FLOW_SEGMENT_MIN_COUNT = 3
    const val FLOW_SEGMENT_COLOR = "#4CAF50"
    const val FLOW_SEGMENT_LINE_WIDTH = 3f

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

    // Map-matching (snapping GPS tracks to road-graph edge sequences)
    const val INTERVAL_EDGE_SNAP_RADIUS_M = 20.0
    const val INTERVAL_MATCH_MAX_REPAIR_DEPTH = 6
    // Minimum consecutive GPS points snapped to the same edge for that edge to be kept as an
    // "anchor" (a high-confidence edge actually ridden). Runs below this are dropped; repairGaps
    // re-inserts them as zero-count bridges if connectivity requires it.
    const val INTERVAL_MATCH_MIN_ANCHOR_POINTS = 2
    // Max bearing difference between GPS heading and edge direction for a snap to be preferred.
    // Among in-radius candidates, the nearest one within this bearing of the GPS heading is
    // chosen; if none qualify, falls back to the nearest candidate by distance so a point is
    // never dropped purely due to heading noise.
    const val INTERVAL_SNAP_BEARING_MAX_DIFF_DEG = 45.0

    // RepeatedRoute clustering (grouping raw sessions into deduped route archetypes): also the
    // floor a resolved route's *surviving* session count must clear at read time (#192) — a
    // stale/missing member (e.g. one deleted since the last recluster) is filtered out before this
    // check, so a route that drops below it that way is hidden exactly like one that never reached
    // it during clustering.
    const val ROUTE_CLUSTER_MIN_GROUP_SIZE = 3

    // RepeatedInterval clustering / matching (grouping raw intervals into deduped archetypes)
    const val INTERVAL_LENGTH_TOLERANCE_M = 100.0
    const val INTERVAL_POINT_SIMILARITY_THRESHOLD = 0.8
    const val INTERVAL_POINT_MATCH_RADIUS_M = 20.0
    const val INTERVAL_SUBSET_OVERLAP_THRESHOLD = 0.8

    // Archetype-merge length gate (Step 2 of IntervalClusteringService): looser than
    // INTERVAL_LENGTH_TOLERANCE_M above (which governs raw-interval clustering/matching) because
    // it's comparing two already-map-matched archetypes, not noisy raw GPS. Scales with route
    // length so short routes aren't merged too liberally: max(floor, pct * the longer archetype's
    // distance).
    const val INTERVAL_MERGE_LENGTH_TOLERANCE_FLOOR_M = 500.0
    const val INTERVAL_MERGE_LENGTH_TOLERANCE_PCT = 0.20

    // Navigation / POI
    const val POI_CIRCLE_COLOR = "#37474F"
    const val POI_MARKER_RADIUS = 16f
    const val POI_MARKER_STROKE_WIDTH = 4f
    const val NAV_TRACK_COLOR = "#2979FF"
    const val NAV_TRACK_WIDTH = 5f
    const val NAV_USER_MARKER_COLOR = "#2196F3"
    const val NAV_USER_MARKER_RADIUS = 10f
    const val USER_HEADING_ARROW_ICON_SIZE = 0.9f

    // Training load (#190): CTL/ATL/TSB fitness-fatigue trend
    // Standard TrainingPeaks/Strava exponential-moving-average time constants.
    const val CTL_TIME_CONSTANT_DAYS = 42
    const val ATL_TIME_CONSTANT_DAYS = 7
    const val TRAINING_LOAD_CHART_WINDOW_DAYS = 182  // ~6 months, default visible chart window

    // HR-based load score for rides with no power data (TRIMP-zonal style): each HR zone's
    // time is weighted by increasing multipliers, then scaled by HR_LOAD_CALIBRATION_CONSTANT
    // so scores land in roughly the same numeric range as power-based TSS. This is a deliberate
    // approximation, not a physiological formula — tune the calibration constant here if
    // HR-based load reads systematically high/low vs. power-based TSS on the same rider's
    // mixed history.
    // Derivation: target a steady 60-min Zone 3 ride landing near TSS 65 (mid of the 60-70
    // range a similar power ride would score). weightedMinutes = 3.0 * 60 = 180 →
    // k = 65 / 180 ≈ 0.36.
    val HR_LOAD_ZONE_MULTIPLIERS = mapOf(
        "Zone 1" to 1.0,
        "Zone 2" to 2.0,
        "Zone 3" to 3.0,
        "Zone 4" to 4.0,
        "Zone 5" to 5.0
    )
    const val HR_LOAD_CALIBRATION_CONSTANT = 0.36

    // Speed IQ braking events (#226). P_brake per second = -m·v·a - m·g·dh/dt + P_pedal - drag - rolling.
    const val SPEED_IQ_DEFAULT_SYSTEM_MASS_KG = 85.0   // rider + bike + kit while the setting is unset (#228)
    const val DEFAULT_BIKE_KIT_WEIGHT_KG = 10            // bike + kit until set; also the #237 migration split
    val RIDER_WEIGHT_RANGE_KG = 30..180                  // plausible body weight for the setting
    val BIKE_KIT_WEIGHT_RANGE_KG = 3..40                 // plausible bike + kit for the setting
    const val DEFAULT_RIDER_WEIGHT_KG = 75               // onboarding pre-fill (#238): 85 kg system default − bike/kit
    val FTP_RANGE_W = 50..700                            // plausible FTP for the onboarding field
    val MAX_HR_RANGE_BPM = 100..230                      // plausible max HR for the onboarding field
    // Calibrated against three real rides (#231, 96 kg, FTP 300): every rider-confirmed braking spot
    // (a full stop after a 56 km/h descent, red lights, a T-junction) is a top-5 event, so the
    // 150 W / 2 s / 5 s join / 1.5 kJ / 2 km/h / 3 min thresholds and smoothing windows stay as they
    // are. Only CdA changed: steady (< 0.05 m/s²) stretches above 20 km/h fitted 0.25-0.32, and at
    // 0.37 a steady 56 km/h descent showed 200-380 W of negative braking, cutting the descent stop's
    // onset short (49 instead of 54 km/h, 38 s instead of 44 s). 0.30 is the top of the fitted
    // range, so coasting fast without braking still doesn't read as braking.
    const val SPEED_IQ_CDA_M2 = 0.30
    const val SPEED_IQ_CRR = 0.013
    const val SPEED_IQ_AIR_DENSITY = 1.225              // kg/m³
    const val SPEED_IQ_GRAVITY = 9.81
    const val SPEED_IQ_SPEED_SMOOTH_HALF_SEC = 1         // centred 3 s window
    const val SPEED_IQ_ALTITUDE_SMOOTH_HALF_SEC = 5      // centred ~10 s window
    const val SPEED_IQ_MAX_SAMPLE_GAP_SEC = 5            // a longer gap between records (timer paused) splits the stream
    const val SPEED_IQ_BRAKE_POWER_W = 150.0             // a second counts as braking above this
    const val SPEED_IQ_MIN_BRAKE_SEC = 2                 // ...for at least this many seconds in a row
    const val SPEED_IQ_JOIN_GAP_SEC = 5                  // braking stretches closer than this are one event
    const val SPEED_IQ_MIN_EVENT_ENERGY_J = 1500.0       // events below this aren't reported
    const val SPEED_IQ_PEDAL_VETO_FTP_FRACTION = 0.3     // a step above this share of ride-date FTP (3 s avg) isn't braking (#232)
    const val SPEED_IQ_MIN_SPEED_DROP_KMH = 5.0          // an event's peak minus low speed must be at least this (#232)
    const val SPEED_IQ_MOVING_KMH = 2.0                  // pedalling samples for P need at least this speed
    const val SPEED_IQ_TOP_EVENTS = 5
    const val SPEED_IQ_STANDING_KMH = 2.0                // below this (or timer paused) counts as standing
    const val SPEED_IQ_COFFEE_STOP_SEC = 180             // a single stop longer than this isn't counted
    const val SPEED_IQ_MIN_STANDING_ONLY_SEC = 5         // a stop with no braking counts from this long
    const val SPEED_IQ_FALLBACK_RIDES = 10               // a ride without power takes P from this many earlier power rides (#229)
    const val SPEED_IQ_FALLBACK_FTP_FRACTION = 0.6       // ...else from this fraction of FTP as of the ride
    const val SPEED_IQ_MARKER_COLOR = "#D32F2F"           // numbered event markers on the Ride Detail map (#230)
    const val SPEED_IQ_MARKER_RADIUS = 11f
    const val SPEED_IQ_FOCUS_ZOOM = 15.0                  // tapping an event row zooms the map to this

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
