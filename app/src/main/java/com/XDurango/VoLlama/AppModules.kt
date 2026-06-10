package com.XDurango.VoLlama

import android.content.Context
import android.media.AudioManager
import com.XDurango.VoLlama.Nearby.NearbyConnectionService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule{

    @Provides
    @Singleton
    fun provideNearbyConnectionService(
        @ApplicationContext context: Context
    ): NearbyConnectionService {
        return NearbyConnectionService(context)
    }
}
@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    fun provideAudioManager(
        @ApplicationContext context: Context
    ): AudioManager {
        return context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
}
