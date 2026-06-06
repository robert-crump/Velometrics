package com.velometrics.app.di

import com.velometrics.app.data.repository.CyclingSessionRepositoryImpl
import com.velometrics.app.data.repository.IntervalRepositoryImpl
import com.velometrics.app.data.repository.MapGraphRepositoryImpl
import com.velometrics.app.data.repository.RepeatedRouteRepositoryImpl
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindCyclingSessionRepository(
        impl: CyclingSessionRepositoryImpl
    ): CyclingSessionRepository

    @Binds
    @Singleton
    abstract fun bindIntervalRepository(
        impl: IntervalRepositoryImpl
    ): IntervalRepository

    @Binds
    @Singleton
    abstract fun bindMapGraphRepository(
        impl: MapGraphRepositoryImpl
    ): MapGraphRepository

    @Binds
    @Singleton
    abstract fun bindRepeatedRouteRepository(
        impl: RepeatedRouteRepositoryImpl
    ): RepeatedRouteRepository
}
