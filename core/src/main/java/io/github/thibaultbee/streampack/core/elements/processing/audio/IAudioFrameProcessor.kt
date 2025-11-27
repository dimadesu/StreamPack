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

/**
 * Callback for audio level updates.
 * 
 * @param rms Root Mean Square level (0.0 to 1.0, linear scale)
 * @param peak Peak level (0.0 to 1.0, linear scale)
 */
typealias AudioLevelCallback = (rms: Float, peak: Float) -> Unit

/**
 * Public interface for audio frame processor.
 */
interface IAudioFrameProcessor {
    /**
     * Mute audio.
     */
    var isMuted: Boolean
    
    /**
     * Callback for audio level updates.
     * Called for each audio frame with RMS and peak values (0.0 to 1.0 linear scale).
     * Set to null to disable audio level monitoring.
     */
    var audioLevelCallback: AudioLevelCallback?
}