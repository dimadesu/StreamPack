package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fixed-size, thread-safe circular buffer for audio frames.
 *
 * @param byteCapacity The maximum number of bytes the buffer can hold.
 */
class CircularPcmBuffer(private val byteCapacity: Int) {

    // Define the AudioFrame data class
    private data class AudioFrame(val data: ByteBuffer, val timestamp: Long)

    // The ArrayBlockingQueue manages the frames themselves. Its capacity should be
    // large enough to hold multiple frames, but the primary limit is the total byte size.
    private val frameBuffer = ArrayBlockingQueue<AudioFrame>(50)
    private val availableBytes = AtomicInteger(0)

    val available: Int
        get() = availableBytes.get()

    val capacity: Int
        get() = byteCapacity

    /** Clears the buffer. */
    fun clear() {
        frameBuffer.clear()
        availableBytes.set(0)
    }

    /**
     * Writes an audio frame with its timestamp to the buffer.
     * Returns true if the frame was successfully written, false if the buffer is full.
     */
    fun writeFrame(data: ByteBuffer, timestamp: Long): Boolean {
        // Check if there is enough space in the buffer based on total bytes
        if (availableBytes.get() + data.remaining() > byteCapacity) {
            return false // Buffer is full
        }

        // Create a new ByteBuffer to hold the copied data
        val copiedData = ByteBuffer.allocate(data.remaining())

        val savedPosition = data.position()
        copiedData.put(data)
        data.position(savedPosition)

        // CRITICAL: Flip the buffer to prepare it for reading.
        copiedData.flip()

        val frame = AudioFrame(copiedData, timestamp)

        // Add the copied data to the buffer.
        val success = frameBuffer.offer(frame)

        if (success) {
            availableBytes.addAndGet(copiedData.limit())
        }
        return success
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * Returns null if the buffer is empty.
     */
    fun readFrame(): Pair<ByteBuffer, Long>? {
        val frame = frameBuffer.poll() // Thread-safe poll operation

        if (frame == null) {
            return null // Buffer is empty
        }

        // Atomically subtract the frame's size from the available bytes counter.
        availableBytes.addAndGet(-frame.data.limit())

        return Pair(frame.data, frame.timestamp)
    }
}