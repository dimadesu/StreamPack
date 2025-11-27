/*
 * Copyright (C) 2025 Thibault B.
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
package io.github.thibaultbee.streampack.core.elements.processing.audio

import io.github.thibaultbee.streampack.core.elements.data.RawFrame
import io.github.thibaultbee.streampack.core.elements.processing.IFrameProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio frame processor.
 *
 * Supports mute effect and audio level monitoring.
 */
class AudioFrameProcessor : IFrameProcessor<RawFrame>,
    IAudioFrameProcessor {
    override var isMuted = false
    private val muteEffect = MuteEffect()
    
    override var audioLevelCallback: AudioLevelCallback? = null

    override fun processFrame(frame: RawFrame): RawFrame {
        // Calculate audio levels if callback is set
        audioLevelCallback?.let { callback ->
            val (rms, peak) = calculateAudioLevels(frame.rawBuffer)
            callback(rms, peak)
        }
        
        if (isMuted) {
            return muteEffect.processFrame(frame)
        }
        return frame
    }
    
    /**
     * Calculate RMS and peak audio levels from 16-bit PCM audio buffer.
     * 
     * @param buffer ByteBuffer containing 16-bit PCM audio samples
     * @return Pair of (rms, peak) values normalized to 0.0-1.0 range
     */
    private fun calculateAudioLevels(buffer: ByteBuffer): Pair<Float, Float> {
        val position = buffer.position()
        val limit = buffer.limit()
        val remaining = limit - position
        
        if (remaining < 2) {
            return Pair(0f, 0f)
        }
        
        // Ensure we read as little-endian (standard for PCM)
        val originalOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        var maxSample = 0
        var sumSquares = 0.0
        var sampleCount = 0
        
        // Create a duplicate to avoid modifying original position
        val readBuffer = buffer.duplicate()
        readBuffer.position(position)
        readBuffer.order(ByteOrder.LITTLE_ENDIAN)
        
        while (readBuffer.remaining() >= 2) {
            val sample = readBuffer.short.toInt()
            val absSample = abs(sample)
            if (absSample > maxSample) {
                maxSample = absSample
            }
            sumSquares += (sample.toLong() * sample.toLong()).toDouble()
            sampleCount++
        }
        
        buffer.order(originalOrder)
        
        if (sampleCount == 0) {
            return Pair(0f, 0f)
        }
        
        // Normalize to 0.0-1.0 range (32767 is max for 16-bit signed)
        val peak = maxSample / 32767f
        val rms = (sqrt(sumSquares / sampleCount) / 32767.0).toFloat()
        
        return Pair(rms.coerceIn(0f, 1f), peak.coerceIn(0f, 1f))
    }
}