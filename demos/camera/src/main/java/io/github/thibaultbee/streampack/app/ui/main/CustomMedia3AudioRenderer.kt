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
   // Removed background handler; copy synchronously to avoid buffer reuse by codec

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
           // Copy bytes synchronously before releasing the codec buffer
           val pos = buffer.position()
           val len = buffer.remaining()
           val copy = ByteBuffer.allocate(len)
           copy.put(buffer)
           buffer.position(pos)
           copy.flip()
           audioBuffer.writeFrame(copy, bufferPresentationTimeUs)
       }

       // Continue normal rendering path (lets ExoPlayer release the buffer)
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
}
