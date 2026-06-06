package com.velometrics.app.di

import com.velometrics.app.data.location.AndroidLocationSource
import com.velometrics.app.domain.service.LocationSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {

    @Binds
    @Singleton
    abstract fun bindLocationSource(impl: AndroidLocationSource): LocationSource
}
