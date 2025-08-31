package io.github.thibaultbee.streampack.app.ui.main

import android.util.Log

/**
 * A centralized model to handle the lifecycle of resources like buffers for the Buffer Visualizer.
 */
object BufferVisualizerModel {
    private const val TAG = "BufferVisualizerModel"

    var circularPcmBuffer: CircularPcmBuffer? = null

    var isStreaming: Boolean = false

    fun release() {
        isStreaming = false
        circularPcmBuffer = null
        Log.i(TAG, "All resources released.")
    }
}