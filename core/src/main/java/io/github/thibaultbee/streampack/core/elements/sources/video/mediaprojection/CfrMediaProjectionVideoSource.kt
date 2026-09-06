/*
 * Custom Constant Frame Rate (CFR) MediaProjection source for LifeStreamer.
 * Uses CfrSurfaceProcessor to maintain a continuous 60fps render loop.
 */
package io.github.thibaultbee.streampack.core.elements.sources.video.mediaprojection

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.util.Size
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.processing.video.CfrSurfaceProcessorFactory
import io.github.thibaultbee.streampack.core.elements.processing.video.ISurfaceProcessorInternal
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.source.DefaultSourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.processing.video.source.ISourceInfoProvider
import io.github.thibaultbee.streampack.core.elements.sources.IMediaProjectionSource
import io.github.thibaultbee.streampack.core.elements.sources.video.ISurfaceSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.VideoSourceConfig
import io.github.thibaultbee.streampack.core.elements.utils.RotationValue
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.extensions.densityDpi
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isRotationPortrait
import io.github.thibaultbee.streampack.core.elements.utils.extensions.landscapize
import io.github.thibaultbee.streampack.core.elements.utils.extensions.portraitize
import io.github.thibaultbee.streampack.core.elements.utils.extensions.screenRect
import io.github.thibaultbee.streampack.core.elements.utils.extensions.size
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_VIRTUAL_DISPLAY
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.HandlerThreadExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

class CfrMediaProjectionVideoSource(
    private val context: Context,
    override val mediaProjection: MediaProjection,
    private val dispatcherProvider: IVideoDispatcherProvider,
    private val handlerThreadExecutor: HandlerThreadExecutor,
    private val fps: Int = 30,
    @RotationValue private val overrideRotation: Int? = null,
) : IVideoSourceInternal, ISurfaceSourceInternal, IMediaProjectionSource {
    override val timebase = Timebase.UPTIME
    override val infoProviderFlow =
        MutableStateFlow(
            FullScreenInfoProvider(
                context,
                overrideRotation
            ) as ISourceInfoProvider
        ).asStateFlow()

    private val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private var outputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    
    // Internal surface processor to maintain CFR
    private var surfaceProcessor: ISurfaceProcessorInternal? = null
    private var inputSurface: Surface? = null
    private var outputSurfaceOutput: SurfaceOutput? = null

    private val virtualDisplayHandler = handlerThreadExecutor.handler
    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            super.onPaused()
            Logger.i(TAG, "onPaused")
        }

        override fun onStopped() {
            super.onStopped()
            Logger.i(TAG, "onStopped")

            runBlocking {
                stopStream()
            }
        }
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Logger.i(TAG, "onStop")

            runBlocking {
                stopStream()
            }
        }
    }

    override suspend fun getOutput() = outputSurface
    
    override suspend fun setOutput(surface: Surface) {
        outputSurface = surface
        initializeSurfaceProcessor()
    }

    override suspend fun resetOutput() {
        stopStream()
        
        outputSurfaceOutput?.let { surfaceOutput ->
            surfaceProcessor?.removeOutputSurface(surfaceOutput)
        }
        outputSurfaceOutput = null
        outputSurface = null
    }

    override suspend fun configure(config: VideoSourceConfig) = Unit

    private fun initializeSurfaceProcessor() {
        if (surfaceProcessor == null && outputSurface != null) {
            try {
                val processorFactory = CfrSurfaceProcessorFactory(fps)
                surfaceProcessor = processorFactory.create(DynamicRangeProfile.sdr, dispatcherProvider)

                val screenSize = getMediaProjectionSurfaceSize()
                inputSurface = surfaceProcessor!!.createInputSurface(screenSize, timebase)

                outputSurfaceOutput = SurfaceOutput(
                    targetSurface = outputSurface!!,
                    targetResolution = screenSize,
                    targetRotation = 0,
                    isStreaming = { _isStreamingFlow.value },
                    sourceResolution = screenSize,
                    needMirroring = false,
                    sourceInfoProvider = infoProviderFlow.value
                )
                surfaceProcessor!!.addOutputSurface(outputSurfaceOutput!!)
                
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to initialize surface processor: ${e.message}")
                surfaceProcessor?.release()
                surfaceProcessor = null
            }
        }
    }

    override suspend fun startStream() {
        val screenSize = getMediaProjectionSurfaceSize()
        
        // Ensure processor is initialized before starting
        initializeSurfaceProcessor()
        
        if (inputSurface == null) {
            Logger.e(TAG, "Cannot start stream: inputSurface is null")
            return
        }

        mediaProjection.registerCallback(mediaProjectionCallback, virtualDisplayHandler)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            screenSize.width,
            screenSize.height,
            context.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, // Feed into our custom processor instead of output directly!
            virtualDisplayCallback,
            virtualDisplayHandler
        )
        _isStreamingFlow.emit(true)
    }

    override suspend fun stopStream() {
        virtualDisplay?.release()
        virtualDisplay = null

        try {
            mediaProjection.unregisterCallback(mediaProjectionCallback)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to unregister MediaProjection callback: $e")
        }
        _isStreamingFlow.emit(false)
    }

    override suspend fun release() {
        handlerThreadExecutor.quit()
        
        try {
            inputSurface?.let { surface ->
                surfaceProcessor?.removeInputSurface(surface)
                inputSurface = null
            }
            surfaceProcessor?.release()
            surfaceProcessor = null
            outputSurfaceOutput = null
        } catch (e: Exception) {
            Logger.e(TAG, "Error releasing surface processor: ${e.message}")
        }
    }

    private fun getMediaProjectionSurfaceSize(): Size {
        val screenSize = context.screenRect.size
        return if (overrideRotation != null) {
            if (context.isRotationPortrait(overrideRotation)) {
                screenSize.portraitize
            } else {
                screenSize.landscapize
            }
        } else {
            screenSize
        }
    }

    private inner class FullScreenInfoProvider(
        private val context: Context,
        @RotationValue private val overrideRotation: Int? = null,
    ) :
        DefaultSourceInfoProvider() {
        override fun getSurfaceSize(targetResolution: Size): Size {
            return getMediaProjectionSurfaceSize()
        }
    }

    companion object {
        private const val TAG = "CfrMediaProjectionVideo"
        private const val VIRTUAL_DISPLAY_NAME = "StreamPackScreenSource"
    }
}

class CfrMediaProjectionVideoSourceFactory(
    private val mediaProjection: MediaProjection,
    private val fps: Int = 30,
    @RotationValue private val overrideRotation: Int? = null
) : IVideoSourceInternal.Factory {
    override suspend fun create(
        context: Context,
        dispatcherProvider: IVideoDispatcherProvider
    ): IVideoSourceInternal {
        val source = CfrMediaProjectionVideoSource(
            context,
            mediaProjection,
            dispatcherProvider,
            dispatcherProvider.createVideoHandlerExecutor(THREAD_NAME_VIRTUAL_DISPLAY),
            fps,
            overrideRotation
        )
        return source
    }

    override fun isSourceEquals(source: IVideoSourceInternal?): Boolean {
        return source is CfrMediaProjectionVideoSource
    }
}
