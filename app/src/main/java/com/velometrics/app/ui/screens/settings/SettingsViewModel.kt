package com.velometrics.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.RideClassificationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val sessionRepository: CyclingSessionRepository,
    private val rideClassificationService: RideClassificationService,
    private val dropboxAuthRepository: DropboxAuthRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val ftp = userSettingsRepository.ftp
    val maxHr = userSettingsRepository.maxHr
    val homeLat = userSettingsRepository.homeLat
    val homeLon = userSettingsRepository.homeLon
    val homeDisplayName = userSettingsRepository.homeDisplayName
    val dropboxSyncFolder = userSettingsRepository.dropboxSyncFolder
    val isDropboxConnected = dropboxAuthRepository.isConnected
    val needsDropboxReauth = dropboxAuthRepository.needsReauth

    private val _pendingFtp = MutableStateFlow<Int?>(null)
    val pendingFtp: StateFlow<Int?> = _pendingFtp.asStateFlow()

    private val _pendingMaxHr = MutableStateFlow<Int?>(null)
    val pendingMaxHr: StateFlow<Int?> = _pendingMaxHr.asStateFlow()

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

    fun requestMaxHrChange(newMaxHr: Int) {
        _pendingMaxHr.value = newMaxHr
    }

    fun cancelMaxHrChange() {
        _pendingMaxHr.value = null
    }

    fun confirmMaxHrChange() {
        val newMaxHr = _pendingMaxHr.value ?: return
        _pendingMaxHr.value = null
        viewModelScope.launch(Dispatchers.IO) {
            userSettingsRepository.saveMaxHr(newMaxHr)
        }
    }

    private val _retagStatus = MutableStateFlow<String?>(null)
    val retagStatus: StateFlow<String?> = _retagStatus.asStateFlow()

    /**
     * Debug-only pair to [dumpSessionTagsForReview]: writes the tags the classifier would
     * produce now (current FTP, current thresholds) onto every stored session. Tags are otherwise
     * frozen at import (ADR 0001).
     */
    fun applyRetag() {
        viewModelScope.launch(Dispatchers.IO) {
            _retagStatus.value = "Re-tagging…"
            val ftp = userSettingsRepository.ftp.first()
            val stale = rideClassificationService.reviewRows(ftp).count { it.isStale }
            rideClassificationService.reclassifyAll(ftp)
            _retagStatus.value = "Re-tagged $stale rides"
        }
    }

    private val _dumpStatus = MutableStateFlow<String?>(null)
    val dumpStatus: StateFlow<String?> = _dumpStatus.asStateFlow()

    /**
     * Debug-only entry point for the #170 threshold-tuning review. Pull the result with:
     * `adb shell run-as com.velometrics.app cat files/ride_tag_dump.csv > ride_tag_dump.csv`
     */
    fun dumpSessionTagsForReview() {
        viewModelScope.launch(Dispatchers.IO) {
            _dumpStatus.value = "Dumping…"
            val ftp = userSettingsRepository.ftp.first()
            val rows = rideClassificationService.reviewRows(ftp)
            val outFile = File(appContext.filesDir, "ride_tag_dump.csv")
            outFile.writeText(RideTagCsv.render(rows, ftp))
            val byTag = rows.groupingBy { it.storedTag ?: "(none)" }.eachCount()
            _dumpStatus.value = "Wrote ${rows.size} rows ($byTag) to ${outFile.name}"
        }
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
