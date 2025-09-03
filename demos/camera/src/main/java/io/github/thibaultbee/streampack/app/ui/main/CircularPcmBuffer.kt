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
    private val frameBuffer = ArrayBlockingQueue<AudioFrame>(5000)
    private val availableBytes = AtomicInteger(0)

    private var isFilling = true

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
     * Automatically manages the `isFilling` state.
     *
     * @return A pair of ByteBuffer and timestamp if available, or null otherwise.
     */
    fun readFrame(): Pair<ByteBuffer, Long>? {
        if (isFilling) {
            if (isAtLeastFull(80)) {
                isFilling = false // Switch to draining mode
            } else {
                return null // Still in filling mode
            }
        }

        val frame = frameBuffer.poll() // Thread-safe poll operation

        if (frame == null) {
            isFilling = true // Switch back to filling mode when fully drained
            return null // Buffer is empty
        }

        // Atomically subtract the frame's size from the available bytes counter.
        availableBytes.addAndGet(-frame.data.limit())

        return Pair(frame.data, frame.timestamp)
    }

    /**
     * Checks if the buffer is at least the specified percentage full.
     *
     * @param percentage The percentage to check (0-100).
     * @return True if the buffer is at least the specified percentage full, false otherwise.
     */
    fun isAtLeastFull(percentage: Int): Boolean {
        require(percentage in 0..100) { "Percentage must be between 0 and 100" }
        return availableBytes.get() >= (byteCapacity * percentage / 100)
    }
}