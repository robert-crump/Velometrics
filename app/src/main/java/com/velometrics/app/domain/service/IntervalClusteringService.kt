package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.util.CyclingConstants.INTERVAL_SUBSET_OVERLAP_THRESHOLD
import com.velometrics.app.util.PolylineDecoder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs
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
        val allIntervals = intervalRepository.getAllIntervals().first()
        val intervalsById = allIntervals.associateBy { it.id }
        val prepared = allIntervals.mapNotNull { prepareInterval(it) }
        val n = prepared.size

        if (n == 0) {
            repeatedIntervalRepository.deleteAll()
            return@withContext
        }

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

        // ─── Step 1c: build edge geometry for each cluster via map-matching ───
        val candidates = components.mapNotNull { indices ->
            buildArchetype(indices.map { intervalsById.getValue(prepared[it].intervalId) })
        }

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

        withContext(Dispatchers.IO) {
            repeatedIntervalRepository.deleteRepeatedIntervalsByIds(toDelete)
            for (entry in finalEntries) {
                repeatedIntervalRepository.saveRepeatedInterval(entry)
            }
        }
    }

    private suspend fun buildArchetype(intervals: List<IntervalSession>): CandidateArchetype? {
        val sorted = intervals.sortedBy { it.distanceM }
        val medianIndex = sorted.size / 2
        val candidateOrder = sorted.indices.sortedBy { abs(it - medianIndex) }

        for (idx in candidateOrder) {
            val representative = sorted[idx]
            val track = parseGpsTrack(representative.gpsTrack)
            val edges = mapMatcher.matchTrack(track) ?: continue
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
