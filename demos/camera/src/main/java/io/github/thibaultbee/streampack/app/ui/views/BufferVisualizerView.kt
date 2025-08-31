package io.github.thibaultbee.streampack.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.thibaultbee.streampack.app.sources.audio.AudioRecordWrapper3
import java.nio.ByteBuffer

class BufferVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    var audioRecordWrapper: AudioRecordWrapper3? = null
        set(value) {
            field = value
//            android.util.Log.d("BufferVisualizerView", "audioRecordWrapper updated")
//            drawBuffer() // Trigger a redraw when the wrapper updates
        }

    private var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    init {
        holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
        setZOrderOnTop(true) // Ensure the SurfaceView is drawn on top
        holder.addCallback(this)
    }

    private fun drawBuffer() {
//        android.util.Log.d("BufferVisualizerView", "drawBuffer() called")
        val canvas = holder.lockCanvas()
        if (canvas == null) {
            android.util.Log.e("BufferVisualizerView", "Failed to lock canvas")
            return
        }
        try {
            // Clear the canvas at the start
            canvas.drawColor(Color.BLACK)

            audioRecordWrapper?.let { wrapper ->
                val buffer = wrapper.audioBuffer

                if (buffer != null) {
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
            }
        } finally {
            android.util.Log.d("BufferVisualizerView", "Canvas unlocked and posted")
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
//        drawBuffer() // Draw the initial state when the surface is created
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
//        android.util.Log.d("BufferVisualizerView", "Surface changed, forcing redraw")
//        drawBuffer() // Redraw when the surface changes
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopDrawing()
    }

    fun startDrawing() {
        if (scheduler.isShutdown) {
            scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        }
        scheduler.scheduleAtFixedRate({
            drawBuffer() // Trigger the redraw
        }, 0, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun stopDrawing() {
        scheduler.shutdownNow() // Stop the periodic redraw immediately
    }
}
