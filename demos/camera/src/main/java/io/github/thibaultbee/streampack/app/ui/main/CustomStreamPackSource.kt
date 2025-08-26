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

class CustomStreamPackSourceInternal : IVideoSourceInternal, MediaOutput {
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
        // TODO: Start streaming frames from RTMP
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        // TODO: Stop streaming
        _isStreamingFlow.value = false
    }

    override suspend fun configure(config: VideoSourceConfig) {
        // TODO: Configure source as needed
    }

    override fun release() {
        // Clean up RTMP session
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

    // MediaOutput: called by RTMP pipeline
    override fun append(buffer: MediaBuffer) {
        // Enqueue the incoming frame for later delivery
        frameQueue.offer(buffer)
    }

    // Implement IVideoFrameSourceInternal: deliver frames to StreamPack
    fun getVideoFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val buffer = frameQueue.poll()
        return if (buffer != null && buffer.payload != null) {
            // Wrap the RTMP frame data in a RawFrame for StreamPack
            RawFrame(
                rawBuffer = buffer.payload!!,
                timestampInUs = buffer.timestamp
            )
        } else {
            // If no frame is available, return an empty RawFrame
            frameFactory.create(0, 0L)
        }
    }

    companion object {
        fun wireToRtmpStream(streamSession: StreamSession): CustomStreamPackSourceInternal {
            val customSource = CustomStreamPackSourceInternal()
            streamSession.stream.registerOutput(customSource)
            return customSource
        }
    }

    class Factory : IVideoSourceInternal.Factory {
        override suspend fun create(context: Context): IVideoSourceInternal {
            // Build RTMP session for playback
            val rtmpUrl = "rtmp://localhost:1935/publish/live" // TODO: Replace with your RTMP URL or pass as parameter
            val uri = android.net.Uri.parse(rtmpUrl)
            StreamSession.Builder.registerFactory(com.haishinkit.rtmp.RtmpStreamSessionFactory)
            val session = StreamSession.Builder(context, uri).build()

            // Create custom source and wire to RTMP
            val customSource = CustomStreamPackSourceInternal()
            customSource.rtmpStreamSession = session
            session.stream.registerOutput(customSource)

            // Start playback
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
