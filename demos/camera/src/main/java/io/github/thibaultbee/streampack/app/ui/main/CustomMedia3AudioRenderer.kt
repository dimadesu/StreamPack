package io.github.thibaultbee.streampack.app.ui.main

import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Format
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

   // Initialize a HandlerThread for background processing
   private val handlerThread = HandlerThread("AudioRendererThread").apply { start() }
   private val backgroundHandler = Handler(handlerThread.looper)

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
           // Copy the buffer data to ensure it remains accessible
           val copiedBuffer = ByteBuffer.allocateDirect(buffer.remaining())
           copiedBuffer.put(buffer)
           copiedBuffer.flip()

           // Offload the write operation to the background thread
           backgroundHandler.post {
               audioBuffer.writeFrame(copiedBuffer, bufferPresentationTimeUs)
           }
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
           _isDecodeOnlyBuffer,
           isLastBuffer,
           format
       )
   }

   override fun onRelease() {
       // First, call the superclass method to ensure its cleanup logic runs.
       super.onRelease()

       // Now, safely quit your custom HandlerThread.
       handlerThread.quitSafely()
   }
}
