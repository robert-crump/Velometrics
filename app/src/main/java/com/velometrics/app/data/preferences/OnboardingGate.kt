package com.velometrics.app.data.preferences

import com.velometrics.app.domain.repository.CyclingSessionRepository
import javax.inject.Inject
import javax.inject.Singleton

/** The first-run onboarding flag (#238). */
interface OnboardingFlagStore {
    suspend fun isOnboardingDone(): Boolean
    suspend fun markOnboardingDone()
}

/**
 * Decides whether the first-run onboarding (#238) is shown: only while the flag is unset and the
 * database has no rides. An install that already has rides (an upgrade) gets the flag set here, so
 * deleting all its rides later doesn't bring the onboarding back.
 */
@Singleton
class OnboardingGate @Inject constructor(
    private val flagStore: OnboardingFlagStore,
    private val sessionRepository: CyclingSessionRepository
) {
    suspend fun shouldShow(): Boolean {
        if (flagStore.isOnboardingDone()) return false
        if (sessionRepository.getSessionCount() > 0) {
            flagStore.markOnboardingDone()
            return false
        }
        return true
    }

    suspend fun complete() = flagStore.markOnboardingDone()
}
