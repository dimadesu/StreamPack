package io.github.thibaultbee.streampack.app.ui.main

import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock

/**
 * Circular PCM buffer for audio streaming. Thread-safe for single producer/single consumer.
 */
class CircularPcmBuffer(private val bufferSize: Int) {
    private val playerBuffer = ByteArray(bufferSize)
    private var readPos = 0
    private var writePos = 0
    private var availableBytes = 0
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    val availableData: Int
        get() = availableBytes

    val capacity: Int
        get() = bufferSize

    /** Returns the number of bytes available to read. */
    fun available(): Int = availableBytes

    /** Clears the buffer. */
    fun clear() {
        lock.lock()
        try {
            readPos = 0
            writePos = 0
            availableBytes = 0
        } finally {
            lock.unlock() // Ensure the lock is always released
        }
    }

    /**
     * Reads up to [size] bytes into [mediaCodecBuffer] ByteBuffer.
     * Returns the number of bytes actually read.
     */
    @Synchronized
    fun read(mediaCodecBuffer: ByteBuffer, size: Int, readMode: Int = READ_BLOCKING): Int {
        lock.lock()
        try {
            if (readMode != READ_BLOCKING && readMode != READ_NON_BLOCKING) {
                return ERROR_BAD_VALUE
            }

            if (size < 0 || !mediaCodecBuffer.isDirect) {
                return ERROR_BAD_VALUE
            }

            while (availableBytes == 0) {
                if (readMode == READ_NON_BLOCKING) {
                    return 0 // Non-blocking mode, return immediately
                }
                try {
                    if (!condition.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        println("Timeout while waiting for data in read")
                        return 0 // Timeout occurred
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }

            val bytesToRead = minOf(size, availableBytes)
            val endPos = (readPos + bytesToRead)

            if (endPos <= bufferSize) {
                mediaCodecBuffer.put(playerBuffer, readPos, bytesToRead)
            } else {
                val bytesToEnd = bufferSize - readPos
                mediaCodecBuffer.put(playerBuffer, readPos, bytesToEnd)
                mediaCodecBuffer.put(playerBuffer, 0, bytesToRead - bytesToEnd)
            }

            // Important!
            mediaCodecBuffer.flip()

            readPos = (readPos + bytesToRead) % bufferSize
            availableBytes -= bytesToRead
            condition.signalAll()

            return bytesToRead
        } finally {
            lock.unlock()
        }
    }

    /**
     * Writes up to [src.remaining()] bytes from [src] ByteBuffer.
     * Returns the number of bytes actually written.
     */
    @Synchronized
    fun write(src: ByteBuffer, writeMode: Int = WRITE_NON_BLOCKING): Int {
        lock.lock()
        try {
            if (src.remaining() < 0 || !src.isDirect) {
                return ERROR_BAD_VALUE
            }

            val length = src.remaining()
            var freeSpace = bufferSize - availableBytes

            while (freeSpace == 0) {
                if (writeMode == WRITE_NON_BLOCKING) {
                    return 0 // Non-blocking mode, return immediately
                }
                try {
                    if (!condition.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                        println("Timeout while waiting for free space in write")
                        return 0 // Timeout occurred
                    }
                    freeSpace = bufferSize - availableBytes // Recalculate free space after waiting
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return 0
                }
            }

            val bytesToWrite = minOf(length, freeSpace)
            val endPos = (writePos + bytesToWrite)

            if (endPos <= bufferSize) {
                src.get(playerBuffer, writePos, bytesToWrite)
            } else {
                val bytesToEnd = bufferSize - writePos
                src.get(playerBuffer, writePos, bytesToEnd)
                src.get(playerBuffer, 0, bytesToWrite - bytesToEnd)
            }

            writePos = (writePos + bytesToWrite) % bufferSize
            availableBytes += bytesToWrite
            condition.signalAll()

            return bytesToWrite
        } finally {
            lock.unlock()
        }
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
