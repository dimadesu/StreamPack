package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BufferVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    var bufferVisualizerModel: BufferVisualizerModel? = null

    private var scheduler = Executors.newSingleThreadScheduledExecutor()

    init {
        holder.setFormat(PixelFormat.TRANSPARENT)
        setZOrderOnTop(true) // Ensure the SurfaceView is drawn on top
        holder.addCallback(this)
    }

    private fun drawBuffer() {
//        android.util.Log.d("BufferVisualizerView", "drawBuffer() called")
        val canvas = holder.lockCanvas()
        if (canvas == null) {
            Log.e("BufferVisualizerView", "Failed to lock canvas")
            return
        }
        try {
            // Clear the canvas at the start
            canvas.drawColor(Color.BLACK)

            bufferVisualizerModel?.circularPcmBuffer?.let { buffer ->
                val bufferSize = buffer.capacity
                val availableData = buffer.availableData
//                android.util.Log.d("BufferVisualizerView", "Available data: $availableData, Buffer size: $bufferSize")

                if (bufferSize > 0) {
                    // Calculate the fill percentage
                    val fillPercentage = availableData.toFloat() / bufferSize
//                    android.util.Log.d("BufferVisualizerView", "Fill percentage: $fillPercentage")

                    // Calculate the bar width based on the fill percentage
                    val barWidth = (fillPercentage * width).coerceAtLeast(5f) // Minimum width of 5 pixels
                    val left = 0f
                    val right = barWidth

                    // Draw a single bar representing the fill percentage
                    canvas.drawRect(left, 0f, right, height.toFloat(), paint)
                }

            }
        } finally {
//            android.util.Log.d("BufferVisualizerView", "Canvas unlocked and posted")
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
//        drawBuffer() // Draw the initial state when the surface is created

        // Observe the isStreaming state in the model
        bufferVisualizerModel?.let { model ->
            model.isStreaming.let { isStreaming ->
                if (isStreaming) {
                    startDrawing()
                } else {
                    stopDrawing()
                }
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
//        android.util.Log.d("BufferVisualizerView", "Surface changed, forcing redraw")
//        drawBuffer() // Redraw when the surface changes
        stopDrawing()
        startDrawing()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopDrawing()
        bufferVisualizerModel = null // Remove reference to avoid memory leaks
    }

    fun startDrawing() {
        if (scheduler.isShutdown) {
            scheduler = Executors.newSingleThreadScheduledExecutor()
        }
        scheduler.scheduleAtFixedRate({
            drawBuffer() // Trigger the redraw
        }, 0, 200, TimeUnit.MILLISECONDS)
    }

    fun stopDrawing() {
        if (!scheduler.isShutdown) {
            scheduler.shutdownNow() // Stop the periodic redraw immediately
        }
    }
}