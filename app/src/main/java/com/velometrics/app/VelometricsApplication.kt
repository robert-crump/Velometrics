package com.velometrics.app

import android.app.Application
import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.data.cache.RepeatedRoutesCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VelometricsApplication : Application() {
    // Injecting the caches here forces Hilt to instantiate them at app startup,
    // so their SharingStarted.Eagerly collection begins immediately and the Routes
    // tab data is ready before the user navigates there.
    @Inject lateinit var repeatedRoutesCache: RepeatedRoutesCache
    @Inject lateinit var repeatedIntervalsCache: RepeatedIntervalsCache
}
