package com.velometrics.app.di

import com.dropbox.core.DbxRequestConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DropboxModule {

    @Provides
    @Singleton
    fun provideDbxRequestConfig(): DbxRequestConfig =
        DbxRequestConfig.newBuilder("velometrics-android").build()
}
