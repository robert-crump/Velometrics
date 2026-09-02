package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.util.CyclingConstants.INTERVAL_EDGE_SNAP_RADIUS_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_MERGE_LENGTH_TOLERANCE_FLOOR_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_MERGE_LENGTH_TOLERANCE_PCT
import com.velometrics.app.util.CyclingConstants.INTERVAL_POINT_MATCH_RADIUS_M
import com.velometrics.app.util.CyclingConstants.INTERVAL_SUBSET_OVERLAP_THRESHOLD
import com.velometrics.app.util.GeoUtils
import com.velometrics.app.util.GraphUtils
import com.velometrics.app.util.JsonSafeParser
import com.velometrics.app.util.PolylineDecoder
import com.velometrics.app.util.SpatialPointGrid
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
 * geometry, then merges archetypes whose edges are mostly a subset of a longer, length-similar
 * archetype's into that archetype — folding in their raw intervals rather than discarding them,
 * so a route that clustered into two near-duplicate archetypes (map-matching noise, a GPS
 * track that just misses the point-overlap threshold) still counts as one repeated interval,
 * while a deliberately different-length variant of the same road (a short version of a climb vs.
 * one extended onto a follow-up road) stays a separate archetype. The subset test itself has two
 * independent ways to qualify (#176): matching map-graph edge identity, or — when that finds no
 * shared edges, e.g. the same ride recorded once on a road and once on its adjacent, separately
 * mapped bike lane/cycleway — matching edge *geometry* closely enough instead, so a merge candidate
 * doesn't need to have snapped onto the exact same road-graph edges as the archetype it duplicates.
 */
@Singleton
class IntervalClusteringService @Inject constructor(
    private val intervalRepository: IntervalRepository,
    private val repeatedIntervalRepository: RepeatedIntervalRepository,
    private val mapMatcher: MapMatcher
) {
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
        val components = GraphUtils.connectedComponents(n, adjacency)
        val tGraph = System.nanoTime()

        // ─── Step 1c: build edge geometry for each cluster via map-matching ───
        // Bin clusters into REGION_GROUP_CELL_M spatial cells, load the road graph once per cell as
        // a reusable MapMatcher.Region, and match every cluster in that cell against it — collapsing
        // a per-cluster DB query + RTree rebuild into one per cell. Cells too dense to index fall
        // back to per-track matchTrack. Results are written back in component order so the downstream
        // subset-discard and name assignment are identical to matching clusters one-by-one.
        val archetypeByComponent = arrayOfNulls<CandidateArchetype>(components.size)
        // Every index gets filled below (buildArchetype always returns; clusterBox is only null for
        // an empty component, which connectedComponents never produces) — filterNotNull afterwards
        // is just a defensive net, not a normal path.
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

        // ─── Step 2: merge archetypes that are mostly a subset of a longer, length-similar archetype ───
        val finalArchetypes = mergeSimilarArchetypes(candidates)

        // ─── Step 3: preserve names by matching overlapping raw-interval-ID subsets ───
        // Since Step 2 can now fold two previously-separate archetypes into one, more than one
        // existing entry may qualify as "a subset of" the same new archetype (e.g. a curated
        // "Schlangenweg" and an auto-numbered near-duplicate that just got merged into it). Picking
        // by most prior intervals (ties broken by lowest id, for determinism) favors the
        // established archetype's name/id over the newly-absorbed one's.
        val existing = repeatedIntervalRepository.getAllRepeatedIntervalsList()
        val newEntries = finalArchetypes.map { archetype ->
            val intervalIdSet = archetype.intervals.map { it.id }.toSet()
            val matchedExisting = existing
                .filter { existingEntry ->
                    val existingSet = existingEntry.intervals.map { it.id }.toSet()
                    existingSet.isNotEmpty() && existingSet.all { id -> id in intervalIdSet }
                }
                .maxWithOrNull(compareBy({ it.intervals.size }, { -it.id }))
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
                "perf: n=$n clusters=${components.size} archetypes=${finalArchetypes.size} | " +
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
    ): CandidateArchetype {
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

        // Map-matching failed for every representative (e.g. the matched sequence collapses to a
        // single leaf-pruned edge, or the road graph has a gap here). Falling through to `null`
        // would silently drop this entire cluster — including intervals that qualified by
        // confident GPS similarity — so fall back to a GPS-only archetype instead (#175): no
        // road-matched edge geometry, but still saved, named, and shown, using the median-length
        // member's own recorded endpoints/distance.
        Log.w(TAG, "Could not map-match any representative for cluster of ${intervals.size}; using GPS-only fallback")
        val representative = sorted[medianIndex]
        return CandidateArchetype(
            intervals = intervals,
            edges = emptyList(),
            distanceM = representative.distanceM,
            startLat = representative.startLat,
            startLon = representative.startLon,
            endLat = representative.endLat,
            endLon = representative.endLon
        )
    }

    /**
     * Folds an archetype into a longer, length-similar (already-kept) archetype it's mostly a
     * subset of, instead of keeping it as a separate entry. Merges when, against some already-kept
     * `longer`, BOTH hold:
     *  - length gate: `|longer.distanceM - candidate.distanceM| <= max(
     *    [INTERVAL_MERGE_LENGTH_TOLERANCE_FLOOR_M], [INTERVAL_MERGE_LENGTH_TOLERANCE_PCT] *
     *    max(longer.distanceM, candidate.distanceM))` — looser than the raw-interval tolerance
     *    ([IntervalSimilarity.qualifies]) since these are two already-map-matched archetypes, not
     *    noisy GPS; scaled by length so short routes aren't merged too liberally. This is what
     *    keeps a deliberately different-length variant of the same road (e.g. a short version of a
     *    climb vs. one extended much further onto a follow-up road) from merging — those differ
     *    well beyond the tolerance and stay separate archetypes.
     *  - overlap gate: EITHER of —
     *    - edge-key overlap: the summed length of `candidate`'s edges that also appear in
     *      `longer`'s edges (by `(fromNode, toNode)` identity) is ≥
     *      [INTERVAL_SUBSET_OVERLAP_THRESHOLD] of `candidate`'s own total
     *      [CandidateArchetype.distanceM] — i.e. `candidate` snapped onto basically the same
     *      road-graph edges as `longer`. A route that shares only a common start with `longer`
     *      before diverging onto a different road (a fork, not a shorter/longer variant of the
     *      same road) fails this even when lengths are close.
     *    - geometric overlap (#176): ≥ [INTERVAL_SUBSET_OVERLAP_THRESHOLD] of `candidate`'s
     *      decoded edge points have a neighbor within [INTERVAL_POINT_MATCH_RADIUS_M] on
     *      `longer`'s decoded edge points ([geometricOverlapRatio]) — catches the same physical
     *      ride recorded on a parallel piece of infrastructure (most commonly a road vs. the
     *      bike lane/cycleway running alongside it), which the road graph represents as distinct
     *      edges with zero shared keys no matter how close the two lines actually run. Only
     *      evaluated when the edge-key test doesn't already qualify (short-circuit — decoding
     *      polylines is the pricier check), and only an *additional* way to pass, not a
     *      replacement: real edge-key overlap still qualifies on its own as before. Known
     *      limitation: this is a proximity test, so a frontage road, a genuinely distinct parallel
     *      minor road, or a divided carriageway's two directions could also pass it if within
     *      range and length-similar — accepted for now, same as the length gate's own tolerance
     *      is a judgment call, revisit if false merges show up in practice.
     *    Failing pairs where exactly one gate passed are logged as a near-miss below to make
     *    tuning debuggable.
     *
     * The candidate's raw intervals are appended to the matched archetype's; its own edges/name/
     * geometry are discarded in favor of the (longer, already-kept) archetype's.
     */
    private fun mergeSimilarArchetypes(archetypes: List<CandidateArchetype>): List<CandidateArchetype> {
        val byDescendingLength = archetypes.sortedByDescending { it.distanceM }
        val kept = mutableListOf<CandidateArchetype>()
        for (candidate in byDescendingLength) {
            if (candidate.distanceM <= 0.0) continue
            val mergeTargetIndex = kept.indexOfFirst { longer -> mergeQualifies(candidate, longer, logNearMisses = true) }
            if (mergeTargetIndex >= 0) {
                val target = kept[mergeTargetIndex]
                kept[mergeTargetIndex] = target.copy(intervals = target.intervals + candidate.intervals)
            } else {
                kept.add(candidate)
            }
        }
        return kept
    }

    /** True if [candidate] should merge into [longer]; when [logNearMisses], logs pairs where exactly one gate passed. */
    private fun mergeQualifies(candidate: CandidateArchetype, longer: CandidateArchetype, logNearMisses: Boolean): Boolean {
        val lengthDelta = abs(longer.distanceM - candidate.distanceM)
        val lengthTolerance = max(
            INTERVAL_MERGE_LENGTH_TOLERANCE_FLOOR_M,
            INTERVAL_MERGE_LENGTH_TOLERANCE_PCT * max(longer.distanceM, candidate.distanceM)
        )
        val lengthOk = lengthDelta <= lengthTolerance

        val longerEdgeKeys = longer.edges.map { it.fromNode to it.toNode }.toSet()
        val sharedLength = candidate.edges
            .filter { (it.fromNode to it.toNode) in longerEdgeKeys }
            .sumOf { it.lengthM }
        val edgeOverlapRatio = if (candidate.distanceM > 0.0) sharedLength / candidate.distanceM else 0.0
        val edgeOverlapOk = edgeOverlapRatio >= INTERVAL_SUBSET_OVERLAP_THRESHOLD

        // Geometric fallback (#176) only runs on an edge-key near-miss — decoding polylines is the
        // pricier check, and a real edge-key overlap already settles the gate on its own.
        val geometricOverlapRatio = if (edgeOverlapOk) null else geometricOverlapRatio(candidate, longer)
        val overlapOk = edgeOverlapOk || (geometricOverlapRatio != null && geometricOverlapRatio >= INTERVAL_SUBSET_OVERLAP_THRESHOLD)

        if (logNearMisses && lengthOk != overlapOk) {
            Log.d(
                TAG,
                "merge near-miss: candidate(${candidate.distanceM.toInt()}m, ids=${candidate.intervals.map { it.id }}) " +
                    "vs longer(${longer.distanceM.toInt()}m) | lengthDelta=${lengthDelta.toInt()}m " +
                    "tolerance=${lengthTolerance.toInt()}m lengthOk=$lengthOk | " +
                    "edgeOverlap=${"%.2f".format(edgeOverlapRatio)} " +
                    "geometricOverlap=${geometricOverlapRatio?.let { "%.2f".format(it) } ?: "skipped"} " +
                    "threshold=$INTERVAL_SUBSET_OVERLAP_THRESHOLD overlapOk=$overlapOk"
            )
        }
        return lengthOk && overlapOk
    }

    /**
     * Fraction of `candidate`'s decoded edge points that have a neighbor within
     * [INTERVAL_POINT_MATCH_RADIUS_M] among `longer`'s decoded edge points — the same
     * spatial-coverage approach [IntervalSimilarity] applies to raw GPS tracks, applied instead to
     * archetypes' map-matched edge geometry (#176), so a road-edge recording and a geometrically
     * near-identical bike-lane/cycleway recording of the same ride can still qualify as a subset
     * even though the road graph gives them no shared `(fromNode, toNode)` keys. One-directional
     * (candidate → longer only), matching the edge-key overlap test's own asymmetry: the gate asks
     * whether `candidate` is contained in `longer`, not whether the two are mutually similar.
     * Each [MapEdge] carries its own polyline ([MapEdge.geometryEncoded]), so no road-graph lookup
     * is needed to decode either side. Returns 0.0 if either archetype has no edges (e.g. a
     * GPS-only fallback archetype from #175) — same "never qualifies" behavior the edge-key test
     * already has for that case.
     */
    private fun geometricOverlapRatio(candidate: CandidateArchetype, longer: CandidateArchetype): Double {
        val candidatePoints = candidate.edges.flatMap { edge ->
            PolylineDecoder.decode(edge.geometryEncoded).map { listOf(it.latitude, it.longitude) }
        }
        if (candidatePoints.isEmpty()) return 0.0
        val longerPoints = longer.edges.flatMap { edge ->
            PolylineDecoder.decode(edge.geometryEncoded).map { listOf(it.latitude, it.longitude) }
        }
        if (longerPoints.isEmpty()) return 0.0

        val grid = SpatialPointGrid(longerPoints, INTERVAL_POINT_MATCH_RADIUS_M)
        val matched = candidatePoints.count { grid.hasPointWithin(it[0], it[1]) }
        return matched.toDouble() / candidatePoints.size
    }

    private fun parseGpsTrack(json: String): List<List<Double>> =
        JsonSafeParser.parseOrDefault<List<List<Double>>>(json, TAG, "Failed to parse GPS track JSON", emptyList())
}
