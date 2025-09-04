package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fixed-size, thread-safe circular buffer for audio frames.
 *
 * This buffer is designed to be filled with `ByteBuffer`s containing audio data
 * from a source (e.g., a microphone) and then drained by a consumer (e.g., a MediaCodec encoder).
 * It manages the total byte capacity and provides thread-safe access.
 *
 * @param byteCapacity The maximum total number of bytes the buffer can hold.
 */
class CircularPcmBuffer(private val byteCapacity: Int) {

    // A data class to hold the audio frame's ByteBuffer and its presentation timestamp.
    private data class AudioFrame(val data: ByteBuffer, val timestamp: Long)

    // A thread-safe queue to manage the individual AudioFrame objects.
    // The capacity is a number of frames, not bytes.
    // We choose a generous capacity to prevent blocking on the queue itself,
    // as the main bottleneck will be the total byte capacity.
    private val frameBuffer = ArrayBlockingQueue<AudioFrame>(5000)

    // An atomic integer to track the total number of bytes currently in the buffer.
    private val availableBytes = AtomicInteger(0)

    // A state variable to manage the "filling" vs. "draining" state of the buffer.
    private var isFilling = true

    /**
     * Gets the number of bytes currently available in the buffer.
     */
    val available: Int
        get() = availableBytes.get()

    /**
     * Gets the total byte capacity of the buffer.
     */
    val capacity: Int
        get() = byteCapacity

    /**
     * Clears the buffer, removing all frames and resetting the available byte count.
     */
    fun clear() {
        println("[CircularPcmBuffer] Clearing buffer. Available bytes before clear: ${availableBytes.get()}")
        frameBuffer.clear()
        availableBytes.set(0)
        isFilling = true
        println("[CircularPcmBuffer] Buffer cleared. Available bytes after clear: ${availableBytes.get()}")
    }

    /**
     * Writes an audio frame with its timestamp to the buffer.
     * This method is thread-safe. It will split the incoming data into 4096-byte chunks
     * to ensure compatibility with fixed-size MediaCodec buffers.
     *
     * @param data The ByteBuffer containing the audio data. The ByteBuffer's position and limit
     * will be used to determine the size of the data to be written.
     * @param timestamp The presentation timestamp for this frame in microseconds.
     * @return `true` if the frame was successfully written, `false` if the buffer is full.
     */
    fun writeFrame(data: ByteBuffer, timestamp: Long): Boolean {
        // Calculate the total size of the data to be written.
        val totalSize = data.remaining()
        println("[CircularPcmBuffer] Attempting to write frame. Total data size: $totalSize, Timestamp: $timestamp")

        // Split the data into smaller chunks of 4096 bytes to match the MediaCodec buffer size.
        val chunkSize = 2048

        val originalPosition = data.position()

        // Use the copied data for processing
        while (data.remaining() > 0) {
            val currentChunkSize = minOf(chunkSize, data.remaining())
            val chunk = ByteBuffer.allocate(currentChunkSize)

            val savedPosition = data.position()
            val limit = savedPosition + currentChunkSize
            data.limit(limit)
            chunk.put(data)
            data.limit(data.capacity())
            data.position(savedPosition + currentChunkSize)

            // CRITICAL: Flip the chunk to prepare it for reading.
            chunk.flip()

            val frame = AudioFrame(chunk, timestamp)

            // Add the chunk to the buffer.
            val success = frameBuffer.offer(frame)

            if (success) {
                availableBytes.addAndGet(chunk.limit())
                println("[CircularPcmBuffer] Chunk written. Chunk size: ${chunk.limit()}, Available bytes: ${availableBytes.get()}")
            } else {
                println("[CircularPcmBuffer] Buffer is full. Remaining data size: ${data.remaining()}")
                break
            }
        }

        data.position(originalPosition)
        return true
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * This method is thread-safe and implements a basic buffering strategy:
     * it will not start reading until the buffer is at least `30%` full to
     * avoid feeding an encoder with too little data, which could cause glitches.
     *
     * @return A `Pair` of `ByteBuffer` and timestamp if a frame is available, or `null` otherwise.
     */
    fun readFrame(): Pair<ByteBuffer, Long>? {
        // The `isFilling` state prevents premature reading.
        if (isFilling) {
            // Check if the buffer has reached the minimum fill level to start draining.
            if (isAtLeastFull(60)) { // Using a 30% fill level to ensure smooth draining.
                isFilling = false
                println("[CircularPcmBuffer] Switching to draining mode. Available bytes: ${availableBytes.get()}")
            } else {
                println("[CircularPcmBuffer] Still in filling mode. Available bytes: ${availableBytes.get()}")
                return null // Still filling, so no frame is available yet.
            }
        }

        // Poll the frame from the queue. `poll` is a non-blocking, thread-safe operation.
        val frame = frameBuffer.poll()

        if (frame == null) {
            // If the buffer is empty, switch back to the filling state.
            isFilling = true
            println("[CircularPcmBuffer] Buffer drained. Switching back to filling mode.")
            return null // Buffer is empty.
        }

        // Atomically subtract the size of the read frame from the total byte count.
        availableBytes.addAndGet(-frame.data.remaining())
        println("[CircularPcmBuffer] Frame read. Frame size: ${frame.data.remaining()}, Available bytes: ${availableBytes.get()}")

        return Pair(frame.data, frame.timestamp)
    }

    /**
     * Checks if the buffer's current size is at least the specified percentage of its total capacity.
     *
     * @param percentage The percentage to check (an integer between 0 and 100).
     * @return `true` if the buffer is at least the specified percentage full, `false` otherwise.
     */
    private fun isAtLeastFull(percentage: Int): Boolean {
        require(percentage in 0..100) { "Percentage must be between 0 and 100" }
        return availableBytes.get() >= (byteCapacity * percentage / 100)
    }
}
