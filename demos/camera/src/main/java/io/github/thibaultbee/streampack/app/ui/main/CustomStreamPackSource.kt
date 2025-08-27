package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CustomStreamPackSourceInternal : AbstractPreviewableSource(), IVideoSourceInternal {
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> = MutableStateFlow(object : ISourceInfoProvider {
        override fun getSurfaceSize(targetResolution: android.util.Size): android.util.Size = targetResolution
        override val rotationDegrees: Int = 0
        override val isMirror: Boolean = false
    })
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> get() = _isStreamingFlow
    private var exoPlayer: ExoPlayer? = null

    override suspend fun startStream() {
        withContext(Dispatchers.Main) {
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        _isStreamingFlow.value = false
    }

    override suspend fun configure(config: VideoSourceConfig) {
    }

    override fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }

    // AbstractPreviewableSource required members (stubbed for RTMP source)
    override val timestampOffsetInNs: Long
        get() = 0L

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean>
        get() = _isPreviewingFlow

    private var outputSurface: android.view.Surface? = null

    override suspend fun getOutput(): android.view.Surface? {
        return outputSurface
    }

    override suspend fun setOutput(surface: android.view.Surface) {
        outputSurface = surface

        withContext(Dispatchers.Main) {
            exoPlayer?.setVideoSurface(surface)
        }
    }

    override suspend fun hasPreview(): Boolean {
        return false
    }

    override suspend fun setPreview(surface: android.view.Surface) {
    }

    override suspend fun startPreview() {
        _isPreviewingFlow.value = false
    }

    override suspend fun startPreview(previewSurface: android.view.Surface) {
        _isPreviewingFlow.value = false
    }

    override suspend fun stopPreview() {
        _isPreviewingFlow.value = false
    }

    override fun <T> getPreviewSize(targetSize: android.util.Size, targetClass: Class<T>): android.util.Size {
        return targetSize
    }

    override suspend fun resetPreviewImpl() {
    }

    override suspend fun resetOutputImpl() {
    }

    class Factory() : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {
            val customSrc = CustomStreamPackSourceInternal()
            val exoPlayer = ExoPlayer.Builder(context).build()
            withContext(Dispatchers.Main) {
                val rtmpUrl = "rtmp://localhost:1935/publish/live"
                val mediaItem = MediaItem.fromUri(rtmpUrl)
                val mediaSource = ProgressiveMediaSource.Factory(
                    try {
                        androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
                    } catch (e: Exception) {
                        androidx.media3.datasource.DefaultDataSource.Factory(context)
                    }
                ).createMediaSource(mediaItem)
                exoPlayer.setMediaSource(mediaSource)
            }
            customSrc.exoPlayer = exoPlayer
            return customSrc
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is CustomStreamPackSourceInternal
        }
    }
}
