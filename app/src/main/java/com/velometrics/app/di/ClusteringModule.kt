package com.velometrics.app.di

import com.velometrics.app.domain.service.IntervalClusterer
import com.velometrics.app.domain.service.RideClusterer
import com.velometrics.app.domain.service.RouteClusterer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** The clusterers `RideLifecycle` refreshes after rides are added. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ClusteringModule {

    @Binds @IntoSet
    abstract fun bindRoutes(impl: RouteClusterer): RideClusterer

    @Binds @IntoSet
    abstract fun bindIntervals(impl: IntervalClusterer): RideClusterer
}
