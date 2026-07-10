package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.Corridor
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.util.GeoUtils
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Shape gate thresholds (issue #133): a candidate whose refined route meets both is preferred
 * over any higher-reward candidate that doesn't. Calibrated per issue #135 against the production
 * graph's 20/50/80km x {NONE,N,E,S,W} matrix, judged perceptually by the maintainer against the
 * rendered routes: the 3 runs rated "reads as an oval" (d50km_east 0.29, d50km_south 0.28,
 * d80km_west 0.30) and the runs rated "too many inner loops" (worst offender d80km_north at 0.24)
 * split cleanly around compactness ~0.24-0.28, so 0.26 sits centered in that gap with symmetric
 * margin. repeatFraction stayed uniformly low (0.00-0.04) across both good and bad shapes in that
 * matrix — it wasn't a discriminator for the "inner loops" failure mode, so 0.15 (from #133) is
 * left as-is; the compactness floor is doing the calibration work here.
 */
data class RouteShapeGateConfig(
    val minCompactness: Double = 0.26,
    val maxRepeatFraction: Double = 0.15,
)

data class GeneratorConfig(
    val orienteerConfig: OrienteerConfig = OrienteerConfig(),
    val refinerConfig: RefinerConfig = RefinerConfig(),
    val degradationConfig: DegradationConfig = DegradationConfig(),
    val exitLegConfig: ExitLegConfig = ExitLegConfig(),
    val shapeConfig: RouteShapeGateConfig = RouteShapeGateConfig(),
    val direction: RideDirection? = null,
    val seed: Long = System.currentTimeMillis(),
)

data class RankedCandidate(
    val refinedRoute: RefinedRoute,
    val coarseLoop: CandidateLoop,
    val rank: Int,
    val distanceDeviationPercent: Double,
    val corridorEdges: List<MapEdge>,
)

data class ExitLegPlan(
    val exitLeg: ExitLeg,
    val exitCorridorId: Long,
    val adjustedTargetM: Double,
    val estimatedReturnDistM: Double,
)

/** A refined (and possibly stitched) candidate together with its route-shape scoring (issue #133). */
internal data class RefinedCandidate(
    val coarseLoop: CandidateLoop,
    val refinedRoute: RefinedRoute,
    val shapeReport: RouteShapeReport,
)

sealed interface RoutePlanResult {
    data class Success(
        val candidate: RankedCandidate,
        val appliedTier: DegradationPolicy.RelaxationTier,
    ) : RoutePlanResult

    data class Failure(
        val reason: String,
    ) : RoutePlanResult
}

object RouteGenerator {

    private const val TAG = "RouteGenerator"
    private const val REFINED_BUFFER_FACTOR = 2
    private const val REFINEMENT_CANDIDATE_MULTIPLIER = 5
    internal const val ROAD_DISTANCE_FACTOR = 1.3
    internal const val MIN_CORRIDOR_BUDGET_FRACTION = 0.3

    /**
     * Coarse connector estimates (haversine x [ROAD_DISTANCE_FACTOR]) vs. real refined distance
     * can diverge by 40-70% on winding road networks. Outside this tolerance, [calibrateTarget]
     * rescales the coarse search's target once so the *real* distance lands near the request.
     */
    internal const val CALIBRATION_TOLERANCE = 0.15

    suspend fun generate(
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        repository: MapGraphRepository,
        weights: RewardWeights = RewardWeights(),
        rewardContext: RewardContext = RewardContext(),
        config: GeneratorConfig = GeneratorConfig(),
    ): RoutePlanResult {
        val genStart = System.currentTimeMillis()
        Log.d(TAG, "generate: start targetDistance=${targetDistanceM.toInt()}m direction=${config.direction}")

        val corridors = repository.getAllCorridors()
        val connectors = repository.getAllCorridorConnectors()
        Log.d(TAG, "generate: loaded ${corridors.size} corridors, ${connectors.size} connectors in ${System.currentTimeMillis() - genStart}ms")

        if (corridors.isEmpty()) {
            return RoutePlanResult.Failure(
                "No corridor data available. Check that your home is set near mapped cycling roads.",
            )
        }

        val corridorMap = corridors.associateBy { it.id }

        val exitPlan = planExitLeg(
            homeLat, homeLon, targetDistanceM, config.direction,
            corridors, repository, config.exitLegConfig,
        )
        var effectiveTargetM = exitPlan?.adjustedTargetM ?: targetDistanceM
        Log.d(TAG, "generate: exitPlan=${if (exitPlan != null) "exitCorridor=${exitPlan.exitCorridorId} exitDist=${exitPlan.exitLeg.distanceM.toInt()}m estReturn=${exitPlan.estimatedReturnDistM.toInt()}m adjustedTarget=${exitPlan.adjustedTargetM.toInt()}m" else "null (fallback to home)"}")

        val maxCandidates = config.orienteerConfig.candidateCount
        var currentTier = DegradationPolicy.RelaxationTier.NONE
        val allRefined = mutableListOf<RefinedCandidate>()
        var calibrationDone = false

        suspend fun runCoarseSearch(target: Double, orienteerConfig: OrienteerConfig): List<CandidateLoop> =
            CorridorOrienteer.search(
                corridors, homeLat, homeLon,
                target,
                weights, orienteerConfig,
                config.direction,
                startCorridorId = exitPlan?.exitCorridorId,
                nodeResolver = { ids ->
                    if (ids.isEmpty()) {
                        emptyMap()
                    } else {
                        repository.getNodesByIds(*ids.toLongArray())
                            .associate { it.id to (it.lat to it.lon) }
                    }
                },
                edgeResolver = { minLat, minLon, maxLat, maxLon ->
                    repository.getEdgesNear(minLat, minLon, maxLat, maxLon)
                },
            )

        while (true) {
            coroutineContext.ensureActive()
            val tierStart = System.currentTimeMillis()
            Log.d(TAG, "generate: tier=$currentTier starting")

            val tierParams = DegradationPolicy.tierParams(currentTier, config.degradationConfig)

            val refinementCount = maxCandidates * REFINEMENT_CANDIDATE_MULTIPLIER
            val tierOrienteerConfig = config.orienteerConfig.copy(
                candidateCount = refinementCount,
                reachFraction = tierParams.reachFraction,
                separationM = tierParams.separationM,
                headingConeCosine = tierParams.headingConeCosine,
            )

            val coarseStart = System.currentTimeMillis()
            var coarseCandidates = runCoarseSearch(effectiveTargetM, tierOrienteerConfig)
            Log.d(TAG, "generate: coarse search found ${coarseCandidates.size} candidates in ${System.currentTimeMillis() - coarseStart}ms")

            if (!calibrationDone) {
                val calibration = calibrateTarget(
                    coarseCandidates, corridorMap, repository, config.refinerConfig,
                    closeLoop = exitPlan == null, currentTargetM = effectiveTargetM,
                )
                if (calibration.attempted) {
                    calibrationDone = true
                    Log.d(TAG, "generate: calibration ratio=${calibration.ratio} rescaled=${calibration.rescaledTargetM != null}")
                    if (calibration.rescaledTargetM != null) {
                        Log.d(
                            TAG,
                            "generate: calibration rescaling target " +
                                "${effectiveTargetM.toInt()}m -> ${calibration.rescaledTargetM.toInt()}m",
                        )
                        effectiveTargetM = calibration.rescaledTargetM
                        coarseCandidates = runCoarseSearch(effectiveTargetM, tierOrienteerConfig)
                        Log.d(TAG, "generate: recalibrated coarse search found ${coarseCandidates.size} candidates")
                    }
                }
            }

            for ((idx, candidate) in coarseCandidates.withIndex()) {
                if (allRefined.any { it.coarseLoop.corridors == candidate.corridors }) continue

                // Pseudo-corridors synthesized for empty quadrants are not in the repository-backed
                // map, so merge them in before refining/stitching this candidate.
                val effectiveCorridorMap = if (candidate.syntheticCorridors.isEmpty()) {
                    corridorMap
                } else {
                    corridorMap + candidate.syntheticCorridors
                }

                val refineStart = System.currentTimeMillis()
                val refined = RouteRefiner.refine(
                    candidate, effectiveCorridorMap, repository,
                    config.refinerConfig,
                    closeLoop = exitPlan == null,
                )
                if (refined == null) {
                    Log.d(TAG, "generate: refine candidate[$idx] corridors=${candidate.corridors.size} -> null in ${System.currentTimeMillis() - refineStart}ms")
                    continue
                }

                val finalRoute = if (exitPlan != null) {
                    stitchRoute(
                        exitPlan.exitLeg, refined, candidate, effectiveCorridorMap,
                        homeLat, homeLon, repository, config.exitLegConfig,
                    )
                } else {
                    refined
                }

                val nodeCoords = resolveNodeCoords(repository, finalRoute.edges)
                val shapeReport = RouteShapeMetrics.evaluate(finalRoute.edges, nodeCoords)

                Log.d(TAG, "generate: refine candidate[$idx] corridors=${candidate.corridors.size} -> ${finalRoute.edges.size} edges, ${finalRoute.actualDistanceM.toInt()}m${if (exitPlan != null) " (stitched)" else ""} compact=${"%.2f".format(shapeReport.compactness)} repeat=${"%.2f".format(shapeReport.repeatFraction)} in ${System.currentTimeMillis() - refineStart}ms")
                allRefined.add(RefinedCandidate(candidate, finalRoute, shapeReport))
            }

            val band = tierParams.distanceBandFraction
            trimRefined(allRefined, maxCandidates * REFINED_BUFFER_FACTOR, targetDistanceM, band, config.shapeConfig)

            val refinedDistances = allRefined.map { it.refinedRoute.actualDistanceM }
            val maxReachable = estimateMaxReachable(corridors, connectors)

            val bandLo = (targetDistanceM * (1.0 - band) / 1000.0).toInt()
            val bandHi = (targetDistanceM * (1.0 + band) / 1000.0).toInt()
            val distSummary = if (refinedDistances.isEmpty()) "none" else
                refinedDistances.joinToString { d ->
                    val km = (d / 1000.0).toInt()
                    if (d in targetDistanceM * (1.0 - band)..targetDistanceM * (1.0 + band)) "${km}km✓" else "${km}km✗"
                }
            Log.d(TAG, "generate: tier=$currentTier band=[${bandLo}km,${bandHi}km] distances=[$distSummary]")
            val shapeSummary = allRefined.joinToString {
                "${(it.refinedRoute.actualDistanceM / 1000.0).toInt()}km" +
                    " compact=${"%.2f".format(it.shapeReport.compactness)}" +
                    " repeat=${"%.2f".format(it.shapeReport.repeatFraction)}" +
                    " gate=${passesShapeGate(it.shapeReport, config.shapeConfig)}"
            }
            Log.d(TAG, "generate: tier=$currentTier shapes=[$shapeSummary]")

            val outcome = DegradationPolicy.evaluate(
                refinedDistances, targetDistanceM, currentTier,
                maxReachable, config.degradationConfig,
            )
            Log.d(TAG, "generate: tier=$currentTier outcome=${outcome::class.simpleName} refined=${allRefined.size} tierTime=${System.currentTimeMillis() - tierStart}ms")

            when (outcome) {
                is DegradationPolicy.EvaluationOutcome.Sufficient -> {
                    val band = tierParams.distanceBandFraction
                    val winner = selectWinner(allRefined, targetDistanceM, band, config.shapeConfig)
                        ?: continue
                    val coarse = winner.coarseLoop
                    val refined = winner.refinedRoute
                    Log.d(
                        TAG,
                        "generate: selected ${(refined.actualDistanceM / 1000.0).toInt()}km " +
                            "compact=${"%.2f".format(winner.shapeReport.compactness)} " +
                            "repeat=${"%.2f".format(winner.shapeReport.repeatFraction)} " +
                            "gate=${passesShapeGate(winner.shapeReport, config.shapeConfig)}",
                    )
                    val deviation = if (targetDistanceM > 0) {
                        (refined.actualDistanceM - targetDistanceM) / targetDistanceM * 100.0
                    } else {
                        0.0
                    }
                    val effectiveMap = if (coarse.syntheticCorridors.isEmpty()) corridorMap
                        else corridorMap + coarse.syntheticCorridors
                    val corridorEdgePairs = coarse.corridors
                        .mapNotNull { effectiveMap[it] }
                        .flatMapTo(HashSet()) { c -> c.edgeList }
                    val corridorEdges = refined.edges.filter { (it.fromNode to it.toNode) in corridorEdgePairs }
                    Log.d(TAG, "generate: done in ${System.currentTimeMillis() - genStart}ms, corridorEdges=${corridorEdges.size}")
                    return RoutePlanResult.Success(
                        RankedCandidate(refined, coarse, 1, deviation, corridorEdges),
                        outcome.appliedTier,
                    )
                }

                is DegradationPolicy.EvaluationOutcome.NeedsRelaxation -> {
                    currentTier = outcome.nextTier
                }

                is DegradationPolicy.EvaluationOutcome.HardFailure -> {
                    Log.d(TAG, "generate: hard failure in ${System.currentTimeMillis() - genStart}ms: ${outcome.reason}")
                    return RoutePlanResult.Failure(outcome.reason)
                }
            }
        }
    }

    internal data class CalibrationOutcome(
        /** True once a candidate was actually refined and its ratio measured, win or lose. */
        val attempted: Boolean,
        val ratio: Double? = null,
        val rescaledTargetM: Double? = null,
    )

    /**
     * Refines the first coarse candidate that resolves and compares its actual (real-road)
     * distance against the coarse plan's straight-line-derived estimate. Road networks that wind
     * more than the coarse haversine*[ROAD_DISTANCE_FACTOR] estimate assumes push that ratio above
     * [CALIBRATION_TOLERANCE]; rescaling the coarse target by the inverse ratio before refining the
     * rest compensates, since the same road network inflates the rescaled search's plan by roughly
     * the same ratio, landing the real distance back near the original request.
     */
    private suspend fun calibrateTarget(
        coarseCandidates: List<CandidateLoop>,
        corridorMap: Map<Long, Corridor>,
        repository: MapGraphRepository,
        refinerConfig: RefinerConfig,
        closeLoop: Boolean,
        currentTargetM: Double,
    ): CalibrationOutcome {
        for (candidate in coarseCandidates) {
            if (candidate.totalDistanceM <= 0.0) continue
            val effectiveCorridorMap = if (candidate.syntheticCorridors.isEmpty()) {
                corridorMap
            } else {
                corridorMap + candidate.syntheticCorridors
            }
            val refined = RouteRefiner.refine(candidate, effectiveCorridorMap, repository, refinerConfig, closeLoop)
                ?: continue

            val ratio = refined.actualDistanceM / candidate.totalDistanceM
            val outOfTolerance = ratio < 1.0 - CALIBRATION_TOLERANCE || ratio > 1.0 + CALIBRATION_TOLERANCE
            return CalibrationOutcome(
                attempted = true,
                ratio = ratio,
                rescaledTargetM = if (outOfTolerance) currentTargetM / ratio else null,
            )
        }
        return CalibrationOutcome(attempted = false)
    }

    internal suspend fun planExitLeg(
        homeLat: Double,
        homeLon: Double,
        targetDistanceM: Double,
        direction: RideDirection?,
        corridors: List<Corridor>,
        repository: MapGraphRepository,
        config: ExitLegConfig,
    ): ExitLegPlan? {
        val exitLeg = ExitLegPlanner.computeExitLeg(
            homeLat, homeLon, direction, corridors, targetDistanceM, repository, config,
        ) ?: return null

        val exitCorridor = corridors.firstOrNull { it.entryNode == exitLeg.targetNode }
            ?: return null

        val homeCorridor = CorridorOrienteer.findNearestCorridor(corridors, homeLat, homeLon)
        if (homeCorridor != null && exitCorridor.id == homeCorridor.id) {
            Log.d(TAG, "planExitLeg: exit corridor is home corridor, skipping exit leg")
            return null
        }

        val estimatedReturnDistM = GeoUtils.haversineDistance(
            exitCorridor.centroidLat, exitCorridor.centroidLon, homeLat, homeLon,
        ) * ROAD_DISTANCE_FACTOR

        val adjustedTargetM = targetDistanceM - exitLeg.distanceM - estimatedReturnDistM
        if (adjustedTargetM < targetDistanceM * MIN_CORRIDOR_BUDGET_FRACTION) {
            Log.d(TAG, "planExitLeg: adjusted budget ${adjustedTargetM.toInt()}m < ${(targetDistanceM * MIN_CORRIDOR_BUDGET_FRACTION).toInt()}m min, skipping exit leg")
            return null
        }

        return ExitLegPlan(exitLeg, exitCorridor.id, adjustedTargetM, estimatedReturnDistM)
    }

    private suspend fun stitchRoute(
        exitLeg: ExitLeg,
        corridorRoute: RefinedRoute,
        candidate: CandidateLoop,
        corridorMap: Map<Long, Corridor>,
        homeLat: Double,
        homeLon: Double,
        repository: MapGraphRepository,
        exitLegConfig: ExitLegConfig,
    ): RefinedRoute {
        val lastCorridorId = candidate.corridors.last()
        val lastCorridor = corridorMap[lastCorridorId]
        val returnLeg = if (lastCorridor != null) {
            ExitLegPlanner.computeReturnLeg(
                lastCorridor.exitNode,
                lastCorridor.centroidLat, lastCorridor.centroidLon,
                homeLat, homeLon,
                repository, exitLegConfig,
                avoidNodePairs = ExitLegPlanner.nodePairsOf(exitLeg.edges),
            )
        } else {
            null
        }
        if (returnLeg != null) {
            Log.d(TAG, "stitchRoute: return leg ${returnLeg.edges.size} edges, ${returnLeg.distanceM.toInt()}m from corridor $lastCorridorId")
        } else {
            Log.d(TAG, "stitchRoute: no return leg from corridor $lastCorridorId")
        }

        val edges = exitLeg.edges + corridorRoute.edges + (returnLeg?.edges ?: emptyList())
        val distance = exitLeg.distanceM + corridorRoute.actualDistanceM + (returnLeg?.distanceM ?: 0.0)
        return RefinedRoute(edges, distance)
    }

    /** True when a refined route's shape clears both #133 thresholds. */
    internal fun passesShapeGate(report: RouteShapeReport, config: RouteShapeGateConfig): Boolean =
        report.compactness >= config.minCompactness && report.repeatFraction <= config.maxRepeatFraction

    /** Composite ranking used only to pick among candidates that fail the shape gate. */
    private fun shapeScore(report: RouteShapeReport): Double = report.compactness - report.repeatFraction

    /**
     * Winner among in-band candidates: prefers shape-gate-passing candidates (ties broken by
     * reward), falling back to the best-shaped in-band candidate when none pass (issue #133).
     * Null when no candidate is in-band.
     */
    internal fun selectWinner(
        candidates: List<RefinedCandidate>,
        targetDistanceM: Double,
        bandFraction: Double,
        shapeConfig: RouteShapeGateConfig,
    ): RefinedCandidate? {
        val lo = targetDistanceM * (1.0 - bandFraction)
        val hi = targetDistanceM * (1.0 + bandFraction)
        val inBand = candidates.filter { it.refinedRoute.actualDistanceM in lo..hi }
        if (inBand.isEmpty()) return null
        val gatePassing = inBand.filter { passesShapeGate(it.shapeReport, shapeConfig) }
        return if (gatePassing.isNotEmpty()) {
            gatePassing.maxByOrNull { it.coarseLoop.totalReward }
        } else {
            inBand.maxByOrNull { shapeScore(it.shapeReport) }
        }
    }

    /**
     * In-band routes are kept before out-of-band routes; within each group, shape-gate-passing
     * candidates are kept before failing ones; within each of those groups, higher reward wins
     * (issue #133 — a relaxed tier's trim can no longer discard the only shapely candidate in
     * favor of two higher-reward lollipops).
     */
    internal fun trimRefined(
        refined: MutableList<RefinedCandidate>,
        maxKeep: Int,
        targetDistanceM: Double,
        bandFraction: Double,
        shapeConfig: RouteShapeGateConfig,
    ) {
        if (refined.size <= maxKeep) return
        val lo = targetDistanceM * (1.0 - bandFraction)
        val hi = targetDistanceM * (1.0 + bandFraction)
        refined.sortWith(
            compareByDescending<RefinedCandidate> { it.refinedRoute.actualDistanceM in lo..hi }
                .thenByDescending { passesShapeGate(it.shapeReport, shapeConfig) }
                .thenByDescending { it.coarseLoop.totalReward },
        )
        while (refined.size > maxKeep) {
            refined.removeAt(refined.lastIndex)
        }
    }

    private suspend fun resolveNodeCoords(
        repository: MapGraphRepository,
        edges: List<MapEdge>,
    ): Map<Long, Pair<Double, Double>> {
        val ids = edges.flatMapTo(LinkedHashSet()) { listOf(it.fromNode, it.toNode) }
        if (ids.isEmpty()) return emptyMap()
        return repository.getNodesByIds(*ids.toLongArray()).associate { it.id to (it.lat to it.lon) }
    }

    internal fun estimateMaxReachable(
        corridors: List<Corridor>,
        connectors: List<com.velometrics.app.domain.model.CorridorConnector>,
    ): Double? {
        if (corridors.isEmpty() || connectors.isEmpty()) return null
        val totalCorridorLength = corridors.sumOf { it.lengthM }
        val avgConnectorDistance = connectors.map { it.distanceM }.average()
        return totalCorridorLength + avgConnectorDistance * corridors.size
    }
}
