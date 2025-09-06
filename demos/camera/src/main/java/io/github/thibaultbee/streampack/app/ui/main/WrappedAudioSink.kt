package io.github.thibaultbee.streampack.app.ui.main

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.util.zip.CRC32
import io.github.thibaultbee.streampack.core.elements.encoders.RuntimeAudioFormat

@UnstableApi
class WrappedAudioSink(
    private val delegate: AudioSink,
    private val audioBuffer: CircularPcmBuffer
) : AudioSink {

    private val TAG = "WrappedAudioSink"

    override fun setListener(listener: AudioSink.Listener) {
        delegate.setListener(listener)
    }

    override fun setPlayerId(playerId: PlayerId?) {
        delegate.setPlayerId(playerId)
    }

    override fun setClock(clock: androidx.media3.common.util.Clock) {
        delegate.setClock(clock)
    }

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = delegate.getFormatSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = delegate.getCurrentPositionUs(sourceEnded)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // If the input format reports sample rate / channel count / pcm encoding, update our
        // CircularPcmBuffer so duration math matches the actual PCM produced by the sink.
        try {
            val sampleRate = if (inputFormat.sampleRate == Format.NO_VALUE) null else inputFormat.sampleRate
            val channelCount = if (inputFormat.channelCount == Format.NO_VALUE) null else inputFormat.channelCount
            val pcmEncoding = if (inputFormat.pcmEncoding == Format.NO_VALUE) null else inputFormat.pcmEncoding

            if (sampleRate != null && channelCount != null && pcmEncoding != null) {
                Log.i(TAG, "Sink configure: sampleRate=$sampleRate channelCount=$channelCount pcmEncoding=$pcmEncoding")
                val bytesPerSample = when (pcmEncoding) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    android.media.AudioFormat.ENCODING_PCM_32BIT -> 4
                    else -> 2
                }
                audioBuffer.updateFormat(sampleRate, channelCount, bytesPerSample)
                		// Propagate runtime format to core so encoder can prefer it when created.
                		RuntimeAudioFormat.set(sampleRate, channelCount, bytesPerSample)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to extract format from sink configure: ${t.message}")
        }

        delegate.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun play() { delegate.play() }

    override fun handleDiscontinuity() { delegate.handleDiscontinuity() }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        // Copy the buffer contents so we can compute diagnostics and hand a copy to the circular buffer
        val originalPosition = buffer.position()
        val len = buffer.remaining()
        if (len > 0) {
            try {
                val copy = ByteBuffer.allocate(len)
                // Put advances both positions; we want the delegate to see the original buffer unchanged
                val tmp = buffer.duplicate()
                copy.put(tmp)
                copy.flip()

                // CRC32 diagnostics
                val dup = copy.duplicate()
                val arr = ByteArray(dup.remaining())
                dup.get(arr)
                val crc = CRC32()
                crc.update(arr)
                val crcVal = crc.value

                // Attempt to compute a sink-adjusted timestamp.
                // Use the sink's current playback position as an anchor when available so
                // forwarded timestamps reflect playback-time adjustments (latency/speed).
                var adjustedTs = presentationTimeUs
                try {
                    val sinkPos = try { getCurrentPositionUs(false) } catch (t: Throwable) { Long.MIN_VALUE }
                    if (sinkPos != Long.MIN_VALUE && sinkPos > 0L) {
                        // Use sink position as a more accurate playback anchor.
                        adjustedTs = sinkPos
                    }
                } catch (t: Throwable) {
                    // ignore and fall back to presentationTimeUs
                }

                // Write to our circular buffer (non-blocking) and capture sequence id for correlation
                val seq = audioBuffer.writeFrame(copy, adjustedTs)
                Log.d(TAG, "SINK-INTERCEPT: origTs=$presentationTimeUs adjustedTs=$adjustedTs size=$len seq=$seq crc=0x${java.lang.Long.toHexString(crcVal)}")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy PCM buffer for diagnostics: ${e.message}")
            } finally {
                // restore original buffer position
                buffer.position(originalPosition)
            }
        }

        // Delegate actual handling to the underlying sink
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun playToEndOfStream() { delegate.playToEndOfStream() }

    override fun isEnded(): Boolean = delegate.isEnded()

    override fun hasPendingData(): Boolean = delegate.hasPendingData()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { delegate.setPlaybackParameters(playbackParameters) }

    override fun getPlaybackParameters(): PlaybackParameters = delegate.getPlaybackParameters()

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { delegate.setSkipSilenceEnabled(skipSilenceEnabled) }

    override fun getSkipSilenceEnabled(): Boolean = delegate.getSkipSilenceEnabled()

    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) { delegate.setAudioAttributes(audioAttributes) }

    override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = delegate.getAudioAttributes()

    override fun setAudioSessionId(audioSessionId: Int) { delegate.setAudioSessionId(audioSessionId) }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) { delegate.setAuxEffectInfo(auxEffectInfo) }

    override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) { delegate.setPreferredDevice(audioDeviceInfo) }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { delegate.setOutputStreamOffsetUs(outputStreamOffsetUs) }

    override fun getAudioTrackBufferSizeUs(): Long = delegate.getAudioTrackBufferSizeUs()

    override fun enableTunnelingV21() { delegate.enableTunnelingV21() }

    override fun disableTunneling() { delegate.disableTunneling() }

    override fun setOffloadMode(offloadMode: Int) { delegate.setOffloadMode(offloadMode) }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) { delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames) }

    override fun setVolume(volume: Float) { delegate.setVolume(volume) }

    override fun pause() { delegate.pause() }

    override fun flush() { delegate.flush() }

    override fun reset() { delegate.reset() }

    override fun release() { delegate.release() }
}
