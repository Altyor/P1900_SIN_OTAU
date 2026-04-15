package com.siliconlabs.bledemo.features.firmware_browser.di

import com.siliconlabs.bledemo.features.firmware_browser.data.SftpRepository
import com.siliconlabs.bledemo.features.firmware_browser.data.SftpRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FirmwareBrowserModule {

    @Provides
    @Singleton
    fun provideSftpRepository(): SftpRepository = SftpRepositoryImpl()
}
