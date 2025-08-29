package io.github.thibaultbee.streampack.app.sources.audio

import android.Manifest
import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CustomAudioInput2 : IAudioSourceInternal {
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    companion object {
        private const val TAG = "CustomAudioInput2"
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configure(config: AudioSourceConfig) {
        audioRecord?.release()
        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            config.channelConfig,
            config.byteFormat,
            bufferSize!!
        )
    }

    override suspend fun startStream() {
        audioRecord?.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        audioRecord?.stop()
        _isStreamingFlow.tryEmit(false)
    }

    override fun release() {
        audioRecord?.release()
        audioRecord = null
        _isStreamingFlow.tryEmit(false)
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val buffer = frameFactory.create(bufferSize!!, 0)
        val length = audioRecord?.read(buffer.rawBuffer, buffer.rawBuffer.remaining()) ?: 0
        if (length > 0) {
            buffer.timestampInUs = System.nanoTime() / 1000
            return buffer
        } else {
            buffer.close()
            throw IllegalStateException("Failed to read audio data")
        }
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val audioRecord = requireNotNull(audioRecord) { "Audio source is not initialized" }
        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("Audio source is not recording")
        }

        val buffer = frame.rawBuffer
        val length = audioRecord.read(buffer, buffer.remaining())
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
            return CustomAudioInput2()
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput2
        }
    }
}
