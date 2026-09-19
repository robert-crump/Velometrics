package com.velometrics.app.fakes

import com.velometrics.app.domain.service.RideClusterer

class FakeRideClusterer(override val name: String = "Fake") : RideClusterer {
    var runs = 0
        private set
    var failWith: Exception? = null

    override suspend fun recluster() {
        runs++
        failWith?.let { throw it }
    }
}
