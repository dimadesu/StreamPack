/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord

import android.Manifest
import android.content.Context
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.AudioRecordEffect.Companion.isValidUUID
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.AudioRecordEffect.Factory.Companion.getFactoryForEffectType
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.AudioRecordSource.Companion.isEffectAvailable
import io.github.thibaultbee.streampack.core.elements.utils.TimeUtils
import io.github.thibaultbee.streampack.core.elements.utils.extensions.type
import io.github.thibaultbee.streampack.core.elements.utils.pool.IReadOnlyRawFrameFactory
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The [AudioRecordSource] class is an implementation of [IAudioSourceInternal] that captures audio
 * from [AudioRecord].
 */
public sealed class AudioRecordSource : IAudioSourceInternal, IAudioRecordSource {
    private var audioRecord: AudioRecord? = null
    private var bufferSize: Int? = null
    private var currentConfig: AudioSourceConfig? = null

    private var processor: EffectProcessor? = null
    private var pendingAudioEffects = mutableListOf<UUID>()

    private val isRunning: Boolean
        get() = audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    protected val _isStreamingFlow = MutableStateFlow(false)
    override val isStreamingFlow = _isStreamingFlow.asStateFlow()

    private val audioTimestamp = AudioTimestamp()

    abstract fun buildAudioRecord(
        config: AudioSourceConfig,
        bufferSize: Int
    ): AudioRecord

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun configure(config: AudioSourceConfig) {
        /**
         * [configure] might be called multiple times.
         * If audio source is already running, we need to prevent reconfiguration.
         */
        audioRecord?.let {
            if (it.state == AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Audio source is already running")
            } else {
                release()
            }
        }

        currentConfig = config
        bufferSize = getMinBufferSize(config)

        audioRecord = buildAudioRecord(config, bufferSize!!).also {
            val previousEffects = processor?.getAll() ?: emptyList()
            processor?.clear()

            // Add effects
            processor = EffectProcessor(it.audioSessionId).apply {
                (previousEffects + pendingAudioEffects).forEach { effectType ->
                    try {
                        add(effectType)
                    } catch (t: Throwable) {
                        Logger.e(TAG, "Failed to add effect: $effectType: ${t.message}")
                    }
                }
                pendingAudioEffects.clear()
            }

            if (it.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalArgumentException("Failed to initialized audio source with config: $config")
            }
        }
    }

    override suspend fun startStream() {
        if (isRunning) {
            Logger.d(TAG, "Already running")
            return
        }
        val audioRecord = requireNotNull(audioRecord)

        processor?.setEnabled(true)

        audioRecord.startRecording()
        _isStreamingFlow.tryEmit(true)
    }

    override suspend fun stopStream() {
        // Log stack trace to see what's calling stopStream
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.getOrNull(3)?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        Logger.w(TAG, "Audio recording stopStream() called from: $caller")
        
        _isStreamingFlow.tryEmit(false)
        audioRecord?.stop()
        Logger.i(TAG, "Audio recording stopped")
    }

    override fun release() {
        // Log stack trace to see what's calling release
        val stackTrace = Thread.currentThread().stackTrace
        val caller = stackTrace.getOrNull(3)?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "unknown"
        Logger.w(TAG, "Audio source release() called from: $caller")
        
        _isStreamingFlow.tryEmit(false)
        processor?.clear()
        processor = null

        // Release audio record
        audioRecord?.release()
        audioRecord = null
        
        Logger.i(TAG, "Audio source released")
    }

    private fun getTimestampInUs(audioRecord: AudioRecord): Long {
        // Get timestamp from AudioRecord
        // If we can not get timestamp through getTimestamp, we timestamp audio sample.
        var timestamp: Long = -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (audioRecord.getTimestamp(
                    audioTimestamp,
                    AudioTimestamp.TIMEBASE_MONOTONIC
                ) == AudioRecord.SUCCESS
            ) {
                timestamp = audioTimestamp.nanoTime / 1000 // to us
            }
        }

        // Fallback
        if (timestamp < 0) {
            timestamp = TimeUtils.currentTime()
        }

        return timestamp
    }


    private var lastRecordingFailureTime = 0L
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 3
    private val failureRetryDelayMs = 2000L
    private var silentModeActive = false
    
    // Simple, reliable audio level detection  
    private var consecutiveSilentFrames = 0
    private val maxSilentFramesBeforeRestart = 300 // Much longer - ~6 seconds at 50fps
    private val silenceThreshold = 100 // Higher threshold to reduce false positives
    private var lastNonSilentTime = System.currentTimeMillis()
    private var lastRestartTime = 0L
    private val restartCooldownMs = 10000L // 10 second cooldown to reduce restart frequency
    private var lastValidAudioData: ByteArray? = null
    private var consecutiveRestarts = 0
    private val maxConsecutiveRestarts = 5 // Limit restart attempts
    
    // Dual recording strategy
    private var backupAudioRecord: AudioRecord? = null
    private var usingBackup = false

    override fun fillAudioFrame(frame: RawFrame): RawFrame {
        val currentAudioRecord = requireNotNull(audioRecord) { "Audio source is not initialized" }
        
        // Check if recording state is valid
        if (currentAudioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            val currentTime = System.currentTimeMillis()
            
            // Implement backoff strategy to avoid excessive restart attempts
            if (currentTime - lastRecordingFailureTime < failureRetryDelayMs) {
                if (silentModeActive) {
                    // Generate silent frame to keep stream alive
                    return generateSilentFrame(frame)
                }
                frame.close()
                throw IllegalStateException("Audio source is not recording (too many recent failures)")
            }
            
            lastRecordingFailureTime = currentTime
            consecutiveFailures++
            
            Logger.w(TAG, "Audio recording stopped (RecordingState: ${currentAudioRecord.recordingState}, State: ${currentAudioRecord.state}, failure #$consecutiveFailures), attempting to restart for background streaming")
            
            if (consecutiveFailures > maxConsecutiveFailures) {
                Logger.w(TAG, "Too many consecutive audio failures ($consecutiveFailures), switching to silent mode for background streaming")
                silentModeActive = true
                return generateSilentFrame(frame)
            }
            
            try {
                // Try to restart recording
                currentAudioRecord.startRecording()
                val newRecordingState = currentAudioRecord.recordingState
                val newState = currentAudioRecord.state
                Logger.i(TAG, "AudioRecord restart attempt - RecordingState: $newRecordingState, State: $newState")
                
                if (newRecordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        Logger.w(TAG, "Failed to restart audio recording (RecordingState: $newRecordingState, State: $newState), switching to silent mode")
                        silentModeActive = true
                        return generateSilentFrame(frame)
                    }
                    frame.close()
                    Logger.e(TAG, "Failed to restart audio recording (attempt $consecutiveFailures)")
                    throw IllegalStateException("Audio source is not recording and failed to restart")
                }
                Logger.i(TAG, "Successfully restarted audio recording for background streaming (attempt $consecutiveFailures)")
                // Reset failure count on successful restart
                consecutiveFailures = 0
                silentModeActive = false
            } catch (e: Exception) {
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    Logger.w(TAG, "Failed to restart audio recording, switching to silent mode", e)
                    silentModeActive = true
                    return generateSilentFrame(frame)
                }
                frame.close()
                Logger.e(TAG, "Failed to restart audio recording (attempt $consecutiveFailures)", e)
                throw IllegalStateException("Audio source is not recording")
            }
        } else {
            // Reset failure count when recording is working normally
            if (consecutiveFailures > 0) {
                Logger.i(TAG, "Audio recording restored, resetting failure count and disabling silent mode")
                consecutiveFailures = 0
                silentModeActive = false
            }
        }

        try {
            val buffer = frame.rawBuffer
            val length = currentAudioRecord.read(buffer, buffer.remaining())
            if (length > 0) {
                frame.timestampInUs = getTimestampInUs(currentAudioRecord)
                
                // Store valid audio data for potential buffering during restart
                val bufferArray = ByteArray(buffer.remaining())
                buffer.duplicate().get(bufferArray)
                
                // Check audio level to detect background muting
                val audioLevel = calculateAudioLevel(buffer)
                if (audioLevel > silenceThreshold) {
                    consecutiveSilentFrames = 0
                    lastNonSilentTime = System.currentTimeMillis()
                    consecutiveRestarts = 0 // Reset restart counter when audio is detected
                    
                    // Store this as valid audio data for potential crossfading
                    lastValidAudioData = bufferArray.clone()
                } else {
                    consecutiveSilentFrames++
                    
                    // If we've had silence for too long, try to restart AudioRecord (but not too often)
                    if (consecutiveSilentFrames >= maxSilentFramesBeforeRestart && 
                        System.currentTimeMillis() - lastNonSilentTime > 5000 && // 5 seconds of silence
                        System.currentTimeMillis() - lastRestartTime > restartCooldownMs && // Respect cooldown
                        consecutiveRestarts < maxConsecutiveRestarts) { // Limit restart attempts
                        
                        Logger.w(TAG, "Detected prolonged silence ($consecutiveSilentFrames frames), attempting AudioRecord restart (attempt ${consecutiveRestarts + 1}/$maxConsecutiveRestarts)")
                        
                        lastRestartTime = System.currentTimeMillis()
                        consecutiveRestarts++
                        
                        try {
                            // Simple restart sequence
                            this@AudioRecordSource.audioRecord?.stop()
                            Thread.sleep(200) // Longer pause to allow system reset
                            this@AudioRecordSource.audioRecord?.startRecording()
                            
                            if (this@AudioRecordSource.audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                Logger.i(TAG, "Successfully restarted AudioRecord after silence detection")
                                consecutiveSilentFrames = 0
                                lastNonSilentTime = System.currentTimeMillis()
                            } else {
                                Logger.w(TAG, "AudioRecord restart failed, state: ${this@AudioRecordSource.audioRecord?.recordingState}")
                            }
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to restart AudioRecord after silence detection", e)
                            // Check if this might be an AppOps permission issue
                            if (e.message?.contains("permission", ignoreCase = true) == true ||
                                e.message?.contains("appops", ignoreCase = true) == true) {
                                Logger.e(TAG, "Audio restart failed due to potential AppOps/Permission issue. Check if RECORD_AUDIO is allowed at system level.")
                                Logger.e(TAG, "To resolve: Go to Settings > Apps > [App Name] > Permissions > Microphone > Allow")
                                Logger.e(TAG, "Or disable battery optimization: Settings > Battery > Battery Optimization > [App Name] > Don't optimize")
                            }
                        }
                    } else if (consecutiveRestarts >= maxConsecutiveRestarts) {
                        // If we've hit the restart limit, enable background mode with reduced restart attempts
                        if (System.currentTimeMillis() - lastRestartTime > 30000L) { // Try again after 30 seconds
                            Logger.w(TAG, "Background audio restriction detected. Audio has been silent for ${System.currentTimeMillis() - lastNonSilentTime}ms. This is likely due to Android's background microphone restrictions.")
                            Logger.i(TAG, "Background mode: Attempting restart after 30 second cooldown (restarts: $consecutiveRestarts)")
                            Logger.i(TAG, "To improve background audio performance, disable battery optimization for this app in Settings > Battery > Battery Optimization")
                            consecutiveRestarts = 0 // Reset for one more attempt
                        } else if (consecutiveSilentFrames % 150 == 0) { // Log every ~3 seconds when silent
                            Logger.d(TAG, "Background silence continues: ${consecutiveSilentFrames} frames (${(System.currentTimeMillis() - lastNonSilentTime) / 1000}s). Next restart attempt in ${30 - (System.currentTimeMillis() - lastRestartTime) / 1000}s")
                        }
                    }
                }
                
                return frame
            } else {
                frame.close()
                throw IllegalArgumentException(audioRecordErrorToString(length))
            }
        } catch (e: Exception) {
            if (silentModeActive || consecutiveFailures > 0) {
                Logger.w(TAG, "AudioRecord read failed, generating silent frame", e)
                return generateSilentFrame(frame)
            }
            throw e
        }
    }
    
    private fun generateSilentFrame(frame: RawFrame): RawFrame {
        // Fill buffer with zeros (silence)
        val buffer = frame.rawBuffer
        while (buffer.hasRemaining()) {
            buffer.put(0)
        }
        buffer.flip()
        
        // Use system timestamp for silent frames
        frame.timestampInUs = System.nanoTime() / 1000
        return frame
    }
    
    /**
     * Calculate audio level from buffer to detect silence/muting
     */
    private fun calculateAudioLevel(buffer: java.nio.ByteBuffer): Int {
        val position = buffer.position()
        var sum = 0L
        var sampleCount = 0
        
        // Assume 16-bit PCM samples
        while (buffer.remaining() >= 2) {
            val sample = buffer.short.toInt()
            sum += kotlin.math.abs(sample)
            sampleCount++
        }
        
        // Reset buffer position
        buffer.position(position)
        
        return if (sampleCount > 0) (sum / sampleCount).toInt() else 0
    }

    override fun getAudioFrame(frameFactory: IReadOnlyRawFrameFactory): RawFrame {
        val bufferSize = requireNotNull(bufferSize) { "Buffer size is not initialized" }

        /**
         * Dummy timestamp: it is overwritten later.
         */
        return fillAudioFrame(frameFactory.create(bufferSize, 0))
    }

    /**
     * Adds and enables an effect to the audio source.
     *
     * Get supported effects with [availableEffect].
     */
    override fun addEffect(effectType: UUID): Boolean {
        require(isValidUUID(effectType)) { "Unsupported effect type: $effectType" }
        require(isEffectAvailable(effectType)) { "Effect $effectType is not available" }

        val processor = processor
        return if (processor == null) {
            pendingAudioEffects.add(effectType)
            false
        } else {
            try {
                processor.add(effectType)
            } catch (t: Throwable) {
                Logger.e(TAG, "Failed to add effect: $effectType: ${t.message}")
                false
            }
        }
    }

    /**
     * Removes an effect from the audio source.
     */
    override fun removeEffect(effectType: UUID) {
        val processor = processor
        if (processor == null) {
            pendingAudioEffects.remove(effectType)
            return
        } else {
            processor.remove(effectType)
        }
    }

    companion object {
        private const val TAG = "AudioRecordSource"

        /**
         * Gets minimum buffer size for audio capture.
         */
        private fun getMinBufferSize(config: AudioSourceConfig): Int {
            val bufferSize = AudioRecord.getMinBufferSize(
                config.sampleRate,
                config.channelConfig,
                config.byteFormat
            )
            if (bufferSize <= 0) {
                throw IllegalArgumentException(audioRecordErrorToString(bufferSize))
            }
            return bufferSize
        }

        /**
         * Converts audio record error to string.
         */
        private fun audioRecordErrorToString(audioRecordError: Int) = when (audioRecordError) {
            AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord returns an invalid operation error"
            AudioRecord.ERROR_BAD_VALUE -> "AudioRecord returns a bad value error"
            AudioRecord.ERROR_DEAD_OBJECT -> "AudioRecord returns a dead object error"
            else -> "Unknown audio record error: $audioRecordError"
        }


        /**
         * Get available effects.
         *
         * @return [List] of supported effects.
         * @see AudioEffect
         */
        val availableEffect: List<UUID>
            get() = AudioRecordEffect.availableEffects

        /**
         * Whether the effect is available.
         *
         * @param effectType Effect type
         * @return true if effect is available
         */
        fun isEffectAvailable(effectType: UUID): Boolean {
            return AudioRecordEffect.isEffectAvailable(effectType)
        }
    }


    private class EffectProcessor(private val audioSessionId: Int) {
        private val audioEffects: MutableSet<AudioEffect> = mutableSetOf()

        init {
            require(audioSessionId >= 0) { "Invalid audio session ID: $audioSessionId" }
        }

        fun getAll(): List<UUID> {
            return audioEffects.map { it.type }
        }

        fun add(effectType: UUID): Boolean {
            require(isValidUUID(effectType)) { "Unsupported effect type: $effectType" }

            val previousEffect = audioEffects.firstOrNull { it.type == effectType }
            if (previousEffect != null) {
                Logger.w(TAG, "Effect ${previousEffect.descriptor.name} already enabled")
                return false
            }

            val factory = getFactoryForEffectType(effectType)
            factory.build(audioSessionId).let {
                audioEffects.add(it)
                return true
            }
        }

        fun setEnabled(enabled: Boolean) {
            audioEffects.forEach { it.enabled = enabled }
        }

        fun remove(effectType: UUID) {
            require(isValidUUID(effectType)) { "Unknown effect type: $effectType" }

            val effect = audioEffects.firstOrNull { it.descriptor.type == effectType }
            if (effect != null) {
                effect.release()
                audioEffects.remove(effect)
            }
        }

        fun release() {
            audioEffects.forEach { it.release() }
        }

        fun clear() {
            release()
            audioEffects.clear()
        }

        companion object {
            private const val TAG = "EffectProcessor"
        }
    }
}


abstract class AudioRecordSourceFactory(
    private val effects: Set<UUID>
) : IAudioSourceInternal.Factory {
    /**
     * Create an [AudioRecordSource] implementation.
     */
    internal abstract suspend fun createImpl(context: Context): AudioRecordSource

    override suspend fun create(context: Context): IAudioSourceInternal {
        return createImpl(context).apply {
            effects.forEach { effect ->
                if (isEffectAvailable(effect)) {
                    addEffect(effect)
                }
            }
        }
    }

    companion object {
        internal val defaultAudioEffects = setOf(
            AudioEffect.EFFECT_TYPE_AEC,
            AudioEffect.EFFECT_TYPE_NS
        )
    }
}