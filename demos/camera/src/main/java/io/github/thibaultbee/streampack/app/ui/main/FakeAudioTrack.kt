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
 * A pass-through AudioTrack wrapper that intercepts all write() calls to copy
 * PCM data to CircularPcmBuffer while still forwarding all operations to a real
 * AudioTrack. This preserves ExoPlayer's A/V synchronization while capturing audio data.
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
    
    init {
        Log.i(TAG, "FakeAudioTrack created! SampleRate: ${audioFormat.sampleRate}, Channels: ${audioFormat.channelCount}, BufferSize: $bufferSizeInBytes")
    }
    
    // Create a real AudioTrack to handle actual playback and maintain A/V sync
    private val realAudioTrack = AudioTrack(
        audioAttributes,
        audioFormat,
        bufferSizeInBytes,
        mode,
        sessionId
    )
    
    // Audio format info for CircularPcmBuffer
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
    
    // Position tracking for timestamp estimation (used by writeInternal methods)
    private val writtenFrames = AtomicLong(0L)
    
    // Consistent timestamp base - initialized when first write occurs
    private var timestampBaseUs: Long = 0L
    private var firstWriteTime: Long = 0L
    private val timestampLock = Any()
    
    init {
        Log.i(TAG, "FakeAudioTrack created: sampleRate=$sampleRate, channels=$channelCount, bytesPerFrame=$bytesPerFrame")
        // Update CircularPcmBuffer with the actual format detected by ExoPlayer
        audioBuffer.updateFormat(sampleRate, channelCount, bytesPerSample)
        Log.i(TAG, "Updated CircularPcmBuffer format from ExoPlayer AudioFormat")
    }
    
    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        val framesToWrite = sizeInBytes / bytesPerFrame
        val newFrameCount = writtenFrames.addAndGet(framesToWrite.toLong())
        val presentationTimeUs = generateMonotonicTimestampUs(newFrameCount)
        
        // Copy to our buffer first
        writeInternal(audioData, offsetInBytes, sizeInBytes, presentationTimeUs)
        // Then forward to real AudioTrack for proper playback
        return realAudioTrack.write(audioData, offsetInBytes, sizeInBytes)
    }
    
    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int): Int {
        val framesToWrite = sizeInBytes / bytesPerFrame
        val newFrameCount = writtenFrames.addAndGet(framesToWrite.toLong())
        val presentationTimeUs = generateMonotonicTimestampUs(newFrameCount)
        
        // Copy to our buffer first
        writeInternal(audioData, offsetInBytes, sizeInBytes, presentationTimeUs)
        // Then forward to real AudioTrack
        return realAudioTrack.write(audioData, offsetInBytes, sizeInBytes, writeMode)
    }
    
    override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int): Int {
        val framesToWrite = sizeInBytes / bytesPerFrame
        val newFrameCount = writtenFrames.addAndGet(framesToWrite.toLong())
        val presentationTimeUs = generateMonotonicTimestampUs(newFrameCount)
        
        // Create a copy for our buffer since writeInternal will consume the data
        val originalPosition = audioData.position()
        val copyForOurBuffer = audioData.duplicate()
        copyForOurBuffer.limit(originalPosition + sizeInBytes)
        
        // Copy to our buffer first
        writeInternal(copyForOurBuffer, sizeInBytes, presentationTimeUs)
        
        // Forward to real AudioTrack (audioData position unchanged)
        return realAudioTrack.write(audioData, sizeInBytes, writeMode)
    }
    
    @RequiresApi(Build.VERSION_CODES.M)
    override fun write(audioData: ByteBuffer, sizeInBytes: Int, writeMode: Int, timestamp: Long): Int {
        val framesToWrite = sizeInBytes / bytesPerFrame
        val newFrameCount = writtenFrames.addAndGet(framesToWrite.toLong())
        // Use monotonic timestamp instead of provided timestamp for consistency
        val presentationTimeUs = generateMonotonicTimestampUs(newFrameCount)
        
        // Create a copy for our buffer since writeInternal will consume the data
        val originalPosition = audioData.position()
        val copyForOurBuffer = audioData.duplicate()
        copyForOurBuffer.limit(originalPosition + sizeInBytes)
        
        // Copy to our buffer first  
        writeInternal(copyForOurBuffer, sizeInBytes, presentationTimeUs)
        
        // Forward to real AudioTrack (audioData position unchanged)
        return realAudioTrack.write(audioData, sizeInBytes, writeMode, timestamp)
    }
    
    private fun writeInternal(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, timestamp: Long): Int {
        if (sizeInBytes <= 0) return 0
        
        try {
            // Optimize: Use wrap instead of allocate + put for better performance
            val buffer = ByteBuffer.wrap(audioData, offsetInBytes, sizeInBytes)
            
            // Compute CRC32 for diagnostics (reduce frequency to lower overhead)
            val crc = if (sizeInBytes % 1024 == 0) { // Only log every ~1024 bytes
                val c = CRC32()
                c.update(audioData, offsetInBytes, sizeInBytes)
                c
            } else null
            
            // Write to circular buffer
            audioBuffer.writeFrame(buffer, timestamp)
            
            // Reduce logging overhead
            if (crc != null) {
                Log.v(TAG, "FAKE-TRACK-WRITE: size=$sizeInBytes timestampUs=$timestamp crc=0x${java.lang.Long.toHexString(crc.value)}")
            }
            
            return sizeInBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to circular buffer", e)
            return ERROR_BAD_VALUE
        }
    }
    
    private fun writeInternal(audioData: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long): Int {
        if (sizeInBytes <= 0) return 0
        
        try {
            // Optimize: Use slice instead of allocate + put for better performance
            val originalPosition = audioData.position()
            val originalLimit = audioData.limit()
            
            // Create a slice that represents exactly the data we want
            audioData.limit(originalPosition + sizeInBytes)
            val sliceBuffer = audioData.slice()
            
            // Restore original buffer state
            audioData.limit(originalLimit)
            audioData.position(originalPosition + sizeInBytes)
            
            // Reduce CRC32 computation frequency for performance
            val crc = if (sizeInBytes % 2048 == 0) { // Only compute every ~2KB
                val bytes = ByteArray(sizeInBytes)
                sliceBuffer.duplicate().get(bytes)
                val c = CRC32()
                c.update(bytes)
                c
            } else null
            
            // Write to circular buffer using the slice directly
            audioBuffer.writeFrame(sliceBuffer, presentationTimeUs)
            
            // Reduce logging overhead
            if (crc != null) {
                Log.v(TAG, "FAKE-TRACK-WRITE-BB: size=$sizeInBytes timestampUs=$presentationTimeUs crc=0x${java.lang.Long.toHexString(crc.value)}")
            }
            
            return sizeInBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ByteBuffer to circular buffer", e)
            return ERROR_BAD_VALUE
        }
    }
    
    private fun estimateTimestampUs(): Long {
        // Use consistent audio timeline based on frames written
        val writtenFrameCount = writtenFrames.get()
        return (writtenFrameCount * 1_000_000L) / sampleRate.toLong()
    }
    
    /**
     * Generate monotonically increasing timestamps based on audio timeline
     * Starting from a small positive value for SRT compatibility
     */
    private fun generateMonotonicTimestampUs(frameCount: Long): Long {
        synchronized(timestampLock) {
            if (firstWriteTime == 0L) {
                firstWriteTime = System.nanoTime() / 1000L
                timestampBaseUs = 1000L  // Start timestamps from 1ms for SRT compatibility
                Log.i(TAG, "Initializing monotonic timestamps - starting from ${timestampBaseUs}µs")
            }
            
            // Calculate timestamp based on audio timeline (frames * duration per frame)
            // This ensures monotonic progression starting from timestampBaseUs
            val timestampUs = timestampBaseUs + (frameCount * 1_000_000L) / sampleRate.toLong()
            
            // Log first few timestamps to verify they're reasonable
            if (frameCount <= 5) {
                Log.i(TAG, "Timestamp: frameCount=$frameCount -> $timestampUs µs")
            }
            
            return timestampUs
        }
    }
    
    override fun play() {
        Log.d(TAG, "FakeAudioTrack.play() - delegating to real AudioTrack")
        realAudioTrack.play()
    }
    
    override fun stop() {
        Log.d(TAG, "FakeAudioTrack.stop() - delegating to real AudioTrack")
        realAudioTrack.stop()
    }
    
    override fun pause() {
        Log.d(TAG, "FakeAudioTrack.pause() - delegating to real AudioTrack")
        realAudioTrack.pause()
    }
    
    override fun flush() {
        Log.d(TAG, "FakeAudioTrack.flush() - delegating to real AudioTrack and clearing buffer")
        realAudioTrack.flush()
        audioBuffer.clear()
    }
    
    override fun release() {
        Log.d(TAG, "FakeAudioTrack.release() - delegating to real AudioTrack")
        realAudioTrack.release()
    }
    
    // Delegate all properties/methods to real AudioTrack for proper behavior
    override fun getState(): Int = realAudioTrack.state
    override fun getPlayState(): Int = realAudioTrack.playState
    override fun getBufferSizeInFrames(): Int = realAudioTrack.bufferSizeInFrames
    override fun getBufferCapacityInFrames(): Int = realAudioTrack.bufferCapacityInFrames
    override fun getChannelCount(): Int = realAudioTrack.channelCount
    override fun getSampleRate(): Int = realAudioTrack.sampleRate
    override fun getAudioFormat(): Int = realAudioTrack.audioFormat
    override fun getAudioSessionId(): Int = realAudioTrack.audioSessionId
    
    override fun setVolume(volume: Float): Int = realAudioTrack.setVolume(volume)
    override fun getPlaybackHeadPosition(): Int = realAudioTrack.playbackHeadPosition
    override fun getTimestamp(timestamp: AudioTimestamp?): Boolean = realAudioTrack.getTimestamp(timestamp)
    
    // Delegate other methods to real AudioTrack
    override fun setPlaybackParams(params: PlaybackParams) = realAudioTrack.setPlaybackParams(params)
    override fun getPlaybackParams(): PlaybackParams = realAudioTrack.playbackParams
    override fun attachAuxEffect(effectId: Int): Int = realAudioTrack.attachAuxEffect(effectId)
    override fun setAuxEffectSendLevel(level: Float): Int = realAudioTrack.setAuxEffectSendLevel(level)
    
    @RequiresApi(Build.VERSION_CODES.M)
    override fun setPreferredDevice(deviceInfo: AudioDeviceInfo?): Boolean = realAudioTrack.setPreferredDevice(deviceInfo)
    
    @RequiresApi(Build.VERSION_CODES.N)
    override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener?, handler: android.os.Handler?) = 
        realAudioTrack.addOnRoutingChangedListener(listener, handler)
    
    @RequiresApi(Build.VERSION_CODES.N)  
    override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener?) = 
        realAudioTrack.removeOnRoutingChangedListener(listener)
}
