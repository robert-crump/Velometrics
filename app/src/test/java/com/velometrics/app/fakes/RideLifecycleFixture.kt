package com.velometrics.app.fakes

import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import com.velometrics.app.domain.service.RideLifecycle
import com.velometrics.app.domain.service.RideLifecycleImpl
import com.velometrics.app.domain.service.RideRevealEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Builds a [RideLifecycleImpl] over fakes, exposing each fake for arranging and asserting. */
class RideLifecycleFixture(
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
) {
    val sessions = FakeCyclingSessionRepository()
    val cursor = FakeDropboxSyncCursorRepository()
    val importer = FakeFitImportService(sessions)
    val routeClusterer = FakeRideClusterer("Route")
    val intervalClusterer = FakeRideClusterer("Interval")
    val revealEvaluator = RideRevealEvaluator(sessions, emptySet())

    fun lifecycle(
        sessionRepository: CyclingSessionRepository = sessions,
        cursorRepository: DropboxSyncCursorRepository = cursor
    ): RideLifecycle = RideLifecycleImpl(
        sessionRepository, cursorRepository, importer, revealEvaluator,
        setOf(routeClusterer, intervalClusterer), scope
    )
}
