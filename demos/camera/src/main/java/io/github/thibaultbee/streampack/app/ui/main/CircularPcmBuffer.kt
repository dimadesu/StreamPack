package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer

/**
 * Circular PCM buffer for audio streaming. Thread-safe for single producer/single consumer.
 */
class CircularPcmBuffer(private val bufferSize: Int) {
    private val playerBuffer = ByteArray(bufferSize)
    private var readPos = 0
    private var writePos = 0
    private var availableBytes = 0

    val capacity: Int
        get() = bufferSize

    companion object {
        private const val TAG = "CircularPcmBuffer"
    }

    /** Returns the number of bytes available to read. */
    fun available(): Int = availableBytes

    /** Clears the buffer. */
    fun clear() {
        readPos = 0
        writePos = 0
        availableBytes = 0
    }

    private val fillThreshold = (bufferSize * 0.8).toInt() // 80% of the buffer size
    private var isFilling = true // Tracks whether the buffer is in the filling phase

    private var lastReadTime = 0L // Tracks the last time the read method was called
    private val readInterval = 22L // Minimum interval between reads in milliseconds

    /**
     * Reads up to [dest.remaining()] bytes into [mediaCodecBuffer] ByteBuffer.
     * Returns the number of bytes actually read.
     */
    fun read(mediaCodecBuffer: ByteBuffer, size: Int): Int {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastReadTime < readInterval) {
            android.util.Log.d(TAG, "(read): Throttled. Too soon since last read.")
            return 0 // Return immediately if called too soon
        }

        lastReadTime = currentTime // Update the last read time


        if (isFilling) {
            // Buffer is in the filling phase, skip filling mediaCodecBuffer
            android.util.Log.d(TAG, "(read): Buffer in filling phase. Skipping fill, size=$size")

            // Transition to draining phase if the buffer reaches the threshold
            if (availableBytes >= fillThreshold) {
                isFilling = false
                android.util.Log.d(TAG, "(read): Transitioning to draining phase. availableBytes=$availableBytes")
            }
            return 0
        }

        android.util.Log.d(TAG, "(read): isFilling=$isFilling, availableBytes=$availableBytes, fillThreshold=$fillThreshold")
        android.util.Log.d(TAG, "(read): currentTime=$currentTime, lastReadTime=$lastReadTime, readInterval=$readInterval")

        var bytesRead = 0
        // Buffer is in the draining phase, read data into mediaCodecBuffer
        val bytesToRead = minOf(size, availableBytes)
        android.util.Log.d(TAG, "(read): requested=$size availableBefore=$availableBytes readPos=$readPos writePos=$writePos")
        while (bytesRead < bytesToRead) {
            mediaCodecBuffer.put(playerBuffer[readPos])
            readPos = (readPos + 1) % bufferSize
            bytesRead++
        }
        availableBytes -= bytesRead

        android.util.Log.d(TAG, "(read): bytesRead=$bytesRead, availableAfter=$availableBytes readPos=$readPos writePos=$writePos")

        // Transition back to filling phase if the buffer is fully drained
        if (availableBytes == 0) {
            isFilling = true
            android.util.Log.d(TAG, "(read): Transitioning to filling phase. Buffer fully drained.")
        }

        android.util.Log.d(TAG, "(read): isFilling=$isFilling, availableBytes=$availableBytes, fillThreshold=$fillThreshold")
        android.util.Log.d(TAG, "(read): currentTime=$currentTime, lastReadTime=$lastReadTime, readInterval=$readInterval")
        android.util.Log.d(TAG, "(read): bytesToRead=$bytesToRead, bytesRead=$bytesRead")

        return bytesRead
    }

    /**
     * Writes up to [src.remaining()] bytes from [src] ByteBuffer.
     * Returns the number of bytes actually written.
     */
    fun write(src: ByteBuffer): Int {
        val length = src.remaining()
        val freeSpace = bufferSize - availableBytes
        val bytesToWrite = minOf(length, freeSpace)
        var bytesWritten = 0
        android.util.Log.i(TAG, "write-to-circular-buffer: requested=$length freeSpaceBefore=$freeSpace availableBefore=$availableBytes readPos=$readPos writePos=$writePos")
        while (bytesWritten < bytesToWrite) {
            playerBuffer[writePos] = src.get()
            writePos = (writePos + 1) % bufferSize
            bytesWritten++
        }
        availableBytes += bytesWritten
//        android.util.Log.d(TAG, "write-to-circular-buffer: bytesWritten=$bytesWritten availableAfter=$availableBytes readPos=$readPos writePos=$writePos")
        return bytesWritten
    }

    val buffer: ByteArray
        get() = playerBuffer

    val readPosition: Int
        get() = readPos

    val writePosition: Int
        get() = writePos

    val availableData: Int
        get() = availableBytes
}
