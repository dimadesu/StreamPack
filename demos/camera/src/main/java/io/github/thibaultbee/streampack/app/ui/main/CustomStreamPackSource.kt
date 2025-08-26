package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaOutput
import com.haishinkit.media.MediaOutputDataSource
import com.haishinkit.stream.StreamSession
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.LinkedBlockingQueue

import io.github.thibaultbee.streampack.core.elements.sources.video.AbstractPreviewableSource
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoFrameSourceInternal

class CustomStreamPackSourceInternal : AbstractPreviewableSource(), MediaOutput,
    IVideoFrameSourceInternal {
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
        frameQueue.clear()
    }


    // Buffer to store incoming RTMP frames
    private val frameQueue: LinkedBlockingQueue<MediaBuffer> = LinkedBlockingQueue()

    // MediaOutput interface requirement
    override var dataSource: WeakReference<MediaOutputDataSource>? = null

    // AbstractPreviewableSource required members (stubbed for RTMP source)
    override val timestampOffsetInNs: Long
        get() = 0L

    private val _isPreviewingFlow = MutableStateFlow(false)
    override val isPreviewingFlow: StateFlow<Boolean>
        get() = _isPreviewingFlow

    override suspend fun getOutput(): android.view.Surface? {
        // RTMP source does not use output surface
        return null
    }

    override suspend fun setOutput(surface: android.view.Surface) {
        // RTMP source does not use output surface
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
        android.util.Log.v("CustomStreamPackSource", "append: Called, queue size before offer: ${frameQueue.size}")
        android.util.Log.v("CustomStreamPackSource", "append: Buffer info: timestamp=${buffer.timestamp}, payload type=${buffer.payload?.javaClass?.name}")
        val payloadSize = (buffer.payload as? ByteArray)?.size ?: -1
        android.util.Log.d("CustomStreamPackSource", "append: Received RTMP frame, size=$payloadSize, timestamp=${buffer.timestamp}")
        val offered = frameQueue.offer(buffer)
        if (!offered) {
            android.util.Log.w("CustomStreamPackSource", "append: Frame queue full, dropping frame")
        }
        android.util.Log.v("CustomStreamPackSource", "append: Queue size after offer: ${frameQueue.size}")
    }

    // AbstractPreviewableSource: deliver frames to StreamPack
    override fun getVideoFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val buffer = frameQueue.poll()
        android.util.Log.v("CustomStreamPackSource", "getVideoFrame: Called, queue size before poll: ${frameQueue.size + if (buffer != null) 1 else 0}")
        if (buffer != null) {
            android.util.Log.v("CustomStreamPackSource", "getVideoFrame: Polled buffer: timestamp=${buffer.timestamp}, payload type=${buffer.payload?.javaClass?.name}")
            if (buffer.payload != null) {
                val payloadSize = (buffer.payload as? ByteArray)?.size ?: -1
                android.util.Log.d("CustomStreamPackSource", "getVideoFrame: Consuming frame, size=$payloadSize, timestamp=${buffer.timestamp}, queue size=${frameQueue.size}")
                val frame = RawFrame(
                    rawBuffer = buffer.payload!!,
                    timestampInUs = buffer.timestamp
                )
                android.util.Log.v("CustomStreamPackSource", "getVideoFrame: Created RawFrame: buffer size=$payloadSize, timestamp=${frame.timestampInUs}")
                return frame
            } else {
                android.util.Log.w("CustomStreamPackSource", "getVideoFrame: Frame payload is null, buffer timestamp=${buffer.timestamp}")
            }
        } else {
            android.util.Log.d("CustomStreamPackSource", "getVideoFrame: No frame available in queue")
        }
        val fallbackFrame = frameFactory.create(0, 0L)
        android.util.Log.v("CustomStreamPackSource", "getVideoFrame: Returning fallback frame, timestamp=${fallbackFrame.timestampInUs}")
        return fallbackFrame
    }

    // AbstractPreviewableSource: implement preview reset logic
    override suspend fun resetPreviewImpl() {
        // No-op for RTMP source, unless preview surface needs to be reset
    }

    // AbstractPreviewableSource: implement output reset logic
    override suspend fun resetOutputImpl() {
        frameQueue.clear()
    }

    class Factory(private val hkSurfaceView: com.haishinkit.view.HkSurfaceView? = null) : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {
            val rtmpUrl = "rtmp://localhost:1935/publish/live" // TODO: Replace with your RTMP URL or pass as parameter
            val uri = android.net.Uri.parse(rtmpUrl)
            StreamSession.Builder.registerFactory(com.haishinkit.rtmp.RtmpStreamSessionFactory)
            val session = StreamSession.Builder(context, uri).build()

            val customSource = CustomStreamPackSourceInternal()
            customSource.rtmpStreamSession = session
            session.stream.registerOutput(customSource)

            // If a HkSurfaceView is provided, wire it to the RTMP stream for preview
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
