package io.github.thibaultbee.streampack.app.ui.main

import android.util.Log

/**
 * A centralized model to handle the lifecycle of resources like buffers for the Buffer Visualizer.
 */
object BufferVisualizerModel {
    private const val TAG = "BufferVisualizerModel"

    var circularPcmBuffer: CircularPcmBuffer? = null

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