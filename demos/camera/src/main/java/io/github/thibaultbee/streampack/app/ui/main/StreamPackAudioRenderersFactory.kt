package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlayer.Builder
import androidx.media3.exoplayer.source.MediaSource
import java.nio.ByteBuffer

@UnstableApi
class StreamPackAudioRenderersFactory(
    private val context: Context,
    private val audioSink: AudioSink
) : DefaultRenderersFactory(context) {
    @Suppress("UNCHECKED_CAST")
    protected override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: ArrayList<androidx.media3.exoplayer.Renderer>
    ) {
        out.add(
            androidx.media3.exoplayer.audio.MediaCodecAudioRenderer(
                context,
                mediaCodecSelector,
                eventHandler,
                eventListener,
                this.audioSink
            )
        )
    }
}
