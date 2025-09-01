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
    suspend fun startRecording() {
        audioBuffer.clear()
        android.util.Log.i(TAG, "Audio buffer cleared before streaming start.")
        withContext(Dispatchers.Main) {
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
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
        // Read data from CircularPcmBuffer into the ByteBuffer
        val bytesRead = audioBuffer.read(buffer, size)

        // Return the number of bytes read
        return bytesRead
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
