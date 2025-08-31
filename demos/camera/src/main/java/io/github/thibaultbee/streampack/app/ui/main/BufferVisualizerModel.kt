package io.github.thibaultbee.streampack.app.ui.main

import android.util.Log

/**
 * A centralized model to handle the lifecycle of resources like buffers for the Buffer Visualizer.
 */
object BufferVisualizerModel {
    private const val TAG = "BufferVisualizerModel"

    private var circularPcmBuffer: CircularPcmBuffer? = null

    /**
     * Initialize the CircularPcmBuffer.
     *
     * @param bufferSize The size of the buffer to initialize.
     */
    fun initCircularPcmBuffer(bufferSize: Int) {
        if (circularPcmBuffer == null) {
            circularPcmBuffer = CircularPcmBuffer(bufferSize)
            Log.i(TAG, "CircularPcmBuffer initialized with size: $bufferSize")
        } else {
            Log.w(TAG, "CircularPcmBuffer is already initialized.")
        }
    }

    /**
     * Get the CircularPcmBuffer instance.
     *
     * @return The CircularPcmBuffer instance, or null if not initialized.
     */
    fun getCircularPcmBuffer(): CircularPcmBuffer? {
        return circularPcmBuffer
    }

    /**
     * Release the CircularPcmBuffer and clean up resources.
     */
    fun releaseCircularPcmBuffer() {
        circularPcmBuffer?.clear()
        circularPcmBuffer = null
        Log.i(TAG, "CircularPcmBuffer released.")
    }

    /**
     * Release all managed resources.
     */
    fun releaseAllResources() {
        releaseCircularPcmBuffer()
        Log.i(TAG, "All resources released.")
    }
}