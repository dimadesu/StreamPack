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
    private var audioRecordWrapper: AudioRecordWrapper3? = null
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

        val pcmBuffer = CircularPcmBuffer(safeBufferSize * 320)
        val renderersFactory = CustomAudioRenderersFactory(ctx, pcmBuffer)
        val exoPlayerInstance = ExoPlayer.Builder(ctx, renderersFactory).build()

        android.util.Log.d("CustomAudioSource", "audioBuffer identity (assigned): ${System.identityHashCode(pcmBuffer)}")
        audioRecordWrapper = AudioRecordWrapper3(ctx, exoPlayerInstance, pcmBuffer)
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
        android.util.Log.d(TAG, "fillAudioFrame called with buffer size: ${buffer.remaining()} and length: $length")
        if (length > 0) {
            frame.timestampInUs = System.nanoTime() / 1000
            return frame
        } else {
            android.util.Log.w(TAG, "Failed to read audio data, filling frame with a sine wave pattern.")
            val frequency = 440.0 // Frequency of the sine wave in Hz
            val sampleRate = 44100 // Sample rate in Hz
            val amplitude = 32767 // Max amplitude for 16-bit PCM
            var phase = 0.0
            val phaseIncrement = 2.0 * Math.PI * frequency / sampleRate

            while (buffer.hasRemaining()) {
                val sample = (amplitude * Math.sin(phase)).toInt()
                buffer.putShort(sample.toShort())
                phase += phaseIncrement
                if (phase >= 2.0 * Math.PI) {
                    phase -= 2.0 * Math.PI
                }
            }
            frame.timestampInUs = System.nanoTime() / 1000
            return frame
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
