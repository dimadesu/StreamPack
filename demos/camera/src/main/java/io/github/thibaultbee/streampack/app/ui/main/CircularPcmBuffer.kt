package io.github.thibaultbee.streampack.app.ui.main

import android.util.Log
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import io.github.thibaultbee.streampack.core.elements.encoders.RuntimeAudioFormat

/**
 * A fixed-size, thread-safe circular buffer for audio frames.
 *
 * This buffer is designed to be filled with `ByteBuffer`s containing audio data
 * from a source (e.g., a microphone) and then drained by a consumer (e.g., a MediaCodec encoder).
 * It manages the total byte capacity and provides thread-safe access.
 *
 * @param byteCapacity The maximum total number of bytes the buffer can hold.
 */
/**
 * byteCapacity: total buffer capacity in bytes
 * sampleRate: sample rate in Hz (used to convert frames -> microseconds). Default 48000Hz.
 * channelCount: number of audio channels (default 2).
 * bytesPerSample: bytes per sample per channel (default 2 = 16-bit PCM).
 *
 * Note: defaults chosen to match common Android audio (48kHz, stereo, 16-bit). These can be
 * overridden by the app if different audio format is used.
 */
class CircularPcmBuffer(
    private val byteCapacity: Int,
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2,
    private val bytesPerSample: Int = 2,
) {

    // Allow runtime updates when actual sink format differs from configured one
    @Volatile
    private var sampleRateInternal: Int = sampleRate
    @Volatile
    private var channelCountInternal: Int = channelCount
    @Volatile
    private var bytesPerSampleInternal: Int = bytesPerSample

    private val bytesPerFrame: Int
        get() = channelCountInternal * bytesPerSampleInternal
    private val TAG = "CircularPcmBuffer"
    // Rate-limiting state for filling-mode logs to avoid spamming logcat.
    private var lastFillingLogTimeMs: Long = 0L
    private var lastFillingAvailableLogged: Int = -1

    // A data class to hold the audio frame's ByteBuffer and its presentation timestamp.
    // totalSize stores the original number of bytes in the frame chunk when it was written.
    // Store the audio data along with the format that was in effect when the chunk
    // was written. This avoids using the current (possibly updated) format to
    // compute timestamp offsets for older chunks.
    private data class AudioFrame(
        val data: ByteBuffer,
        val timestamp: Long,
        val totalSize: Int,
        val bytesPerFrameAtWrite: Int,
        val sampleRateAtWrite: Int,
        val seqId: Long,
    )

    // A thread-safe queue to manage the individual AudioFrame objects.
    // The capacity is a number of frames, not bytes.
    // We choose a generous capacity to prevent blocking on the queue itself,
    // as the main bottleneck will be the total byte capacity.
    private val frameBuffer = ArrayBlockingQueue<AudioFrame>(5000)

    // An atomic integer to track the total number of bytes currently in the buffer.
    private val availableBytes = AtomicInteger(0)

    // Sequence counter for written chunks, useful for correlating producer/consumer logs.
    private val seqCounter = AtomicLong(0L)
    // Metrics counters for diagnostics
    private val mergeSuccessCount = AtomicInteger(0)
    private val mergeFailCount = AtomicInteger(0)

    // Track the end timestamp (in microseconds) of the last enqueued chunk so that
    // subsequent chunks that incorrectly carry an earlier or equal timestamp can be
    // adjusted to ensure monotonic, non-decreasing timestamps.
    private var lastEnqueuedEndTimestamp: Long = 0L
    // Additional tracking for alignment diagnostics: timestamp and size of last enqueued chunk
    private var lastEnqueueTimestampRaw: Long = 0L
    private var lastEnqueueSize: Int = 0
    private var lastEnqueueSeqId: Long = 0L

    // A state variable to manage the "filling" vs. "draining" state of the buffer.
    private var isFilling = true

    // Dedicated accumulator for small writes. We keep small writes in-memory and only
    // publish a queued chunk when the accumulator reaches ACCUMULATE_TARGET or
    // when a large write arrives.
    private var accumulator: ByteBuffer? = null
    private var accumulatorTimestamp: Long = 0L
    private var accumulatorBpf: Int = 0
    private var accumulatorSr: Int = 0
    private var accumulatorWrites: Int = 0
    private var accumulatorLastSeqId: Long = 0L
    private val ACCUMULATE_TARGET = 2048

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
     * Clears the buffer, removing all frames and resetting the available byte count.
     */
    fun clear() {
        Log.i(TAG, "Clearing buffer. Available bytes before clear: ${availableBytes.get()}")
        frameBuffer.clear()
        availableBytes.set(0)
    // Clear accumulator as well.
    accumulator = null
    accumulatorTimestamp = 0L
    accumulatorBpf = 0
    accumulatorSr = 0
    accumulatorWrites = 0
    accumulatorLastSeqId = 0L
    isFilling = true
    lastEnqueuedEndTimestamp = 0L
        Log.i(TAG, "Buffer cleared. Available bytes after clear: ${availableBytes.get()}")
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
    /**
     * Writes a frame and returns the sequence id assigned to this chunk.
     */
    fun writeFrame(data: ByteBuffer, timestamp: Long): Long {
        val totalSize = data.remaining()
        Log.d(TAG, "Attempting to write frame. Total data size: $totalSize, Timestamp: $timestamp")

        // Ensure stored buffer is independent and positioned at 0 with correct limit.
        val stored = data.duplicate()
        // normalize position to 0 so reads compute offsets reliably
        stored.position(0)
    // Capture format values at write time so later reads use the correct
    // conversion from bytes -> frames -> microseconds even if updateFormat
    // was called in the meantime.
    val bpfAtWrite = bytesPerFrame
    val srAtWrite = sampleRateInternal

    val seqId = seqCounter.incrementAndGet()
    // Try coalescing small consecutive writes while in filling mode. This reduces the
    // number of tiny queued chunks and helps the consumer reach the drain threshold.
    // Tuned thresholds: allow larger small-chunks to be coalesced and permit larger merges.
    val SMALL_CHUNK_THRESHOLD = 1024
    val MERGE_MAX = 8192

    synchronized(this) {
        try {
            // Use dedicated accumulator for small writes when in filling mode.
            if (isFilling && totalSize <= SMALL_CHUNK_THRESHOLD) {
                // Initialize accumulator if needed.
                if (accumulator == null) {
                    accumulator = ByteBuffer.allocate(ACCUMULATE_TARGET)
                    accumulatorTimestamp = timestamp
                    accumulatorBpf = bpfAtWrite
                    accumulatorSr = srAtWrite
                    accumulatorWrites = 0
                    accumulatorLastSeqId = seqId
                }

                // If format changed or accumulator overflow would occur, flush first.
                if (accumulatorBpf != bpfAtWrite || accumulatorSr != srAtWrite || accumulator!!.remaining() < totalSize) {
                    flushAccumulator()
                    // create new accumulator for this write
                    accumulator = ByteBuffer.allocate(ACCUMULATE_TARGET)
                    accumulatorTimestamp = timestamp
                    accumulatorBpf = bpfAtWrite
                    accumulatorSr = srAtWrite
                    accumulatorWrites = 0
                    accumulatorLastSeqId = seqId
                }

                // Append to accumulator.
                val posBefore = accumulator!!.position()
                val appendView = stored.duplicate()
                appendView.position(0)
                accumulator!!.put(appendView)
                accumulatorWrites++
                accumulatorLastSeqId = seqId

                // If we've reached the target, publish a frame.
                if (accumulator!!.position() >= ACCUMULATE_TARGET) {
                    accumulator!!.flip()
                    val accSize = accumulator!!.remaining()
                    val accBuf = ByteBuffer.allocate(accSize)
                    accBuf.put(accumulator!!)
                    accBuf.flip()
                    // Adjust timestamp to ensure monotonicity relative to previously enqueued data.
                    var frameTimestamp = accumulatorTimestamp
                    val accDurUs = bytesToDurationUs(accSize)
                    val proposedEnd = frameTimestamp + accDurUs
                    if (proposedEnd <= lastEnqueuedEndTimestamp) {
                        // Shift timestamp forward so frames do not go backwards.
                        frameTimestamp = lastEnqueuedEndTimestamp
                    }
                    val accFrame = AudioFrame(accBuf, frameTimestamp, accSize, accumulatorBpf, accumulatorSr, accumulatorLastSeqId)
                    val offered = frameBuffer.offer(accFrame)
                    if (offered) {
                        availableBytes.addAndGet(accSize)
                        mergeSuccessCount.incrementAndGet()
                        // Update last enqueued end timestamp for next writes
                        lastEnqueuedEndTimestamp = frameTimestamp + bytesToDurationUs(accSize)
                        Log.d(TAG, "Accumulator flushed. seq=${accumulatorLastSeqId} size=$accSize Available bytes: ${availableBytes.get()}")
                        (this as java.lang.Object).notifyAll()
                    } else {
                        mergeFailCount.incrementAndGet()
                        Log.w(TAG, "Accumulator flush failed to enqueue. seq=${accumulatorLastSeqId} size=$accSize")
                    }
                    accumulator = null
                }

                return seqId
            }

            // Default: enqueue as a separate chunk
            // Before enqueuing a large write, flush any pending accumulator contents so
            // ordering and timestamps remain correct.
            if (accumulator != null) {
                flushAccumulator()
            }

            // Ensure timestamps are monotonic when enqueuing a new chunk.
            var enqueueTimestamp = timestamp
            val durUs = bytesToDurationUs(totalSize)
            val proposedEnd = enqueueTimestamp + durUs
            if (proposedEnd <= lastEnqueuedEndTimestamp) {
                enqueueTimestamp = lastEnqueuedEndTimestamp
            }

            val frame = AudioFrame(stored, enqueueTimestamp, totalSize, bpfAtWrite, srAtWrite, seqId)
            val success = frameBuffer.offer(frame)

            if (success) {
                availableBytes.addAndGet(totalSize)
                // Update last enqueued end timestamp
                lastEnqueuedEndTimestamp = enqueueTimestamp + bytesToDurationUs(totalSize)
                Log.d(TAG, "Chunk written. seq=$seqId Chunk size: $totalSize, Available bytes: ${availableBytes.get()}")
                try {
                    (this as java.lang.Object).notifyAll()
                } catch (ignored: Exception) {
                }
            } else {
                Log.w(TAG, "Buffer is full. seq=$seqId Remaining data size: ${data.remaining()}")
            }

            return seqId
        } catch (e: Exception) {
            Log.w(TAG, "writeFrame: unexpected exception during write/merge: ${e.message}")
            // Fallback behaviour: attempt non-synchronized offer
            val fallbackSuccess = frameBuffer.offer(AudioFrame(stored, timestamp, totalSize, bpfAtWrite, srAtWrite, seqId))
            if (fallbackSuccess) availableBytes.addAndGet(totalSize)
            if (!fallbackSuccess) mergeFailCount.incrementAndGet()
            return seqId
        }
    }
    }

    /**
     * Wait up to [timeoutMs] milliseconds for data to become available in the buffer.
     * Returns true if data is available, false if timed out.
     */
    fun waitForData(timeoutMs: Long): Boolean {
        if (availableBytes.get() > 0) return true
        synchronized(this) {
            if (availableBytes.get() > 0) return true
            try {
                (this as java.lang.Object).wait(timeoutMs)
            } catch (ignored: InterruptedException) {
                // ignore
            }
            return availableBytes.get() > 0
        }
    }

    // Flush any accumulated small writes into the main frameBuffer.
    private fun flushAccumulator() {
        val acc = accumulator ?: return
        try {
            acc.flip()
            val accSize = acc.remaining()
            val buf = ByteBuffer.allocate(accSize)
            buf.put(acc)
            buf.flip()
            val seq = accumulatorLastSeqId
            val frame = AudioFrame(buf, accumulatorTimestamp, accSize, accumulatorBpf, accumulatorSr, seq)
            val offered = frameBuffer.offer(frame)
            if (offered) {
                availableBytes.addAndGet(accSize)
                mergeSuccessCount.incrementAndGet()
                Log.d(TAG, "flushAccumulator: seq=$seq size=$accSize Available bytes: ${availableBytes.get()}")
                (this as java.lang.Object).notifyAll()
            } else {
                mergeFailCount.incrementAndGet()
                Log.w(TAG, "flushAccumulator: failed to offer accumulated frame seq=$seq size=$accSize")
            }
        } catch (e: Exception) {
            Log.w(TAG, "flushAccumulator: unexpected: ${e.message}")
        } finally {
            accumulator = null
            accumulatorTimestamp = 0L
            accumulatorBpf = 0
            accumulatorSr = 0
            accumulatorWrites = 0
            accumulatorLastSeqId = 0L
        }
    }

    // Expose simple accumulator metrics
    fun getAccumulatorPendingBytes(): Int = accumulator?.position() ?: 0
    fun getAccumulatorWrites(): Int = accumulatorWrites

    /**
     * Reads an audio frame with its timestamp from the buffer.
     * This method is thread-safe and implements a basic buffering strategy:
     * it will not start reading until the buffer is at least `30%` full to
     * avoid feeding an encoder with too little data, which could cause glitches.
     *
     * @return A `Pair` of `ByteBuffer` and timestamp if a frame is available, or `null` otherwise.
     */
    /**
     * Reads up to `size` bytes and returns (buffer, timestamp, seqId) or null if none available.
     */
    fun readFrame(size: Int): Triple<ByteBuffer, Long, Long>? {
        // The `isFilling` state prevents premature reading.
        if (isFilling) {
            // If we already have enough bytes for the requested read, allow draining
            // immediately to reduce fallback risk. Otherwise fall back to the
            // percentage-based threshold used previously.
            val avail = availableBytes.get()
            if (avail >= size || isAtLeastFull(40)) { // Using a 40% fill level to ensure smooth draining.
                isFilling = false
                Log.i(TAG, "Switching to draining mode. Available bytes: ${availableBytes.get()}")
            } else {
                // Rate-limit debug logs while filling.
                val now = SystemClock.elapsedRealtime()
                if (avail != lastFillingAvailableLogged && now - lastFillingLogTimeMs >= 200L) {
                    Log.d(TAG, "Still in filling mode. Available bytes: $avail")
                    lastFillingAvailableLogged = avail
                    lastFillingLogTimeMs = now
                }

                // If we already have some bytes, give producer a short chance to reach the
                // drain threshold or enough bytes for this read before returning null.
                if (avail > 0) {
                    // Wait until either percentage threshold or sufficient bytes available.
                    val reached = waitUntilAtLeastFullOrBytes(40, size, 120L)
                    if (reached) {
                        isFilling = false
                        Log.i(TAG, "Switching to draining mode after short wait. Available bytes: ${availableBytes.get()}")
                    } else {
                        return null
                    }
                } else {
                    return null // Still filling, so no frame is available yet.
                }
            }
        }


    val currentFrame = pendingFrame ?: frameBuffer.poll()
        if (currentFrame == null) {
            // If the buffer is empty, switch back to the filling state.
            isFilling = true
            Log.i(TAG, "Buffer drained. Switching back to filling mode.")
            return null // Buffer is empty.
        }

    pendingFrame = currentFrame

        val availableInChunk = currentFrame.data.remaining()
        val toRead = minOf(availableInChunk, size)
    Log.d(TAG, "Frame read. toRead $toRead")

    val out = ByteBuffer.allocate(toRead)
        val sourceView = currentFrame.data.duplicate()
        sourceView.limit(sourceView.position() + toRead)
        out.put(sourceView)
        out.flip()

        // Advance original buffer's position by the amount consumed.
        val prevPos = currentFrame.data.position()
        currentFrame.data.position(prevPos + toRead)

        // If we've consumed all bytes from this stored chunk, clear pending.
        val seqId = currentFrame.seqId
        if (!currentFrame.data.hasRemaining()) {
            pendingFrame = null
        }

        // Subtract only the number of bytes actually consumed.
        availableBytes.addAndGet(-toRead)
    Log.d(TAG, "Frame read. seq=$seqId toRead $toRead")
    Log.d(TAG, "Frame read. Bytes read: $toRead, Available bytes: ${availableBytes.get()}")

        // Compute adjusted timestamp for this slice. The timestamp stored is for the start
        // of the original frame. We must offset it by how many audio frames were skipped.
        val consumedBefore = currentFrame.totalSize - currentFrame.data.remaining() - toRead
        // consumedBefore is bytes already consumed before this returned slice.
        val consumedFramesBefore = if (currentFrame.bytesPerFrameAtWrite > 0) {
            consumedBefore / currentFrame.bytesPerFrameAtWrite
        } else 0

        // Use the sample rate that was in effect when the chunk was written.
        val timestampAdjustmentUs = if (currentFrame.sampleRateAtWrite > 0) {
            (consumedFramesBefore * 1_000_000L) / currentFrame.sampleRateAtWrite
        } else 0L

    val adjustedTimestamp = currentFrame.timestamp + timestampAdjustmentUs

    return Triple(out, adjustedTimestamp, seqId)
    }

    /**
     * Peeks at the timestamp of the next available chunk without removing or
     * consuming any data. Returns null if no frame is currently queued.
     */
    fun peekNextTimestamp(): Long? {
        val head = pendingFrame ?: frameBuffer.peek() ?: return null
        return head.timestamp
    }

    /**
     * Peek next sequence id if any
     */
    fun peekNextSeqId(): Long? {
        val head = pendingFrame ?: frameBuffer.peek() ?: return null
        return head.seqId
    }

    /**
     * Convert a number of bytes to duration in microseconds using configured audio format.
     */
    fun bytesToDurationUs(bytes: Int): Long {
        // Prefer explicit runtime format reported by the sink/decoder if available.
        val runtimeSr = RuntimeAudioFormat.sampleRate
        val runtimeChannels = RuntimeAudioFormat.channelCount
        val runtimeBps = RuntimeAudioFormat.bytesPerSample

        val useSampleRate = runtimeSr ?: sampleRateInternal
        val useBytesPerFrame = if (runtimeChannels != null && runtimeBps != null) {
            runtimeChannels * runtimeBps
        } else {
            bytesPerFrame
        }

        if (useBytesPerFrame == 0 || useSampleRate == 0) {
            Log.w(TAG, "bytesToDurationUs: invalid format: useBytesPerFrame=$useBytesPerFrame useSampleRate=$useSampleRate")
            return 0L
        }

        // Heuristic: sometimes the runtime-reported channel count is wrong (mono vs stereo).
        // If runtime reports exactly half of the configured bytesPerFrame (e.g. 2 vs 4), prefer
        // the configured value to avoid doubling durations incorrectly.
        var finalBytesPerFrame = useBytesPerFrame
        var preferred = "runtime"
        if (runtimeSr != null) {
            if (useBytesPerFrame * 2 == bytesPerFrame) {
                Log.w(TAG, "bytesToDurationUs: runtime bytesPerFrame ($useBytesPerFrame) is half of configured bytesPerFrame ($bytesPerFrame). Preferring configured value to avoid timing mismatch.")
                finalBytesPerFrame = bytesPerFrame
                preferred = "configured"
            }
        }

        val dur = (bytes.toLong() * 1_000_000L) / (finalBytesPerFrame.toLong() * useSampleRate.toLong())
        Log.d(TAG, "bytesToDurationUs: bytes=$bytes runtimeChannels=${runtimeChannels ?: "null"} runtimeBps=${runtimeBps ?: "null"} runtimeSr=${runtimeSr ?: "null"} configuredBytesPerFrame=$bytesPerFrame chosenBytesPerFrame=$finalBytesPerFrame sampleRateUsed=$useSampleRate -> durUs=$dur preferred=$preferred")
        return dur
    }

    // Expose metrics for diagnostics
    fun getMergeSuccessCount(): Int = mergeSuccessCount.get()
    fun getMergeFailCount(): Int = mergeFailCount.get()

    /**
     * Update internal audio format used for duration computations.
     * Thread-safe and can be called at runtime when the sink/decoder reports a different format.
     */
    fun updateFormat(newSampleRate: Int, newChannelCount: Int, newBytesPerSample: Int) {
        if (newSampleRate <= 0 || newChannelCount <= 0 || newBytesPerSample <= 0) return
        sampleRateInternal = newSampleRate
        channelCountInternal = newChannelCount
        bytesPerSampleInternal = newBytesPerSample
        Log.i(TAG, "Audio format updated: sampleRate=$newSampleRate channelCount=$newChannelCount bytesPerSample=$newBytesPerSample")
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

    /**
     * Wait up to [timeoutMs] milliseconds for the buffer to reach at least [percentage]
     * percent fullness. Returns true if reached, false on timeout.
     */
    private fun waitUntilAtLeastFull(percentage: Int, timeoutMs: Long): Boolean {
        if (isAtLeastFull(percentage)) return true
        val start = SystemClock.elapsedRealtime()
        synchronized(this) {
            while (!isAtLeastFull(percentage) && SystemClock.elapsedRealtime() - start < timeoutMs) {
                try {
                    (this as java.lang.Object).wait(10L)
                } catch (ignored: InterruptedException) {
                }
            }
        }
        return isAtLeastFull(percentage)
    }

    /**
     * Wait up to [timeoutMs] milliseconds for the buffer to reach at least [percentage]
     * percent fullness or for the available bytes to reach [minBytes], whichever comes first.
     */
    private fun waitUntilAtLeastFullOrBytes(percentage: Int, minBytes: Int, timeoutMs: Long): Boolean {
        if (isAtLeastFull(percentage) || availableBytes.get() >= minBytes) return true
        val start = SystemClock.elapsedRealtime()
        synchronized(this) {
            while (!isAtLeastFull(percentage) && availableBytes.get() < minBytes && SystemClock.elapsedRealtime() - start < timeoutMs) {
                try {
                    (this as java.lang.Object).wait(10L)
                } catch (ignored: InterruptedException) {
                }
            }
        }
        return isAtLeastFull(percentage) || availableBytes.get() >= minBytes
    }
}
