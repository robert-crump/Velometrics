package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.util.CyclingConstants.INTERVAL_EDGE_SNAP_RADIUS_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_SUBSET_OVERLAP_THRESHOLD
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.PolylineDecoder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Condenses raw [IntervalSession]s into deduped [RepeatedInterval] archetypes (#10/#25):
 * single-linkage clusters raw intervals by length + GPS-point overlap, picks each cluster's
 * median-length interval, map-matches it onto the road graph via [MapMatcher] for its edge
 * geometry, then discards archetypes whose edges are mostly a subset of a longer archetype's.
 */
@Singleton
class IntervalClusteringService @Inject constructor(
    private val intervalRepository: IntervalRepository,
    private val repeatedIntervalRepository: RepeatedIntervalRepository,
    private val mapMatcher: MapMatcher
) {
    private val gson = Gson()
    private val trackType = object : TypeToken<List<List<Double>>>() {}.type

    companion object {
        private const val TAG = "IntervalClusteringService"

        // Logs per-phase wall-clock timings of runClustering for on-device profiling.
        private const val PERF_LOGGING = true

        // Edge length of the spatial cells clusters are binned into before loading a shared road-
        // graph Region. Large enough to amortize the graph load across many nearby clusters, small
        // enough that a cell's union bounding box stays within MapMatcher's edge cap in dense areas.
        private const val REGION_GROUP_CELL_M = 5_000.0
    }

    // ─── Shared data structures ───

    private data class PreparedInterval(
        val intervalId: Long,
        val track: IntervalSimilarity.PreparedTrack
    )

    private data class CandidateArchetype(
        val intervals: List<IntervalSession>,
        val edges: List<MapEdge>,
        val distanceM: Double,
        val startLat: Double,
        val startLon: Double,
        val endLat: Double,
        val endLon: Double
    )

    private fun prepareInterval(interval: IntervalSession): PreparedInterval? {
        val points = parseGpsTrack(interval.gpsTrack)
        if (points.size < 2) return null
        return PreparedInterval(
            intervalId = interval.id,
            track = IntervalSimilarity.PreparedTrack(points, interval.distanceM)
        )
    }

    // ─── Full clustering (single-linkage / connected components) ───

    suspend fun runClustering() = withContext(Dispatchers.Default) {
        val tStart = System.nanoTime()
        val allIntervals = intervalRepository.getAllIntervals().first()
        val intervalsById = allIntervals.associateBy { it.id }
        val prepared = allIntervals.mapNotNull { prepareInterval(it) }
        val n = prepared.size

        if (n == 0) {
            repeatedIntervalRepository.deleteAll()
            return@withContext
        }
        val tPrepared = System.nanoTime()

        // ─── Step 1a: build edge graph (qualifying pairs) ───
        val adjacency = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (IntervalSimilarity.qualifies(prepared[i].track, prepared[j].track)) {
                    adjacency[i].add(j)
                    adjacency[j].add(i)
                }
            }
        }

        // ─── Step 1b: connected components (single-linkage; no minimum group size) ───
        val componentId = IntArray(n) { -1 }
        var nextComponent = 0
        for (start in 0 until n) {
            if (componentId[start] != -1) continue
            val queue = ArrayDeque<Int>()
            queue.add(start)
            componentId[start] = nextComponent
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (neighbor in adjacency[node]) {
                    if (componentId[neighbor] == -1) {
                        componentId[neighbor] = nextComponent
                        queue.add(neighbor)
                    }
                }
            }
            nextComponent++
        }
        val components = Array(nextComponent) { mutableListOf<Int>() }
        for (i in 0 until n) components[componentId[i]].add(i)
        val tGraph = System.nanoTime()

        // ─── Step 1c: build edge geometry for each cluster via map-matching ───
        // Bin clusters into REGION_GROUP_CELL_M spatial cells, load the road graph once per cell as
        // a reusable MapMatcher.Region, and match every cluster in that cell against it — collapsing
        // a per-cluster DB query + RTree rebuild into one per cell. Cells too dense to index fall
        // back to per-track matchTrack. Results are written back in component order so the downstream
        // subset-discard and name assignment are identical to matching clusters one-by-one.
        val archetypeByComponent = arrayOfNulls<CandidateArchetype>(components.size)
        val refLat = prepared.map { it.track.minLat }.average()
        val latCell = REGION_GROUP_CELL_M / GeoUtils.METERS_PER_DEG_LAT
        val lonCell = REGION_GROUP_CELL_M / (GeoUtils.METERS_PER_DEG_LAT * cos(Math.toRadians(refLat)))
        val cellToComponents = HashMap<Long, MutableList<Int>>()
        for (c in components.indices) {
            val box = clusterBox(components[c], prepared) ?: continue
            val row = floor(((box[0] + box[2]) / 2) / latCell).toLong()
            val col = floor(((box[1] + box[3]) / 2) / lonCell).toLong()
            cellToComponents.getOrPut(row * 2_000_000L + col + 1_000_000L) { mutableListOf() }.add(c)
        }
        val latMargin = GeoUtils.metersToLat(INTERVAL_EDGE_SNAP_RADIUS_M)
        for (cellComponents in cellToComponents.values) {
            var nLat = Double.MAX_VALUE; var nLon = Double.MAX_VALUE
            var xLat = -Double.MAX_VALUE; var xLon = -Double.MAX_VALUE
            for (c in cellComponents) {
                val box = clusterBox(components[c], prepared) ?: continue
                nLat = min(nLat, box[0]); nLon = min(nLon, box[1])
                xLat = max(xLat, box[2]); xLon = max(xLon, box[3])
            }
            val lonMargin = GeoUtils.metersToLon(INTERVAL_EDGE_SNAP_RADIUS_M, (nLat + xLat) / 2)
            val region = mapMatcher.loadRegion(nLat - latMargin, nLon - lonMargin, xLat + latMargin, xLon + lonMargin)
            val match: suspend (List<List<Double>>) -> List<MapEdge>? =
                if (region != null) region::match else mapMatcher::matchTrack
            for (c in cellComponents) {
                val intervals = components[c].map { intervalsById.getValue(prepared[it].intervalId) }
                archetypeByComponent[c] = buildArchetype(intervals, match)
            }
        }
        val candidates = archetypeByComponent.filterNotNull()
        val tMatched = System.nanoTime()

        // ─── Step 2: discard archetypes that are mostly a subset of a longer archetype ───
        val finalArchetypes = discardSubsets(candidates)

        // ─── Step 3: preserve names by matching overlapping raw-interval-ID subsets ───
        val existing = repeatedIntervalRepository.getAllRepeatedIntervalsList()
        val newEntries = finalArchetypes.map { archetype ->
            val intervalIdSet = archetype.intervals.map { it.id }.toSet()
            val matchedExisting = existing.firstOrNull { existingEntry ->
                val existingSet = existingEntry.intervals.map { it.id }.toSet()
                existingSet.isNotEmpty() && existingSet.all { id -> id in intervalIdSet }
            }
            Triple(archetype, matchedExisting?.name ?: "", matchedExisting?.id ?: 0L)
        }

        var counter = 1
        val finalEntries = newEntries.map { (archetype, name, existingId) ->
            RepeatedInterval(
                id = existingId,
                name = if (name.isNotEmpty()) name else "Repeated Interval ${counter++}",
                intervals = archetype.intervals,
                edges = archetype.edges,
                startLat = archetype.startLat,
                startLon = archetype.startLon,
                endLat = archetype.endLat,
                endLon = archetype.endLon,
                distanceM = archetype.distanceM
            )
        }

        val matchedOldIds = newEntries.map { (_, _, id) -> id }.filter { it != 0L }.toSet()
        val toDelete = existing.map { it.id }.filter { it !in matchedOldIds }

        val tCompute = System.nanoTime()
        withContext(Dispatchers.IO) {
            repeatedIntervalRepository.deleteRepeatedIntervalsByIds(toDelete)
            for (entry in finalEntries) {
                repeatedIntervalRepository.saveRepeatedInterval(entry)
            }
        }

        if (PERF_LOGGING) {
            fun ms(from: Long, to: Long) = (to - from) / 1_000_000
            Log.i(
                TAG,
                "perf: n=$n clusters=$nextComponent archetypes=${finalArchetypes.size} | " +
                    "prepare=${ms(tStart, tPrepared)}ms graph=${ms(tPrepared, tGraph)}ms " +
                    "mapmatch=${ms(tGraph, tMatched)}ms postprocess=${ms(tMatched, tCompute)}ms " +
                    "persist=${ms(tCompute, System.nanoTime())}ms total=${ms(tStart, System.nanoTime())}ms"
            )
        }
    }

    /** Union bounding box `[minLat, minLon, maxLat, maxLon]` of a cluster's member tracks, or null if empty. */
    private fun clusterBox(indices: List<Int>, prepared: List<PreparedInterval>): DoubleArray? {
        if (indices.isEmpty()) return null
        var nLat = Double.MAX_VALUE; var nLon = Double.MAX_VALUE
        var xLat = -Double.MAX_VALUE; var xLon = -Double.MAX_VALUE
        for (i in indices) {
            val t = prepared[i].track
            nLat = min(nLat, t.minLat); nLon = min(nLon, t.minLon)
            xLat = max(xLat, t.maxLat); xLon = max(xLon, t.maxLon)
        }
        return doubleArrayOf(nLat, nLon, xLat, xLon)
    }

    private suspend fun buildArchetype(
        intervals: List<IntervalSession>,
        match: suspend (List<List<Double>>) -> List<MapEdge>?
    ): CandidateArchetype? {
        val sorted = intervals.sortedBy { it.distanceM }
        val medianIndex = sorted.size / 2
        val candidateOrder = sorted.indices.sortedBy { abs(it - medianIndex) }

        for (idx in candidateOrder) {
            val representative = sorted[idx]
            val track = parseGpsTrack(representative.gpsTrack)
            val edges = match(track) ?: continue
            val start = PolylineDecoder.decode(edges.first().geometryEncoded).firstOrNull() ?: continue
            val end = PolylineDecoder.decode(edges.last().geometryEncoded).lastOrNull() ?: continue
            return CandidateArchetype(
                intervals = intervals,
                edges = edges,
                distanceM = edges.sumOf { it.lengthM },
                startLat = start.latitude,
                startLon = start.longitude,
                endLat = end.latitude,
                endLon = end.longitude
            )
        }

        Log.w(TAG, "Could not map-match any representative for cluster of ${intervals.size}; dropping")
        return null
    }

    /**
     * Discards an archetype if the summed length of its edges that overlap with a longer
     * (already-kept) archetype's edges is ≥ [INTERVAL_SUBSET_OVERLAP_THRESHOLD] of its own
     * total [CandidateArchetype.distanceM].
     */
    private fun discardSubsets(archetypes: List<CandidateArchetype>): List<CandidateArchetype> {
        val byDescendingLength = archetypes.sortedByDescending { it.distanceM }
        val kept = mutableListOf<CandidateArchetype>()
        for (candidate in byDescendingLength) {
            if (candidate.distanceM <= 0.0) continue
            val isSubset = kept.any { longer ->
                val longerEdgeKeys = longer.edges.map { it.fromNode to it.toNode }.toSet()
                val sharedLength = candidate.edges
                    .filter { (it.fromNode to it.toNode) in longerEdgeKeys }
                    .sumOf { it.lengthM }
                sharedLength / candidate.distanceM >= INTERVAL_SUBSET_OVERLAP_THRESHOLD
            }
            if (!isSubset) kept.add(candidate)
        }
        return kept
    }

    private fun parseGpsTrack(json: String): List<List<Double>> {
        return try {
            gson.fromJson(json, trackType) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPS track JSON", e)
            emptyList()
        }
    }
}
