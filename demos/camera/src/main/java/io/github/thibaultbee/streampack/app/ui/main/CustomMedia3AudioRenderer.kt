package io.github.thibaultbee.streampack.app.ui.main

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Assertions
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer

class CustomMedia3AudioRenderer(
    context: android.content.Context,
    mediaCodecSelector: MediaCodecSelector,
    private val audioBuffer: CircularPcmBuffer
) : MediaCodecAudioRenderer(context, mediaCodecSelector) {
    private var lastPresentationTimeUs: Long = C.TIME_UNSET

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        lastPresentationTimeUs = C.TIME_UNSET
    }

    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codecAdapter: MediaCodecAdapter?,
        buffer: ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: Format
    ): Boolean {
        if (buffer == null) return true
        // Assert monotonic timestamps
//        if (lastPresentationTimeUs != C.TIME_UNSET) {
//            Assertions.checkState(bufferPresentationTimeUs >= lastPresentationTimeUs)
//        }
//        lastPresentationTimeUs = bufferPresentationTimeUs

        // Intercept decoded audio data
        val bytesWritten = audioBuffer.write(buffer)
        android.util.Log.i("CustomMedia3AudioRenderer", "processOutputBuffer: wrote $bytesWritten bytes to audioBuffer")
        android.util.Log.d("CustomMedia3AudioRenderer", "audioBuffer identity (write): ${System.identityHashCode(audioBuffer)} available after write: ${audioBuffer.available()}")

        // Release buffer without rendering to audio device to prevent MediaCodec error -38
        codecAdapter?.releaseOutputBuffer(bufferIndex, false)
        return true
    }
}
