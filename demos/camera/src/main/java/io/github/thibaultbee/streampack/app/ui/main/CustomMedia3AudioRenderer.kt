package io.github.thibaultbee.streampack.app.ui.main

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer

class CustomMedia3AudioRenderer(
    context: android.content.Context,
    mediaCodecSelector: MediaCodecSelector,
    private val audioBuffer: CircularPcmBuffer
) : MediaCodecAudioRenderer(
        context,
        mediaCodecSelector,
) {
//    private val handlerThread = android.os.HandlerThread("AudioRendererThread").apply { start() }
//    private val backgroundHandler = android.os.Handler(handlerThread.looper)

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
        if (buffer != null && buffer.remaining() > 0) {
//            backgroundHandler.post {
                audioBuffer.writeFrame(buffer, bufferPresentationTimeUs)
//            }
        }
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codecAdapter,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            bufferPresentationTimeUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )
   }

   override fun onRelease() {
       super.onRelease()
//       handlerThread.quitSafely()
   }
}
