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
    private val _isDecodeOnlyBuffer = true

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
        // Intercept decoded audio data
        if (buffer != null) {
            val bytesWritten = audioBuffer.write(buffer)
            android.util.Log.i("CustomMedia3AudioRenderer", "processOutputBuffer: wrote $bytesWritten bytes to audioBuffer")
            android.util.Log.d("CustomMedia3AudioRenderer", "audioBuffer identity (write): ${System.identityHashCode(audioBuffer)} available after write: ${audioBuffer.available()}")
        }


        return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codecAdapter, buffer, bufferIndex, bufferFlags, sampleCount, bufferPresentationTimeUs, _isDecodeOnlyBuffer, isLastBuffer, format)

    }
}
