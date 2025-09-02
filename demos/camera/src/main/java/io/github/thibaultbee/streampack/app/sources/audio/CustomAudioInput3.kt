package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import android.media.AudioRecord
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.app.ui.main.BufferVisualizerModel
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.app.ui.main.CustomAudioRenderersFactory
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CustomAudioInput3(
    private var audioRecordWrapper: AudioRecordWrapper3,
    private val bufferVisualizerModel: BufferVisualizerModel
) : IAudioSourceInternal {

    var bufferSize: Int? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    companion object {
        private const val TAG = "CustomAudioInput3"
    }

    override suspend fun configure(config: AudioSourceConfig) {
//        audioRecordWrapper.release()
        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
    }

    override suspend fun startStream() {
        audioRecordWrapper.startRecording()
        bufferVisualizerModel.setStreamingState(true)
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        bufferVisualizerModel.setStreamingState(false)
        audioRecordWrapper.stop()
        _isStreamingFlow.tryEmit(false)
    }

    override fun release() {
        audioRecordWrapper.release()
        _isStreamingFlow.tryEmit(false)
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val buffer = frameFactory.create(bufferSize!!, 0)
        val (bytesRead, timestamp) = audioRecordWrapper.read(buffer.rawBuffer, buffer.rawBuffer.remaining())
        buffer.timestampInUs = timestamp ?: (System.nanoTime() / 1000)
        buffer.rawBuffer.flip()
        return buffer
    }

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val buffer = frame.rawBuffer
        val (bytesRead, timestamp) = audioRecordWrapper.read(buffer, buffer.remaining())
        // TODO figure out how to build correct timestamp
//        frame.timestampInUs = timestamp ?: (System.nanoTime() / 1000)
        frame.timestampInUs = System.nanoTime() / 1000
        if (bytesRead > 0) {
            android.util.Log.d(TAG, "Audio bytes read: $bytesRead, Timestamp: ${frame.timestampInUs}")
        }
        buffer.flip()
        return frame
    }

    class Factory(
        private var audioRecordWrapper: AudioRecordWrapper3,
        private val bufferVisualizerModel: BufferVisualizerModel
    ) : IAudioSourceInternal.Factory {
        override suspend fun create(context: Context): IAudioSourceInternal {
            val customAudioInput = CustomAudioInput3(audioRecordWrapper, bufferVisualizerModel)
            return customAudioInput
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput3
        }
    }
}
