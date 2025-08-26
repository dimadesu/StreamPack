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
        // TODO: Clean up resources
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
            return CustomStreamPackSourceInternal()
        }

        override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
            return source is CustomStreamPackSourceInternal
        }
    }
}
