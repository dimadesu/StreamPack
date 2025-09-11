package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomStreamPackSourceInternal (
    private val exoPlayer: ExoPlayer,
) : AbstractPreviewableSource(), IVideoSourceInternal {
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> = MutableStateFlow(object : ISourceInfoProvider {
        override fun getSurfaceSize(targetResolution: android.util.Size): android.util.Size = targetResolution
        override val rotationDegrees: Int = 0
        override val isMirror: Boolean = false
    })
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> get() = _isStreamingFlow
    override suspend fun startStream() {
        Handler(Looper.getMainLooper()).post {
            try {
                android.util.Log.d("CustomStreamPackSource", "Starting stream - playbackState: ${exoPlayer.playbackState}")
                android.util.Log.d("CustomStreamPackSource", "MediaItem count: ${exoPlayer.mediaItemCount}")
                android.util.Log.d("CustomStreamPackSource", "Output surface: $outputSurface")
                
                // Ensure we have an output surface before starting
                if (outputSurface == null) {
                    android.util.Log.w("CustomStreamPackSource", "No output surface set - cannot start streaming")
                    _isStreamingFlow.value = false
                    return@post
                }
                
                outputSurface?.let { surface ->
                    exoPlayer.setVideoSurface(surface)
                    android.util.Log.d("CustomStreamPackSource", "Set video surface to output")
                }
                
                // Set streaming to true - streaming pipeline can work even if ExoPlayer preview fails
                _isStreamingFlow.value = true
                
                // Try to prepare and start ExoPlayer for preview, but don't fail streaming if it doesn't work
                try {
                    if (exoPlayer.mediaItemCount > 0) {
                        android.util.Log.d("CustomStreamPackSource", "ExoPlayer has media items - preparing for playback")
                        if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                            android.util.Log.d("CustomStreamPackSource", "Preparing ExoPlayer")
                            exoPlayer.prepare()
                        }
                        android.util.Log.d("CustomStreamPackSource", "Setting playWhenReady = true")
                        exoPlayer.playWhenReady = true
                    } else {
                        android.util.Log.d("CustomStreamPackSource", "No media items - ExoPlayer will be used as surface target only")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CustomStreamPackSource", "ExoPlayer preparation failed, but streaming can continue: ${e.message}")
                }
                
                // Add a listener to monitor playback state changes
                exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        android.util.Log.d("CustomStreamPackSource", "Playback state changed to: $playbackState")
                        when (playbackState) {
                            androidx.media3.common.Player.STATE_READY -> {
                                android.util.Log.i("CustomStreamPackSource", "ExoPlayer is ready and playing")
                            }
                            androidx.media3.common.Player.STATE_ENDED -> {
                                android.util.Log.w("CustomStreamPackSource", "ExoPlayer playback ended")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("CustomStreamPackSource", "ExoPlayer error: ${error.message}", error)
                        // Don't stop streaming on ExoPlayer error - it might be used as surface target only
                        android.util.Log.w("CustomStreamPackSource", "Continuing streaming despite ExoPlayer error")
                    }
                })
            } catch (e: Exception) {
                android.util.Log.e("CustomStreamPackSource", "Error starting stream: ${e.message}", e)
                _isStreamingFlow.value = false
            }
        }
    }

    override suspend fun stopStream() {
        android.util.Log.d("CustomStreamPackSource", "stopStream() called - streaming: ${_isStreamingFlow.value}")
        _isStreamingFlow.value = false
        Handler(Looper.getMainLooper()).post {
            try {
                android.util.Log.d("CustomStreamPackSource", "Stopping stream - current state: ${exoPlayer.playbackState}")
                // Only stop playback, don't clear surface immediately to avoid rebuffering
                exoPlayer.playWhenReady = false
                android.util.Log.d("CustomStreamPackSource", "Set playWhenReady = false")
                
                // Give a small delay before stopping completely
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        // Only stop and clear surface if we're still not streaming
                        if (!_isStreamingFlow.value) {
                            exoPlayer.stop()
                            exoPlayer.setVideoSurface(null)
                            android.util.Log.d("CustomStreamPackSource", "Stream stopped and surface cleared")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("CustomStreamPackSource", "Error in delayed stop: ${e.message}", e)
                    }
                }, 100) // Slightly longer delay to avoid immediate restarts
            } catch (e: Exception) {
                android.util.Log.e("CustomStreamPackSource", "Error stopping ExoPlayer: ${e.message}", e)
            }
        }
    }

    override suspend fun configure(config: VideoSourceConfig) {
        // Using main exoPlayer instance for both streaming and preview
        withContext(Dispatchers.Main) {
            if (!exoPlayer.isCommandAvailable(androidx.media3.common.Player.COMMAND_PREPARE)) {
                return@withContext
            }
            // ExoPlayer will be prepared when startStream() or startPreview() is called
        }
    }

    override fun release() {
        Handler(Looper.getMainLooper()).post {
            try {
                // Clear surfaces first
                exoPlayer.setVideoSurface(null)
                // Stop playback if still playing
                if (exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                    exoPlayer.stop()
                }
                // Release the player
                exoPlayer.release()
            } catch (e: Exception) {
                android.util.Log.e("CustomStreamPackSource", "Error releasing ExoPlayer: ${e.message}", e)
            }
        }
    }

    // AbstractPreviewableSource required members (stubbed for RTMP source)
    override val timestampOffsetInNs: Long
        get() = 0L

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean>
        get() = _isPreviewingFlow

    private var outputSurface: android.view.Surface? = null
    private var previewSurface: android.view.Surface? = null

    override suspend fun getOutput(): android.view.Surface? {
        return outputSurface
    }

    override suspend fun setOutput(surface: android.view.Surface) {
        outputSurface = surface
        Handler(Looper.getMainLooper()).post {
            try {
                // Only set surface if we're not currently using preview surface
                if (!_isPreviewingFlow.value || surface != previewSurface) {
                    exoPlayer.setVideoSurface(surface)
                }
            } catch (e: Exception) {
                android.util.Log.e("CustomStreamPackSource", "Error setting output surface: ${e.message}", e)
            }
        }
    }

    override suspend fun hasPreview(): Boolean {
        return previewSurface != null
    }

    override suspend fun setPreview(surface: android.view.Surface) {
        previewSurface = surface
        // Use main exoPlayer for preview - no separate instance needed
        Handler(Looper.getMainLooper()).post {
            try {
                exoPlayer.setVideoSurface(surface)
            } catch (e: Exception) {
                android.util.Log.e("CustomStreamPackSource", "Error setting preview surface: ${e.message}", e)
            }
        }
    }

    override suspend fun startPreview() {
        previewSurface?.let { surface ->
            Handler(Looper.getMainLooper()).post {
                try {
                    exoPlayer.setVideoSurface(surface)
                    // Only prepare and start playback if we have media items for preview
                    if (exoPlayer.mediaItemCount > 0) {
                        android.util.Log.d("CustomStreamPackSource", "Starting preview with media items")
                        if (exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE) {
                            exoPlayer.prepare()
                        }
                        exoPlayer.playWhenReady = true
                    } else {
                        android.util.Log.d("CustomStreamPackSource", "Starting preview without media items - surface target only")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CustomStreamPackSource", "Error starting preview: ${e.message}", e)
                }
            }
            _isPreviewingFlow.value = true
        } ?: run {
            _isPreviewingFlow.value = false
        }
    }

    override suspend fun startPreview(previewSurface: android.view.Surface) {
        setPreview(previewSurface)
        startPreview()
    }

    override suspend fun stopPreview() {
        _isPreviewingFlow.value = false
        Handler(Looper.getMainLooper()).post {
            try {
                // Only stop if we're not streaming - if streaming, keep playing but just remove preview surface
                if (!_isStreamingFlow.value) {
                    exoPlayer.setVideoSurface(null)
                    exoPlayer.stop()
                } else {
                    // If streaming, switch back to output surface if different from preview
                    if (outputSurface != previewSurface && outputSurface != null) {
                        exoPlayer.setVideoSurface(outputSurface)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CustomStreamPackSource", "Error stopping preview: ${e.message}", e)
            }
        }
    }

    override fun <T> getPreviewSize(targetSize: android.util.Size, targetClass: Class<T>): android.util.Size {
        android.util.Log.d("CustomStreamPackSource", "exoPlayer.videoFormat ${exoPlayer.videoFormat}")
        android.util.Log.d("CustomStreamPackSource", "targetSize $targetSize")
        val width = exoPlayer.videoFormat?.width ?: 1920
        val height = exoPlayer.videoFormat?.height ?: 1080
        val previewSize = android.util.Size(width, height)
        android.util.Log.d("CustomStreamPackSource", "previewSize: $previewSize")
        return previewSize
    }

    override suspend fun resetPreviewImpl() {
    }

    override suspend fun resetOutputImpl() {
    }

    class Factory(
        private val exoPlayer: ExoPlayer,
    ) : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {
            val customSrc = CustomStreamPackSourceInternal(exoPlayer)
            return customSrc
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is CustomStreamPackSourceInternal
        }
    }
}
