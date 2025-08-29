package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.IAudioRecordSource
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * A custom audio input implementation for StreamPack that generates synthetic audio data.
 */
class CustomAudioInput : IAudioSourceInternal, IAudioRecordSource {
    private var isStreaming = false
    private val sampleRate = 44100
    private val bufferSize = 1024
    private val sineWaveBuffer = ByteArray(bufferSize)

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    override suspend fun configure(config: AudioSourceConfig) {
        // Configuration logic if needed
    }

    override suspend fun startStream() {
        Logger.d(TAG, "Starting custom audio input stream")
        isStreaming = true
        _isStreamingFlow.tryEmit(true)
        generateSineWave()
    }

    override suspend fun stopStream() {
        Logger.d(TAG, "Stopping custom audio input stream")
        isStreaming = false
        _isStreamingFlow.tryEmit(false)
    }

    private fun generateSineWave() {
        val frequency = 440.0 // A4 note
        val amplitude = 32767
        val twoPiF = 2 * Math.PI * frequency / sampleRate

        for (i in sineWaveBuffer.indices step 2) {
            val sample = (amplitude * Math.sin(twoPiF * (i / 2))).toInt()
            sineWaveBuffer[i] = (sample and 0xFF).toByte()
            sineWaveBuffer[i + 1] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        if (!isStreaming) throw IllegalStateException("Audio source is not streaming")
        val buffer = frame.rawBuffer
        buffer.put(sineWaveBuffer)
        frame.timestampInUs = System.nanoTime() / 1000
        return frame
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }

    override fun addEffect(effectType: UUID): Boolean {
        Logger.d(TAG, "Adding effect: $effectType")
        return false // No effects supported in this implementation
    }

    override fun removeEffect(effectType: UUID) {
        Logger.d(TAG, "Removing effect: $effectType")
        // No effects supported in this implementation
    }

    override fun release() {
        isStreaming = false
        _isStreamingFlow.tryEmit(false)
    }

    companion object {
        private const val TAG = "CustomAudioInput"
    }

    class Factory : IAudioSourceInternal.Factory {
        override suspend fun create(context: Context): IAudioSourceInternal {
            return CustomAudioInput()
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput
        }
    }
}
