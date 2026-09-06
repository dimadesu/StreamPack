/*
 * Custom Constant Frame Rate (CFR) Surface Processor for LifeStreamer.
 * Based on StreamPack's DefaultSurfaceProcessor.
 */
package io.github.thibaultbee.streampack.core.elements.processing.video

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.util.Size
import android.view.Surface
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import io.github.thibaultbee.streampack.core.elements.interfaces.ISnapshotable
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.ISurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.outputs.SurfaceOutput
import io.github.thibaultbee.streampack.core.elements.processing.video.utils.GLUtils
import io.github.thibaultbee.streampack.core.elements.utils.av.video.DynamicRangeProfile
import io.github.thibaultbee.streampack.core.elements.utils.time.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.time.Timebase
import io.github.thibaultbee.streampack.core.elements.utils.time.VideoTimebaseConverter
import io.github.thibaultbee.streampack.core.logger.Logger
import io.github.thibaultbee.streampack.core.pipelines.DispatcherProvider.Companion.THREAD_NAME_GL
import io.github.thibaultbee.streampack.core.pipelines.IVideoDispatcherProvider
import io.github.thibaultbee.streampack.core.pipelines.utils.HandlerThreadExecutor
import java.util.concurrent.atomic.AtomicBoolean

private class CfrSurfaceProcessor(
    private val dynamicRangeProfile: DynamicRangeProfile,
    private val glThread: HandlerThreadExecutor,
    private val fps: Int = 30
) : ISurfaceProcessorInternal, SurfaceTexture.OnFrameAvailableListener, ISnapshotable {
    override var isMuted: Boolean = false

    private val renderer = OpenGlRenderer()
    private val glHandler = glThread.handler

    private val isReleaseRequested = AtomicBoolean(false)
    private var isReleased = false

    // We store the latest texture matrix and timestamp to redraw it
    private val textureMatrix = FloatArray(16)
    private val surfaceOutputMatrix = FloatArray(16)
    @Volatile private var latestTimestampNs: Long = 0L
    @Volatile private var hasFirstFrame: Boolean = false

    private val surfaceOutputs: MutableList<ISurfaceOutput> = mutableListOf()
    private val surfaceInputs: MutableList<SurfaceInput> = mutableListOf()
    private val surfaceInputsToTimeConverterMap: MutableMap<SurfaceTexture, VideoTimebaseConverter> =
        hashMapOf()

    // Render loop state
    private var isRenderLoopRunning = false
    private val renderRunnable = object : Runnable {
        override fun run() {
            if (isReleaseRequested.get() || isReleased) return
            renderLatestFrame()
            
            // Re-schedule for specified FPS
            val delayMs = if (fps > 0) 1000L / fps else 16L
            glHandler.postDelayed(this, delayMs)
        }
    }

    init {
        Logger.d(TAG, "Setting dynamic range profile to $dynamicRangeProfile for CFR Processor")

        val future = submitSafely {
            renderer.init(dynamicRangeProfile)
        }
        try {
            future.get()
        } catch (e: Exception) {
            release()
            Logger.e(TAG, "Error while initializing renderer", e)
            throw e
        }
    }

    override fun createInputSurface(surfaceSize: Size, timebase: Timebase): Surface {
        if (isReleaseRequested.get()) throw IllegalStateException("SurfaceProcessor is released")

        val future = submitSafely {
            if (isReleaseRequested.get()) throw IllegalStateException("SurfaceProcessor is released")

            val surfaceTexture = SurfaceTexture(renderer.textureName)
            surfaceTexture.setDefaultBufferSize(surfaceSize.width, surfaceSize.height)
            surfaceTexture.setOnFrameAvailableListener(this, glHandler)
            if (dynamicRangeProfile.isHdr) {
                renderer.setInputFormat(GLUtils.InputFormat.YUV)
            }

            surfaceInputsToTimeConverterMap[surfaceTexture] = VideoTimebaseConverter(
                timebase,
                TimeUtils.systemTimeProvider
            )
            SurfaceInput(Surface(surfaceTexture), surfaceTexture)
        }

        val surfaceInput = future.get()
        surfaceInputs.add(surfaceInput)
        
        // Start render loop when input is created
        executeSafely {
            if (!isRenderLoopRunning) {
                isRenderLoopRunning = true
                glHandler.post(renderRunnable)
                Logger.i(TAG, "Started CFR render loop")
            }
        }
        
        return surfaceInput.surface
    }

    override fun removeInputSurface(surface: Surface) {
        if (isReleaseRequested.get()) return
        executeSafely {
            val surfaceInput = surfaceInputs.find { it.surface == surface }
            if (surfaceInput != null) {
                val surfaceTexture = surfaceInput.surfaceTexture
                surfaceTexture.setOnFrameAvailableListener(null, glHandler)
                surfaceTexture.release()
                surface.release()

                surfaceInputsToTimeConverterMap.remove(surfaceTexture)
                surfaceInputs.remove(surfaceInput)

                if (surfaceInputs.isEmpty()) {
                    isRenderLoopRunning = false
                    glHandler.removeCallbacks(renderRunnable)
                }
                checkReadyToRelease()
            }
        }
    }

    override fun setTimebase(surface: Surface, timebase: Timebase) {
        executeSafely {
            val surfaceInput = surfaceInputs.find { it.surface == surface }
            if (surfaceInput != null) {
                surfaceInputsToTimeConverterMap[surfaceInput.surfaceTexture] =
                    VideoTimebaseConverter(timebase, TimeUtils.systemTimeProvider)
            }
        }
    }

    override fun addOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) throw IllegalStateException("SurfaceProcessor is released")
        executeSafely {
            if (isReleaseRequested.get()) throw IllegalStateException("SurfaceProcessor is released")
            if (!surfaceOutputs.map { it.targetSurface }.contains(surfaceOutput.targetSurface)) {
                renderer.registerOutputSurface(surfaceOutput.targetSurface, surfaceOutput.viewportRect)
                surfaceOutputs.add(surfaceOutput)
            }
        }
    }

    private fun removeOutputSurfaceInternal(surfaceOutput: ISurfaceOutput) {
        if (surfaceOutputs.contains(surfaceOutput)) {
            renderer.unregisterOutputSurface(surfaceOutput.targetSurface)
            surfaceOutputs.remove(surfaceOutput)
        }
    }

    override fun removeOutputSurface(surfaceOutput: ISurfaceOutput) {
        if (isReleaseRequested.get()) return
        executeSafely {
            if (isReleaseRequested.get()) return@executeSafely
            removeOutputSurfaceInternal(surfaceOutput)
        }
    }

    override fun removeOutputSurface(surface: Surface) {
        if (isReleaseRequested.get()) return
        executeSafely {
            if (isReleaseRequested.get()) return@executeSafely
            val surfaceOutput = surfaceOutputs.firstOrNull { it.targetSurface == surface }
            if (surfaceOutput != null) {
                removeOutputSurfaceInternal(surfaceOutput)
            }
        }
    }

    override fun removeAllOutputSurfaces() {
        if (isReleaseRequested.get()) return
        executeSafely {
            if (isReleaseRequested.get()) return@executeSafely
            surfaceOutputs.forEach { renderer.unregisterOutputSurface(it.targetSurface) }
            surfaceOutputs.clear()
        }
    }

    override fun release() {
        if (isReleaseRequested.getAndSet(true)) return
        executeSafely(block = {
            if (!isReleased) {
                isReleased = true
                isRenderLoopRunning = false
                glHandler.removeCallbacks(renderRunnable)
                checkReadyToRelease()
            }
        })
    }

    private fun checkReadyToRelease() {
        if (isReleased && surfaceInputs.isEmpty()) {
            surfaceOutputs.forEach { renderer.unregisterOutputSurface(it.targetSurface) }
            surfaceOutputs.clear()
            renderer.release()
            glThread.quit()
        }
    }

    override suspend fun takeSnapshot(rotationDegrees: Int): Bitmap {
        throw UnsupportedOperationException("Snapshot not supported in CFR processor")
    }

    // Executed on GL thread when a new frame is available from MediaProjection
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleaseRequested.get() || isReleased) return

        try {
            surfaceTexture.updateTexImage()
            hasFirstFrame = true
        } catch (e: RuntimeException) {
            Logger.w(TAG, "updateTexImage failed: ${e.message}")
            return
        }
        
        surfaceTexture.getTransformMatrix(textureMatrix)
        val timeConverter = surfaceInputsToTimeConverterMap[surfaceTexture]
        if (timeConverter != null) {
            latestTimestampNs = timeConverter.convertToUptimeNs(surfaceTexture.timestamp)
        }
    }

    // Executed on GL thread continuously
    private fun renderLatestFrame() {
        if (!hasFirstFrame) return // Don't draw anything until we have at least one frame
        
        // Feed the encoder an advancing timestamp to keep CFR perfectly paced
        val renderTimestampNs = TimeUtils.currentTime() * 1000L
        
        surfaceOutputs.filterIsInstance<SurfaceOutput>().forEach {
            try {
                it.updateTransformMatrix(surfaceOutputMatrix, textureMatrix)
                if (it.isStreaming()) {
                    renderer.render(
                        renderTimestampNs, // Pass the synthetic advancing timestamp!
                        surfaceOutputMatrix,
                        it.targetSurface,
                        isMuted
                    )
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "Error while rendering frame", t)
            }
        }
    }

    private fun executeSafely(block: () -> Unit) {
        try {
            glHandler.post {
                if (!isReleased) {
                    try {
                        block()
                    } catch (t: Throwable) {
                        Logger.e(TAG, "Error in block", t)
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "Error posting block", t)
        }
    }

    private fun <T : Any> submitSafely(block: () -> T): ListenableFuture<T> {
        return CallbackToFutureAdapter.getFuture { completer ->
            executeSafely {
                try {
                    completer.set(block())
                } catch (t: Throwable) {
                    completer.setException(t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CfrSurfaceProcessor"
    }

    private data class SurfaceInput(val surface: Surface, val surfaceTexture: SurfaceTexture)
}

class CfrSurfaceProcessorFactory(private val fps: Int = 30) : ISurfaceProcessorInternal.Factory {
    override fun create(
        dynamicRangeProfile: DynamicRangeProfile,
        dispatcherProvider: IVideoDispatcherProvider
    ): ISurfaceProcessorInternal {
        return CfrSurfaceProcessor(
            dynamicRangeProfile,
            dispatcherProvider.createVideoHandlerExecutor(THREAD_NAME_GL),
            fps
        )
    }
}
