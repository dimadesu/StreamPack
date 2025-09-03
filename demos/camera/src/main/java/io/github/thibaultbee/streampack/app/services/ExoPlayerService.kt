package io.github.thibaultbee.streampack.app.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.app.sources.audio.AudioRecordWrapper3
import io.github.thibaultbee.streampack.app.sources.audio.CustomAudioInput3
import io.github.thibaultbee.streampack.app.ui.main.BufferVisualizerModel
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.app.ui.main.CustomAudioRenderersFactory

class ExoPlayerService : Service() {

    private val binder = ExoPlayerBinder()
    private lateinit var exoPlayerInstance: ExoPlayer
    private lateinit var pcmBuffer: CircularPcmBuffer
    private var bufferVisualizerModel: BufferVisualizerModel? = null

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        initializeExoPlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayerInstance.release()
    }

    private fun initializeExoPlayer() {
        val application = applicationContext
        pcmBuffer = CircularPcmBuffer(1024 * 64) // Example buffer size
        val renderersFactory = CustomAudioRenderersFactory(application, pcmBuffer)
        exoPlayerInstance = ExoPlayer.Builder(application, renderersFactory).build()

        val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(application)
        ).createMediaSource(mediaItem)
        exoPlayerInstance.setMediaSource(mediaSource)
        exoPlayerInstance.prepare()

        val audioRecordWrapper = AudioRecordWrapper3(exoPlayerInstance, pcmBuffer)
        BufferVisualizerModel.circularPcmBuffer = pcmBuffer
        bufferVisualizerModel = BufferVisualizerModel
    }

    fun getExoPlayerInstance(): ExoPlayer = exoPlayerInstance
    fun getAudioRecordWrapper(): AudioRecordWrapper3 = AudioRecordWrapper3(exoPlayerInstance, pcmBuffer)

    inner class ExoPlayerBinder : Binder() {
        fun getService(): ExoPlayerService = this@ExoPlayerService
    }
}
