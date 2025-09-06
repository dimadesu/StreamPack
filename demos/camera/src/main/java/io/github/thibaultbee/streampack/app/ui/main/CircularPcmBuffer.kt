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

    // Audio format tracking - updated when format is determined
    private var sampleRateInternal: Int = 48000
    private var channelCountInternal: Int = 2  
    private var bytesPerSampleInternal: Int = 2
    
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

    // If a consumer only reads part of a queued chunk, keep the remainder here for next read.
    private var pendingFrame: AudioFrame? = null

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
     * Updates the audio format parameters. Called when the actual format is determined
     * from ExoPlayer's audio sink configuration.
     */
    fun updateFormat(sampleRate: Int, channelCount: Int, bytesPerSample: Int) {
        val oldSampleRate = this.sampleRateInternal
        val oldChannelCount = this.channelCountInternal
        val oldBytesPerSample = this.bytesPerSampleInternal
        
        this.sampleRateInternal = sampleRate
        this.channelCountInternal = channelCount
        this.bytesPerSampleInternal = bytesPerSample
        
        if (oldSampleRate != sampleRate || oldChannelCount != channelCount || oldBytesPerSample != bytesPerSample) {
            android.util.Log.i("CircularPcmBuffer", "Format changed: $oldSampleRate→$sampleRate Hz, $oldChannelCount→$channelCount ch, $oldBytesPerSample→$bytesPerSample bytes/sample")
            
            // Warn about significant sample rate mismatches that could cause audio quality issues
            if (oldSampleRate != sampleRate && kotlin.math.abs(oldSampleRate - sampleRate) > 1000) {
                android.util.Log.w("CircularPcmBuffer", "WARNING: Large sample rate change detected! This could cause audio pitch/speed issues. Old: $oldSampleRate Hz, New: $sampleRate Hz")
            }
        } else {
            android.util.Log.d("CircularPcmBuffer", "Format update called but no change: sampleRate=$sampleRate, channelCount=$channelCount, bytesPerSample=$bytesPerSample")
        }
    }

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
    fun writeFrame(data: ByteBuffer, timestamp: Long) {
        val totalSize = data.remaining()
        println("[CircularPcmBuffer] Attempting to write frame. Total data size: $totalSize, Timestamp: $timestamp")

        val frame = AudioFrame(data, timestamp)

        val success = frameBuffer.offer(frame)

        if (success) {
            availableBytes.addAndGet(data.limit())
            println("[CircularPcmBuffer] Chunk written. Chunk size: ${data.limit()}, Available bytes: ${availableBytes.get()}")
        } else {
            println("[CircularPcmBuffer] Buffer is full. Remaining data size: ${data.remaining()}")
        }
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * This method is thread-safe and implements a basic buffering strategy:
     * it will not start reading until the buffer is at least `30%` full to
     * avoid feeding an encoder with too little data, which could cause glitches.
     *
     * @return A `Pair` of `ByteBuffer` and timestamp if a frame is available, or `null` otherwise.
     */
    fun readFrame(size: Int): Pair<ByteBuffer, Long>? {
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


        val currentFrame = pendingFrame ?: frameBuffer.poll()
        if (currentFrame == null) {
            // If the buffer is empty, switch back to the filling state.
            isFilling = true
            println("[CircularPcmBuffer] Buffer drained. Switching back to filling mode.")
            return null // Buffer is empty.
        }

        pendingFrame = currentFrame

        val availableInChunk = currentFrame.data.remaining()

//        println("[CircularPcmBuffer] Frame read. hello1")
        val toRead = minOf(availableInChunk, size)

        println("[CircularPcmBuffer] Frame read. toRead $toRead")

        // The destination buffer is correctly sized.
        val out = ByteBuffer.allocate(toRead)

//        println("[CircularPcmBuffer] Frame read. hello3")

        // Create a temporary view of the source buffer to control the data transfer.
        val sourceView = currentFrame.data.duplicate()

//        println("[CircularPcmBuffer] Frame read. hello4")

        sourceView.limit(sourceView.position() + toRead)

//        println("[CircularPcmBuffer] Frame read. hello5")

        // Put from the temporary view into our output buffer. This is the key step.
        out.put(sourceView)
        out.flip()

        // Now, manually advance the position of the original currentFrame.data
        // by the amount we just read. This is crucial for subsequent reads.
        currentFrame.data.position(currentFrame.data.position() + toRead)

        if (!currentFrame.data.hasRemaining()) {
            pendingFrame = null
        }

        // Subtract only the number of bytes actually consumed.
        availableBytes.addAndGet(-toRead)
        println("[CircularPcmBuffer] Frame read. Bytes read: $toRead, Available bytes: ${availableBytes.get()}")

        return Pair(out, currentFrame.timestamp)
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
