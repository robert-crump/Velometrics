package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.util.GeoUtils
import kotlin.math.cos
import kotlin.math.sin

data class OrienteerConfig(
    val candidateCount: Int = 1,
    // Retained as the base value for DegradationPolicy's relaxation tiers; the
    // quadrant skeleton itself does not apply a reuse penalty.
    val reusePenaltyWeight: Double = 2.0,
    val farWeight: Double = 1.0,
    val headingWeight: Double = 0.5,
    val rewardWeight: Double = 0.1,
)

data class CandidateLoop(
    val corridors: List<Long>,
    val totalDistanceM: Double,
    val totalReward: Double,
    val flowScore: Double,
    val discoveryScore: Double,
)

/**
 * Deterministic quadrant-skeleton constructor.
 *
 * Reachable corridors (within `reach = target / 3` of home) are classified into four
 * quadrants of a ride-aligned frame (`u = unit(rideBearing)`, `v = perp(u)`). A single
 * geometry-first anchor is picked per quadrant and emitted in order Q1->Q2->Q3->Q4. The
 * ordered corridor-ID list is consumed by [RouteRefiner] to produce a real refined loop.
 *
 * This replaces the previous GRASP construction + local-search engine outright.
 */
object CorridorOrienteer {

    private const val TAG = "CorridorOrienteer"

    /** Outer reach radius as a fraction of the target distance (reach = target / 3). */
    internal const val FAR_POINT_BUDGET_FRACTION = 1.0 / 3.0

    /** Rough road-vs-straight-line factor for the informational coarse distance estimate. */
    private const val ROAD_DISTANCE_FACTOR = 1.3

    private const val QUADRANT_COUNT = 4

    suspend fun search(
        corridors: List<Corridor>,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        weights: RewardWeights = RewardWeights(),
        config: OrienteerConfig = OrienteerConfig(),
        direction: RideDirection? = null,
        startCorridorId: Long? = null,
    ): List<CandidateLoop> {
        if (corridors.isEmpty()) return emptyList()

        val reach = targetDistanceM * FAR_POINT_BUDGET_FRACTION
        val reachable = filterByRadius(corridors, homeLat, homeLon, reach)
        if (reachable.isEmpty()) return emptyList()

        val homeCorridor = if (startCorridorId != null) {
            corridors.firstOrNull { it.id == startCorridorId }
                ?: findNearestCorridor(reachable, homeLat, homeLon)
        } else {
            findNearestCorridor(reachable, homeLat, homeLon)
        } ?: return emptyList()

        val rideBearing = direction?.bearingCenter
            ?: defaultRideBearing(reachable, homeCorridor.id, homeLat, homeLon)
        Log.d(
            TAG,
            "search: ${corridors.size} corridors -> ${reachable.size} within reach(${reach.toInt()}m), " +
                "rideBearing=${rideBearing.toInt()} home=${homeCorridor.id} startCorridor=$startCorridorId",
        )

        val rewardCache = reachable.associate { it.id to RewardComposer.composeCorridorReward(it, weights) }
        val rewardTotals = rewardCache.mapValues { it.value.total }
        val minReward = rewardTotals.values.minOrNull() ?: 0.0
        val maxReward = rewardTotals.values.maxOrNull() ?: 0.0

        // --- Classify reachable corridors into the four ride-aligned quadrants ---
        val quadrants = Array(QUADRANT_COUNT) { mutableListOf<Corridor>() }
        for (c in reachable) {
            if (c.id == homeCorridor.id) continue
            val (east, north) = toLocalMeters(homeLat, homeLon, c.centroidLat, c.centroidLon)
            val (along, lateral) = alongLateral(east, north, rideBearing)
            quadrants[quadrantOf(along, lateral)].add(c)
        }

        // --- One geometry-first anchor per quadrant ---
        val anchors = arrayOfNulls<Corridor>(QUADRANT_COUNT)
        for (q in 0 until QUADRANT_COUNT) {
            anchors[q] = quadrants[q].maxByOrNull { c ->
                val (east, north) = toLocalMeters(homeLat, homeLon, c.centroidLat, c.centroidLon)
                val (along, _) = alongLateral(east, north, rideBearing)
                val corridorBearing = GeoUtils.computeBearing(
                    homeLat, homeLon, c.centroidLat, c.centroidLon,
                )
                val rewardNorm = normalizeReward(rewardTotals[c.id] ?: 0.0, minReward, maxReward)
                anchorScore(along, reach, corridorBearing, rideBearing, q, rewardNorm, config)
            }
        }

        // --- Emit the ordered corridor-ID skeleton ---
        val corridorById = HashMap<Long, Corridor>()
        reachable.forEach { corridorById[it.id] = it }
        corridorById[homeCorridor.id] = homeCorridor

        // The exit-plan start corridor is honored as the Q1 anchor (emitted first); the exit leg
        // already reaches it, so the home corridor is not prepended. Otherwise the home corridor
        // opens and closes the loop. Natural quadrant anchors follow in order Q1->Q2->Q3->Q4.
        val startCorridor = startCorridorId?.let { id -> corridors.firstOrNull { it.id == id } }
        val ordered = mutableListOf<Long>()
        if (startCorridor != null) {
            corridorById[startCorridor.id] = startCorridor
            ordered.add(startCorridor.id)
        } else {
            ordered.add(homeCorridor.id)
        }
        for (q in 0 until QUADRANT_COUNT) {
            val anchor = anchors[q] ?: continue
            if (anchor.id == startCorridor?.id || anchor.id == homeCorridor.id) continue
            corridorById[anchor.id] = anchor
            if (ordered.lastOrNull() != anchor.id) ordered.add(anchor.id)
        }

        val distinct = ordered.distinct()
        if (distinct.size < 2) {
            Log.d(TAG, "search: skeleton has ${distinct.size} distinct corridors, no loop possible")
            return emptyList()
        }
        Log.d(TAG, "search: skeleton corridors=$ordered")

        val candidate = buildCandidate(ordered, corridorById, rewardCache)
        return listOf(candidate)
    }

    private fun buildCandidate(
        ordered: List<Long>,
        corridorById: Map<Long, Corridor>,
        rewardCache: Map<Long, ComposedReward>,
    ): CandidateLoop {
        val distinctIds = ordered.distinct()
        var totalReward = 0.0
        var flowScore = 0.0
        var discoveryScore = 0.0
        for (id in distinctIds) {
            val reward = rewardCache[id] ?: continue
            totalReward += reward.total
            flowScore += reward.flow
            discoveryScore += reward.explore
        }

        var straightLine = 0.0
        for (i in 0 until ordered.size - 1) {
            val a = corridorById[ordered[i]] ?: continue
            val b = corridorById[ordered[i + 1]] ?: continue
            straightLine += GeoUtils.haversineDistance(a.centroidLat, a.centroidLon, b.centroidLat, b.centroidLon)
        }
        // Closing leg back to the first corridor.
        val first = corridorById[ordered.first()]
        val last = corridorById[ordered.last()]
        if (first != null && last != null) {
            straightLine += GeoUtils.haversineDistance(last.centroidLat, last.centroidLon, first.centroidLat, first.centroidLon)
        }
        val corridorLength = distinctIds.sumOf { corridorById[it]?.lengthM ?: 0.0 }

        return CandidateLoop(
            corridors = ordered,
            totalDistanceM = straightLine * ROAD_DISTANCE_FACTOR + corridorLength,
            totalReward = totalReward,
            flowScore = flowScore,
            discoveryScore = discoveryScore,
        )
    }

    // --- Ride-aligned frame helpers ---

    /** Local east/north offset (metres) of a point from home using an equirectangular approximation. */
    internal fun toLocalMeters(
        homeLat: Double,
        homeLon: Double,
        lat: Double,
        lon: Double,
    ): Pair<Double, Double> {
        val metersPerDegLat = 111_320.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(homeLat))
        val east = (lon - homeLon) * metersPerDegLon
        val north = (lat - homeLat) * metersPerDegLat
        return east to north
    }

    /**
     * Projects a local east/north offset onto the ride-aligned frame.
     * `along` is the component in the ride direction (`u`); `lateral` is the component to the
     * right of travel (`v = perp(u)`).
     */
    internal fun alongLateral(east: Double, north: Double, rideBearingDeg: Double): Pair<Double, Double> {
        val r = Math.toRadians(rideBearingDeg)
        val ux = sin(r)
        val uy = cos(r)
        val vx = cos(r)
        val vy = -sin(r)
        val along = east * ux + north * uy
        val lateral = east * vx + north * vy
        return along to lateral
    }

    /**
     * Quadrant index in the ride-aligned frame, ordered for a single winding:
     * Q1 front-right (0), Q2 front-left (1), Q3 back-left (2), Q4 back-right (3).
     */
    internal fun quadrantOf(along: Double, lateral: Double): Int = when {
        along >= 0 && lateral >= 0 -> 0
        along >= 0 && lateral < 0 -> 1
        along < 0 && lateral < 0 -> 2
        else -> 3
    }

    /**
     * Geometry-first anchor score for a corridor within its quadrant.
     * `anchorScore = wFar*(along/reach) + wHead*headingAlignment + wReward*rewardNorm`,
     * where reward is only a tiebreak.
     */
    internal fun anchorScore(
        along: Double,
        reach: Double,
        corridorBearing: Double,
        rideBearingDeg: Double,
        quadrant: Int,
        rewardNorm: Double,
        config: OrienteerConfig,
    ): Double {
        val farTerm = config.farWeight * (along / reach)
        val arcTangent = arcTangentBearing(rideBearingDeg, quadrant)
        val headTerm = config.headingWeight * cosineSimilarity(corridorBearing, arcTangent)
        val rewardTerm = config.rewardWeight * rewardNorm
        return farTerm + headTerm + rewardTerm
    }

    /** Bisector bearing of a quadrant in the ride-aligned frame (right of travel = +90). */
    internal fun arcTangentBearing(rideBearingDeg: Double, quadrant: Int): Double {
        val offset = when (quadrant) {
            0 -> 45.0
            1 -> -45.0
            2 -> -135.0
            else -> 135.0
        }
        return (rideBearingDeg + offset + 360.0) % 360.0
    }

    internal fun cosineSimilarity(bearing1: Double, bearing2: Double): Double =
        cos(Math.toRadians(bearing1 - bearing2))

    private fun normalizeReward(reward: Double, minReward: Double, maxReward: Double): Double {
        if (maxReward <= minReward) return 0.0
        return (reward - minReward) / (maxReward - minReward)
    }

    private fun defaultRideBearing(
        reachable: List<Corridor>,
        homeCorridorId: Long,
        homeLat: Double,
        homeLon: Double,
    ): Double {
        val farthest = reachable
            .filter { it.id != homeCorridorId }
            .maxByOrNull { GeoUtils.haversineDistance(homeLat, homeLon, it.centroidLat, it.centroidLon) }
            ?: return 0.0
        return GeoUtils.computeBearing(homeLat, homeLon, farthest.centroidLat, farthest.centroidLon)
    }

    internal fun findNearestCorridor(
        corridors: List<Corridor>,
        lat: Double,
        lon: Double,
    ): Corridor? = corridors.minByOrNull {
        GeoUtils.haversineDistance(lat, lon, it.centroidLat, it.centroidLon)
    }

    internal fun filterByRadius(
        corridors: List<Corridor>,
        homeLat: Double,
        homeLon: Double,
        maxRadiusM: Double,
    ): List<Corridor> = corridors.filter { c ->
        GeoUtils.haversineDistance(homeLat, homeLon, c.centroidLat, c.centroidLon) <= maxRadiusM
    }
}
