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
package io.github.thibaultbee.streampack.ext.srt.configuration

/**
 * Configuration class for BELABOX adaptive bitrate algorithm parameters.
 * 
 * These settings control the behavior of the adaptive bitrate algorithm:
 * - How aggressively it reacts to network conditions
 * - Timing intervals for adjustments  
 * - Threshold levels for different congestion states
 */
data class BelaboxBitrateRegulatorConfig(
    /**
     * Minimum bitrate increase amount in bits per second.
     * Default: 100,000 (100 Kbps)
     */
    val bitrateIncrMin: Long = 100_000L,
    
    /**
     * Scale factor for bitrate increases. 
     * Increase amount = bitrateIncrMin + (currentBitrate / bitrateIncrScale)
     * Higher values = more conservative increases.
     * Default: 30
     */
    val bitrateIncrScale: Long = 30L,
    
    /**
     * Minimum bitrate decrease amount in bits per second.
     * Default: 100,000 (100 Kbps)  
     */
    val bitrateDecrMin: Long = 100_000L,
    
    /**
     * Scale factor for bitrate decreases.
     * Decrease amount = bitrateDecrMin + (currentBitrate / bitrateDecrScale)  
     * Lower values = more aggressive decreases.
     * Default: 10
     */
    val bitrateDecrScale: Long = 10L,
    
    /**
     * Minimum interval between bitrate increases in milliseconds.
     * Default: 400ms
     */
    val bitrateIncrInterval: Long = 400L,
    
    /**
     * Minimum interval between normal bitrate decreases in milliseconds.
     * Default: 200ms
     */
    val bitrateDecrInterval: Long = 200L,
    
    /**
     * Minimum interval between fast bitrate decreases in milliseconds.
     * Default: 250ms
     */
    val bitrateDecrFastInterval: Long = 250L,
    
    /**
     * Packets in flight threshold for congestion detection.
     * Default: 200
     */
    val packetsInFlightThreshold: Long = 200L,
    
    /**
     * Factor for RTT difference high detection (0.0 to 1.0).
     * Lower values = more sensitive to RTT spikes.
     * Default: 0.9
     */
    val rttDiffHighFactor: Double = 0.9,
    
    /**
     * Allowed RTT spike before triggering decrease in milliseconds.
     * Default: 50.0ms
     */
    val rttDiffHighAllowedSpike: Double = 50.0,
    
    /**
     * Minimum decrease amount when RTT diff is high, in bits per second.
     * Default: 250,000 (250 Kbps)
     */
    val rttDiffHighMinDecrease: Long = 250_000L,
    
    /**
     * Increase factor based on packets in flight difference.
     * Default: 100,000 (100 Kbps)
     */
    val pifDiffIncreaseFactor: Long = 100_000L,
    
    /**
     * Absolute minimum bitrate in bits per second.
     * The algorithm will never go below this value.
     * Default: 250,000 (250 Kbps)
     */
    val minimumBitrate: Long = 250_000L,
    
    /**
     * SRT latency in milliseconds for threshold calculations.
     * This should match your SRT latency configuration.
     * Default: 2000ms
     */
    val srtLatency: Int = 2000,
    
    /**
     * Enable relaxed mode for unstable connections.
     * When true, threshold calculations are more lenient.
     * Default: false
     */
    val relaxedMode: Boolean = false
) {
    companion object {
        /**
         * Conservative preset for stable connections.
         * Smoother adjustments, less aggressive reactions.
         */
        fun conservative() = BelaboxBitrateRegulatorConfig(
            bitrateIncrMin = 50_000L,
            bitrateIncrScale = 40L,
            bitrateDecrMin = 75_000L,
            bitrateDecrScale = 15L,
            bitrateIncrInterval = 600L,
            bitrateDecrInterval = 300L,
            rttDiffHighAllowedSpike = 75.0,
            minimumBitrate = 500_000L
        )
        
        /**
         * Aggressive preset for variable connections.
         * Faster reactions, larger adjustments.
         */
        fun aggressive() = BelaboxBitrateRegulatorConfig(
            bitrateIncrMin = 150_000L,
            bitrateIncrScale = 20L,
            bitrateDecrMin = 200_000L,
            bitrateDecrScale = 8L,
            bitrateIncrInterval = 300L,
            bitrateDecrInterval = 150L,
            rttDiffHighAllowedSpike = 25.0,
            minimumBitrate = 100_000L
        )
        
        /**
         * Mobile preset optimized for cellular connections.
         * Conservative increases, quick decreases, lower minimum.
         */
        fun mobile() = BelaboxBitrateRegulatorConfig(
            bitrateIncrMin = 75_000L,
            bitrateIncrScale = 50L,
            bitrateDecrMin = 150_000L,
            bitrateDecrScale = 6L,
            bitrateIncrInterval = 800L,
            bitrateDecrInterval = 100L,
            rttDiffHighAllowedSpike = 30.0,
            minimumBitrate = 200_000L,
            relaxedMode = true
        )
    }
}
