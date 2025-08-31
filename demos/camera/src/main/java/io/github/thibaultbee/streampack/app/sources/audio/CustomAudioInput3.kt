package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import android.media.AudioRecord
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.app.ui.main.CustomAudioRenderersFactory
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CustomAudioInput3(private val context: Context) : IAudioSourceInternal {
    var audioRecordWrapper: AudioRecordWrapper3? = null
        private set
    private var bufferSize: Int? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    companion object {
        private const val TAG = "CustomAudioInput3"
    }

    override suspend fun configure(config: AudioSourceConfig) {
        audioRecordWrapper?.release()
        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
        val ctx = requireNotNull(context) { "Context must be set before configure" }

        val safeBufferSize = bufferSize ?: return

        val pcmBuffer = CircularPcmBuffer(safeBufferSize * 64)
        val renderersFactory = CustomAudioRenderersFactory(ctx, pcmBuffer)
        val exoPlayerInstance = ExoPlayer.Builder(ctx, renderersFactory).build()
        // audioRecordWrapper = AudioRecordWrapper3(ctx)
        audioRecordWrapper?.config(exoPlayerInstance, pcmBuffer)

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
        audioRecordWrapper = null
        _isStreamingFlow.tryEmit(false)
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val buffer = frameFactory.create(bufferSize!!, 0)
        val length = audioRecordWrapper?.read(buffer.rawBuffer, buffer.rawBuffer.remaining()) ?: 0
        if (length > 0) {
            buffer.timestampInUs = System.nanoTime() / 1000
            return buffer
        } else {
            // TODO
            android.util.Log.w(TAG, "Failed to read audio data, filling buffer with blanks.")
            while (buffer.rawBuffer.hasRemaining()) {
                buffer.rawBuffer.put(0) // Fill with blanks (zeros)
            }
            buffer.timestampInUs = System.nanoTime() / 1000
            return buffer
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val audioRecordWrapper = requireNotNull(audioRecordWrapper) { "Audio source is not initialized" }
        val buffer = frame.rawBuffer
        val length = audioRecordWrapper.read(buffer, buffer.remaining())
//        android.util.Log.d(TAG, "fillAudioFrame called with buffer size: ${buffer.remaining()} and length: $length")
        frame.timestampInUs = System.nanoTime() / 1000
        buffer.flip()
        return frame
    }

    class Factory(private val audioRecordWrapper: AudioRecordWrapper3) : IAudioSourceInternal.Factory {
        override suspend fun create(context: Context): IAudioSourceInternal {
            val customAudioInput = CustomAudioInput3(context)
            customAudioInput.audioRecordWrapper = audioRecordWrapper
            return customAudioInput
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput3
        }
    }
}
