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

    /**
     * Data structure to hold audio data and its associated timestamp.
     */
    private data class AudioFrame(val data: ByteArray, val timestamp: Long)

    private val frameBuffer = ArrayDeque<AudioFrame>()

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
    @Synchronized
    fun read(mediaCodecBuffer: ByteBuffer, size: Int, readMode: Int = READ_BLOCKING): Int {
        val audioFrame = readFrame() ?: return 0 // Return 0 if the buffer is empty

        val (data, _) = audioFrame // Destructure the Pair to access the data
        val bytesToRead = minOf(size, data.size)
        mediaCodecBuffer.put(data, 0, bytesToRead)

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

    // /**
    //  * Writes up to [src.remaining()] bytes from [src] ByteBuffer along with a timestamp.
    //  * Returns the number of bytes actually written.
    //  */
    // fun write(src: ByteBuffer, presentationTimeUs: Long, writeMode: Int = WRITE_BLOCKING): Int {
    //     // Handle the timestamp (e.g., store it in a separate structure or log it)
    //     // For now, this implementation just logs the timestamp.
    //     println("Timestamp: $presentationTimeUs")

    //     if (src.remaining() < 0 || !src.isDirect) {
    //         return ERROR_BAD_VALUE
    //     }

    //     val length = src.remaining()
    //     while (availableBytes.get() == bufferSize) {
    //         if (writeMode == WRITE_NON_BLOCKING) {
    //             return 0 // Non-blocking mode, return immediately
    //         }
    //         Thread.yield() // Yield to other threads
    //     }

    //     val bytesToWrite = minOf(length, bufferSize - availableBytes.get())
    //     val currentWritePos = writePos.get()
    //     val endPos = (currentWritePos + bytesToWrite)

    //     if (endPos <= bufferSize) {
    //         src.get(playerBuffer, currentWritePos, bytesToWrite)
    //     } else {
    //         val bytesToEnd = bufferSize - currentWritePos
    //         src.get(playerBuffer, currentWritePos, bytesToEnd)
    //         src.get(playerBuffer, 0, bytesToWrite - bytesToEnd)
    //     }

    //     writePos.set((currentWritePos + bytesToWrite) % bufferSize)
    //     availableBytes.addAndGet(bytesToWrite)

    //     return bytesToWrite
    // }

    /**
     * Writes an audio frame with its timestamp to the buffer.
     * Returns true if the frame was successfully written, false if the buffer is full.
     */
    @Synchronized
    fun writeFrame(data: ByteBuffer, timestamp: Long): Boolean {
        if (frameBuffer.size >= bufferSize) {
            return false // Buffer is full
        }

        val audioData = ByteArray(data.remaining())
        data.get(audioData)
        frameBuffer.addLast(AudioFrame(audioData, timestamp))
        return true
    }

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * Returns null if the buffer is empty.
     */
    @Synchronized
    fun readFrame(): Pair<ByteArray, Long>? {
        return if (frameBuffer.isEmpty()) {
            null // Buffer is empty
        } else {
            val frame = frameBuffer.removeFirst()
            Pair(frame.data, frame.timestamp)
        }
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
