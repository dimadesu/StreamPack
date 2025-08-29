package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AudioRecordWrapper3(
    private val context: Context,
    private var exoPlayer: ExoPlayer? = null,
    private val audioBuffer: CircularPcmBuffer
) {

    companion object {
        private const val TAG = "AudioRecordWrapper3"
    }

    suspend fun config() {
        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
            val mediaSource = ProgressiveMediaSource.Factory(
                try {
                    androidx.media3.datasource.rtmp.RtmpDataSource.Factory()
                } catch (e: Exception) {
                    androidx.media3.datasource.DefaultDataSource.Factory(context)
                }
            ).createMediaSource(mediaItem)
            exoPlayer?.setMediaSource(mediaSource)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true
        }
    }

    init {
    }
    /**
     * Starts recording audio. Add custom behavior here if needed.
     */
    suspend fun startRecording() {

        audioBuffer.clear()
        android.util.Log.i(TAG, "Audio buffer cleared before streaming start.")
        withContext(Dispatchers.Main) {
            exoPlayer?.playWhenReady = true
        }
    }

    /**
     * Stops recording audio.
     */
    suspend fun stop() {
        withContext(Dispatchers.Main) {
            exoPlayer?.playWhenReady = false
        }
    }

    /**
     * Reads audio data into the provided buffer.
     */
    fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        return audioRecord.read(buffer, offset, size)
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
    }
}
