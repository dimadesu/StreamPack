package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class CustomStreamPackAudioSourceInternal : IAudioSourceInternal {
    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow: StateFlow<Boolean> get() = _isStreamingFlow
    private var exoPlayer: ExoPlayer? = null
    lateinit var audioBuffer: ByteBuffer
    private val lock = Any()

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

    override suspend fun configure(config: AudioSourceConfig) {
        // Optionally use config for sample rate, channels, etc.
    }

    override fun release() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.release()
            exoPlayer = null
        } else {
            Handler(Looper.getMainLooper()).post {
                exoPlayer?.release()
                exoPlayer = null
            }
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        synchronized(lock) {
            val len = audioBuffer.position()
            if (len > 0) {
                audioBuffer.flip()
                frame.rawBuffer.put(audioBuffer)
                frame.rawBuffer.flip()
                audioBuffer.clear()
            }
        }
        return frame
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val bufferSize = audioBuffer.capacity()
        val timestampInUs = System.nanoTime() / 1000L // Use current time as placeholder
        val frame = frameFactory.create(bufferSize, timestampInUs)
        return fillAudioFrame(frame)
    }

    class Factory : IAudioSourceInternal.Factory {
        @OptIn(UnstableApi::class)
        override suspend fun create(context: Context): IAudioSourceInternal {
            val customSrc = CustomStreamPackAudioSourceInternal()
            val rtmpUrl = "rtmp://localhost:1935/publish/live"
            val mediaItem = MediaItem.fromUri(rtmpUrl)
            val mediaSource = ProgressiveMediaSource.Factory(
                try {
                    androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
                } catch (e: Exception) {
                    androidx.media3.datasource.DefaultDataSource.Factory(context)
                }
            ).createMediaSource(mediaItem)
            val pcmBuffer = ByteBuffer.allocate(1024 * 1024)
            val audioSink = StreamPackAudioSink(pcmBuffer)
            val renderersFactory = StreamPackAudioRenderersFactory(context, audioSink)
            val exoPlayer = ExoPlayer.Builder(context, renderersFactory).build()
            withContext(Dispatchers.Main) {
                exoPlayer.setMediaSource(mediaSource)
            }
            customSrc.exoPlayer = exoPlayer
            customSrc.audioBuffer = pcmBuffer
            return customSrc
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomStreamPackAudioSourceInternal
        }
    }
}
