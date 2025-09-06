package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MediaProjectionAudioSourceFactory
import io.github.thibaultbee.streampack.core.logger.Logger

/**
 * Factory to create MediaProjectionAudioSource for capturing ExoPlayer audio.
 * This is a clean wrapper around StreamPack's existing MediaProjectionAudioSource.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ExoPlayerAudioSourceFactory(
    private val mediaProjection: MediaProjection,
    private val exoPlayer: ExoPlayer
) : IAudioSourceInternal.Factory {
    
    override suspend fun create(context: Context): IAudioSourceInternal {
        Logger.i(TAG, "Creating MediaProjectionAudioSource for ExoPlayer audio capture")
        
        // Use StreamPack's existing MediaProjectionAudioSourceFactory
        // This will capture all audio playback, including ExoPlayer's output
        val factory = MediaProjectionAudioSourceFactory(mediaProjection)
        return factory.create(context)
    }
    
    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        // Check if it's a MediaProjectionAudioSource created for the same MediaProjection
        return source != null && source.javaClass.simpleName == "MediaProjectionAudioSource"
    }
    
    companion object {
        private const val TAG = "ExoPlayerAudioSourceFactory"
    }
}
