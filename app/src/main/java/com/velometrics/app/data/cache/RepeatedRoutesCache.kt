package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hoists [RepeatedRouteRepository.getAllRoutes] into a singleton-scoped hot [StateFlow]
 * so that switching to the Routes tab does not restart collection (and re-parse
 * representative GPS tracks) each time the screen's ViewModel is recreated.
 *
 * [isLoading] reflects "have we ever received a DB emission?" — it flips to false on the
 * first emission and stays false. Tab switches after that show the cached data instantly.
 */
@Singleton
class RepeatedRoutesCache @Inject constructor(
    repository: RepeatedRouteRepository,
    @ApplicationScope scope: CoroutineScope
) {
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val routes: StateFlow<List<RepeatedRoute>> = repository
        .getAllRoutes()
        .onEach { _isLoading.value = false }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
