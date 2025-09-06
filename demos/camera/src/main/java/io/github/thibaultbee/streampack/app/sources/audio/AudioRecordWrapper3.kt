package io.github.thibaultbee.streampack.app.sources.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.core.elements.data.RawFrame

class AudioRecordWrapper3(
    private val exoPlayer: ExoPlayer,
    private val audioBuffer: CircularPcmBuffer,
) {
    companion object {
        private const val TAG = "AudioRecordWrapper3"
    }
    
    private var consecutiveEmptyReads = 0

    /**
     * Starts recording audio. Add custom behavior here if needed.
     */
    fun startRecording() {
        audioBuffer.clear()
        android.util.Log.i(TAG, "Audio buffer cleared before streaming start.")


//       if (Looper.myLooper() == Looper.getMainLooper()) {
//           exoPlayer.prepare()
//           exoPlayer.playWhenReady = true
//       } else {
           Handler(Looper.getMainLooper()).post {
               exoPlayer.prepare()
               exoPlayer.playWhenReady = true
           }
//       }
    }

    /**
     * Stops recording audio.
     */
    fun stop() {
        // I don't know why this doesn't seem to need this main thread thing.
        // It actually breaks transition to setting activity if it's enabled here
        // I think audio input's stopStream is called on activity destroy or smth like that
        // Also maybe smth to do with suspend
//       withContext(Dispatchers.Main) {
            // exoPlayer.stop()
//       }

       Handler(Looper.getMainLooper()).post {
           exoPlayer.stop()
       }
    }

    /**
     * Reads audio data into the provided buffer.
     */
    fun read(streamPackAudioFrame: RawFrame) {
        val exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())
//        streamPackAudioFrame.timestampInUs = System.nanoTime() / 1000

        if (exoPlayerAudioFrame != null) {
            val (data, timestamp) = exoPlayerAudioFrame

            // Check if we actually have audio data to process BEFORE consuming it
            val dataSize = data.remaining()
            if (dataSize > 0) {
                consecutiveEmptyReads = 0 // Reset empty read counter
                streamPackAudioFrame.rawBuffer.put(data)

                // Ensure we never send zero timestamps to StreamPack - this causes SRT rejection
                if (timestamp <= 0) {
                    Log.w(TAG, "TIMESTAMP-DEBUG: Rejecting zero timestamp ($timestamp µs) - using fallback")
                    streamPackAudioFrame.timestampInUs = System.nanoTime() / 1000L
                } else {
                    streamPackAudioFrame.timestampInUs = timestamp
                }
                
                // Debug timestamp flow from ExoPlayer -> StreamPack (log size before consumption)
                Log.i(TAG, "TIMESTAMP-DEBUG: ExoPlayer->StreamPack timestamp=${streamPackAudioFrame.timestampInUs} µs, size=${dataSize}")
                
                // Longer delay to prevent overwhelming the encoder with bursts and reduce underruns
                try {
                    Thread.sleep(5) // 5ms delay to smooth data flow and reduce polling frequency
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } else {
                // Frame exists but has no data - treat as buffer empty
                Log.w(TAG, "TIMESTAMP-DEBUG: Got empty frame from ExoPlayer buffer (${dataSize} bytes)")
                handleEmptyBuffer(streamPackAudioFrame)
            }
        } else {
            // No frame available at all
            consecutiveEmptyReads++
            handleEmptyBuffer(streamPackAudioFrame)
        }

        // CRITICAL: Always flip the buffer after writing data, so StreamPack can read it
        streamPackAudioFrame.rawBuffer.flip()
    }

    private fun handleEmptyBuffer(streamPackAudioFrame: RawFrame) {
        // CRITICAL: When buffer is empty, we must still provide a valid timestamp!
        // This prevents 0µs timestamps that cause SRT rejection
        Log.w(TAG, "TIMESTAMP-DEBUG: No audio frame available from ExoPlayer buffer (attempt ${consecutiveEmptyReads})")
        
        // Adjust delay based on how many consecutive empty reads we've had
        // Use more aggressive delays to reduce polling frequency and prevent stuttering
        val delayMs = when {
            consecutiveEmptyReads < 2 -> 10  // First attempts: moderate delay
            consecutiveEmptyReads < 5 -> 20  // Medium attempts: longer delay
            else -> 30                       // After many attempts: substantial delay
        }
        
        // Brief pause to reduce excessive polling when buffer is empty
        // This reduces CPU usage and gives ExoPlayer time to provide more data
        try {
            Thread.sleep(delayMs.toLong()) 
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        // Generate a monotonic timestamp even when no audio data is available
        // This ensures SRT doesn't reject packets due to 0µs timestamps
        streamPackAudioFrame.timestampInUs = System.nanoTime() / 1000L
        Log.w(TAG, "TIMESTAMP-DEBUG: Using fallback timestamp=${streamPackAudioFrame.timestampInUs} µs (prevents SRT rejection)")
        
        // Fill with silence when no audio data available
        // This maintains continuous audio stream even during buffer underruns
        val bufferSize = streamPackAudioFrame.rawBuffer.remaining()
        val silenceBuffer = ByteArray(bufferSize) // Silence is all zeros
        streamPackAudioFrame.rawBuffer.put(silenceBuffer)
        Log.i(TAG, "TIMESTAMP-DEBUG: Filled ${bufferSize} bytes with silence to maintain stream continuity (${delayMs}ms delay)")
    }

    /**
     * Releases the AudioRecord resources.
     */
    fun release() {
        // I think this also doesn't need main thread stuff for exoPlayer
//       if (Looper.myLooper() == Looper.getMainLooper()) {
//            exoPlayer.release()
//            exoPlayer = null
//       } else {
           Handler(Looper.getMainLooper()).post {
               exoPlayer.release()
//           }
       }
        audioBuffer.clear()
    }
}
