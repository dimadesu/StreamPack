package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue

/**
 * Circular PCM buffer for audio streaming. Thread-safe for single producer/single consumer.
 */
class CircularPcmBuffer(private val bufferSize: Int) {
    private var readPos = 0
    private var writePos = 0
    private var availableBytes = 0

    // Add a buffer pool to reuse ByteBuffer instances
    private val bufferPool = ArrayBlockingQueue<ByteBuffer>(100)

    // Define the AudioFrame data class
    private data class AudioFrame(val data: ByteBuffer, val timestamp: Long)

    // Define the frameBuffer queue
    private val frameBuffer = ArrayBlockingQueue<AudioFrame>(100)

    val availableData: Int
        get() = availableBytes

    val capacity: Int
        get() = bufferSize

    companion object {
        private const val TAG = "CircularPcmBuffer"
    }

    /** Clears the buffer. */
    fun clear() {
        readPos = 0
        writePos = 0
        availableBytes = 0
    }

    private fun getBuffer(size: Int): ByteBuffer {
        return bufferPool.poll() ?: ByteBuffer.allocate(size)
    }

    private fun releaseBuffer(buffer: ByteBuffer) {
        buffer.clear()
        bufferPool.offer(buffer)
    }

    fun writeFrame(data: ByteBuffer, timestamp: Long): Boolean {
        // Check if there is enough space in the buffer
        if (availableBytes + data.remaining() > bufferSize) {
            return false // Buffer is full
        }

        // Reuse or allocate a new ByteBuffer
        val copiedData = getBuffer(data.remaining())
        val savedPosition = data.position()
        copiedData.put(data)
        data.position(savedPosition)

        val frame = AudioFrame(copiedData, timestamp)

        // Add the copied data to the buffer
        val success = frameBuffer.offer(frame)

        if (success) {
            availableBytes += data.remaining()
        } else {
            releaseBuffer(copiedData) // Release the buffer if not added
        }

        return success
    }

    fun readFrame(): Pair<ByteBuffer, Long>? {
        val frame = frameBuffer.poll() // Thread-safe poll operation

        return if (frame == null) {
            null // Buffer is empty
        } else {
            val duplicateBuffer = frame.data.duplicate()
            duplicateBuffer.rewind()
            val remainingBytes = duplicateBuffer.remaining()

            availableBytes -= remainingBytes

            // Release the buffer back to the pool
            releaseBuffer(frame.data)

            Pair(frame.data, frame.timestamp)
        }
    }
}
