package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.GeoUtils
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Snaps a raw GPS track (`IntervalSession.gpsTrack`, decoded to lat/lon pairs) onto the road
 * graph, producing a single connected, ordered [MapEdge] sequence — the representation
 * `RepeatedInterval` clustering uses to compare and render archetypes (#10/#25).
 *
 * Algorithm: greedy nearest-edge snapping via [RTreeSpatialIndex], then anchor-by-GPS-order
 * pruning — only runs of consecutive snaps with at least
 * [CyclingConstants.INTERVAL_MATCH_MIN_ANCHOR_POINTS] points are kept as ordered anchors — and
 * repair of gaps between consecutive anchors via bounded local adjacency-graph search (the same
 * edge-indexing/successor-map pattern as [FastWayHomeService]). Gaps too large to repair reject
 * the whole track.
 */
/**
 * Result of [MapMatcher.matchTrackChunked]: the edges matched across all chunks (deduped), plus
 * the total and successfully-matched along-track distance, so callers can report the covered
 * fraction of the route.
 */
data class ChunkedMatchResult(
    val matchedEdges: List<MapEdge>,
    val totalDistanceM: Double,
    val matchedDistanceM: Double
) {
    val coveragePercent: Int
        get() = if (totalDistanceM <= 0.0) 0 else (100 * matchedDistanceM / totalDistanceM).roundToInt().coerceIn(0, 100)
}

@Singleton
class MapMatcher @Inject constructor(
    private val repository: MapGraphRepository
) {
    private data class Run(val edgeIdx: Int, val pointCount: Int)

    companion object {
        // Cap on edges loaded into a single reusable Region. Above this the slice is too large to
        // index without risking the heap (the full graph is 500K+ edges, #29), so callers fall back
        // to per-track matching. Below 2 there is nothing to match against.
        private const val MAX_REGION_EDGES = 60_000
    }

    /**
     * A slice of the road graph — edges, nodes, adjacency, and a spatial index — loaded once and
     * reused to [match] many GPS tracks that fall within it. Clustering many nearby intervals shares
     * one Region instead of paying a DB query + RTree rebuild per track.
     */
    inner class Region internal constructor(
        private val edges: List<MapEdge>,
        private val nodes: Map<Long, MapNode>,
        private val adj: Map<Int, List<Int>>,
        private val index: RTreeSpatialIndex
    ) {
        /**
         * Returns a connected, ordered list of [MapEdge]s matching [gpsTrack], or null if no
         * coherent match could be produced (too few usable snaps, or a gap too large to repair).
         */
        suspend fun match(gpsTrack: List<List<Double>>): List<MapEdge>? {
            val points = gpsTrack.mapNotNull { coords -> if (coords.size >= 2) LatLng(coords[0], coords[1]) else null }
            if (points.size < 2 || edges.size < 2) return null

            val snapped = snapPoints(points, index)
            val anchors = collapseConsecutive(snapped)
                .filter { it.pointCount >= CyclingConstants.INTERVAL_MATCH_MIN_ANCHOR_POINTS }
            if (anchors.isEmpty()) return null

            val sequence = dedupeConsecutive(anchors.map { it.edgeIdx })
            val repaired = repairGaps(sequence, adj, edges)?.let(::dedupeConsecutive) ?: return null

            val pruned = pruneLeafEdges(repaired, edges, nodes, points.first(), points.last())
            if (pruned.isEmpty()) return null

            return pruned.mapNotNull { edges.getOrNull(it) }.takeIf { it.size > 1 }
        }
    }

    /**
     * Loads the graph slice within the given bounding box into a reusable [Region], or null if the
     * box holds fewer than 2 edges (nothing to match) or more than [MAX_REGION_EDGES] (too large —
     * the caller should fall back to per-track [matchTrack]).
     */
    suspend fun loadRegion(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Region? {
        val edges = repository.getEdgesNear(minLat, minLon, maxLat, maxLon)
        if (edges.size < 2 || edges.size > MAX_REGION_EDGES) return null
        val nodes = repository.getNodesNear(minLat, minLon, maxLat, maxLon).associateBy { it.id }
        val adj = buildAdjacency(edges)
        val index = RTreeSpatialIndex()
        index.rebuildIndex(edges, nodes)
        return Region(edges, nodes, adj, index)
    }

    /**
     * Single-track convenience: loads a [Region] for just this track's bounding box and matches
     * against it. Equivalent to the original per-track path; clustering reuses one [Region] across
     * many tracks via [loadRegion] instead.
     */
    suspend fun matchTrack(gpsTrack: List<List<Double>>): List<MapEdge>? {
        val points = gpsTrack.mapNotNull { coords -> if (coords.size >= 2) LatLng(coords[0], coords[1]) else null }
        if (points.size < 2) return null

        // The full road graph is 500K+ edges — far too large to load and spatially index in
        // one go (decoding every polyline and parsing its metadata blows the heap, #29).
        // Query only the slice of the graph near this track's bounding box.
        val bbox = TrackGeometryUtils.computeBoundingBox(points, CyclingConstants.INTERVAL_EDGE_SNAP_RADIUS_M)
        return loadRegion(bbox.minLat, bbox.minLon, bbox.maxLat, bbox.maxLon)?.match(gpsTrack)
    }

    /**
     * Matches an imported .gpx route (#15) against the road graph in [chunkSizeM]-long pieces
     * instead of all at once. [matchTrack] is all-or-nothing: a single unmatchable section (e.g.
     * part of the route lies outside the graph's coverage area) blanks the entire result. Here,
     * each chunk is matched independently — chunks that fail simply don't contribute, while the
     * rest still produce a score. Matched edges are deduped by node pair across chunk
     * boundaries.
     */
    suspend fun matchTrackChunked(
        gpsTrack: List<List<Double>>,
        chunkSizeM: Double = CyclingConstants.GPX_ANALYSIS_MATCH_CHUNK_M
    ): ChunkedMatchResult {
        val points = gpsTrack.mapNotNull { coords -> if (coords.size >= 2) LatLng(coords[0], coords[1]) else null }
        if (points.size < 2) return ChunkedMatchResult(emptyList(), 0.0, 0.0)

        val matchedEdges = LinkedHashMap<Pair<Long, Long>, MapEdge>()
        var totalDistanceM = 0.0
        var matchedDistanceM = 0.0
        for (chunk in splitIntoChunks(points, chunkSizeM)) {
            val chunkDistanceM = chunk.zipWithNext().sumOf { (a, b) ->
                GeoUtils.haversineDistance(a.latitude, a.longitude, b.latitude, b.longitude)
            }
            totalDistanceM += chunkDistanceM
            val chunkGps = chunk.map { listOf(it.latitude, it.longitude) }
            val edges = matchTrack(chunkGps)
            if (!edges.isNullOrEmpty()) {
                matchedDistanceM += chunkDistanceM
                for (edge in edges) {
                    matchedEdges.putIfAbsent(edge.fromNode to edge.toNode, edge)
                }
            }
        }
        return ChunkedMatchResult(matchedEdges.values.toList(), totalDistanceM, matchedDistanceM)
    }

    /** Splits [points] into consecutive runs whose along-track length is roughly [chunkSizeM]. */
    private fun splitIntoChunks(points: List<LatLng>, chunkSizeM: Double): List<List<LatLng>> {
        val chunks = mutableListOf<List<LatLng>>()
        var current = mutableListOf(points.first())
        var accumulated = 0.0
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            current.add(curr)
            accumulated += GeoUtils.haversineDistance(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            if (accumulated >= chunkSizeM && i != points.lastIndex) {
                chunks.add(current)
                current = mutableListOf(curr)
                accumulated = 0.0
            }
        }
        if (current.size >= 2) chunks.add(current)
        return chunks
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
    private suspend fun snapPoints(points: List<LatLng>, index: RTreeSpatialIndex): List<Int?> {
        val headings = computeHeadings(points)
        return points.mapIndexed { i, point ->
            val candidates = index.queryEdgesNear(
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
     * matched set, forming a dead-end branch. Degree is counted over the undirected graph (an
     * anti-parallel pair, e.g. an out-and-back spur, collapses to a single connection), so a
     * dead-end node touched only by such a pair is still recognized as degree-1. The nodes
     * nearest to the GPS track's first and last points are protected so the interval's true
     * start/end is never pruned. Runs iteratively until stable.
     */
    private fun pruneLeafEdges(
        sequence: List<Int>,
        edges: List<MapEdge>,
        nodes: Map<Long, MapNode>,
        startPoint: LatLng,
        endPoint: LatLng
    ): List<Int> {
        if (sequence.size <= 1) return sequence

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
            val degree = undirectedDegree(kept, edges)
            val iter = kept.iterator()
            while (iter.hasNext()) {
                val idx = iter.next()
                val edge = edges[idx]
                val fromLeaf = (degree[edge.fromNode] ?: 0) == 1 && edge.fromNode !in protectedNodes
                val toLeaf = (degree[edge.toNode] ?: 0) == 1 && edge.toNode !in protectedNodes
                if (fromLeaf || toLeaf) {
                    iter.remove()
                    changed = true
                }
            }
        }
        return kept
    }

    /**
     * Node degree over the undirected graph formed by [sequence]: each edge's endpoints are
     * collapsed to an unordered pair, so an anti-parallel pair (A->B and B->A) counts as a single
     * connection between A and B.
     */
    private fun undirectedDegree(sequence: List<Int>, edges: List<MapEdge>): Map<Long, Int> {
        val pairs = sequence.mapTo(mutableSetOf()) { idx ->
            val edge = edges[idx]
            if (edge.fromNode <= edge.toNode) edge.fromNode to edge.toNode else edge.toNode to edge.fromNode
        }
        val degree = mutableMapOf<Long, Int>()
        for ((a, b) in pairs) {
            degree[a] = (degree[a] ?: 0) + 1
            degree[b] = (degree[b] ?: 0) + 1
        }
        return degree
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
