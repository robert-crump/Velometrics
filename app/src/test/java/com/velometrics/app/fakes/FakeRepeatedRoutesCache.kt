package com.velometrics.app.fakes

import com.velometrics.app.data.cache.RepeatedRoutesCache
import com.velometrics.app.domain.model.RepeatedRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeRepeatedRoutesCache(
    initialRoutes: List<RepeatedRoute> = emptyList(),
    initialIsLoading: Boolean = false
) : RepeatedRoutesCache {
    private val _isLoading = MutableStateFlow(initialIsLoading)
    override val isLoading: StateFlow<Boolean> = _isLoading

    private val _routes = MutableStateFlow(initialRoutes)
    override val routes: StateFlow<List<RepeatedRoute>> = _routes

    fun emit(routes: List<RepeatedRoute>) {
        _routes.value = routes
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}
