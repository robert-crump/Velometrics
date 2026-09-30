package com.velometrics.app.demo

/**
 * An effort pinned to a fixed stretch of a loop ([startKm]..[endKm]), so every ride of that loop
 * puts it in the same place: detected as an interval each time *and* matched as a repeated
 * interval across rides. [minFtp]..[maxFtp] is the target band as a fraction of FTP; where in the
 * band a ride lands is drawn per climb, or builds over the training block for intervals and
 * threshold work (see [DemoRideModel.plan]).
 */
data class PinnedEffort(val startKm: Double, val endKm: Double, val minFtp: Double, val maxFtp: Double)

enum class DemoWorkout { INTERVALS, TEMPO, CLIMBS, ENDURANCE }

/**
 * The four public komoot loops the README demo rides are built from, reduced to lat/lon/ele under
 * `sharedTest/assets/demo/`. Effort positions come from each loop's elevation profile: Aachen's
 * intervals sit on its gentle rises and flat middle, Herzogenrath's threshold block on the flat
 * Wurm valley stretch, Mechelen's efforts on its named climbs (Loorberg, Veursbos, ...).
 */
enum class DemoLoop(
    val id: String,
    val asset: String,
    val workout: DemoWorkout,
    /** Endurance power outside efforts, as a fraction of FTP. */
    val baseFtp: Double,
    val efforts: List<PinnedEffort>
) {
    AACHEN(
        "aachen", "demo/aachen.gpx", DemoWorkout.INTERVALS, 0.62,
        listOf(
            PinnedEffort(1.2, 3.6, 1.03, 1.08),
            PinnedEffort(4.6, 7.0, 1.03, 1.08),
            PinnedEffort(14.6, 17.2, 1.03, 1.08),
            PinnedEffort(19.2, 21.8, 1.03, 1.08)
        )
    ),
    HERZOGENRATH(
        "herzogenrath", "demo/herzogenrath.gpx", DemoWorkout.TEMPO, 0.68,
        listOf(PinnedEffort(8.0, 22.5, 1.0, 1.08))
    ),
    MECHELEN(
        "mechelen", "demo/mechelen.gpx", DemoWorkout.CLIMBS, 0.66,
        listOf(
            PinnedEffort(2.4, 3.6, 1.03, 1.1),
            PinnedEffort(10.4, 11.6, 1.03, 1.1),
            PinnedEffort(17.9, 19.1, 1.03, 1.1),
            PinnedEffort(23.4, 25.0, 1.03, 1.1),
            PinnedEffort(33.4, 35.0, 1.03, 1.1),
            PinnedEffort(39.8, 41.1, 1.03, 1.1),
            PinnedEffort(44.0, 45.5, 1.03, 1.1)
        )
    ),
    SUED_LIMBURG(
        "sued_limburg", "demo/sued_limburg.gpx", DemoWorkout.ENDURANCE, 0.82,
        emptyList()
    );
}
