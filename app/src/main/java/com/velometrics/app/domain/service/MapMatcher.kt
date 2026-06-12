package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snaps a raw GPS track (`IntervalSession.gpsTrack`, decoded to lat/lon pairs) onto the road
 * graph, producing a single connected, ordered [MapEdge] sequence — the representation
 * `RepeatedInterval` clustering uses to compare and render archetypes (#10/#25).
 *
 * Algorithm: greedy nearest-edge snapping via [RTreeSpatialIndex],
 * then repair of small gaps between non-adjacent snapped edges via bounded local
 * adjacency-graph search (the same edge-indexing/successor-map pattern as [FastWayHomeService]).
 * Isolated single-point mis-snaps are dropped; gaps too large to repair reject the whole track.
 */
@Singleton
class MapMatcher @Inject constructor(
    private val repository: MapGraphRepository
) {
    private val spatialIndex = RTreeSpatialIndex()

    private data class Run(val edgeIdx: Int, val pointCount: Int)

    /**
     * Returns a connected, ordered list of [MapEdge]s matching [gpsTrack], or null if no
     * coherent match could be produced (too few usable snaps, or a gap too large to repair).
     */
    suspend fun matchTrack(gpsTrack: List<List<Double>>): List<MapEdge>? {
        val points = gpsTrack.mapNotNull { coords -> if (coords.size >= 2) LatLng(coords[0], coords[1]) else null }
        if (points.size < 2) return null

        // The full road graph is 500K+ edges — far too large to load and spatially index in
        // one go (decoding every polyline and parsing its metadata blows the heap, #29).
        // Query only the slice of the graph near this track's bounding box.
        val bbox = TrackGeometryUtils.computeBoundingBox(points, CyclingConstants.INTERVAL_EDGE_SNAP_RADIUS_M)
        val edges = repository.getEdgesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)
        if (edges.size < 2) return null
        val nodes = repository.getNodesNear(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon).associateBy { it.id }
        val adj = buildAdjacency(edges)

        spatialIndex.rebuildIndex(edges, nodes)

        val snapped = snapPoints(points)
        val runs = dropIsolatedOutliers(collapseConsecutive(snapped), adj)
        if (runs.isEmpty()) return null

        val sequence = dedupeConsecutive(runs.map { it.edgeIdx })
        val repaired = repairGaps(sequence, adj, edges)?.let(::dedupeConsecutive) ?: return null

        val pruned = pruneLeafEdges(repaired, edges, nodes, points.first(), points.last())
        if (pruned.isEmpty()) return null
        val finalSequence = repairGaps(pruned, adj, edges)?.let(::dedupeConsecutive) ?: return null

        return finalSequence.mapNotNull { edges.getOrNull(it) }.takeIf { it.size > 1 }
    }

    private fun buildAdjacency(edges: List<MapEdge>): Map<Int, List<Int>> {
        val edgesByFromNode = mutableMapOf<Long, MutableList<Int>>()
        edges.forEachIndexed { idx, edge ->
            edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
        }
        val adj = mutableMapOf<Int, List<Int>>()
        edges.forEachIndexed { idx, edge ->
            val successors = edgesByFromNode[edge.toNode]?.filter { it != idx } ?: emptyList()
            if (successors.isNotEmpty()) adj[idx] = successors
        }
        return adj
    }

    /**
     * Snaps each point to the nearest in-radius edge whose direction is within
     * [CyclingConstants.INTERVAL_SNAP_BEARING_MAX_DIFF_DEG] of the GPS heading at that point
     * (rejecting perpendicular side streets and reverse twins on two-way roads). If no
     * candidate qualifies, falls back to the nearest candidate by distance so a point is never
     * dropped purely due to heading noise.
     */
    private suspend fun snapPoints(points: List<LatLng>): List<Int?> {
        val headings = computeHeadings(points)
        return points.mapIndexed { i, point ->
            val candidates = spatialIndex.queryEdgesNear(
                point.latitude, point.longitude, CyclingConstants.INTERVAL_EDGE_SNAP_RADIUS_M
            )
            selectSnapCandidate(candidates, headings[i])?.edgeKey?.toInt()
        }
    }

    /**
     * Picks the nearest [candidates] (pre-sorted by distance) whose bearing is within
     * [CyclingConstants.INTERVAL_SNAP_BEARING_MAX_DIFF_DEG] of [heading] (rejecting perpendicular
     * side streets and reverse twins on two-way roads). If [heading] is undefined or no
     * candidate qualifies, falls back to the nearest candidate by distance so a point is never
     * dropped purely due to heading noise.
     */
    internal fun selectSnapCandidate(
        candidates: List<RTreeSpatialIndex.EdgeCandidate>,
        heading: Double?
    ): RTreeSpatialIndex.EdgeCandidate? {
        val withinBearing = heading?.let { h ->
            candidates.firstOrNull { GeoUtils.bearingDifference(it.bearingDeg, h) <= CyclingConstants.INTERVAL_SNAP_BEARING_MAX_DIFF_DEG }
        }
        return withinBearing ?: candidates.firstOrNull()
    }

    /**
     * Computes the GPS heading at each point as the bearing across a small surrounding window,
     * damping single-point (1 Hz) jitter. Returns null where the window collapses to a single
     * coordinate (e.g. the track is stationary at that point).
     */
    internal fun computeHeadings(points: List<LatLng>): List<Double?> {
        val windowRadius = 2
        return points.indices.map { i ->
            val start = points[maxOf(0, i - windowRadius)]
            val end = points[minOf(points.lastIndex, i + windowRadius)]
            if (start.latitude == end.latitude && start.longitude == end.longitude) {
                null
            } else {
                GeoUtils.computeBearing(start.latitude, start.longitude, end.latitude, end.longitude)
            }
        }
    }

    private fun collapseConsecutive(snapped: List<Int?>): List<Run> {
        val runs = mutableListOf<Run>()
        for (idx in snapped) {
            if (idx == null) continue
            val last = runs.lastOrNull()
            if (last != null && last.edgeIdx == idx) {
                runs[runs.lastIndex] = last.copy(pointCount = last.pointCount + 1)
            } else {
                runs.add(Run(idx, 1))
            }
        }
        return runs
    }

    private fun dedupeConsecutive(sequence: List<Int>): List<Int> {
        val result = mutableListOf<Int>()
        for (idx in sequence) {
            if (result.lastOrNull() != idx) result.add(idx)
        }
        return result
    }

    /**
     * Drops single-point runs that are sandwiched between two runs which are themselves
     * mutually adjacent — a classic GPS-noise mis-snap rather than a genuine excursion.
     */
    private fun dropIsolatedOutliers(runsIn: List<Run>, adj: Map<Int, List<Int>>): List<Run> {
        val runs = runsIn.toMutableList()
        var i = 1
        while (i < runs.size - 1) {
            val prev = runs[i - 1]
            val current = runs[i]
            val next = runs[i + 1]
            val prevAdjToNext = areAdjacent(prev.edgeIdx, next.edgeIdx, adj)
            val currentFitsBetween = areAdjacent(prev.edgeIdx, current.edgeIdx, adj) &&
                areAdjacent(current.edgeIdx, next.edgeIdx, adj)
            if (current.pointCount <= CyclingConstants.INTERVAL_MATCH_MAX_OUTLIER_RUN_POINTS &&
                prevAdjToNext && !currentFitsBetween
            ) {
                runs.removeAt(i)
            } else {
                i++
            }
        }
        return runs
    }

    private fun areAdjacent(fromIdx: Int, toIdx: Int, adj: Map<Int, List<Int>>): Boolean =
        fromIdx == toIdx || adj[fromIdx]?.contains(toIdx) == true

    /**
     * Walks the snapped sequence, splicing in a short connecting path (via [findConnectingPath])
     * wherever consecutive edges aren't graph-adjacent. Returns null if any gap can't be
     * bridged within [CyclingConstants.INTERVAL_MATCH_MAX_REPAIR_DEPTH] hops.
     *
     * If the only connecting path starts by reversing the preceding edge — a side-street snap
     * that would create a U-turn loop — that snap is removed and the gap is re-bridged from
     * its predecessor. If the retry also fails, the track is rejected.
     */
    private fun repairGaps(sequence: List<Int>, adj: Map<Int, List<Int>>, edges: List<MapEdge>): List<Int>? {
        if (sequence.size <= 1) return sequence

        val result = mutableListOf(sequence.first())
        for (i in 1 until sequence.size) {
            val prev = result.last()
            val curr = sequence[i]
            if (prev == curr || adj[prev]?.contains(curr) == true) {
                result.add(curr)
                continue
            }
            val connector = findConnectingPath(prev, curr, adj) ?: return null
            val firstStep = connector.firstOrNull()
            val isUTurn = firstStep != null &&
                edges.getOrNull(firstStep)?.toNode == edges.getOrNull(prev)?.fromNode
            if (isUTurn) {
                result.removeLastOrNull() ?: return null
                val newPrev = result.lastOrNull() ?: return null
                if (newPrev == curr || adj[newPrev]?.contains(curr) == true) {
                    result.add(curr)
                } else {
                    val retryConnector = findConnectingPath(newPrev, curr, adj) ?: return null
                    result.addAll(retryConnector)
                    result.add(curr)
                }
            } else {
                result.addAll(connector)
                result.add(curr)
            }
        }
        return result
    }

    /** Bounded BFS over [adj] returning the intermediate edge indices between [from] and [to] (exclusive). */
    private fun findConnectingPath(from: Int, to: Int, adj: Map<Int, List<Int>>): List<Int>? {
        if (from == to) return emptyList()

        val queue = ArrayDeque<Int>()
        val cameFrom = mutableMapOf<Int, Int>()
        val visited = mutableSetOf(from)
        queue.add(from)

        var depth = 0
        while (queue.isNotEmpty() && depth < CyclingConstants.INTERVAL_MATCH_MAX_REPAIR_DEPTH) {
            repeat(queue.size) {
                val current = queue.removeFirst()
                for (succ in adj[current].orEmpty()) {
                    if (!visited.add(succ)) continue
                    cameFrom[succ] = current
                    if (succ == to) return reconstructIntermediates(succ, from, cameFrom)
                    queue.add(succ)
                }
            }
            depth++
        }
        return null
    }

    /**
     * Removes edges that are topological "leaves" — one endpoint connects to no other edge in the
     * matched set, forming a dead-end branch. The nodes nearest to the GPS track's first and last
     * points are protected so the interval's true start/end is never pruned. Runs iteratively
     * until stable, then hands back to the caller for a BFS safety-net pass.
     */
    private fun pruneLeafEdges(
        sequence: List<Int>,
        edges: List<MapEdge>,
        nodes: Map<Long, MapNode>,
        startPoint: LatLng,
        endPoint: LatLng
    ): List<Int> {
        if (sequence.size <= 1) return sequence

        val degree = mutableMapOf<Long, Int>()
        for (idx in sequence) {
            val edge = edges[idx]
            degree[edge.fromNode] = (degree[edge.fromNode] ?: 0) + 1
            degree[edge.toNode] = (degree[edge.toNode] ?: 0) + 1
        }

        val matchedNodeIds = sequence.flatMapTo(mutableSetOf()) { idx ->
            listOf(edges[idx].fromNode, edges[idx].toNode)
        }
        val protectedNodes = setOfNotNull(
            nearestNodeId(startPoint, matchedNodeIds, nodes),
            nearestNodeId(endPoint, matchedNodeIds, nodes)
        )

        val kept = sequence.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            val iter = kept.iterator()
            while (iter.hasNext()) {
                val idx = iter.next()
                val edge = edges[idx]
                val fromLeaf = (degree[edge.fromNode] ?: 0) == 1 && edge.fromNode !in protectedNodes
                val toLeaf = (degree[edge.toNode] ?: 0) == 1 && edge.toNode !in protectedNodes
                if (fromLeaf || toLeaf) {
                    iter.remove()
                    degree[edge.fromNode] = maxOf(0, (degree[edge.fromNode] ?: 0) - 1)
                    degree[edge.toNode] = maxOf(0, (degree[edge.toNode] ?: 0) - 1)
                    changed = true
                }
            }
        }
        return kept
    }

    private fun nearestNodeId(point: LatLng, nodeIds: Set<Long>, nodes: Map<Long, MapNode>): Long? =
        nodeIds
            .mapNotNull { id -> nodes[id]?.let { node -> id to GeoUtils.haversineDistance(point.latitude, point.longitude, node.lat, node.lon) } }
            .minByOrNull { it.second }
            ?.first

    private fun reconstructIntermediates(to: Int, from: Int, cameFrom: Map<Int, Int>): List<Int> {
        val path = mutableListOf<Int>()
        var node: Int? = to
        while (node != null && node != from) {
            if (node != to) path.add(0, node)
            node = cameFrom[node]
        }
        return path
    }
}
