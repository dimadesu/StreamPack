package io.github.thibaultbee.streampack.app.ui.main

import android.util.Log
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
       var copy = ByteBuffer.allocate(0)
       var originalPosition = 0

       if (buffer != null) {

           // Copy bytes synchronously before releasing the codec buffer
           originalPosition = buffer.position()
//       val len = buffer.remaining()
//       val copy = ByteBuffer.allocate(len)
//       copy.put(buffer)
//       buffer.position(originalPosition)
//       copy.flip()

           copy = buffer.asReadOnlyBuffer()
           copy.position(originalPosition)
//       copy.limit(buffer.limit())

//           Log.i("XXX", "before buffer.position ${buffer.position()}, buffer.limit ${buffer.limit()}, buffer.remaining ${buffer.remaining()}. copy position ${copy.position()}, copy limit ${copy.limit()}, copy remaining ${copy.remaining()}")
       }

       // Continue normal rendering path (lets ExoPlayer release the buffer)
       val isFullyProcessed =  super.processOutputBuffer(
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

       if (buffer != null) {

           val bytesRead = buffer.position() - originalPosition

           Log.i("XXX", "writing bytes $bytesRead")

//           Log.i("XXX", "after buffer.position ${buffer.position()}, buffer.limit ${buffer.limit()}, buffer.remaining ${buffer.remaining()}. copy position ${copy.position()}, copy limit ${copy.limit()}, copy remaining ${copy.remaining()}, isFullyProcessed $isFullyProcessed")

           if (!isFullyProcessed) {

               if (bytesRead > 0) {
                   copy.limit(originalPosition + bytesRead)
//                   Log.i("XXX", "after2 buffer.position ${buffer.position()}, buffer.limit ${buffer.limit()}, buffer.remaining ${buffer.remaining()}. copy position ${copy.position()}, copy limit ${copy.limit()}, copy remaining ${copy.remaining()}")
                   audioBuffer.writeFrame(copy, bufferPresentationTimeUs)
               }

           } else {
               audioBuffer.writeFrame(copy, bufferPresentationTimeUs)
           }
       }

       return isFullyProcessed
   }
}
