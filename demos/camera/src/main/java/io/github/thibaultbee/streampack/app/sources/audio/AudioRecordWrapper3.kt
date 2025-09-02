package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
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
    fun read(buffer: ByteBuffer, size: Int): Int {
        val audioBuffer = this.audioBuffer ?: throw IllegalStateException("audioBuffer is not initialized. Call config() first.")
        // Read data from CircularPcmBuffer using readFrame
        // TODO pass timestamp
        val frame = audioBuffer.readFrame()
        if (frame != null) {
            val (data, _) = frame
            val bytesToRead = minOf(size, data.remaining())
            buffer.put(data.array(), data.arrayOffset(), bytesToRead)
            return bytesToRead
        }
        return 0 // No data available
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
