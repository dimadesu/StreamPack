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
       audioBuffer.clear()
    }

    /**
     * Reads audio data into the provided buffer and returns a Pair of bytes read and the timestamp.
     */
    fun read(buffer: ByteBuffer, size: Int): Pair<Int, Long?> {
//        android.util.Log.w(TAG, "1111")
        val frame = audioBuffer.readFrame()

        if (frame == null) {
//            android.util.Log.w(TAG, "Audio buffer is empty, returning 0 bytes and null timestamp.")
            return Pair(0, null) // Return 0 bytes read and null timestamp if the buffer is empty
        }

//        android.util.Log.w(TAG, "2222")

        val (data, timestamp) = frame

//        android.util.Log.w(TAG, "3333")

        val bytesToRead = minOf(size, data.size)

//        android.util.Log.w(TAG, "4444")

        buffer.put(data, 0, bytesToRead)

        android.util.Log.d(TAG, "Audio received $bytesToRead bytes, timestamp: $timestamp")
        return Pair(bytesToRead, timestamp)
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
        }
        audioBuffer.clear()
    }
}
