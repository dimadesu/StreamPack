package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.stream.StreamSession
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource

class CustomStreamPackSourceInternal : AbstractPreviewableSource(), MediaOutput, IVideoSourceInternal {
    internal var hkSurfaceView: MyRtmpSurfaceView? = null
    override val infoProviderFlow: StateFlow<ISourceInfoProvider> = MutableStateFlow(object : ISourceInfoProvider {
        override fun getSurfaceSize(targetResolution: android.util.Size): android.util.Size = targetResolution
        override val rotationDegrees: Int = 0
        override val isMirror: Boolean = false
    })
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> get() = _isStreamingFlow

    // Store RTMP session for lifecycle management
    private var rtmpStreamSession: StreamSession? = null

    override suspend fun startStream() {

        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        _isStreamingFlow.value = false
    }

    override suspend fun configure(config: VideoSourceConfig) {
        // TODO: Configure source as needed
    }

    override fun release() {
        rtmpStreamSession?.let { session ->
            GlobalScope.launch {
                session.close()
            }
        }
        rtmpStreamSession = null
    }

    // MediaOutput interface requirement
    override var dataSource: WeakReference<MediaOutputDataSource>? = null

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
//        hkSurfaceView?.pixelTransform?.surface = surface
        hkSurfaceView?.setSurface(surface)
    }

    override suspend fun hasPreview(): Boolean {
        // RTMP source does not support preview surface
        return false
    }

    override suspend fun setPreview(surface: android.view.Surface) {
        // RTMP source does not support preview surface
    }

    override suspend fun startPreview() {
        // RTMP source does not support preview
        _isPreviewingFlow.value = false
    }

    override suspend fun startPreview(previewSurface: android.view.Surface) {
        // RTMP source does not support preview
        _isPreviewingFlow.value = false
    }

    override suspend fun stopPreview() {
        // RTMP source does not support preview
        _isPreviewingFlow.value = false
    }

    override fun <T> getPreviewSize(targetSize: android.util.Size, targetClass: Class<T>): android.util.Size {
        // RTMP source does not support preview size selection
        return targetSize
    }

    // MediaOutput: called by RTMP pipeline
    override fun append(buffer: MediaBuffer) {
        android.util.Log.v("CustomStreamPackSource", "append: Buffer info: buffer=${buffer}")
    }

    // AbstractPreviewableSource: implement preview reset logic
    override suspend fun resetPreviewImpl() {
        // No-op for RTMP source, unless preview surface needs to be reset
    }

    // AbstractPreviewableSource: implement output reset logic
    override suspend fun resetOutputImpl() {
    }

    class Factory(private val hkSurfaceView: MyRtmpSurfaceView? = null) : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {

            val customSource = CustomStreamPackSourceInternal()

            val rtmpUrl = "rtmp://localhost:1935/publish/live" // TODO: Replace with your RTMP URL or pass as parameter
            val uri = android.net.Uri.parse(rtmpUrl)
            StreamSession.Builder.registerFactory(com.haishinkit.rtmp.RtmpStreamSessionFactory)
            val session = StreamSession.Builder(context, uri).build()
            customSource.rtmpStreamSession = session
            customSource.hkSurfaceView = hkSurfaceView


            hkSurfaceView?.dataSource = WeakReference(session.stream)

            GlobalScope.launch {
                try {
                    val result = session.connect(StreamSession.Method.PLAYBACK)
                    if (result == null || result.isFailure) {
                        android.util.Log.e("CustomStreamPackSource", "RTMP playback failed: ${result?.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CustomStreamPackSource", "RTMP playback exception: ${e.message}", e)
                }
            }

            return customSource
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is CustomStreamPackSourceInternal
        }
    }
}
