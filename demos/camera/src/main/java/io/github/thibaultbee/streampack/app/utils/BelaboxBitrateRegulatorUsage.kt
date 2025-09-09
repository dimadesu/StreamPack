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
package io.github.thibaultbee.streampack.app.utils

import android.util.Range
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.BelaboxBitrateRegulatorConfig
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.BelaboxSrtBitrateRegulatorController
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.DefaultSrtBitrateRegulatorController
import io.github.thibaultbee.streampack.app.data.models.BitrateRegulatorPreset

/**
 * Utility class demonstrating how to use the BELABOX adaptive bitrate regulator.
 * 
 * The BELABOX algorithm provides three main advantages over the default regulator:
 * 1. Smoother bitrate transitions with less viewer disruption
 * 2. Better handling of temporary network congestion spikes  
 * 3. Configurable presets for different network conditions
 */
object BelaboxBitrateRegulatorUsage {
    
    /**
     * Creates a bitrate regulator controller based on the preset and user's bitrate config.
     * This is the main entry point for the demo app.
     */
    fun createControllerForPreset(
        preset: BitrateRegulatorPreset,
        bitrateRegulatorConfig: BitrateRegulatorConfig
    ) = when (preset) {
        BitrateRegulatorPreset.CONSERVATIVE -> createConservativeControllerWithConfig(bitrateRegulatorConfig)
        BitrateRegulatorPreset.AGGRESSIVE -> createAggressiveControllerWithConfig(bitrateRegulatorConfig)
        BitrateRegulatorPreset.MOBILE -> createMobileControllerWithConfig(bitrateRegulatorConfig)
        BitrateRegulatorPreset.CUSTOM -> createCustomControllerWithConfig(bitrateRegulatorConfig)
    }
    
    /**
     * Get the current preset being used. 
     * For now, defaults to AGGRESSIVE for faster network adaptation.
     */
    fun getCurrentPreset(): BitrateRegulatorPreset {
        // TODO: This could be made configurable via SharedPreferences or DataStore
        // Using aggressive preset for better responsiveness to network changes
        return BitrateRegulatorPreset.AGGRESSIVE
    }
    
    /**
     * Creates a BELABOX controller with conservative settings.
     * Best for: Stable WiFi connections, professional streaming setups
     * Characteristics: Smooth adjustments, less aggressive reactions
     */
    fun createConservativeController(): BelaboxSrtBitrateRegulatorController.Factory {
        val bitrateConfig = BitrateRegulatorConfig(
            videoBitrateRange = Range(1_000_000, 8_000_000), // 1-8 Mbps
            audioBitrateRange = Range(128_000, 256_000)       // 128-256 Kbps
        )
        
        return BelaboxSrtBitrateRegulatorController.Factory()
            .withConservativeSettings(
                bitrateRegulatorConfig = bitrateConfig,
                delayTimeInMs = 500 // Check every 500ms
            )
    }
    
    /**
     * Creates a BELABOX controller with conservative settings using user's bitrate config.
     * Best for: Stable WiFi connections, professional streaming setups
     * Characteristics: Smooth adjustments, less aggressive reactions
     */
    fun createConservativeControllerWithConfig(
        bitrateRegulatorConfig: BitrateRegulatorConfig
    ): BelaboxSrtBitrateRegulatorController.Factory {
        return BelaboxSrtBitrateRegulatorController.Factory()
            .withConservativeSettings(
                bitrateRegulatorConfig = bitrateRegulatorConfig,
                delayTimeInMs = 500 // Check every 500ms
            )
    }
    
    /**
     * Creates a BELABOX controller with aggressive settings.
     * Best for: Variable connections, live events with changing conditions
     * Characteristics: Fast reactions, larger adjustments
     */
    fun createAggressiveController(): BelaboxSrtBitrateRegulatorController.Factory {
        val bitrateConfig = BitrateRegulatorConfig(
            videoBitrateRange = Range(500_000, 12_000_000), // 500K-12 Mbps (wider range)
            audioBitrateRange = Range(96_000, 320_000)       // 96-320 Kbps
        )
        
        return BelaboxSrtBitrateRegulatorController.Factory()
            .withAggressiveSettings(
                bitrateRegulatorConfig = bitrateConfig,
                delayTimeInMs = 300 // Check more frequently
            )
    }
    
    /**
     * Creates a BELABOX controller with aggressive settings using user's bitrate config.
     * Best for: Variable connections, live events with changing conditions
     * Characteristics: Fast reactions, larger adjustments
     */
    fun createAggressiveControllerWithConfig(
        bitrateRegulatorConfig: BitrateRegulatorConfig
    ): BelaboxSrtBitrateRegulatorController.Factory {
        return BelaboxSrtBitrateRegulatorController.Factory()
            .withAggressiveSettings(
                bitrateRegulatorConfig = bitrateRegulatorConfig,
                delayTimeInMs = 300 // Check more frequently
            )
    }
    
    /**
     * Creates a BELABOX controller with mobile-optimized settings using user's bitrate config.
     * Best for: Mobile streaming, cellular connections, battery-conscious scenarios
     * Characteristics: Conservative increases, quick decreases, lower minimums
     */
    fun createMobileControllerWithConfig(
        bitrateRegulatorConfig: BitrateRegulatorConfig
    ): BelaboxSrtBitrateRegulatorController.Factory {
        return BelaboxSrtBitrateRegulatorController.Factory()
            .withMobileSettings(
                bitrateRegulatorConfig = bitrateRegulatorConfig,
                delayTimeInMs = 400
            )
    }
    fun createMobileController(): BelaboxSrtBitrateRegulatorController.Factory {
        val bitrateConfig = BitrateRegulatorConfig(
            videoBitrateRange = Range(200_000, 6_000_000), // 200K-6 Mbps (cellular-friendly)
            audioBitrateRange = Range(64_000, 192_000)      // 64-192 Kbps (bandwidth-conscious)
        )
        
        return BelaboxSrtBitrateRegulatorController.Factory()
            .withMobileSettings(
                bitrateRegulatorConfig = bitrateConfig,
                delayTimeInMs = 400
            )
    }
    
    /**
     * Creates a fully custom BELABOX controller with specific algorithm parameters.
     * Best for: Advanced users who want fine-grained control
     */
    fun createCustomController(): BelaboxSrtBitrateRegulatorController.Factory {
        val bitrateConfig = BitrateRegulatorConfig(
            videoBitrateRange = Range(750_000, 10_000_000),
            audioBitrateRange = Range(128_000, 256_000)
        )
        
        // Custom BELABOX algorithm settings
        val belaboxConfig = BelaboxBitrateRegulatorConfig(
            bitrateIncrMin = 125_000L,        // 125 Kbps minimum increase
            bitrateIncrScale = 35L,           // Conservative increase scaling
            bitrateDecrMin = 150_000L,        // 150 Kbps minimum decrease  
            bitrateDecrScale = 12L,           // Moderate decrease scaling
            bitrateIncrInterval = 450L,       // 450ms between increases
            bitrateDecrInterval = 225L,       // 225ms between decreases
            rttDiffHighAllowedSpike = 40.0,   // 40ms RTT spike tolerance
            minimumBitrate = 400_000L,        // 400 Kbps absolute minimum
            srtLatency = 2500,                // 2.5s SRT latency
            relaxedMode = false               // Strict mode
        )
        
        return BelaboxSrtBitrateRegulatorController.Factory(
            belaboxConfig = belaboxConfig,
            bitrateRegulatorConfig = bitrateConfig,
            delayTimeInMs = 400
        )
    }
    
    /**
     * Creates a fully custom BELABOX controller with user's bitrate config.
     * Uses moderate custom settings that work well for most scenarios.
     */
    fun createCustomControllerWithConfig(
        bitrateRegulatorConfig: BitrateRegulatorConfig
    ): BelaboxSrtBitrateRegulatorController.Factory {
        // Custom BELABOX algorithm settings - moderate profile
        val belaboxConfig = BelaboxBitrateRegulatorConfig(
            bitrateIncrMin = 125_000L,        // 125 Kbps minimum increase
            bitrateIncrScale = 35L,           // Conservative increase scaling
            bitrateDecrMin = 150_000L,        // 150 Kbps minimum decrease  
            bitrateDecrScale = 12L,           // Moderate decrease scaling
            bitrateIncrInterval = 450L,       // 450ms between increases
            bitrateDecrInterval = 225L,       // 225ms between decreases
            rttDiffHighAllowedSpike = 40.0,   // 40ms RTT spike tolerance
            minimumBitrate = 400_000L,        // 400 Kbps absolute minimum
            srtLatency = 2500,                // 2.5s SRT latency
            relaxedMode = false               // Strict mode
        )
        
        return BelaboxSrtBitrateRegulatorController.Factory(
            belaboxConfig = belaboxConfig,
            bitrateRegulatorConfig = bitrateRegulatorConfig,
            delayTimeInMs = 400
        )
    }
    
    /**
     * Comparison function showing the difference between default and BELABOX controllers.
     */
    fun getControllerComparison(): Map<String, Any> {
        return mapOf(
            "default" to DefaultSrtBitrateRegulatorController.Factory(),
            "belabox_conservative" to createConservativeController(),
            "belabox_aggressive" to createAggressiveController(),
            "belabox_mobile" to createMobileController(),
            "belabox_custom" to createCustomController()
        )
    }
    
    /**
     * Usage example for integrating into PreviewViewModel
     * 
     * Replace the existing DefaultSrtBitrateRegulatorController.Factory() calls with:
     * 
     * // For stable connections:
     * BelaboxBitrateRegulatorUsage.createConservativeController()
     * 
     * // For mobile/cellular:
     * BelaboxBitrateRegulatorUsage.createMobileController()
     * 
     * // For variable network conditions:
     * BelaboxBitrateRegulatorUsage.createAggressiveController()
     */
    
    /**
     * Performance characteristics comparison:
     * 
     * DEFAULT REGULATOR:
     * - Simple packet loss + bandwidth based
     * - Binary reactions (increase/decrease fixed amounts)
     * - No memory of past network conditions
     * - Can cause viewer-visible bitrate oscillations
     * 
     * BELABOX REGULATOR:
     * - Multi-metric analysis (RTT, buffer, throughput)
     * - Graduated response levels (emergency, fast, normal)
     * - Tracks network condition trends over time
     * - Smoother adjustments with less viewer impact
     * - Configurable for different network types
     */
}
