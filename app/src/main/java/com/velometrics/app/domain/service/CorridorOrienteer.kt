package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.util.GeoUtils
import kotlin.math.cos
import kotlin.math.sin

data class OrienteerConfig(
    val candidateCount: Int = 1,
    val farWeight: Double = 1.0,
    val headingWeight: Double = 0.5,
    val rewardWeight: Double = 0.1,
    // Skeleton relaxation levers. DegradationPolicy retargets these per tier so a failed
    // retry actually changes the geometry the builder can assemble: a wider reach, a smaller
    // any-node separation, and a wider heading cone each admit more anchors and fills.
    val reachFraction: Double = CorridorOrienteer.FAR_POINT_BUDGET_FRACTION,
    val separationM: Double = CorridorOrienteer.SEPARATION_M,
    val headingConeCosine: Double = 0.0,
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

    /**
     * Fill stops once the estimated perimeter reaches this fraction of target. Anchors land the
     * skeleton at ~0.7-0.9x target; fill raises it toward target while harvesting reward.
     */
    internal const val FILL_TARGET_FRACTION = 0.9

    private const val QUADRANT_COUNT = 4

    /** Full minimum air separation between any node of two chosen corridors. */
    internal const val SEPARATION_M = 2000.0

    /**
     * Fraction of `reach` used as the adaptive separation cap, so short-target routes still
     * populate all four quadrants: `sep = min(SEPARATION_M, reach * SEPARATION_REACH_FRACTION)`.
     */
    internal const val SEPARATION_REACH_FRACTION = 0.5

    /**
     * Resolves node IDs to (lat, lon). Defaults to an empty resolver so the pure scoring tests
     * need not supply coordinates; [RouteGenerator] wires this to the repository. When a pair of
     * separation-candidate corridors cannot be resolved, the pair is treated as separated.
     */
    suspend fun search(
        corridors: List<Corridor>,
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        weights: RewardWeights = RewardWeights(),
        config: OrienteerConfig = OrienteerConfig(),
        direction: RideDirection? = null,
        startCorridorId: Long? = null,
        nodeResolver: suspend (Set<Long>) -> Map<Long, Pair<Double, Double>> = { emptyMap() },
    ): List<CandidateLoop> {
        if (corridors.isEmpty()) return emptyList()

        val reach = targetDistanceM * config.reachFraction
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

        // The exit-plan start corridor (when present) is the loop origin; otherwise the home
        // corridor is. Anchors are kept clear of the origin and of each other by the separation
        // rule below.
        val startCorridor = startCorridorId?.let { id -> corridors.firstOrNull { it.id == id } }
        val origin = startCorridor ?: homeCorridor

        // --- Classify reachable corridors into the four ride-aligned quadrants ---
        val quadrants = Array(QUADRANT_COUNT) { mutableListOf<Corridor>() }
        for (c in reachable) {
            if (c.id == homeCorridor.id || c.id == origin.id) continue
            val (east, north) = toLocalMeters(homeLat, homeLon, c.centroidLat, c.centroidLon)
            val (along, lateral) = alongLateral(east, north, rideBearing)
            quadrants[quadrantOf(along, lateral)].add(c)
        }

        // Batch-resolve node coordinates once for the candidate corridors (every reachable
        // quadrant candidate). The cheap centroid+half-length prefilter in [corridorsSeparated]
        // short-circuits far pairs without consulting this map.
        val candidateCorridors = quadrants.flatMap { it }
        val neededNodeIds = buildSet { candidateCorridors.forEach { addAll(corridorNodeIds(it)) } }
        val nodeCoords = nodeResolver(neededNodeIds)
        val sep = separationDistance(reach, config.separationM)

        // --- One geometry-first anchor per quadrant, respecting the separation rule ---
        // Selection is sequential so each pick is checked against the anchors chosen for earlier
        // quadrants. Within a quadrant, the highest geometry-first score that stays separated
        // wins. The home/start origin is the fixed loop endpoint and is not part of the spread
        // set — anchors are only kept apart from one another.
        val anchors = arrayOfNulls<Corridor>(QUADRANT_COUNT)
        val chosen = mutableListOf<Corridor>()
        for (q in 0 until QUADRANT_COUNT) {
            val ranked = quadrants[q].sortedByDescending { c ->
                val (east, north) = toLocalMeters(homeLat, homeLon, c.centroidLat, c.centroidLon)
                val (along, _) = alongLateral(east, north, rideBearing)
                val corridorBearing = GeoUtils.computeBearing(
                    homeLat, homeLon, c.centroidLat, c.centroidLon,
                )
                val rewardNorm = normalizeReward(rewardTotals[c.id] ?: 0.0, minReward, maxReward)
                anchorScore(along, reach, corridorBearing, rideBearing, q, rewardNorm, config)
            }
            val pick = ranked.firstOrNull { cand ->
                chosen.all { corridorsSeparated(cand, it, sep, nodeCoords) }
            }
            if (pick != null) {
                // Twin orientation: among a group_id pair (same physical road, opposite directions)
                // this swaps to the twin whose entry->exit heading matches the quadrant's arc
                // tangent, so RouteRefiner traverses the road in the geometrically-correct
                // direction. Solo corridors are returned unchanged; the twin shares geometry with
                // the pick, so it satisfies the same separation check.
                val anchor = headingAlignedTwin(pick, arcTangentBearing(rideBearing, q), quadrants[q], nodeCoords)
                anchors[q] = anchor
                chosen.add(anchor)
            }
        }

        // --- Build the ordered loop: origin first, then quadrant anchors Q1->Q4 ---
        // The exit-plan start corridor is honored as the Q1 anchor (emitted first); the exit leg
        // already reaches it, so the home corridor is not prepended. Otherwise the home corridor
        // opens and closes the loop. Natural quadrant anchors follow in order Q1->Q2->Q3->Q4.
        val loop = mutableListOf<Corridor>()
        loop.add(startCorridor ?: homeCorridor)
        for (q in 0 until QUADRANT_COUNT) {
            val anchor = anchors[q] ?: continue
            if (anchor.id == startCorridor?.id || anchor.id == homeCorridor.id) continue
            if (loop.lastOrNull()?.id != anchor.id) loop.add(anchor)
        }

        // --- Fill toward target length ---
        // Anchors usually reach only ~0.7-0.9x target. Iteratively insert the highest-reward valid
        // candidate into the largest connector gap until the estimated perimeter reaches
        // target * FILL_TARGET_FRACTION or no valid fill remains. Each corridor is used once.
        val usedIds = loop.mapTo(mutableSetOf()) { it.id }
        val fillPool = candidateCorridors.filter { it.id !in usedIds }.toMutableList()
        fillToTarget(loop, fillPool, chosen, targetDistanceM, sep, nodeCoords, rewardTotals, config.headingConeCosine)

        // --- Emit the ordered corridor-ID skeleton ---
        val ordered = loop.map { it.id }
        val corridorById = HashMap<Long, Corridor>()
        reachable.forEach { corridorById[it.id] = it }
        corridorById[homeCorridor.id] = homeCorridor
        startCorridor?.let { corridorById[it.id] = it }
        loop.forEach { corridorById[it.id] = it }

        val distinct = ordered.distinct()
        if (distinct.size < 2) {
            Log.d(TAG, "search: skeleton has ${distinct.size} distinct corridors, no loop possible")
            return emptyList()
        }
        Log.d(TAG, "search: skeleton corridors=$ordered (${fillPool.size} fill candidates unused)")

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

    // --- Fill helpers ---

    /**
     * Inserts fill corridors into [loop] until the estimated perimeter reaches
     * `target * FILL_TARGET_FRACTION` or no valid fill remains. Each iteration locates the largest
     * connector gap and inserts the highest-reward candidate that lies between the gap's two
     * neighbours, stays separated from every chosen corridor, and keeps a consistent heading.
     * Inserted fills are appended to [chosen] so later picks honour the separation rule against
     * them; each corridor leaves [fillPool] when used, so the loop is finite.
     */
    private fun fillToTarget(
        loop: MutableList<Corridor>,
        fillPool: MutableList<Corridor>,
        chosen: MutableList<Corridor>,
        targetDistanceM: Double,
        sep: Double,
        nodeCoords: Map<Long, Pair<Double, Double>>,
        rewardTotals: Map<Long, Double>,
        headingConeCosine: Double,
    ) {
        if (loop.size < 2) return
        val ceiling = targetDistanceM * FILL_TARGET_FRACTION
        while (fillPool.isNotEmpty() && estimatedPerimeter(loop, nodeCoords) < ceiling) {
            val gapIndex = indexOfLargestGap(loop, nodeCoords)
            val before = loop[gapIndex]
            val after = loop[(gapIndex + 1) % loop.size]
            val best = fillPool
                .filter { isValidFill(it, before, after, chosen, sep, nodeCoords, headingConeCosine) }
                .maxByOrNull { rewardTotals[it.id] ?: 0.0 }
                ?: break
            loop.add(gapIndex + 1, best)
            fillPool.remove(best)
            chosen.add(best)
        }
    }

    /** Estimated loop perimeter: corridor lengths plus haversine*1.3 connector gaps (cyclic). */
    internal fun estimatedPerimeter(
        loop: List<Corridor>,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Double {
        if (loop.isEmpty()) return 0.0
        var total = loop.sumOf { it.lengthM }
        for (i in loop.indices) {
            total += connectorEstimate(loop[i], loop[(i + 1) % loop.size], nodeCoords)
        }
        return total
    }

    /** Index of the connector gap (`loop[i] -> loop[i+1]`, cyclic) with the largest estimate. */
    private fun indexOfLargestGap(
        loop: List<Corridor>,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Int {
        var bestIdx = 0
        var bestDist = -1.0
        for (i in loop.indices) {
            val d = connectorEstimate(loop[i], loop[(i + 1) % loop.size], nodeCoords)
            if (d > bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * Coarse connector distance: `haversine(exit(a), entry(b)) * ROAD_DISTANCE_FACTOR`. Falls back
     * to corridor centroids when a node coordinate is unresolved (the CorridorConnector table is
     * deliberately not consulted during construction).
     */
    internal fun connectorEstimate(
        a: Corridor,
        b: Corridor,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Double {
        val (aLat, aLon) = nodeCoords[a.exitNode] ?: (a.centroidLat to a.centroidLon)
        val (bLat, bLon) = nodeCoords[b.entryNode] ?: (b.centroidLat to b.centroidLon)
        return GeoUtils.haversineDistance(aLat, aLon, bLat, bLon) * ROAD_DISTANCE_FACTOR
    }

    /** A fill is valid when it sits between the gap neighbours, stays separated, and heads forward. */
    internal fun isValidFill(
        c: Corridor,
        before: Corridor,
        after: Corridor,
        chosen: List<Corridor>,
        sep: Double,
        nodeCoords: Map<Long, Pair<Double, Double>>,
        headingConeCosine: Double = 0.0,
    ): Boolean {
        if (!isBetween(c, before, after)) return false
        if (chosen.any { !corridorsSeparated(c, it, sep, nodeCoords) }) return false
        return headingConsistent(c, before, after, nodeCoords, headingConeCosine)
    }

    /**
     * True when [c]'s centroid projects onto the segment [before]->[after] at a parameter in
     * `[0, 1]` — i.e. it lies geographically between the two gap neighbours rather than off either
     * end. A degenerate (zero-length) segment admits no fill.
     */
    internal fun isBetween(c: Corridor, before: Corridor, after: Corridor): Boolean {
        val (bx, by) = toLocalMeters(before.centroidLat, before.centroidLon, after.centroidLat, after.centroidLon)
        val (cx, cy) = toLocalMeters(before.centroidLat, before.centroidLon, c.centroidLat, c.centroidLon)
        val segLenSq = bx * bx + by * by
        if (segLenSq == 0.0) return false
        val t = (cx * bx + cy * by) / segLenSq
        return t in 0.0..1.0
    }

    /**
     * True when traversing [c] entry->exit does not oppose travel from [before] to [after] beyond
     * the heading cone. The cone half-angle is set by [coneCosine]: the default `0.0` is a 90-degree
     * cone, and DegradationPolicy widens it (toward -1.0) on relaxation so a stuck retry can admit
     * more loosely-aligned fills. When the entry/exit node coordinates are unresolved the heading
     * cannot be evaluated, so the fill is accepted; direction-aware twin selection is handled
     * separately.
     */
    internal fun headingConsistent(
        c: Corridor,
        before: Corridor,
        after: Corridor,
        nodeCoords: Map<Long, Pair<Double, Double>>,
        coneCosine: Double = 0.0,
    ): Boolean {
        val entry = nodeCoords[c.entryNode] ?: return true
        val exit = nodeCoords[c.exitNode] ?: return true
        val corridorHeading = GeoUtils.computeBearing(entry.first, entry.second, exit.first, exit.second)
        val travelBearing = GeoUtils.computeBearing(
            before.centroidLat, before.centroidLon, after.centroidLat, after.centroidLon,
        )
        return cosineSimilarity(corridorHeading, travelBearing) >= coneCosine
    }

    // --- Twin orientation (group_id) ---

    /**
     * Twin orientation. When [corridor] belongs to a `group_id` pair whose partner is also present
     * in [pool], returns the twin whose entry->exit heading better matches [referenceBearing] (e.g.
     * a quadrant arc tangent), so the route traverses the shared physical road in the correct
     * direction. A corridor with no partner in [pool] (a solo road) is returned unchanged — note a
     * twin's `group_id` equals the pair's minimum `id`, so the lower-id member has `group_id == id`
     * too and cannot be told apart from a solo by id alone; only a real partner triggers a swap. A
     * pair whose entry/exit node coordinates are unresolved is also returned unchanged. This selects
     * direction only; the group_id anti-reuse guard is a separate concern (#111).
     *
     * Fills do not need this: a fill candidate is rejected by [headingConsistent] unless it is
     * traversed in the travel direction, so the surviving fill is already correctly oriented.
     */
    internal fun headingAlignedTwin(
        corridor: Corridor,
        referenceBearing: Double,
        pool: List<Corridor>,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Corridor {
        val twin = pool.firstOrNull { it.id != corridor.id && it.groupId == corridor.groupId }
            ?: return corridor
        val ownAlignment = headingAlignment(corridor, referenceBearing, nodeCoords) ?: return corridor
        val twinAlignment = headingAlignment(twin, referenceBearing, nodeCoords) ?: return corridor
        return if (twinAlignment > ownAlignment) twin else corridor
    }

    /**
     * Cosine similarity between [c]'s entry->exit heading and [referenceBearing], or null when
     * either endpoint's coordinates are unresolved (heading cannot be evaluated).
     */
    private fun headingAlignment(
        c: Corridor,
        referenceBearing: Double,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Double? {
        val entry = nodeCoords[c.entryNode] ?: return null
        val exit = nodeCoords[c.exitNode] ?: return null
        val heading = GeoUtils.computeBearing(entry.first, entry.second, exit.first, exit.second)
        return cosineSimilarity(heading, referenceBearing)
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

    // --- Separation rule helpers ---

    /**
     * Adaptive separation: capped at [sepM] (full 2km by default), shrinking with reach so short
     * targets stay fillable. DegradationPolicy lowers [sepM] on relaxation so a stuck retry can pack
     * more anchors into the same area.
     */
    internal fun separationDistance(reach: Double, sepM: Double = SEPARATION_M): Double =
        minOf(sepM, reach * SEPARATION_REACH_FRACTION)

    /** Every node a corridor touches: both ends of each edge plus the entry/exit nodes. */
    internal fun corridorNodeIds(c: Corridor): Set<Long> = buildSet {
        add(c.entryNode)
        add(c.exitNode)
        for ((from, to) in c.edgeList) {
            add(from)
            add(to)
        }
    }

    /**
     * True when corridors [a] and [b] are at least [sep] apart — i.e. no node of one is within
     * [sep] (air distance) of any node of the other.
     *
     * A cheap centroid + half-length prefilter short-circuits far pairs without any node lookup:
     * if the centroids are farther apart than `sep + lenA/2 + lenB/2`, the corridors cannot
     * possibly have nodes within [sep], so they are definitely separated. Only pairs surviving the
     * prefilter resolve node coordinates. When those coordinates are unavailable (e.g. a corridor
     * carries no `edgeList`), the pair is treated as separated — overlap cannot be proven.
     */
    internal fun corridorsSeparated(
        a: Corridor,
        b: Corridor,
        sep: Double,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Boolean {
        val centroidDist = GeoUtils.haversineDistance(
            a.centroidLat, a.centroidLon, b.centroidLat, b.centroidLon,
        )
        if (centroidDist > sep + a.lengthM / 2.0 + b.lengthM / 2.0) return true

        val aNodes = corridorNodeIds(a).mapNotNull { nodeCoords[it] }
        val bNodes = corridorNodeIds(b).mapNotNull { nodeCoords[it] }
        if (aNodes.isEmpty() || bNodes.isEmpty()) return true

        for ((aLat, aLon) in aNodes) {
            for ((bLat, bLon) in bNodes) {
                if (GeoUtils.haversineDistance(aLat, aLon, bLat, bLon) < sep) return false
            }
        }
        return true
    }
}
