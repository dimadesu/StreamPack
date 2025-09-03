package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class AudioRecordWrapper3(
    private val exoPlayer: ExoPlayer,
    private val audioBuffer: CircularPcmBuffer,
) {
    companion object {
        private const val TAG = "AudioRecordWrapper3"
    }

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
    fun read(rawFrame: RawFrame): Int {
        val audioBuffer = this.audioBuffer ?: throw IllegalStateException("audioBuffer is not initialized. Call config() first.")
        // Read data from CircularPcmBuffer using readFrame
        val frame = audioBuffer.readFrame()
        var bytesToRead = 0
        if (frame != null) {
            val (data, timestamp) = frame
            bytesToRead = minOf(rawFrame.rawBuffer.remaining(), data.remaining())
            val tempArray = ByteArray(bytesToRead)

            // Read from the source buffer safely
            data.get(tempArray, 0, bytesToRead)

            // Write to the destination buffer safely
            rawFrame.rawBuffer.put(tempArray, 0, bytesToRead)
        }
        rawFrame.rawBuffer.flip()
        rawFrame.timestampInUs = System.nanoTime() / 1000
        // rawFrame.timestampInUs = timestamp ?: (System.nanoTime() / 1000)
        // TODO figure out how to build correct timestamp
//        if (bytesToRead > 0) {
//            android.util.Log.d(TAG, "Audio bytes read: $bytesToRead, Timestamp: ${rawFrame.timestampInUs}")
//        }
        return bytesToRead
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
