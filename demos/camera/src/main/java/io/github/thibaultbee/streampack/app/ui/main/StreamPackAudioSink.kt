package io.github.thibaultbee.streampack.app.ui.main

import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

@UnstableApi
class StreamPackAudioSink(private val pcmBuffer: ByteBuffer) : AudioSink {
    // Required AudioSink methods
    override fun setListener(listener: AudioSink.Listener) {}
    override fun supportsFormat(format: Format): Boolean = true
    override fun isEnded(): Boolean = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(1f, 1f)
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled(): Boolean = false
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {}
    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // No-op for StreamPack, but you can store format info if needed
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        synchronized(pcmBuffer) {
            if (pcmBuffer.remaining() >= buffer.remaining()) {
                pcmBuffer.put(buffer)
            } else {
                pcmBuffer.clear() // Drop old data if overflow
                pcmBuffer.put(buffer)
            }
        }
        return true // Indicate buffer was handled
    }

    override fun play() {}
    override fun pause() {}
    override fun flush() {}
    override fun reset() {}
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}
    override fun getCurrentPositionUs(isEnded: Boolean): Long = 0L
    // Removed overrides for methods not present in the latest AudioSink interface

    // Newer AudioSink interface methods
    override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    override fun handleDiscontinuity() {}
    override fun playToEndOfStream() {}
    override fun hasPendingData(): Boolean = false
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun getAudioTrackBufferSizeUs(): Long = 0L
}
