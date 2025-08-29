package io.github.thibaultbee.streampack.app.ui.main

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

    /** Returns the number of bytes available to read. */
    fun available(): Int = availableBytes

    /**
     * Reads up to [length] bytes into [dest] starting at [offset].
     * Returns the number of bytes actually read.
     */
    fun read(dest: ByteArray, offset: Int, length: Int): Int {
        val bytesToRead = minOf(length, availableBytes)
        var bytesRead = 0
        while (bytesRead < bytesToRead) {
            dest[offset + bytesRead] = buffer[readPos]
            readPos = (readPos + 1) % bufferSize
            bytesRead++
        }
        availableBytes -= bytesRead
        return bytesRead
    }

    /**
     * Writes up to [length] bytes from [src] starting at [offset].
     * Returns the number of bytes actually written.
     */
    fun write(src: ByteArray, offset: Int, length: Int): Int {
        val freeSpace = bufferSize - availableBytes
        val bytesToWrite = minOf(length, freeSpace)
        var bytesWritten = 0
        while (bytesWritten < bytesToWrite) {
            buffer[writePos] = src[offset + bytesWritten]
            writePos = (writePos + 1) % bufferSize
            bytesWritten++
        }
        availableBytes += bytesWritten
        return bytesWritten
    }

    /** Clears the buffer. */
    fun clear() {
        readPos = 0
        writePos = 0
        availableBytes = 0
    }
}
