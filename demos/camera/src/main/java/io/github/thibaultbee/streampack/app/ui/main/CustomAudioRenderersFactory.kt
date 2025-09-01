package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import java.util.ArrayList

@UnstableApi
class CustomAudioRenderersFactory(
    private val context: Context,
    private val audioBuffer: CircularPcmBuffer
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: androidx.media3.exoplayer.audio.AudioSink,
        eventHandler: android.os.Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        out.add(
            CustomMedia3AudioRenderer(
                context,
                mediaCodecSelector,
                audioBuffer
            )
        )
    }
}
