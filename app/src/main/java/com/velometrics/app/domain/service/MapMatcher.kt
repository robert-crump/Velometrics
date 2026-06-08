package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.CyclingConstants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snaps a raw GPS track (`IntervalSession.gpsTrack`, decoded to lat/lon pairs) onto the road
 * graph, producing a single connected, ordered [MapEdge] sequence — the representation
 * `RepeatedInterval` clustering uses to compare and render archetypes (#10/#25).
 *
 * Algorithm: greedy nearest-edge snapping via [RTreeSpatialIndex] (as in [EdgeStatsEstimator]),
 * then repair of small gaps between non-adjacent snapped edges via bounded local
 * adjacency-graph search (the same edge-indexing/successor-map pattern as [FastWayHomeService]).
 * Isolated single-point mis-snaps are dropped; gaps too large to repair reject the whole track.
 */
@Singleton
class MapMatcher @Inject constructor(
    private val repository: MapGraphRepository
) {
    private val spatialIndex = RTreeSpatialIndex()
    private val mutex = Mutex()
    private var edgeByIndex: Map<Int, MapEdge>? = null
    private var adjacency: Map<Int, List<Int>>? = null

    private data class Run(val edgeIdx: Int, val pointCount: Int)

    /**
     * Returns a connected, ordered list of [MapEdge]s matching [gpsTrack], or null if no
     * coherent match could be produced (too few usable snaps, or a gap too large to repair).
     */
    suspend fun matchTrack(gpsTrack: List<List<Double>>): List<MapEdge>? {
        ensureIndex()
        val edges = edgeByIndex ?: return null
        val adj = adjacency ?: return null

        val points = gpsTrack.mapNotNull { coords -> if (coords.size >= 2) LatLng(coords[0], coords[1]) else null }
        if (points.size < 2) return null

        val snapped = snapPoints(points)
        val runs = dropIsolatedOutliers(collapseConsecutive(snapped), adj)
        if (runs.isEmpty()) return null

        val sequence = dedupeConsecutive(runs.map { it.edgeIdx })
        val repaired = repairGaps(sequence, adj)?.let(::dedupeConsecutive) ?: return null

        return repaired.mapNotNull { edges[it] }.takeIf { it.size > 1 }
    }

    private suspend fun snapPoints(points: List<LatLng>): List<Int?> = points.map { point ->
        spatialIndex.queryEdgesNear(point.latitude, point.longitude, CyclingConstants.INTERVAL_EDGE_SNAP_RADIUS_M)
            .firstOrNull()
            ?.edgeKey?.toInt()
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
     */
    private fun repairGaps(sequence: List<Int>, adj: Map<Int, List<Int>>): List<Int>? {
        if (sequence.size <= 1) return sequence

        val result = mutableListOf(sequence.first())
        for (i in 1 until sequence.size) {
            val prev = result.last()
            val curr = sequence[i]
            if (prev == curr || adj[prev]?.contains(curr) == true) {
                result.add(curr)
            } else {
                val connector = findConnectingPath(prev, curr, adj) ?: return null
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

    private fun reconstructIntermediates(to: Int, from: Int, cameFrom: Map<Int, Int>): List<Int> {
        val path = mutableListOf<Int>()
        var node: Int? = to
        while (node != null && node != from) {
            if (node != to) path.add(0, node)
            node = cameFrom[node]
        }
        return path
    }

    private suspend fun ensureIndex() {
        mutex.withLock {
            if (edgeByIndex != null && adjacency != null) return

            val edges = repository.getAllEdges().first()
            val nodes = repository.getAllNodes().first().associateBy { it.id }

            val byIndex = edges.withIndex().associate { (idx, edge) -> idx to edge }

            val edgesByFromNode = mutableMapOf<Long, MutableList<Int>>()
            edges.forEachIndexed { idx, edge ->
                edgesByFromNode.getOrPut(edge.fromNode) { mutableListOf() }.add(idx)
            }
            val adj = mutableMapOf<Int, List<Int>>()
            edges.forEachIndexed { idx, edge ->
                val successors = edgesByFromNode[edge.toNode]?.filter { it != idx } ?: emptyList()
                if (successors.isNotEmpty()) adj[idx] = successors
            }

            spatialIndex.rebuildIndex(edges, nodes)
            edgeByIndex = byIndex
            adjacency = adj
        }
    }
}
