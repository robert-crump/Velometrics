package com.velometrics.app.di

import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.data.cache.RepeatedRoutesCache
import com.velometrics.app.data.preferences.FtpHistoryRepository
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import com.velometrics.app.domain.service.RideLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The app singletons the androidTest `ReadmeScreenshots` (#220) sets up demo data through. It lives
 * in main because Hilt only aggregates entry points compiled with the `@HiltAndroidApp` class; the
 * test reaches it with `EntryPointAccessors.fromApplication`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReadmeScreenshotsEntryPoint {
    fun rideLifecycle(): RideLifecycle
    fun ftpHistoryRepository(): FtpHistoryRepository
    fun userSettingsRepository(): UserSettingsRepository
    fun repeatedRoutesCache(): RepeatedRoutesCache
    fun repeatedIntervalsCache(): RepeatedIntervalsCache
    fun repeatedRouteRepository(): RepeatedRouteRepository
}
