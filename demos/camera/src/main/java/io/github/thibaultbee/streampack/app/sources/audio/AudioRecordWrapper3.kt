package io.github.thibaultbee.streampack.app.sources.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import io.github.thibaultbee.streampack.app.ui.main.CircularPcmBuffer
import io.github.thibaultbee.streampack.core.elements.data.RawFrame

class AudioRecordWrapper3(
    private val exoPlayer: ExoPlayer,
    private val audioBuffer: CircularPcmBuffer,
) {
    companion object {
        private const val TAG = "AudioRecordWrapper3"
    }

    // Monotonic anchor used when no decoded frames are available.
    private var lastOutputTimestampUs: Long = 0L
    // Rate-limiting empty-buffer logs.
    private var lastEmptyLogTimeMs: Long = 0L
    private var emptyLogCounter: Int = 0
    // Metrics counters for diagnostics
    private var fallbackCount: Int = 0
    private var immediateRetrySuccessCount: Int = 0
    private var immediateRetryAttemptCount: Int = 0

    /**
     * Starts recording audio. Add custom behavior here if needed.
     */
    fun startRecording() {
        audioBuffer.clear()
        android.util.Log.i(TAG, "Audio buffer cleared before streaming start.")


//       if (Looper.myLooper() == Looper.getMainLooper()) {
//           exoPlayer.prepare()
//           exoPlayer.playWhenReady = true
//       } else {
           Handler(Looper.getMainLooper()).post {
               exoPlayer.prepare()
               exoPlayer.playWhenReady = true
           }
//       }
    }

    /**
     * Stops recording audio.
     */
    fun stop() {
        // I don't know why this doesn't seem to need this main thread thing.
        // It actually breaks transition to setting activity if it's enabled here
        // I think audio input's stopStream is called on activity destroy or smth like that
        // Also maybe smth to do with suspend
//       withContext(Dispatchers.Main) {
            // exoPlayer.stop()
//       }

       Handler(Looper.getMainLooper()).post {
           exoPlayer.stop()
       }
    }

    /**
     * Reads audio data into the provided buffer.
     */
    fun read(streamPackAudioFrame: RawFrame) {
        // Try an initial non-blocking read.
    var exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())

        // Default/monotonic timestamp used when no decoded frame is available.
        var outTsUs: Long
        val frameBytes = streamPackAudioFrame.rawBuffer.remaining()

                if (exoPlayerAudioFrame != null) {
                    val (data, timestamp, seq) = exoPlayerAudioFrame
                    val providedSize = data.remaining()
                    streamPackAudioFrame.rawBuffer.put(data)
                    outTsUs = timestamp
                    val durUs = audioBuffer.bytesToDurationUs(providedSize)
                    Log.d(TAG, "read: providedSize=$providedSize computedDurUs=$durUs seq=$seq")
                    lastOutputTimestampUs = outTsUs + durUs
                    Log.d(TAG, "read: provided frame size=$providedSize tsUs=$timestamp seq=$seq")
                } else {
            // If we have no anchor yet, wait briefly for the sink to produce the first chunk.
            if (lastOutputTimestampUs == 0L) {
                // Quick check: peek for timestamp. If none, wait up to 200ms for data.
                val nextTs = audioBuffer.peekNextTimestamp()
                if (nextTs == null) {
                    val got = audioBuffer.waitForData(200L)
                    if (got) {
                        // Re-attempt a read now that data may be available.
                        exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())
                    }
                } else {
                    // We have an upcoming timestamp; use it as anchor without blocking.
                    lastOutputTimestampUs = nextTs
                }

                    if (exoPlayerAudioFrame != null) {
                    val (data, timestamp, seq) = exoPlayerAudioFrame
                    val providedSize = data.remaining()
                    streamPackAudioFrame.rawBuffer.put(data)
                    outTsUs = timestamp
                    val durUs = audioBuffer.bytesToDurationUs(providedSize)
                    Log.d(TAG, "read: providedSize=$providedSize computedDurUs=$durUs (after wait) seq=$seq")
                    lastOutputTimestampUs = outTsUs + durUs
                    Log.d(TAG, "read: provided frame size=$providedSize tsUs=$timestamp (after wait) seq=$seq")
                } else {
                    // Still no data: fall back to monotonic time anchor.
                    lastOutputTimestampUs = System.nanoTime() / 1000
                    outTsUs = lastOutputTimestampUs
                    Log.d(TAG, "read: buffer empty after wait, using monotonic tsUs=$outTsUs")
                }
            } else {
                // Normal fallback advancement when we already have an anchor.
                // But first, give the producer a short chance to refill so we don't
                // emit many monotonic fallback frames while the buffer is in filling mode.
                val nextTs = audioBuffer.peekNextTimestamp()
                    if (nextTs != null) {
                        // Data is queued: try an immediate read instead of emitting a fallback frame.
                        exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())
                        if (exoPlayerAudioFrame != null) {
                            val (data, timestamp, seq) = exoPlayerAudioFrame
                            val providedSize = data.remaining()
                            streamPackAudioFrame.rawBuffer.put(data)
                            outTsUs = timestamp
                            val durUs = audioBuffer.bytesToDurationUs(providedSize)
                            Log.d(TAG, "read: providedSize=$providedSize computedDurUs=$durUs (immediate) seq=$seq")
                            lastOutputTimestampUs = outTsUs + durUs
                            Log.d(TAG, "read: provided frame size=$providedSize tsUs=$timestamp (immediate) seq=$seq")
                            streamPackAudioFrame.timestampInUs = outTsUs
                            streamPackAudioFrame.rawBuffer.flip()
                            return
                        }

                        // Immediate read returned null despite peek. Do several short blocking
                        // retries (producer could be racing to publish small chunks). Use multiple
                        // short waits to reduce races without blocking too long on the main
                        // encoding thread.
                        val maxAttempts = 8
                        var attempt = 0
                        while (attempt < maxAttempts && exoPlayerAudioFrame == null) {
                            immediateRetryAttemptCount++
                            val shortGotLoop = audioBuffer.waitForData(20L)
                            if (!shortGotLoop) {
                                attempt++
                                continue
                            }
                            exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())
                            if (exoPlayerAudioFrame != null) {
                                immediateRetrySuccessCount++
                                val (data, timestamp, seq) = exoPlayerAudioFrame
                                val providedSize = data.remaining()
                                streamPackAudioFrame.rawBuffer.put(data)
                                outTsUs = timestamp
                                val durUs = audioBuffer.bytesToDurationUs(providedSize)
                                Log.d(TAG, "read: providedSize=$providedSize computedDurUs=$durUs (immediate retry attempt=${attempt + 1}) seq=$seq")
                                lastOutputTimestampUs = outTsUs + durUs
                                Log.d(TAG, "read: provided frame size=$providedSize tsUs=$timestamp (immediate retry attempt=${attempt + 1}) seq=$seq")
                                streamPackAudioFrame.timestampInUs = outTsUs
                                streamPackAudioFrame.rawBuffer.flip()
                                return
                            }
                            attempt++
                        }
                        } else {
                    // Wait briefly (non-blocking for the app) for up to 80ms for data to arrive.
                    val got = audioBuffer.waitForData(80L)
                    if (got) {
                        // Re-attempt a read now that data may be available.
                        exoPlayerAudioFrame = audioBuffer.readFrame(streamPackAudioFrame.rawBuffer.remaining())
                        if (exoPlayerAudioFrame != null) {
                            val (data, timestamp, seq) = exoPlayerAudioFrame
                            val providedSize = data.remaining()
                            streamPackAudioFrame.rawBuffer.put(data)
                            outTsUs = timestamp
                            val durUs = audioBuffer.bytesToDurationUs(providedSize)
                            Log.d(TAG, "read: providedSize=$providedSize computedDurUs=$durUs (after refill wait) seq=$seq")
                            lastOutputTimestampUs = outTsUs + durUs
                            Log.d(TAG, "read: provided frame size=$providedSize tsUs=$timestamp (after refill wait) seq=$seq")
                            streamPackAudioFrame.timestampInUs = outTsUs
                            streamPackAudioFrame.rawBuffer.flip()
                            return
                        }
                    }
                }

                // Reduce fallback jump size so each fallback advances time less and is less
                // audible. Use a smaller step than the full buffer when possible.
        val fallbackStep = kotlin.math.min(frameBytes, 512)
                val durUs = audioBuffer.bytesToDurationUs(fallbackStep)
                fallbackCount++
                Log.d(TAG, "read: fallback originalFrameBytes=$frameBytes usingStep=$fallbackStep computedDurUs=$durUs fallbackCount=$fallbackCount")
                if (fallbackCount % 10 == 0) {
                    try {
                        val merges = audioBuffer.getMergeSuccessCount()
                        val mergesFail = audioBuffer.getMergeFailCount()
            Log.i(TAG, "merge metrics: success=$merges fail=$mergesFail immediateRetrySuccess=$immediateRetrySuccessCount")
                    } catch (ignored: Exception) {
                    }
                }
                lastOutputTimestampUs += durUs
                outTsUs = lastOutputTimestampUs

                // Rate-limit empty-buffer debug logs: at most once per 200ms or every 50 occurrences.
                val now = System.currentTimeMillis()
                emptyLogCounter++
                if (now - lastEmptyLogTimeMs >= 200L || emptyLogCounter % 50 == 0) {
                    Log.d(TAG, "read: buffer empty, using monotonic tsUs=$outTsUs")
                    lastEmptyLogTimeMs = now
                }
            }
        }

        streamPackAudioFrame.timestampInUs = outTsUs
        streamPackAudioFrame.rawBuffer.flip()
    }

    /**
     * Releases the AudioRecord resources.
     */
    fun release() {
        // I think this also doesn't need main thread stuff for exoPlayer
//       if (Looper.myLooper() == Looper.getMainLooper()) {
//            exoPlayer.release()
//            exoPlayer = null
//       } else {
           Handler(Looper.getMainLooper()).post {
               exoPlayer.release()
//           }
       }
        audioBuffer.clear()
    }
}
