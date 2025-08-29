package io.github.thibaultbee.streampack.app.sources.audio

import android.Manifest
import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.app.ui.main.CustomAudioRenderersFactory
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class CustomAudioInput3(private val context: Context) : IAudioSourceInternal {
    private var audioRecordWrapper: AudioRecordWrapper2? = null
    private var bufferSize: Int? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private var exoPlayer: ExoPlayer? = null

    companion object {
        private const val TAG = "CustomAudioInput3"
    }

    override suspend fun configure(config: AudioSourceConfig) {
        val ctx = requireNotNull(context) { "Context must be set before configure" }

        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )

        val safeBufferSize = bufferSize ?: return

        val pcmBuffer = CircularPcmBuffer(safeBufferSize * 1)
        val renderersFactory = CustomAudioRenderersFactory(ctx, pcmBuffer)
        val exoPlayerInstance = ExoPlayer.Builder(ctx, renderersFactory).build()
        exoPlayer = exoPlayerInstance


        android.util.Log.d("CustomAudioSource", "audioBuffer identity (assigned): ${System.identityHashCode(pcmBuffer)}")
        audioRecordWrapper = AudioRecordWrapper2(ctx, exoPlayerInstance, pcmBuffer)
        audioRecordWrapper?.config()

    }

    override suspend fun startStream() {
        audioRecordWrapper?.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        audioRecordWrapper?.stop()
        _isStreamingFlow.tryEmit(false)
    }

    override fun release() {
        audioRecordWrapper?.release()
        _isStreamingFlow.tryEmit(false)
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val buffer = frameFactory.create(bufferSize!!, 0)
        val length = audioRecordWrapper?.read(buffer.rawBuffer.array(), 0, buffer.rawBuffer.remaining()) ?: 0
        if (length > 0) {
            buffer.timestampInUs = System.nanoTime() / 1000
            return buffer
        } else {
            buffer.close()
            throw IllegalStateException("Failed to read audio data")
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val audioRecordWrapper = requireNotNull(audioRecordWrapper) { "Audio source is not initialized" }
        val buffer = frame.rawBuffer
        val length = audioRecordWrapper.read(buffer.array(), 0, buffer.remaining())
        if (length > 0) {
            frame.timestampInUs = System.nanoTime() / 1000
            return frame
        } else {
            frame.close()
            throw IllegalStateException("Failed to read audio data")
        }
    }

    class Factory : IAudioSourceInternal.Factory {
        override suspend fun create(context: Context): IAudioSourceInternal {
            return CustomAudioInput3(context)
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput3
        }
    }
}
