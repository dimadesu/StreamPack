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
package io.github.thibaultbee.streampack.ext.srt.regulator.controllers

import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableAudioEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.pipelines.outputs.encoding.IConfigurableVideoEncodingPipelineOutput
import io.github.thibaultbee.streampack.core.regulator.controllers.BitrateRegulatorController
import io.github.thibaultbee.streampack.core.regulator.controllers.DummyBitrateRegulatorController
import io.github.thibaultbee.streampack.ext.srt.configuration.BelaboxBitrateRegulatorConfig
import io.github.thibaultbee.streampack.ext.srt.regulator.BelaboxSrtBitrateRegulator
import io.github.thibaultbee.streampack.ext.srt.regulator.SrtBitrateRegulator

/**
 * A [DummyBitrateRegulatorController] implementation for BELABOX adaptive bitrate algorithm.
 * 
 * This controller uses the BELABOX algorithm which provides conservative, smooth bitrate
 * adaptation based on RTT analysis, send buffer monitoring, and throughput calculations.
 */
class BelaboxSrtBitrateRegulatorController {
    class Factory(
        private val belaboxConfig: BelaboxBitrateRegulatorConfig = BelaboxBitrateRegulatorConfig(),
        private val bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
        private val delayTimeInMs: Long = 500
    ) : BitrateRegulatorController.Factory() {
        
        /**
         * Creates a new BELABOX bitrate regulator controller with conservative settings.
         * Good for stable connections where smooth bitrate changes are preferred.
         */
        fun withConservativeSettings(
            bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
            delayTimeInMs: Long = 500
        ) = Factory(
            BelaboxBitrateRegulatorConfig.conservative(),
            bitrateRegulatorConfig,
            delayTimeInMs
        )
        
        /**
         * Creates a new BELABOX bitrate regulator controller with aggressive settings.
         * Good for variable connections where fast adaptation is needed.
         */
        fun withAggressiveSettings(
            bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
            delayTimeInMs: Long = 500
        ) = Factory(
            BelaboxBitrateRegulatorConfig.aggressive(),
            bitrateRegulatorConfig,
            delayTimeInMs
        )
        
        /**
         * Creates a new BELABOX bitrate regulator controller with mobile settings.
         * Optimized for cellular connections with conservative increases and quick decreases.
         */
        fun withMobileSettings(
            bitrateRegulatorConfig: BitrateRegulatorConfig = BitrateRegulatorConfig(),
            delayTimeInMs: Long = 500
        ) = Factory(
            BelaboxBitrateRegulatorConfig.mobile(),
            bitrateRegulatorConfig,
            delayTimeInMs
        )
        
        override fun newBitrateRegulatorController(pipelineOutput: IEncodingPipelineOutput): DummyBitrateRegulatorController {
            require(pipelineOutput is IConfigurableVideoEncodingPipelineOutput) {
                "Pipeline output must be an video encoding output"
            }

            val videoEncoder = requireNotNull(pipelineOutput.videoEncoder) {
                "Video encoder must be set"
            }

            val audioEncoder = if (pipelineOutput is IConfigurableAudioEncodingPipelineOutput) {
                pipelineOutput.audioEncoder
            } else {
                null
            }
            
            val bitrateRegulatorFactory: SrtBitrateRegulator.Factory = BelaboxSrtBitrateRegulator.Factory(belaboxConfig)
            
            return DummyBitrateRegulatorController(
                audioEncoder,
                videoEncoder,
                pipelineOutput.endpoint,
                bitrateRegulatorFactory,
                bitrateRegulatorConfig,
                delayTimeInMs
            )
        }
    }
}
