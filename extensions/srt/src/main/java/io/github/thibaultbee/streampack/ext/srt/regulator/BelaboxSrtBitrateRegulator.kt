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
package io.github.thibaultbee.streampack.ext.srt.regulator

import android.util.Log
import io.github.thibaultbee.srtdroid.core.models.Stats
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.BelaboxBitrateRegulatorConfig
import kotlin.math.max
import kotlin.math.min

/**
 * BELABOX adaptive bitrate algorithm implementation.
 * 
 * This algorithm is based on the BELABOX project by rationalsa.
 * It provides conservative, smooth bitrate adaptation based on:
 * - RTT (Round Trip Time) analysis with jitter detection
 * - Send buffer size monitoring  
 * - Throughput-based calculations
 * - Multiple threshold levels for different congestion states
 *
 * @param bitrateRegulatorConfig bitrate regulation configuration
 * @param belaboxConfig BELABOX-specific algorithm configuration
 * @param onVideoTargetBitrateChange call when you have to change video bitrate
 * @param onAudioTargetBitrateChange call when you have to change audio bitrate
 */
class BelaboxSrtBitrateRegulator(
    bitrateRegulatorConfig: BitrateRegulatorConfig,
    private val belaboxConfig: BelaboxBitrateRegulatorConfig = BelaboxBitrateRegulatorConfig(),
    onVideoTargetBitrateChange: ((Int) -> Unit),
    onAudioTargetBitrateChange: ((Int) -> Unit)
) : SrtBitrateRegulator(
    bitrateRegulatorConfig,
    onVideoTargetBitrateChange,
    onAudioTargetBitrateChange
) {
    
    companion object {
        private const val TAG = "BelaboxSrtBitrateRegulator"
    }
    
    // Current bitrate state
    private var currentBitrate: Long = 1_000_000L // Start at 1 Mbps
    
    // RTT tracking
    private var rttAvg: Double = 0.0
    private var rttAvgDelta: Double = 0.0
    private var prevRtt: Double = 300.0
    private var rttMin: Double = 200.0
    private var rttJitter: Double = 0.0
    
    // Send buffer tracking
    private var sendBufferSizeAvg: Double = 0.0
    private var sendBufferSizeJitter: Double = 0.0
    private var prevSendBufferSize: Double = 0.0
    
    // Throughput tracking
    private var throughput: Double = 0.0
    
    // Timing control
    private var nextBitrateIncrTime: Long = 0L
    private var nextBitrateDecrTime: Long = 0L
    
    override fun update(stats: Stats, currentVideoBitrate: Int, currentAudioBitrate: Int) {
        val currentTime = System.currentTimeMillis()
        
        // Skip if no RTT data available
        if (stats.msRTT <= 0) {
            return
        }
        
        // Initialize current bitrate if first run
        if (currentBitrate == 0L) {
            currentBitrate = currentVideoBitrate.toLong()
        }
        
        // Extract metrics from SRT stats
        val rtt = stats.msRTT.toDouble()
        val sendBufferSize = stats.pktSndBuf.toDouble()
        val mbpsSendRate = stats.mbpsSendRate
        val srtLatency = belaboxConfig.srtLatency.toDouble()
        
        // Update all tracking variables
        updateSendBufferSizeAverage(sendBufferSize)
        updateSendBufferSizeJitter(sendBufferSize)
        updateRttAverage(rtt)
        val deltaRtt = updateAverageRttDelta(rtt)
        updateRttMin(rtt)
        updateRttJitter(deltaRtt)
        updateThroughput(mbpsSendRate)
        
        // Calculate thresholds
        val sendBufferSizeTh3 = (sendBufferSizeAvg + sendBufferSizeJitter) * 4
        var sendBufferSizeTh2 = max(50.0, sendBufferSizeAvg + max(sendBufferSizeJitter * 3.0, sendBufferSizeAvg))
        sendBufferSizeTh2 = min(sendBufferSizeTh2, rttToSendBufferSize(srtLatency / 2, throughput))
        
        // Apply relaxed mode if enabled
        if (belaboxConfig.relaxedMode) {
            sendBufferSizeTh2 *= 2
        }
        
        val sendBufferSizeTh1 = max(50.0, sendBufferSizeAvg + sendBufferSizeJitter * 2.5)
        val rttThMax = rttAvg + max(rttJitter * 4, rttAvg * 15 / 100)
        val rttThMin = rttMin + max(1.0, rttJitter * 2)
        
        var newBitrate = currentBitrate
        
        // Emergency: Set to minimum bitrate
        if (currentBitrate > belaboxConfig.minimumBitrate && (rtt >= (srtLatency / 3) || sendBufferSize > sendBufferSizeTh3)) {
            newBitrate = belaboxConfig.minimumBitrate
            nextBitrateDecrTime = currentTime + belaboxConfig.bitrateDecrInterval
            logAction("Set min: ${newBitrate / 1000}k, rtt: $rtt >= latency / 3: ${srtLatency / 3} or bs: $sendBufferSize > bs_th3: $sendBufferSizeTh3")
        }
        // Fast decrease
        else if (currentTime > nextBitrateDecrTime && (rtt > (srtLatency / 5) || sendBufferSize > sendBufferSizeTh2)) {
            val decrease = belaboxConfig.bitrateDecrMin + currentBitrate / belaboxConfig.bitrateDecrScale
            newBitrate = currentBitrate - decrease
            nextBitrateDecrTime = currentTime + belaboxConfig.bitrateDecrFastInterval
            logAction("Fast decr: ${decrease / 1000}k, rtt: $rtt > latency / 5: ${srtLatency / 5} or bs: $sendBufferSize > bs_th2: $sendBufferSizeTh2")
        }
        // Normal decrease
        else if (currentTime > nextBitrateDecrTime && (rtt > rttThMax || sendBufferSize > sendBufferSizeTh1)) {
            newBitrate = currentBitrate - belaboxConfig.bitrateDecrMin
            nextBitrateDecrTime = currentTime + belaboxConfig.bitrateDecrInterval
            logAction("Decr: ${belaboxConfig.bitrateDecrMin / 1000}k, rtt: $rtt > rtt_th_max: $rttThMax or bs: $sendBufferSize > bs_th1: $sendBufferSizeTh1")
        }
        // Increase bitrate
        else if (currentTime > nextBitrateIncrTime && rtt < rttThMin && rttAvgDelta < 0.01) {
            val increase = belaboxConfig.bitrateIncrMin + currentBitrate / belaboxConfig.bitrateIncrScale
            newBitrate = currentBitrate + increase
            nextBitrateIncrTime = currentTime + belaboxConfig.bitrateIncrInterval
            // Note: Commented out logging for increases to reduce log spam (as in original)
            // logAction("Incr: ${increase / 1000}k, rtt: $rtt < rtt_th_min: $rttThMin and rtt_avg_delta: $rttAvgDelta < 0.01")
        }
        
        // Apply transport bitrate ceiling (prevent over-streaming after static scenes)
        val transportBitrate = (stats.mbpsBandwidth * 1_000_000).toLong()
        if (transportBitrate > 0) {
            val maximumBitrate = max(transportBitrate + 1_000_000L, (17 * transportBitrate) / 10)
            if (newBitrate > maximumBitrate) {
                newBitrate = maximumBitrate
            }
        }
        
        // Apply bounds
        newBitrate = max(min(newBitrate, bitrateRegulatorConfig.videoBitrateRange.upper.toLong()), 
                        max(belaboxConfig.minimumBitrate, bitrateRegulatorConfig.videoBitrateRange.lower.toLong()))
        
        // Update bitrate if changed
        if (newBitrate != currentBitrate) {
            currentBitrate = newBitrate
            onVideoTargetBitrateChange(newBitrate.toInt())
            Log.d(TAG, "Bitrate updated to: ${newBitrate / 1000}k bps")
        }
    }
    
    private fun rttToSendBufferSize(rtt: Double, throughput: Double): Double {
        return (throughput / 8) * rtt / 1316
    }
    
    private fun updateSendBufferSizeAverage(sendBufferSize: Double) {
        sendBufferSizeAvg = sendBufferSizeAvg * 0.99 + sendBufferSize * 0.01
    }
    
    private fun updateSendBufferSizeJitter(sendBufferSize: Double) {
        sendBufferSizeJitter = 0.99 * sendBufferSizeJitter
        val deltaSendBufferSize = sendBufferSize - prevSendBufferSize
        if (deltaSendBufferSize > sendBufferSizeJitter) {
            sendBufferSizeJitter = deltaSendBufferSize
        }
        prevSendBufferSize = sendBufferSize
    }
    
    private fun updateRttAverage(rtt: Double) {
        rttAvg = if (rttAvg == 0.0) {
            rtt
        } else {
            rttAvg * 0.99 + 0.01 * rtt
        }
    }
    
    private fun updateAverageRttDelta(rtt: Double): Double {
        val deltaRtt = rtt - prevRtt
        rttAvgDelta = rttAvgDelta * 0.8 + deltaRtt * 0.2
        prevRtt = rtt
        return deltaRtt
    }
    
    private fun updateRttMin(rtt: Double) {
        rttMin *= 1.001
        if (rtt != 100.0 && rtt < rttMin && rttAvgDelta < 1.0) {
            rttMin = rtt
        }
    }
    
    private fun updateRttJitter(deltaRtt: Double) {
        rttJitter *= 0.99
        if (deltaRtt > rttJitter) {
            rttJitter = deltaRtt
        }
    }
    
    private fun updateThroughput(mbpsSendRate: Double) {
        throughput *= 0.97
        throughput += (mbpsSendRate * 1000.0 * 1000.0 / 1024.0) * 0.03
    }
    
    private fun logAction(action: String) {
        Log.i(TAG, "adaptive-bitrate: $action")
    }
    
    /**
     * Factory that creates a [BelaboxSrtBitrateRegulator].
     */
    class Factory(
        private val belaboxConfig: BelaboxBitrateRegulatorConfig = BelaboxBitrateRegulatorConfig()
    ) : SrtBitrateRegulator.Factory {
        
        /**
         * Creates a [BelaboxSrtBitrateRegulator] object from given parameters
         *
         * @param bitrateRegulatorConfig bitrate regulation configuration
         * @param onVideoTargetBitrateChange call when you have to change video bitrate
         * @param onAudioTargetBitrateChange call when you have to change audio bitrate
         * @return a [BelaboxSrtBitrateRegulator] object
         */
        override fun newBitrateRegulator(
            bitrateRegulatorConfig: BitrateRegulatorConfig,
            onVideoTargetBitrateChange: ((Int) -> Unit),
            onAudioTargetBitrateChange: ((Int) -> Unit)
        ): BelaboxSrtBitrateRegulator {
            return BelaboxSrtBitrateRegulator(
                bitrateRegulatorConfig,
                belaboxConfig,
                onVideoTargetBitrateChange,
                onAudioTargetBitrateChange
            )
        }
    }
}
