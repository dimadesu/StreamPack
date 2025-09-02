package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Circular PCM buffer for audio streaming. Thread-safe for single producer/single consumer.
 */
class CircularPcmBuffer(private val bufferSize: Int) {
    private val availableBytes = AtomicInteger(0)

    /**
     * Data structure to hold audio data and its associated timestamp.
     */
    private data class AudioFrame(val data: ByteArray, val timestamp: Long)

    private val frameBuffer = ArrayDeque<AudioFrame>()

    val available: Int
        get() = availableBytes.get()

    val capacity: Int
        get() = bufferSize

    /** Clears the buffer. */
    fun clear() {
    }

    /**
     * Writes an audio frame with its timestamp to the buffer.
     * Returns true if the frame was successfully written, false if the buffer is full.
     */
    @Synchronized
    fun writeFrame(data: ByteBuffer, timestamp: Long): Boolean {
        if (frameBuffer.size >= bufferSize) {
            return false // Buffer is full
        }

        val audioData = ByteArray(data.remaining())
        data.get(audioData)
        frameBuffer.addLast(AudioFrame(audioData, timestamp))
        return true
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * Returns null if the buffer is empty.
     */
    @Synchronized
    fun readFrame(): Pair<ByteArray, Long>? {
        return if (frameBuffer.isEmpty()) {
            null // Buffer is empty
        } else {
            val frame = frameBuffer.removeFirst()
            Pair(frame.data, frame.timestamp)
        }
    }

    // Add compareTo function with operator modifier
    operator fun compareTo(other: CircularPcmBuffer): Int {
        return this.available - other.available
    }
}
