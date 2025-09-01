package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Circular PCM buffer for audio streaming. Thread-safe for single producer/single consumer.
 */
class CircularPcmBuffer(private val bufferSize: Int) {
    private val playerBuffer = ByteArray(bufferSize)
    private val readPos = AtomicInteger(0)
    private val writePos = AtomicInteger(0)
    private val availableBytes = AtomicInteger(0)

    val available: Int
        get() = availableBytes.get()

    val capacity: Int
        get() = bufferSize

    /** Clears the buffer. */
    fun clear() {
        readPos.set(0)
        writePos.set(0)
        availableBytes.set(0)
    }

    /**
     * Reads up to [size] bytes into [mediaCodecBuffer] ByteBuffer.
     * Returns the number of bytes actually read.
     */
//    @Synchronized
    fun read(mediaCodecBuffer: ByteBuffer, size: Int, readMode: Int = READ_BLOCKING): Int {
        if (readMode != READ_BLOCKING && readMode != READ_NON_BLOCKING) {
            return ERROR_BAD_VALUE
        }

        if (size < 0 || !mediaCodecBuffer.isDirect) {
            return ERROR_BAD_VALUE
        }

        while (availableBytes.get() == 0) {
            if (readMode == READ_NON_BLOCKING) {
                return 0 // Non-blocking mode, return immediately
            }
            Thread.yield() // Yield to other threads
        }

        val bytesToRead = minOf(size, availableBytes.get())
        val currentReadPos = readPos.get()
        val endPos = (currentReadPos + bytesToRead)

        if (endPos <= bufferSize) {
            mediaCodecBuffer.put(playerBuffer, currentReadPos, bytesToRead)
        } else {
            val bytesToEnd = bufferSize - currentReadPos
            mediaCodecBuffer.put(playerBuffer, currentReadPos, bytesToEnd)
            mediaCodecBuffer.put(playerBuffer, 0, bytesToRead - bytesToEnd)
        }

        readPos.set((currentReadPos + bytesToRead) % bufferSize)
        availableBytes.addAndGet(-bytesToRead)

        return bytesToRead
    }

    /**
     * Writes up to [src.remaining()] bytes from [src] ByteBuffer.
     * Returns the number of bytes actually written.
     */
//    @Synchronized
    fun write(src: ByteBuffer, writeMode: Int = WRITE_BLOCKING): Int {
        if (src.remaining() < 0 || !src.isDirect) {
            return ERROR_BAD_VALUE
        }

        val length = src.remaining()
        while (availableBytes.get() == bufferSize) {
            if (writeMode == WRITE_NON_BLOCKING) {
                return 0 // Non-blocking mode, return immediately
            }
            Thread.yield() // Yield to other threads
        }

        val bytesToWrite = minOf(length, bufferSize - availableBytes.get())
        val currentWritePos = writePos.get()
        val endPos = (currentWritePos + bytesToWrite)

        if (endPos <= bufferSize) {
            src.get(playerBuffer, currentWritePos, bytesToWrite)
        } else {
            val bytesToEnd = bufferSize - currentWritePos
            src.get(playerBuffer, currentWritePos, bytesToEnd)
            src.get(playerBuffer, 0, bytesToWrite - bytesToEnd)
        }

        writePos.set((currentWritePos + bytesToWrite) % bufferSize)
        availableBytes.addAndGet(bytesToWrite)

        return bytesToWrite
    }

    // Add compareTo function with operator modifier
    operator fun compareTo(other: CircularPcmBuffer): Int {
        return this.available - other.available
    }

    companion object {
        const val READ_BLOCKING = 0
        const val READ_NON_BLOCKING = 1

        const val WRITE_BLOCKING = 0
        const val WRITE_NON_BLOCKING = 1

        const val ERROR_INVALID_OPERATION = -3
        const val ERROR_BAD_VALUE = -2
        const val ERROR_DEAD_OBJECT = -6
    }
}
