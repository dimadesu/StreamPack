package io.github.thibaultbee.streampack.app.sources.audio

import android.media.AudioRecord

/**
 * A wrapper around [AudioRecord] to allow overriding methods like [startRecording].
 */
class AudioRecordWrapper(
    private val audioRecord: AudioRecord
) {
    /**
     * Starts recording audio. Add custom behavior here if needed.
     */
    fun startRecording() {
        // Custom logic before starting recording
        println("Custom startRecording logic")

        // Start the actual AudioRecord
        audioRecord.startRecording()
    }

    /**
     * Stops recording audio.
     */
    fun stop() {
        audioRecord.stop()
    }

    /**
     * Reads audio data into the provided buffer.
     */
    fun read(buffer: ByteArray, offset: Int, size: Int): Int {
        return audioRecord.read(buffer, offset, size)
    }

    /**
     * Releases the AudioRecord resources.
     */
    fun release() {
        audioRecord.release()
    }
}
