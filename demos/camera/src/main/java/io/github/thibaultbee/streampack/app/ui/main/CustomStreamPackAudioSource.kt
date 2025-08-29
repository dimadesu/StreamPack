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
    lateinit var audioBuffer: CircularPcmBuffer
    private var context: Context? = null
        init {
            // Log buffer identity when assigned
            android.util.Log.d("CustomAudioSource", "audioBuffer identity (init): not yet assigned")
        }
    private var streamStartTimeUs: Long = 0L
    private var firstAudioBufferTimestampUs: Long? = null
    private var audioSampleRate: Int = 44100 // Default, update from config if needed
    private var totalSamplesSent: Long = 0L
        // For hardware timestamp emulation (if available)
        // If you use AudioRecord, you can get hardware timestamp. Here, fallback to monotonic system time
    fun isReady(): Boolean {
        val frameSize = 3840 // Match encoder expectation
        return audioBuffer.available() >= frameSize
    }

    override suspend fun startStream() {
        streamStartTimeUs = System.nanoTime() / 1000L
        firstAudioBufferTimestampUs = null
        totalSamplesSent = 0L
        android.util.Log.i("CustomAudioSource", "Audio stream start: sampleRate=$audioSampleRate, totalSamplesSent=$totalSamplesSent, streamStartTimeUs=$streamStartTimeUs")
        audioBuffer.clear()
        android.util.Log.i("CustomAudioSource", "Audio buffer cleared before streaming start.")
        withContext(Dispatchers.Main) {
            exoPlayer?.playWhenReady = true
        }
        // Delay to allow ExoPlayer to start and buffer to fill
        kotlinx.coroutines.delay(200)
        _isStreamingFlow.value = true
    }

    override suspend fun stopStream() {
        withContext(Dispatchers.Main) {
            exoPlayer?.playWhenReady = false
        }
        _isStreamingFlow.value = false
    }

    override suspend fun configure(config: AudioSourceConfig) {
        audioSampleRate = config.sampleRate
        val ctx = requireNotNull(context) { "Context must be set before configure" }
    val bufferSize = 3840 // Match encoder expectation
    val pcmBuffer = CircularPcmBuffer(bufferSize * 32) // Increased buffer size for more audio buffering
        val renderersFactory = CustomAudioRenderersFactory(ctx, pcmBuffer)
        val rtmpUrl = "rtmp://localhost:1935/publish/live"
        val mediaItem = MediaItem.fromUri(rtmpUrl)
        val mediaSource = ProgressiveMediaSource.Factory(
            try {
                androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
            } catch (e: Exception) {
                androidx.media3.datasource.DefaultDataSource.Factory(ctx)
            }
        ).createMediaSource(mediaItem)
        val exoPlayerInstance = ExoPlayer.Builder(ctx, renderersFactory).build()
        withContext(Dispatchers.Main) {
            exoPlayerInstance.setMediaSource(mediaSource)
            exoPlayerInstance.prepare()
            exoPlayerInstance.playWhenReady = true
        }
        exoPlayer = exoPlayerInstance
        audioBuffer = pcmBuffer
        android.util.Log.d("CustomAudioSource", "audioBuffer identity (assigned): ${System.identityHashCode(pcmBuffer)}")
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
        android.util.Log.i("CustomAudioSource", "fillAudioFrame called: frame capacity=${frame.rawBuffer.capacity()} remaining=${frame.rawBuffer.remaining()}")
        val bytesPerSample = 2 * 2 // 2 bytes per sample, 2 channels
        synchronized(audioBuffer) {
            val available = audioBuffer.available()
            val frameCapacity = frame.rawBuffer.remaining()
            android.util.Log.i("CustomAudioSource", "fillAudioFrame: available=$available frameCapacity=$frameCapacity")
            android.util.Log.d("CustomAudioSource", "audioBuffer identity (read): ${System.identityHashCode(audioBuffer)} available before read: $available")
            val maxAligned = (minOf(available, frameCapacity) / bytesPerSample) * bytesPerSample
            if (maxAligned > 0) {
                // Prepare ByteBuffer for reading
                val tempBuffer = frame.rawBuffer.slice()
                tempBuffer.limit(maxAligned)
                val bytesRead = audioBuffer.read(tempBuffer)
                frame.rawBuffer.position(frame.rawBuffer.position() + bytesRead)
                frame.rawBuffer.flip()
                android.util.Log.i("CustomAudioSource", "fillAudioFrame: copied $bytesRead bytes (sample-aligned)")
                android.util.Log.d("CustomAudioSource", "audioBuffer available after read: ${audioBuffer.available()}")
            } else {
                if (available > 0 || frameCapacity > 0) {
                    android.util.Log.w("CustomAudioSource", "fillAudioFrame: WARNING - not enough data for a full sample-aligned frame (available=$available, frameCapacity=$frameCapacity, bytesPerSample=$bytesPerSample)")
                }
            }
        }
        return frame
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        android.util.Log.i("CustomAudioSource", "getAudioFrame called: buffer capacity=${audioBuffer.capacity}")
        val bytesPerSample = 2 * 2 // 2 bytes per sample, 2 channels
        val frameSize = 3840 // Match encoder expectation
        val available = audioBuffer.available()
        if (available < frameSize) {
            android.util.Log.e("CustomAudioSource", "ERROR: Not enough data for a full frame: available=$available, required=$frameSize. PCM data may not be flowing from renderer. Check ExoPlayer renderer and PCM source.")
            throw IllegalStateException("Not enough audio data for a full frame")
        }
        val frame = frameFactory.create(frameSize, 0)
        val result = fillAudioFrame(frame)
        val audioDataSize = result.rawBuffer.position()
        android.util.Log.i("CustomAudioSource", "getAudioFrame: audioDataSize=$audioDataSize")
        if (audioDataSize == 0) {
            android.util.Log.e("CustomAudioSource", "ERROR: Skipping frame with zero audio data, timestamp: ${streamStartTimeUs}. PCM data may not be flowing from renderer.")
            result.close()
            throw IllegalStateException("No audio data to send")
        }
        if (audioDataSize % bytesPerSample != 0) {
            android.util.Log.w("CustomAudioSource", "WARNING: Frame is not sample-aligned! audioDataSize=$audioDataSize, bytesPerSample=$bytesPerSample")
            // Optionally, drop or pad the frame here
        }
        val samplesInFrame = audioDataSize / bytesPerSample
        // Moblin-style timestamp normalization: store first buffer timestamp and use zero-based timeline
        val currentBufferTimestampUs = System.nanoTime() / 1000L
        if (firstAudioBufferTimestampUs == null) {
            firstAudioBufferTimestampUs = currentBufferTimestampUs
        }
        val normalizedTimestampUs = currentBufferTimestampUs - (firstAudioBufferTimestampUs ?: 0L)
        val timestampInUs = streamStartTimeUs + normalizedTimestampUs
        android.util.Log.i("CustomAudioSource", "AUDIO TIMESTAMP (Moblin-style): streamStartTimeUs=$streamStartTimeUs, firstAudioBufferTimestampUs=$firstAudioBufferTimestampUs, currentBufferTimestampUs=$currentBufferTimestampUs, normalizedTimestampUs=$normalizedTimestampUs, timestampInUs=$timestampInUs, samplesInFrame=$samplesInFrame")
        result.timestampInUs = timestampInUs
        android.util.Log.w("CustomAudioSource", "Before return: result.timestampInUs=${result.timestampInUs}, totalSamplesSent=$totalSamplesSent")
        android.util.Log.d("CustomAudioSource", "getAudioFrame returning frame with buffer size: $audioDataSize, timestampInUs: $timestampInUs, samplesInFrame: $samplesInFrame, totalSamplesSent: $totalSamplesSent")
        totalSamplesSent += samplesInFrame
        return result
    }

    class Factory : IAudioSourceInternal.Factory {
        @OptIn(UnstableApi::class)
        override suspend fun create(context: Context): IAudioSourceInternal {
            val customSrc = CustomStreamPackAudioSourceInternal()
            customSrc.context = context
            return customSrc
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomStreamPackAudioSourceInternal
        }
    }
}
