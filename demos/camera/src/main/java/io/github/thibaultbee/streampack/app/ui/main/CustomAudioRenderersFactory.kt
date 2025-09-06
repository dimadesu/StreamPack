package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
        android.util.Log.d("CustomAudioRenderersFactory", "Calling super.buildVideoRenderers with context: $context")
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
        // Create DefaultAudioSink with our custom AudioTrackProvider that routes to CircularPcmBuffer
        val customAudioSink = DefaultAudioSink.Builder(context)
            .setAudioTrackProvider(CircularBufferAudioTrackProvider(audioBuffer))
            .build()
        
        // Use super.buildAudioRenderers with our custom sink - this gives us standard
        // ExoPlayer audio renderers but with PCM data routed to our CircularPcmBuffer
        super.buildAudioRenderers(
            context, 
            extensionRendererMode, 
            mediaCodecSelector, 
            enableDecoderFallback, 
            customAudioSink,  // Use our custom sink instead of the provided one
            eventHandler, 
            eventListener, 
            out
        )
        
        android.util.Log.d("CustomAudioRenderersFactory", "Built audio renderers with CircularBufferAudioTrackProvider")
    }
}
