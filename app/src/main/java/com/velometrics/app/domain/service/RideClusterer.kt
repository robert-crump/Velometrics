package com.velometrics.app.domain.service

import javax.inject.Inject

/**
 * One family of clustering that must be refreshed after rides are added. Bound `@IntoSet` in
 * `ClusteringModule`; [RideLifecycle] runs every bound clusterer, isolating failures per clusterer.
 */
interface RideClusterer {
    val name: String
    suspend fun recluster()
}

class RouteClusterer @Inject constructor(
    private val service: RouteClusteringService
) : RideClusterer {
    override val name = "Route"
    override suspend fun recluster() {
        service.runClustering()
    }
}

class IntervalClusterer @Inject constructor(
    private val service: IntervalClusteringService
) : RideClusterer {
    override val name = "Interval"
    override suspend fun recluster() {
        service.runClustering()
    }
}
