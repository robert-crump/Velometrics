package com.velometrics.app.ui.screens.trainingload

import com.velometrics.app.domain.model.TrainingLoadUiState
import com.velometrics.app.fakes.FakeTrainingLoadCache
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the seam introduced by #195: [TrainingLoadCache] is now a fakeable interface. */
class TrainingLoadViewModelTest {

    @Test
    fun `uiState mirrors the cache's current value`() {
        val state = TrainingLoadUiState(
            isLoading = false,
            hasAnySessions = true,
            currentCtl = 55.0,
            currentAtl = 40.0,
            currentTsb = 15.0
        )
        val vm = TrainingLoadViewModel(FakeTrainingLoadCache(state))

        assertEquals(state, vm.uiState.value)
    }

    @Test
    fun `uiState reflects later cache emissions`() {
        val cache = FakeTrainingLoadCache()
        val vm = TrainingLoadViewModel(cache)

        val updated = TrainingLoadUiState(isLoading = false, hasAnySessions = true, currentCtl = 10.0)
        cache.emit(updated)

        assertEquals(updated, vm.uiState.value)
    }
}
