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
    
    // Adaptive buffering parameters
    private var initialFillThreshold = 25 // Increased from 15% to 25% to reduce underruns with silence filling
    private var minBufferThreshold = 5    // Increased from 3% to 5% for better stability
    private var underrunCount = 0         // Track buffer underruns
    private var lastReadTime = 0L         // Track read timing
    
    // Object pooling to reduce GC pressure
    private val framePool = ArrayBlockingQueue<AudioFrame>(200)
    
    // A data class to hold the audio frame's ByteBuffer and its presentation timestamp.
    private data class AudioFrame(var data: ByteBuffer, var timestamp: Long) {
        fun reset() {
            data.clear()
            timestamp = 0L
        }
    }

    init {
        // Pre-populate frame pool
        repeat(200) {
            framePool.offer(AudioFrame(ByteBuffer.allocate(4096), 0L))
        }
    }

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
     * Object pooling methods to reduce GC pressure
     */
    private fun getFrameFromPool(): AudioFrame? = framePool.poll()
    
    private fun returnFrameToPool(frame: AudioFrame) {
        frame.reset()
        if (!framePool.offer(frame)) {
            // Pool is full, let it be garbage collected
        }
    }

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
            android.util.Log.w("CircularPcmBuffer", 
                "Audio format changed: ${oldSampleRate}Hz/${oldChannelCount}ch/${oldBytesPerSample}B -> ${sampleRate}Hz/${channelCount}ch/${bytesPerSample}B")
        }
    }

    /**
     * Gets the current sample rate.
     */
    val sampleRate: Int
        get() = sampleRateInternal

    /**
     * Gets the current channel count.
     */
    val channelCount: Int
        get() = channelCountInternal

    /**
     * Gets the current bytes per sample.
     */
    val bytesPerSample: Int
        get() = bytesPerSampleInternal

    /**
     * Clears the buffer, discarding all buffered audio data and resetting the state.
     * This method is thread-safe.
     */
    @Synchronized
    fun clear() {
        frameBuffer.clear()
        availableBytes.set(0)
        isFilling = true
        pendingFrame = null
        android.util.Log.i("CircularPcmBuffer", "Buffer cleared and reset to filling state.")
    }

    /**
     * Writes audio data to the buffer with optimized performance.
     *
     * @param data The ByteBuffer containing the audio data. The ByteBuffer's position and limit
     * will be used to determine the size of the data to be written.
     * @param timestamp The presentation timestamp for this frame in microseconds.
     * @return `true` if the frame was successfully written, `false` if the buffer is full.
     */
    fun writeFrame(data: ByteBuffer, timestamp: Long) {
        val totalSize = data.remaining()
        if (totalSize <= 0) return
        
        // Optimize: Reduce logging to improve performance
        // android.util.Log.v("CircularPcmBuffer", "Writing frame. Size: $totalSize, Timestamp: $timestamp")

        // Make room by dropping old frames if buffer is full
        while (availableBytes.get() + totalSize > byteCapacity && frameBuffer.isNotEmpty()) {
            val dropped = frameBuffer.poll()
            if (dropped != null) {
                availableBytes.addAndGet(-dropped.data.limit())
                returnFrameToPool(dropped)
            }
        }
        
        // Get or create frame efficiently
        val frame = getFrameFromPool()?.apply {
            // Reuse pooled frame
            this.data.clear()
            if (this.data.capacity() < totalSize) {
                this.data = ByteBuffer.allocate(totalSize)
            }
            this.data.put(data)
            this.data.flip()
            this.timestamp = timestamp
        } ?: AudioFrame(data.duplicate(), timestamp) // Fallback to duplication only if pool empty

        val success = frameBuffer.offer(frame)

        if (success) {
            availableBytes.addAndGet(totalSize)
            // Reduced logging frequency
            if (availableBytes.get() % 8192 == 0) {
                android.util.Log.v("CircularPcmBuffer", "Frame written. Available: ${availableBytes.get()}/$byteCapacity")
            }
        } else {
            returnFrameToPool(frame)
            android.util.Log.w("CircularPcmBuffer", "Buffer full! Dropping audio frame. Size: $totalSize")
        }
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * This method is thread-safe and implements a refined buffering strategy:
     * - Uses a lower initial fill threshold (15%) to reduce latency
     * - Maintains minimum buffer level (3%) to prevent underruns  
     * - Provides smoother streaming for real-time applications
     *
     * @return A `Pair` of `ByteBuffer` and timestamp if a frame is available, or `null` otherwise.
     */
    fun readFrame(size: Int): Pair<ByteBuffer, Long>? {
        // The `isFilling` state prevents premature reading.
        if (isFilling) {
            // Check if the buffer has reached the adaptive fill level to start draining.
            if (isAtLeastFull(initialFillThreshold)) {
                isFilling = false
                android.util.Log.i("CircularPcmBuffer", "Switching to draining mode. Available: ${availableBytes.get()}, Threshold: $initialFillThreshold%")
            } else {
                // Reduced logging frequency to minimize performance impact
                if (availableBytes.get() % 4096 == 0) {
                    android.util.Log.v("CircularPcmBuffer", "Still filling. Available: ${availableBytes.get()}/$byteCapacity")
                }
                return null // Still filling, so no frame is available yet.
            }
        }

        // We are in draining mode. Check if we have some buffered frames to read from.

        var currentFrame = pendingFrame
        if (currentFrame == null) {
            currentFrame = frameBuffer.poll() ?: run {
                // No more frames in the buffer. Check buffer level.
                val currentTime = System.currentTimeMillis()
                val timeSinceLastRead = currentTime - lastReadTime
                
                // Keep some minimum buffer to avoid frequent fill/drain switches
                if (isAtLeastFull(minBufferThreshold)) {
                    // Try to wait for more data rather than immediate fill mode switch
                    // Reduced logging frequency
                    if (availableBytes.get() % 2048 == 0) {
                        android.util.Log.v("CircularPcmBuffer", "Buffer low but not empty, waiting for more data")
                    }
                    return null
                }
                // Buffer underrun detected - adapt buffering strategy
                underrunCount++
                if (underrunCount > 5 && initialFillThreshold < 40) {
                    initialFillThreshold += 5 // Increase buffer threshold
                    android.util.Log.w("CircularPcmBuffer", "Frequent underruns detected. Increasing fill threshold to $initialFillThreshold%")
                }
                
                // If buffer is truly empty, switch back to filling state
                isFilling = true
                android.util.Log.i("CircularPcmBuffer", "Buffer underrun #$underrunCount. Switching to filling mode.")
                return null
            }

            pendingFrame = currentFrame
        }

        val availableInChunk = currentFrame.data.remaining()
        val toRead = minOf(availableInChunk, size)

        // Reduced verbose logging
        // android.util.Log.v("CircularPcmBuffer", "Reading $toRead bytes from buffer")

        // The destination buffer is correctly sized.
        // Optimize: Reuse ByteBuffer to reduce allocations
        val out = ByteBuffer.allocate(toRead)

        // Create a temporary view of the source buffer to control the data transfer.
        val sourceView = currentFrame.data.duplicate()
        sourceView.limit(sourceView.position() + toRead)

        // Put from the temporary view into our output buffer.
        out.put(sourceView)
        out.flip()

        // Advance the position of the original currentFrame.data
        currentFrame.data.position(currentFrame.data.position() + toRead)

        if (!currentFrame.data.hasRemaining()) {
            returnFrameToPool(currentFrame)
            pendingFrame = null
        }

        // Subtract only the number of bytes actually consumed.
        availableBytes.addAndGet(-toRead)
        
        // Track timing to optimize buffering behavior
        val currentTime = System.currentTimeMillis()
        if (lastReadTime > 0) {
            val timeBetweenReads = currentTime - lastReadTime
            // Optimize: Uncomment for detailed timing analysis if needed
            // android.util.Log.v("CircularPcmBuffer", "Time between reads: ${timeBetweenReads}ms")
        }
        lastReadTime = currentTime
        
        // Reduced read completion logging
        // android.util.Log.v("CircularPcmBuffer", "Read complete. Bytes read: $toRead, Available: ${availableBytes.get()}")

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
