package io.github.thibaultbee.streampack.app.ui.main

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * A fake AudioTrack implementation that intercepts all write() calls and forwards
 * PCM data to CircularPcmBuffer instead of playing audio. This allows capturing
 * the exact same data ExoPlayer's DefaultAudioSink would send to the platform
 * AudioTrack, with precise timestamps.
 */
class FakeAudioTrack(
    private val audioBuffer: CircularPcmBuffer,
    audioAttributes: AudioAttributes,
    audioFormat: AudioFormat,
    bufferSizeInBytes: Int,
    mode: Int,
    sessionId: Int
) : AudioTrack(audioAttributes, audioFormat, bufferSizeInBytes, mode, sessionId) {
    
    private val TAG = "FakeAudioTrack"
    
    // Track state
    @Volatile private var playState = PLAYSTATE_STOPPED
    @Volatile private var volume = 1.0f
    
    // Position tracking for getCurrentPositionUs calculations
    private val writtenFrames = AtomicLong(0L)
    private var startTimeNanos = 0L
    private var pauseTimeNanos = 0L
    
    // Audio format info
    private val sampleRate = audioFormat.sampleRate
    private val channelCount = audioFormat.channelCount
    private val bytesPerSample = when (audioFormat.encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 2
    }
    private val bytesPerFrame = channelCount * bytesPerSample
    
    init {
        Log.d(TAG, "FakeAudioTrack created: sampleRate=$sampleRate, channels=$channelCount, bytesPerFrame=$bytesPerFrame")
        // Update CircularPcmBuffer with the actual format
        audioBuffer.updateFormat(sampleRate, channelCount, bytesPerSample)
    }
    
    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        return writeInternal(audioData, offsetInBytes, sizeInBytes, null)
    }
    
    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int): Int {
        return writeInternal(audioData, offsetInBytes, sizeInBytes, null)
    }
    
    override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int): Int {
        val presentationTimeUs = System.nanoTime() / 1000L // Convert to microseconds
        return writeInternal(audioData, sizeInBytes, presentationTimeUs)
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int, timestamp: Long): Int {
        // timestamp is in nanoseconds, convert to microseconds
        val presentationTimeUs = timestamp / 1000L
        return writeInternal(audioData, sizeInBytes, presentationTimeUs)
    }
    
    private fun writeInternal(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, timestamp: Long?): Int {
        if (sizeInBytes <= 0) return 0
        
        try {
            // Copy array data to ByteBuffer for CircularPcmBuffer
            val buffer = ByteBuffer.allocate(sizeInBytes)
            buffer.put(audioData, offsetInBytes, sizeInBytes)
            buffer.flip()
            
            val presentationTimeUs = timestamp ?: estimateTimestampUs()
            
            // Compute CRC32 for diagnostics
            val crc = CRC32()
            crc.update(audioData, offsetInBytes, sizeInBytes)
            
            // Write to circular buffer
            val seq = audioBuffer.writeFrame(buffer, presentationTimeUs)
            
            Log.v(TAG, "FAKE-TRACK-WRITE: size=$sizeInBytes timestampUs=$presentationTimeUs seq=$seq crc=0x${java.lang.Long.toHexString(crc.value)}")
            
            // Update position tracking
            val frames = sizeInBytes / bytesPerFrame
            writtenFrames.addAndGet(frames.toLong())
            
            return sizeInBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to circular buffer", e)
            return ERROR_BAD_VALUE
        }
    }
    
    private fun writeInternal(audioData: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long): Int {
        if (sizeInBytes <= 0) return 0
        
        try {
            // Copy ByteBuffer data
            val originalPosition = audioData.position()
            val copyBuffer = ByteBuffer.allocate(sizeInBytes)
            val limitedBuffer = audioData.duplicate()
            limitedBuffer.limit(originalPosition + sizeInBytes)
            copyBuffer.put(limitedBuffer)
            copyBuffer.flip()
            
            // Restore original position
            audioData.position(originalPosition + sizeInBytes)
            
            // Compute CRC32 for diagnostics
            val bytes = ByteArray(sizeInBytes)
            copyBuffer.duplicate().get(bytes)
            val crc = CRC32()
            crc.update(bytes)
            
            // Write to circular buffer
            val seq = audioBuffer.writeFrame(copyBuffer, presentationTimeUs)
            
            Log.v(TAG, "FAKE-TRACK-WRITE-BB: size=$sizeInBytes timestampUs=$presentationTimeUs seq=$seq crc=0x${java.lang.Long.toHexString(crc.value)}")
            
            // Update position tracking
            val frames = sizeInBytes / bytesPerFrame
            writtenFrames.addAndGet(frames.toLong())
            
            return sizeInBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ByteBuffer to circular buffer", e)
            return ERROR_BAD_VALUE
        }
    }
    
    private fun estimateTimestampUs(): Long {
        // Estimate based on written frames and sample rate
        val writtenFrameCount = writtenFrames.get()
        return (writtenFrameCount * 1_000_000L) / sampleRate
    }
    
    override fun play() {
        Log.d(TAG, "FakeAudioTrack.play()")
        playState = PLAYSTATE_PLAYING
        startTimeNanos = System.nanoTime()
    }
    
    override fun stop() {
        Log.d(TAG, "FakeAudioTrack.stop()")
        playState = PLAYSTATE_STOPPED
    }
    
    override fun pause() {
        Log.d(TAG, "FakeAudioTrack.pause()")
        playState = PLAYSTATE_PAUSED
        pauseTimeNanos = System.nanoTime()
    }
    
    override fun flush() {
        Log.d(TAG, "FakeAudioTrack.flush()")
        writtenFrames.set(0L)
        audioBuffer.clear()
    }
    
    override fun release() {
        Log.d(TAG, "FakeAudioTrack.release()")
        playState = PLAYSTATE_STOPPED
    }
    
    override fun getState(): Int = STATE_INITIALIZED
    override fun getPlayState(): Int = playState
    override fun getBufferSizeInFrames(): Int = super.getBufferSizeInFrames()
    override fun getBufferCapacityInFrames(): Int = super.getBufferCapacityInFrames()
    override fun getChannelCount(): Int = channelCount
    override fun getSampleRate(): Int = sampleRate
    override fun getAudioFormat(): Int = super.getAudioFormat()
    override fun getAudioSessionId(): Int = super.getAudioSessionId()
    
    override fun setVolume(volume: Float): Int {
        this.volume = volume
        return SUCCESS
    }
    
    override fun getPlaybackHeadPosition(): Int {
        // Return position in frames
        return (writtenFrames.get() and 0xFFFFFFFF).toInt()
    }
    
    override fun getTimestamp(timestamp: AudioTimestamp?): Boolean {
        if (timestamp == null) return false
        
        timestamp.framePosition = writtenFrames.get()
        timestamp.nanoTime = System.nanoTime()
        return true
    }
    
    // Stub other methods that ExoPlayer might call
    override fun setPlaybackParams(params: PlaybackParams): Unit = Unit
    override fun getPlaybackParams(): PlaybackParams = PlaybackParams()
    override fun attachAuxEffect(effectId: Int): Int = SUCCESS
    override fun setAuxEffectSendLevel(level: Float): Int = SUCCESS
    
    @RequiresApi(Build.VERSION_CODES.M)
    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean = true
    
    @RequiresApi(Build.VERSION_CODES.N)
    override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener?, handler: android.os.Handler?): Unit = Unit
    
    @RequiresApi(Build.VERSION_CODES.N)  
    override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener?): Unit = Unit
}
