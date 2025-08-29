
package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer

/**
 * Circular PCM buffer for audio streaming. Thread-safe for single producer/single consumer.
 */
class CircularPcmBuffer(private val bufferSize: Int) {
    private val buffer = ByteArray(bufferSize)
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

    /**
     * Reads up to [dest.remaining()] bytes into [dest] ByteBuffer.
     * Returns the number of bytes actually read.
     */
    fun read(dest: ByteBuffer): Int {
        val length = dest.remaining()
        val bytesToRead = minOf(length, availableBytes)
        var bytesRead = 0
        android.util.Log.d(TAG, "read(ByteBuffer): requested=$length availableBefore=$availableBytes readPos=$readPos writePos=$writePos")
        while (bytesRead < bytesToRead) {
            dest.put(buffer[readPos])
            readPos = (readPos + 1) % bufferSize
            bytesRead++
        }
        availableBytes -= bytesRead
        android.util.Log.d(TAG, "read(ByteBuffer): bytesRead=$bytesRead availableAfter=$availableBytes readPos=$readPos writePos=$writePos")
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
        android.util.Log.d(TAG, "write(ByteBuffer): requested=$length freeSpaceBefore=$freeSpace availableBefore=$availableBytes readPos=$readPos writePos=$writePos")
        while (bytesWritten < bytesToWrite) {
            buffer[writePos] = src.get()
            writePos = (writePos + 1) % bufferSize
            bytesWritten++
        }
        availableBytes += bytesWritten
        android.util.Log.d(TAG, "write(ByteBuffer): bytesWritten=$bytesWritten availableAfter=$availableBytes readPos=$readPos writePos=$writePos")
        return bytesWritten
    }
}
