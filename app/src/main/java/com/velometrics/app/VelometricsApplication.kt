package com.velometrics.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.velometrics.app.data.cache.AllTimeStatsCache
import com.velometrics.app.data.cache.GlobalAverageCache
import com.velometrics.app.data.cache.RecapWarmer
import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.data.cache.RepeatedRoutesCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class VelometricsApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    // Injecting the caches here forces Hilt to instantiate them at app startup,
    // so their SharingStarted.Eagerly collection begins immediately and the Routes
    // tab data is ready before the user navigates there.
    @Inject lateinit var repeatedRoutesCache: RepeatedRoutesCache
    @Inject lateinit var repeatedIntervalsCache: RepeatedIntervalsCache
    @Inject lateinit var globalAverageCache: GlobalAverageCache
    @Inject lateinit var allTimeStatsCache: AllTimeStatsCache
    @Inject lateinit var recapWarmer: RecapWarmer

    override fun onCreate() {
        super.onCreate()
        recapWarmer.start()
    }
}
