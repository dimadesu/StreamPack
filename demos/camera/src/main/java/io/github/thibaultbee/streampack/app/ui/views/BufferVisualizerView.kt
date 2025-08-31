package io.github.thibaultbee.streampack.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
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

    var audioBuffer: CircularPcmBuffer? = null
        set(value) {
            field = value
            android.util.Log.d("BufferVisualizerView", "audioBuffer updated: ${value?.availableData}")
            drawBuffer() // Trigger a redraw when the buffer updates
        }

    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    init {
        holder.setFormat(android.graphics.PixelFormat.TRANSPARENT)
        setZOrderOnTop(true) // Ensure the SurfaceView is drawn on top
        holder.addCallback(this)

        // Temporary: Populate a real CircularPcmBuffer with dummy data for testing
//        val dummyBuffer = CircularPcmBuffer(100)
//        val dummyData = ByteArray(100) { (it % 256).toByte() } // Simulate 100 bytes of varying data
//        val byteBuffer = java.nio.ByteBuffer.wrap(dummyData)
//        dummyBuffer.write(byteBuffer)
//        audioBuffer = dummyBuffer

        // Schedule periodic redraw on a background thread
        scheduler.scheduleAtFixedRate({
            drawBuffer() // Trigger the redraw
//            android.util.Log.d("BufferVisualizerView", "Periodic drawBuffer() called")
        }, 0, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
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

            audioBuffer?.let { buffer ->
                val availableData = buffer.availableData
                val bufferSize = buffer.buffer.size
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
            android.util.Log.d("BufferVisualizerView", "Canvas unlocked and posted")
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        drawBuffer() // Draw the initial state when the surface is created
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        android.util.Log.d("BufferVisualizerView", "Surface changed, forcing redraw")
        drawBuffer() // Redraw when the surface changes
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        scheduler.shutdown() // Stop the periodic redraw
        // No-op
    }
}
