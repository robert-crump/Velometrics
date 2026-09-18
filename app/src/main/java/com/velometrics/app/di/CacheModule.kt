package com.velometrics.app.di

import com.velometrics.app.data.cache.AllTimeStatsCache
import com.velometrics.app.data.cache.AllTimeStatsCacheImpl
import com.velometrics.app.data.cache.GlobalAverageCache
import com.velometrics.app.data.cache.GlobalAverageCacheImpl
import com.velometrics.app.data.cache.RepeatedIntervalsCache
import com.velometrics.app.data.cache.RepeatedIntervalsCacheImpl
import com.velometrics.app.data.cache.RepeatedRoutesCache
import com.velometrics.app.data.cache.RepeatedRoutesCacheImpl
import com.velometrics.app.data.cache.TrainingLoadCache
import com.velometrics.app.data.cache.TrainingLoadCacheImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CacheModule {

    @Binds
    @Singleton
    abstract fun bindAllTimeStatsCache(impl: AllTimeStatsCacheImpl): AllTimeStatsCache

    @Binds
    @Singleton
    abstract fun bindTrainingLoadCache(impl: TrainingLoadCacheImpl): TrainingLoadCache

    @Binds
    @Singleton
    abstract fun bindGlobalAverageCache(impl: GlobalAverageCacheImpl): GlobalAverageCache

    @Binds
    @Singleton
    abstract fun bindRepeatedRoutesCache(impl: RepeatedRoutesCacheImpl): RepeatedRoutesCache

    @Binds
    @Singleton
    abstract fun bindRepeatedIntervalsCache(impl: RepeatedIntervalsCacheImpl): RepeatedIntervalsCache
}
