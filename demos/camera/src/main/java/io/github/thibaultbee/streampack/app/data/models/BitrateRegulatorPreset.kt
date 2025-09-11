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
package io.github.thibaultbee.streampack.app.data.models

/**
 * Enum for BELABOX bitrate regulator presets.
 * Each preset is optimized for different network conditions and use cases.
 */
enum class BitrateRegulatorPreset(
    val displayName: String,
    val description: String
) {
    CONSERVATIVE(
        "Conservative", 
        "Stable WiFi connections, smooth adjustments"
    ),
    AGGRESSIVE(
        "Aggressive", 
        "Variable connections, fast reactions"
    ),
    MOBILE(
        "Mobile", 
        "Cellular optimized, battery conscious"
    ),
    CUSTOM(
        "Custom", 
        "Advanced users with specific requirements"
    );
    
    companion object {
        fun getDefault() = CONSERVATIVE
        
        fun fromString(value: String): BitrateRegulatorPreset {
            return values().find { it.name == value } ?: getDefault()
        }
    }
}
