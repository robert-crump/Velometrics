package com.velometrics.app.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.preferences.FtpHistoryRepository
import com.velometrics.app.data.preferences.OnboardingGate
import com.velometrics.app.data.preferences.UserSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

enum class OnboardingPage { Profile, Dropbox }

/**
 * First-run onboarding (#238): the rider profile is saved before Dropbox is connected, because FTP,
 * zones, ride tag and Speed IQ mass are frozen at import (ADR 0001, #228).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val gate: OnboardingGate,
    private val userSettingsRepository: UserSettingsRepository,
    private val ftpHistoryRepository: FtpHistoryRepository,
    private val dropboxAuthRepository: DropboxAuthRepository
) : ViewModel() {

    /** Null until the gate has decided. */
    private val _visible = MutableStateFlow<Boolean?>(null)
    val visible: StateFlow<Boolean?> = _visible.asStateFlow()

    private val _page = MutableStateFlow(OnboardingPage.Profile)
    val page: StateFlow<OnboardingPage> = _page.asStateFlow()

    private val _form = MutableStateFlow(ProfileForm(currentYear = LocalDate.now().year))
    val form: StateFlow<ProfileForm> = _form.asStateFlow()

    val isDropboxConnected = dropboxAuthRepository.isConnected

    init {
        viewModelScope.launch { _visible.value = withContext(Dispatchers.IO) { gate.shouldShow() } }
    }

    fun updateForm(change: (ProfileForm) -> ProfileForm) = _form.update(change)

    /** Saves the profile (the FTP as the history's "Before first test" row), then shows the Dropbox page. */
    fun saveProfile() {
        val profile = _form.value.profile ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                userSettingsRepository.saveRiderWeightKg(profile.riderWeightKg)
                userSettingsRepository.saveBikeKitWeightKg(profile.bikeKitWeightKg)
                userSettingsRepository.saveMaxHr(profile.maxHr)
                ftpHistoryRepository.save(null, profile.ftp)
            }
            _page.value = OnboardingPage.Dropbox
        }
    }

    fun backToProfile() {
        _page.value = OnboardingPage.Profile
    }

    /** Onboarding finishes once the account is connected, so Home's auto sync then starts right away. */
    fun connectDropbox() = dropboxAuthRepository.startAuthFlow()

    fun finish() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { gate.complete() }
            _visible.value = false
        }
    }
}
