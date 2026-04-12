package com.cyclegraph.app.ui.screens.sessiondetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.domain.model.CyclingSession
import com.cyclegraph.app.domain.model.IntervalSession
import com.cyclegraph.app.domain.repository.CyclingSessionRepository
import com.cyclegraph.app.domain.repository.IntervalRepository
import com.cyclegraph.app.domain.service.SessionComparison
import com.cyclegraph.app.domain.service.SessionComparator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: CyclingSessionRepository,
    private val intervalRepository: IntervalRepository,
    private val sessionComparator: SessionComparator
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: 0L

    private val _session = MutableStateFlow<CyclingSession?>(null)
    val session: StateFlow<CyclingSession?> = _session.asStateFlow()

    private val _comparison = MutableStateFlow<SessionComparison?>(null)
    val comparison: StateFlow<SessionComparison?> = _comparison.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val intervals: StateFlow<List<IntervalSession>> = intervalRepository.getIntervalsForSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val loaded = sessionRepository.getSessionById(sessionId)
            _session.value = loaded
            _isLoading.value = false

            if (loaded != null) {
                _comparison.value = sessionComparator.computeComparison(loaded)
            }
        }
    }
}
