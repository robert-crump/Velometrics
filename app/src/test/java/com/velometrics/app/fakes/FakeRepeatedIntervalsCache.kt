package com.velometrics.app.fakes

import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.domain.model.RepeatedInterval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeRepeatedIntervalsCache(
    initialIntervals: List<RepeatedInterval> = emptyList(),
    initialIsLoading: Boolean = false
) : RepeatedIntervalsCache {
    private val _isLoading = MutableStateFlow(initialIsLoading)
    override val isLoading: StateFlow<Boolean> = _isLoading

    private val _repeatedIntervals = MutableStateFlow(initialIntervals)
    override val repeatedIntervals: StateFlow<List<RepeatedInterval>> = _repeatedIntervals

    fun emit(intervals: List<RepeatedInterval>) {
        _repeatedIntervals.value = intervals
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }
}
