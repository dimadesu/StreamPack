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

    val availableData: Int
        get() = availableBytes

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
     * Reads up to [dest.remaining()] bytes into [mediaCodecBuffer] ByteBuffer.
     * Returns the number of bytes actually read.
     */
    fun read(mediaCodecBuffer: ByteBuffer, size: Int): Int {
        var bytesRead = 0
        val bytesToRead = minOf(size, availableBytes)
        while (bytesRead < bytesToRead) {
            mediaCodecBuffer.put(playerBuffer[readPos])
            readPos = (readPos + 1) % bufferSize
            bytesRead++
        }
        availableBytes -= bytesRead

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
//        android.util.Log.i(TAG, "write-to-circular-buffer: requested=$length freeSpaceBefore=$freeSpace availableBefore=$availableBytes readPos=$readPos writePos=$writePos")
        while (bytesWritten < bytesToWrite) {
            playerBuffer[writePos] = src.get()
            writePos = (writePos + 1) % bufferSize
            bytesWritten++
        }
        availableBytes += bytesWritten
//        android.util.Log.d(TAG, "write-to-circular-buffer: bytesWritten=$bytesWritten availableAfter=$availableBytes readPos=$readPos writePos=$writePos")
        return bytesWritten
    }
}
