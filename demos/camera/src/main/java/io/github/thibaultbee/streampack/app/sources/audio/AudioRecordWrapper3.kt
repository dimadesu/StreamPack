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

class AudioRecordWrapper3(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    public lateinit var audioBuffer: CircularPcmBuffer

    companion object {
        private const val TAG = "AudioRecordWrapper3"
    }

    suspend fun config(exoPlayer: ExoPlayer, audioBuffer: CircularPcmBuffer) {
        this.exoPlayer = exoPlayer
        this.audioBuffer = audioBuffer
        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
            val mediaSource = ProgressiveMediaSource.Factory(
                DefaultDataSource.Factory(context)
            ).createMediaSource(mediaItem)
            this@AudioRecordWrapper3.exoPlayer?.setMediaSource(mediaSource)
//            exoPlayer?.prepare()
//            exoPlayer?.playWhenReady = true
        }
    }

    /**
     * Starts recording audio. Add custom behavior here if needed.
     */
    suspend fun startRecording() {
        audioBuffer.clear()
        android.util.Log.i(TAG, "Audio buffer cleared before streaming start.")
        withContext(Dispatchers.Main) {
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    /**
     * Stops recording audio.
     */
    suspend fun stop() {
        withContext(Dispatchers.Main) {
            exoPlayer?.stop()
        }
    }

    /**
     * Reads audio data into the provided buffer.
     */
    fun read(buffer: ByteBuffer, size: Int): Int {
        // Read data from CircularPcmBuffer into the ByteBuffer
        val bytesRead = audioBuffer.read(buffer, size)

        // Return the number of bytes read
        return bytesRead
    }

    /**
     * Releases the AudioRecord resources.
     */
    fun release() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            exoPlayer?.release()
            exoPlayer = null
        } else {
            Handler(Looper.getMainLooper()).post {
                exoPlayer?.release()
                exoPlayer = null
            }
        }
        audioBuffer.clear()
    }
}
