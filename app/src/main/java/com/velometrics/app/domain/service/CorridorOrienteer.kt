package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
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
    val avoidOppositeDirectionReuse: Boolean = true,
)

data class CandidateLoop(
    val corridors: List<Long>,
    val totalDistanceM: Double,
    val totalReward: Double,
    val flowScore: Double,
    val discoveryScore: Double,
    /**
     * Pseudo-corridors synthesized for empty quadrants, keyed by their (negative) synthetic id.
     * These are not in the repository, so [RouteGenerator] merges them into the corridor map before
     * handing the loop to [RouteRefiner], letting a pseudo-corridor route like a real one.
     */
    val syntheticCorridors: Map<Long, Corridor> = emptyMap(),
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

    /** Top-N anchor candidates taken per quadrant per winding before combination enumeration. */
    private const val ANCHOR_COMBOS_PER_QUADRANT = 2

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
        edgeResolver: suspend (
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ) -> List<MapEdge> = { _, _, _, _ -> emptyList() },
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

        val startCorridor = startCorridorId?.let { id -> corridors.firstOrNull { it.id == id } }
        val origin = startCorridor ?: homeCorridor

        // Batch-resolve node coordinates once for all reachable corridors (shared across windings).
        val allCandidateCorridors = reachable.filter { it.id != homeCorridor.id && it.id != origin.id }
        val neededNodeIds = buildSet { allCandidateCorridors.forEach { addAll(corridorNodeIds(it)) } }
        val nodeCoords = nodeResolver(neededNodeIds).toMutableMap()
        val sep = separationDistance(reach, config.separationM)

        // Pre-fetch traversed edges within the reach bbox once for the entire search.
        // fillEmptyQuadrantsFromEdges is called per-combo so fetching inside it would repeat
        // the same DB query hundreds of times. Pre-resolving node coords here as well means
        // the function finds them cached and never issues a second nodeResolver call.
        val latDelta = GeoUtils.metersToLat(reach)
        val lonDelta = GeoUtils.metersToLon(reach, homeLat)
        val reachEdges = edgeResolver(
            homeLat - latDelta, homeLon - lonDelta, homeLat + latDelta, homeLon + lonDelta,
        ).filter { (it.traversalCount ?: 0) > 0 }
        if (reachEdges.isNotEmpty()) {
            val edgeNodeIds = buildSet { reachEdges.forEach { add(it.fromNode); add(it.toNode) } }
            val unresolved = edgeNodeIds.filterTo(mutableSetOf()) { it !in nodeCoords }
            if (unresolved.isNotEmpty()) nodeCoords.putAll(nodeResolver(unresolved))
        }

        val allResults = mutableListOf<CandidateLoop>()
        // Sorted IDs uniquely identify a corridor-set regardless of loop order or winding.
        val seenCorridorSets = mutableSetOf<List<Long>>()

        // --- Enumerate both CW and CCW windings ---
        // CW: lateral > 0 is right of travel (Q1 = front-right).
        // CCW: lateral sign flipped → Q1 = front-left, mirroring the oval left-right.
        for (cw in listOf(true, false)) {

            // Classify reachable corridors into quadrants for this winding.
            val quadrants = Array(QUADRANT_COUNT) { mutableListOf<Corridor>() }
            for (c in allCandidateCorridors) {
                val (east, north) = toLocalMeters(homeLat, homeLon, c.centroidLat, c.centroidLon)
                val (along, rawLateral) = alongLateral(east, north, rideBearing)
                val lateral = if (cw) rawLateral else -rawLateral
                quadrants[quadrantOf(along, lateral)].add(c)
            }

            // Top-N candidates per quadrant sorted by geometry-first anchor score.
            val topPerQ: Array<List<Corridor>> = Array(QUADRANT_COUNT) { q ->
                quadrants[q].sortedByDescending { c ->
                    val (east, north) = toLocalMeters(homeLat, homeLon, c.centroidLat, c.centroidLon)
                    val (along, _) = alongLateral(east, north, rideBearing)
                    val corridorBearing = GeoUtils.computeBearing(homeLat, homeLon, c.centroidLat, c.centroidLon)
                    val rewardNorm = normalizeReward(rewardTotals[c.id] ?: 0.0, minReward, maxReward)
                    anchorScore(along, reach, corridorBearing, rideBearing, q, rewardNorm, config, cw)
                }.take(ANCHOR_COMBOS_PER_QUADRANT)
            }

            // Enumerate anchor combinations: Cartesian product of topPerQ, each combo validated
            // for mutual pairwise separation. With N=2 and 4 quadrants this is at most 2^4 = 16
            // combos per winding.
            for (combo in enumerateAnchorCombos(topPerQ, sep, nodeCoords)) {
                // Orient each anchor to its quadrant's arc tangent (twin-orientation).
                val anchors = arrayOfNulls<Corridor>(QUADRANT_COUNT)
                val chosen = mutableListOf<Corridor>()
                for (q in 0 until QUADRANT_COUNT) {
                    val pick = combo[q] ?: continue
                    val arcTangent = arcTangentBearing(rideBearing, q, cw)
                    val anchor = headingAlignedTwin(pick, arcTangent, quadrants[q], nodeCoords)
                    anchors[q] = anchor
                    chosen.add(anchor)
                }

                // Empty-quadrant edge fallback (uses pre-fetched edge list, not edgeResolver).
                val pseudoCorridors = fillEmptyQuadrantsFromEdges(
                    anchors, chosen, homeLat, homeLon, reach, rideBearing, sep, config,
                    nodeCoords, reachEdges,
                )

                // Build ordered loop: origin first, then quadrant anchors in Q1->Q4 order.
                val loop = mutableListOf<Corridor>()
                loop.add(startCorridor ?: homeCorridor)
                for (q in 0 until QUADRANT_COUNT) {
                    val anchor = anchors[q] ?: continue
                    if (anchor.id == startCorridor?.id || anchor.id == homeCorridor.id) continue
                    if (loop.lastOrNull()?.id != anchor.id) loop.add(anchor)
                }

                // Fill toward target length.
                val usedIds = loop.mapTo(mutableSetOf()) { it.id }
                val fillPool = allCandidateCorridors.filter { it.id !in usedIds }.toMutableList()
                fillToTarget(loop, fillPool, chosen, targetDistanceM, sep, nodeCoords, rewardTotals, config.headingConeCosine, config.avoidOppositeDirectionReuse)

                val ordered = loop.map { it.id }
                val distinct = ordered.distinct()
                if (distinct.size < 2) continue

                // Dedupe: skip if another winding/combo already produced this corridor-set.
                val key = distinct.sorted()
                if (!seenCorridorSets.add(key)) continue

                val corridorById = HashMap<Long, Corridor>()
                reachable.forEach { corridorById[it.id] = it }
                corridorById[homeCorridor.id] = homeCorridor
                startCorridor?.let { corridorById[it.id] = it }
                loop.forEach { corridorById[it.id] = it }
                pseudoCorridors.forEach { (id, c) -> corridorById[id] = c }

                val augmentedRewardCache = if (pseudoCorridors.isEmpty()) rewardCache
                else rewardCache + pseudoCorridors.mapValues { RewardComposer.composeCorridorReward(it.value, weights) }

                val candidate = buildCandidate(ordered, corridorById, augmentedRewardCache)
                    .copy(syntheticCorridors = pseudoCorridors)
                allResults.add(candidate)
            }
        }

        if (allResults.isEmpty()) {
            Log.d(TAG, "search: no valid skeletons from either winding")
            return emptyList()
        }

        // Rank by reward, cap at candidateCount.
        val ranked = allResults.sortedByDescending { it.totalReward }.take(config.candidateCount)
        Log.d(TAG, "search: ${allResults.size} raw candidates -> ${ranked.size} returned (candidateCount=${config.candidateCount})")
        return ranked
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
        avoidOppositeDirectionReuse: Boolean,
    ) {
        if (loop.size < 2) return
        val ceiling = targetDistanceM * FILL_TARGET_FRACTION
        while (fillPool.isNotEmpty() && estimatedPerimeter(loop, nodeCoords) < ceiling) {
            val gapIndex = indexOfLargestGap(loop, nodeCoords)
            val before = loop[gapIndex]
            val after = loop[(gapIndex + 1) % loop.size]
            val best = fillPool
                .filter { isValidFill(it, before, after, chosen, sep, nodeCoords, headingConeCosine, avoidOppositeDirectionReuse) }
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
        avoidOppositeDirectionReuse: Boolean = true,
    ): Boolean {
        if (!isBetween(c, before, after)) return false
        if (avoidOppositeDirectionReuse && chosen.any { it.groupId == c.groupId && it.id != c.id }) return false
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

    // --- Empty-quadrant edge fallback ---

    /** Synthetic id for the pseudo-corridor of quadrant [q] — negative so it never collides with a
     *  real (positive) DB corridor id. */
    private fun pseudoIdFor(q: Int): Long = -(q.toLong() + 1)

    /**
     * For each quadrant left without an anchor, synthesize a pseudo-corridor from the
     * highest-`traversalCount` edge in that quadrant whose heading matches the arc tangent and that
     * stays separated from every already-chosen corridor. Writes the pick into [anchors] and
     * [chosen], folds the edge's endpoint coordinates into [nodeCoords] (so separation and fill can
     * see them), and returns the synthesized corridors keyed by their synthetic id. A quadrant with
     * no suitable edge is left empty.
     *
     * No edges (the default no-op resolver, or none in reach) means no work — the common path where
     * every quadrant already has an anchor returns immediately without resolving anything.
     */
    // Not suspend: no DB calls remain — edges and node coords are pre-fetched by search().
    private fun fillEmptyQuadrantsFromEdges(
        anchors: Array<Corridor?>,
        chosen: MutableList<Corridor>,
        homeLat: Double,
        homeLon: Double,
        reach: Double,
        rideBearing: Double,
        sep: Double,
        config: OrienteerConfig,
        nodeCoords: MutableMap<Long, Pair<Double, Double>>,
        reachEdges: List<MapEdge>,
    ): Map<Long, Corridor> {
        val emptyQuadrants = (0 until QUADRANT_COUNT).filter { anchors[it] == null }
        if (emptyQuadrants.isEmpty()) return emptyMap()
        if (reachEdges.isEmpty()) return emptyMap()

        // Group reachable, resolvable edges by the quadrant of their endpoint-midpoint centroid.
        val byQuadrant = Array(QUADRANT_COUNT) { mutableListOf<MapEdge>() }
        for (edge in reachEdges) {
            val (centroidLat, centroidLon) = edgeCentroid(edge, nodeCoords) ?: continue
            if (GeoUtils.haversineDistance(homeLat, homeLon, centroidLat, centroidLon) > reach) continue
            val (east, north) = toLocalMeters(homeLat, homeLon, centroidLat, centroidLon)
            val (along, lateral) = alongLateral(east, north, rideBearing)
            byQuadrant[quadrantOf(along, lateral)].add(edge)
        }

        val synthesized = LinkedHashMap<Long, Corridor>()
        for (q in emptyQuadrants) {
            val arcTangent = arcTangentBearing(rideBearing, q)
            val ranked = byQuadrant[q]
                .filter { adequateBearing(it, arcTangent, config.headingConeCosine, nodeCoords) }
                .sortedByDescending { it.traversalCount ?: 0 }
            var pick: Corridor? = null
            for (edge in ranked) {
                val pseudo = buildPseudoCorridor(edge, pseudoIdFor(q), nodeCoords) ?: continue
                if (chosen.all { corridorsSeparated(pseudo, it, sep, nodeCoords) }) {
                    pick = pseudo
                    break
                }
            }
            if (pick != null) {
                anchors[q] = pick
                chosen.add(pick)
                synthesized[pick.id] = pick
                Log.d(TAG, "fillEmptyQuadrantsFromEdges: Q$q pseudo-corridor ${pick.id} from edge ${pick.entryNode}->${pick.exitNode} popularity=${pick.popularity}")
            }
        }
        return synthesized
    }

    /** Endpoint-midpoint centroid of [edge], or null when an endpoint coordinate is unresolved. */
    private fun edgeCentroid(
        edge: MapEdge,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Pair<Double, Double>? {
        val from = nodeCoords[edge.fromNode] ?: return null
        val to = nodeCoords[edge.toNode] ?: return null
        return ((from.first + to.first) / 2.0) to ((from.second + to.second) / 2.0)
    }

    /**
     * True when [edge]'s fromNode->toNode heading aligns with [arcTangent] within the cone set by
     * [coneCosine] (same cone the fill step uses). Unresolved endpoints yield false: a heading that
     * cannot be confirmed adequate is not accepted.
     */
    internal fun adequateBearing(
        edge: MapEdge,
        arcTangent: Double,
        coneCosine: Double,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Boolean {
        val from = nodeCoords[edge.fromNode] ?: return false
        val to = nodeCoords[edge.toNode] ?: return false
        val heading = GeoUtils.computeBearing(from.first, from.second, to.first, to.second)
        return cosineSimilarity(heading, arcTangent) >= coneCosine
    }

    /**
     * Builds a pseudo-corridor from [edge]: entry=fromNode, exit=toNode, length from the edge,
     * centroid from the endpoint midpoint, reward from the edge's flow counts, and popularity from
     * its traversal count. Carries the single edge as its `edgeList` so the separation rule and
     * RouteRefiner's waypoint walk both work on it. Returns null when an endpoint is unresolved.
     */
    internal fun buildPseudoCorridor(
        edge: MapEdge,
        id: Long,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): Corridor? {
        val (centroidLat, centroidLon) = edgeCentroid(edge, nodeCoords) ?: return null
        return Corridor(
            id = id,
            entryNode = edge.fromNode,
            exitNode = edge.toNode,
            lengthM = edge.lengthM,
            pedalReward = (edge.pedalFlowCount ?: 0).toDouble(),
            gravityReward = (edge.gravityFlowCount ?: 0).toDouble(),
            exitHazardScore = edge.hazardScore ?: 0.0,
            centroidLat = centroidLat,
            centroidLon = centroidLon,
            edgeList = listOf(edge.fromNode to edge.toNode),
            popularity = edge.traversalCount ?: 0,
            groupId = id,
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
        cw: Boolean = true,
    ): Double {
        val farTerm = config.farWeight * (along / reach)
        val arcTangent = arcTangentBearing(rideBearingDeg, quadrant, cw)
        val headTerm = config.headingWeight * cosineSimilarity(corridorBearing, arcTangent)
        val rewardTerm = config.rewardWeight * rewardNorm
        return farTerm + headTerm + rewardTerm
    }

    /**
     * Bisector bearing of a quadrant in the ride-aligned frame.
     * CW (right of travel = +90): Q0 +45, Q1 -45, Q2 -135, Q3 +135.
     * CCW (left of travel = +90): lateral sign is flipped, so offsets mirror.
     */
    internal fun arcTangentBearing(rideBearingDeg: Double, quadrant: Int, cw: Boolean = true): Double {
        val cwOffset = when (quadrant) {
            0 -> 45.0
            1 -> -45.0
            2 -> -135.0
            else -> 135.0
        }
        val offset = if (cw) cwOffset else -cwOffset
        return (rideBearingDeg + offset + 360.0) % 360.0
    }

    /**
     * Enumerates valid anchor combinations from [topPerQ] (top-N candidates per quadrant).
     * Each combination picks at most one corridor per quadrant; a null pick means the quadrant
     * has no qualifying candidate. All non-null anchors in a combo must be mutually separated by
     * [sep]. With N=2 and 4 quadrants this generates at most 2^4 = 16 combos before filtering.
     */
    private fun enumerateAnchorCombos(
        topPerQ: Array<List<Corridor>>,
        sep: Double,
        nodeCoords: Map<Long, Pair<Double, Double>>,
    ): List<Array<Corridor?>> {
        // Augment each quadrant's option list with a null (no-anchor) slot so the Cartesian
        // product covers the case where no candidate in a quadrant survives separation.
        val options: Array<List<Corridor?>> = Array(QUADRANT_COUNT) { q ->
            (topPerQ[q] as List<Corridor?>) + listOf(null)
        }
        val results = mutableListOf<Array<Corridor?>>()
        fun recurse(q: Int, chosen: MutableList<Corridor>, combo: Array<Corridor?>) {
            if (q == QUADRANT_COUNT) {
                results.add(combo.copyOf())
                return
            }
            for (cand in options[q]) {
                if (cand != null && chosen.any { !corridorsSeparated(cand, it, sep, nodeCoords) }) continue
                combo[q] = cand
                if (cand != null) chosen.add(cand)
                recurse(q + 1, chosen, combo)
                if (cand != null) chosen.removeAt(chosen.lastIndex)
            }
        }
        recurse(0, mutableListOf(), arrayOfNulls(QUADRANT_COUNT))
        // Skip fully-null combos (no anchors at all).
        return results.filter { combo -> combo.any { it != null } }
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
