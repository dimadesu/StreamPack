package io.github.thibaultbee.streampack.app.sources.audio

import android.Manifest
import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class CustomAudioInput2(private val audioSource: Int) : IAudioSourceInternal {
    private var audioRecordWrapper: AudioRecordWrapper? = null
    private var bufferSize: Int? = null

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private lateinit var circularBuffer: CircularPcmBuffer

    companion object {
        private const val TAG = "CustomAudioInput2"
    }

    override suspend fun configure(config: AudioSourceConfig) {
        audioRecordWrapper?.release()
        bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
        val audioRecord = AudioRecord(
            audioSource,
            config.sampleRate,
            config.channelConfig,
            config.byteFormat,
            bufferSize!!
        )
        audioRecordWrapper = AudioRecordWrapper(audioRecord)
        circularBuffer = CircularPcmBuffer(bufferSize!! * 2) // Double the buffer size for safety
    }

    override suspend fun startStream() {
        audioRecordWrapper?.startRecording()
        _isStreamingFlow.tryEmit(true)

        // Start a coroutine to continuously read from AudioRecord and write to the buffer
        // kotlinx.coroutines.GlobalScope.launch {
        //     val tempBuffer = ByteBuffer.allocateDirect(bufferSize!!)
        //     while (_isStreamingFlow.value) {
        //         tempBuffer.clear()
        //         val bytesRead = audioRecordWrapper?.read(tempBuffer.array(), 0, tempBuffer.remaining()) ?: 0
        //         if (bytesRead > 0) {
        //             tempBuffer.flip()
        //             circularBuffer.write(tempBuffer)
        //         }
        //     }
        // }
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
            val audioSource = MediaRecorder.AudioSource.MIC // Example: Create audioSource here
            return CustomAudioInput2(audioSource)
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput2
        }
    }
}
