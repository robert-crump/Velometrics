package com.velometrics.app.di

import com.velometrics.app.data.heatmap.HeatmapServiceImpl
import com.velometrics.app.domain.service.HeatmapService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class HeatmapModule {

    @Binds
    @Singleton
    abstract fun bindHeatmapService(impl: HeatmapServiceImpl): HeatmapService
}
