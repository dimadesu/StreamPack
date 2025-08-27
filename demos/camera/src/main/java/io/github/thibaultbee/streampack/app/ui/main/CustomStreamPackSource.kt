package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private var exoPlayer: ExoPlayer? = null // For output
    private var previewPlayer: ExoPlayer? = null // For preview

    override suspend fun startStream() {
        withContext(Dispatchers.Main) {
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        withContext(Dispatchers.Main) {
            exoPlayer?.playWhenReady = false
        }
        _isStreamingFlow.value = false
    }

    override suspend fun configure(config: VideoSourceConfig) {
    }

    override fun release() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.release()
            exoPlayer = null
            previewPlayer?.release()
            previewPlayer = null
        } else {
            Handler(Looper.getMainLooper()).post {
                exoPlayer?.release()
                exoPlayer = null
                previewPlayer?.release()
                previewPlayer = null
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
        withContext(Dispatchers.Main) {
            exoPlayer?.setVideoSurface(surface)
        }
    }

    override suspend fun hasPreview(): Boolean {
        return previewSurface != null
    }

    override suspend fun setPreview(surface: android.view.Surface) {
        previewSurface = surface
        withContext(Dispatchers.Main) {
            previewPlayer?.setVideoSurface(surface)
        }
    }

    override suspend fun startPreview() {
        previewSurface?.let { surface ->
            withContext(Dispatchers.Main) {
                previewPlayer?.setVideoSurface(surface)
                previewPlayer?.prepare()
                previewPlayer?.playWhenReady = true
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
        withContext(Dispatchers.Main) {
            previewPlayer?.playWhenReady = false
            previewPlayer?.setVideoSurface(null)
        }
        _isPreviewingFlow.value = false
    }

    override fun <T> getPreviewSize(targetSize: android.util.Size, targetClass: Class<T>): android.util.Size {
        val swappedSize = android.util.Size(targetSize.height, targetSize.width)
        android.util.Log.d("CustomStreamPackSource", "Requested preview size: $targetSize, swapped: $swappedSize for $targetClass")
        return swappedSize
    }

    override suspend fun resetPreviewImpl() {
    }

    override suspend fun resetOutputImpl() {
    }

    class Factory() : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {
            val customSrc = CustomStreamPackSourceInternal()
            val rtmpUrl = "rtmp://localhost:1935/publish/live"
            val mediaItem = MediaItem.fromUri(rtmpUrl)
            val mediaSource = ProgressiveMediaSource.Factory(
                try {
                    androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
                } catch (e: Exception) {
                    androidx.media3.datasource.DefaultDataSource.Factory(context)
                }
            ).createMediaSource(mediaItem)
            val mediaSourcePreview = ProgressiveMediaSource.Factory(
                try {
                    androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
                } catch (e: Exception) {
                    androidx.media3.datasource.DefaultDataSource.Factory(context)
                }
            ).createMediaSource(mediaItem)

            val exoPlayer = ExoPlayer.Builder(context).build()
            val previewPlayer = ExoPlayer.Builder(context).build()
            withContext(Dispatchers.Main) {
                exoPlayer.setMediaSource(mediaSource)
                previewPlayer.setMediaSource(mediaSourcePreview)
            }
            customSrc.exoPlayer = exoPlayer
            customSrc.previewPlayer = previewPlayer
            return customSrc
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is CustomStreamPackSourceInternal
        }
    }
}
