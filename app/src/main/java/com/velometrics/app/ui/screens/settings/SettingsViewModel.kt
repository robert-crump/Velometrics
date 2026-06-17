package com.velometrics.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.SessionComparator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RecalcState {
    data object Idle : RecalcState()
    data object Running : RecalcState()
    data object Done : RecalcState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val sessionRepository: CyclingSessionRepository,
    private val sessionComparator: SessionComparator,
    private val dropboxAuthRepository: DropboxAuthRepository
) : ViewModel() {

    val ftp = userSettingsRepository.ftp
    val homeLat = userSettingsRepository.homeLat
    val homeLon = userSettingsRepository.homeLon
    val homeDisplayName = userSettingsRepository.homeDisplayName
    val dropboxSyncFolder = userSettingsRepository.dropboxSyncFolder
    val isDropboxConnected = dropboxAuthRepository.isConnected
    val needsDropboxReauth = dropboxAuthRepository.needsReauth

    private val _recalcState = MutableStateFlow<RecalcState>(RecalcState.Idle)
    val recalcState: StateFlow<RecalcState> = _recalcState.asStateFlow()

    private val _pendingFtp = MutableStateFlow<Int?>(null)
    val pendingFtp: StateFlow<Int?> = _pendingFtp.asStateFlow()

    fun requestFtpChange(newFtp: Int) {
        _pendingFtp.value = newFtp
    }

    fun cancelFtpChange() {
        _pendingFtp.value = null
    }

    fun confirmFtpChange() {
        val newFtp = _pendingFtp.value ?: return
        _pendingFtp.value = null
        viewModelScope.launch(Dispatchers.IO) {
            userSettingsRepository.saveFtp(newFtp)
        }
    }

    fun recalculateAllStats() {
        viewModelScope.launch(Dispatchers.IO) {
            _recalcState.value = RecalcState.Running
            val allSessions = sessionRepository.getAllSessions().first()
            for (session in allSessions) {
                sessionComparator.computeComparison(session)
            }
            _recalcState.value = RecalcState.Done
        }
    }

    fun clearRecalcState() {
        _recalcState.value = RecalcState.Idle
    }

    fun saveDropboxSyncFolder(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            userSettingsRepository.saveDropboxSyncFolder(path)
        }
    }

    fun connectDropbox() {
        dropboxAuthRepository.startAuthFlow()
    }

    fun disconnectDropbox() {
        viewModelScope.launch(Dispatchers.IO) {
            dropboxAuthRepository.disconnect()
        }
    }
}
