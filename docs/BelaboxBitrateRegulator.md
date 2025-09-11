# BELABOX Adaptive Bitrate Regulator for StreamPack

## Overview

The BELABOX adaptive bitrate regulator is a sophisticated algorithm designed to provide smooth, intelligent bitrate adaptation for SRT streaming. Based on the BELABOX project by rationalsa, this implementation offers significant improvements over simple packet-loss-based regulators.

## Features

### 🎯 **Smart Multi-Metric Analysis**

- **RTT (Round Trip Time) Analysis**: Tracks latency trends and jitter detection
- **Send Buffer Monitoring**: Analyzes packet queue buildup to predict congestion
- **Throughput-Based Calculations**: Prevents over-streaming after static scenes
- **Multi-Threshold System**: Emergency, fast, and normal response levels

### 📊 **Algorithm Characteristics**

- **Conservative Approach**: Prioritizes smooth viewer experience over aggressive bandwidth utilization
- **Memory-Based**: Tracks network condition trends over time for better decisions
- **Graduated Response**: Different reaction levels based on congestion severity
- **Transport-Aware**: Considers actual throughput to prevent encoder/network mismatch

### ⚙️ **Configurable Presets**

- **Conservative**: Stable WiFi connections, professional setups
- **Aggressive**: Variable connections, live events
- **Mobile**: Cellular-optimized with battery considerations
- **Custom**: Full parameter control for advanced users

## Usage Examples

### Basic Usage

```kotlin
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.BelaboxSrtBitrateRegulatorController
import io.github.thibaultbee.streampack.app.utils.BelaboxBitrateRegulatorUsage

// Conservative preset (recommended for most use cases)
val controller = BelaboxBitrateRegulatorUsage.createConservativeController()

// Mobile-optimized preset
val mobileController = BelaboxBitrateRegulatorUsage.createMobileController()

// Aggressive preset for variable connections
val aggressiveController = BelaboxBitrateRegulatorUsage.createAggressiveController()
```

### Advanced Custom Configuration

```kotlin
import io.github.thibaultbee.streampack.ext.srt.configuration.BelaboxBitrateRegulatorConfig

val customConfig = BelaboxBitrateRegulatorConfig(
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

val factory = BelaboxSrtBitrateRegulatorController.Factory(
    belaboxConfig = customConfig,
    bitrateRegulatorConfig = BitrateRegulatorConfig(/* ... */),
    delayTimeInMs = 400
)
```

### Integration in StreamPack

Replace your existing bitrate regulator controller:

```kotlin
// Old way:
DefaultSrtBitrateRegulatorController.Factory(
    bitrateRegulatorConfig = bitrateConfig
)

// New way:
BelaboxBitrateRegulatorUsage.createConservativeController()
```

## Algorithm Logic

### Threshold Levels

The algorithm uses multiple threshold levels for different response types:

1. **Emergency (th3)**: Immediate drop to minimum bitrate
   - RTT ≥ latency/3 OR buffer > threshold3
2. **Fast Decrease (th2)**: Quick reaction to congestion
   - RTT > latency/5 OR buffer > threshold2
3. **Normal Decrease (th1)**: Gradual adjustment
   - RTT > rtt_max OR buffer > threshold1
4. **Increase Zone**: Safe to increase bitrate
   - RTT < rtt_min AND rtt_delta < 0.01

### Smoothing Factors

- **RTT Average**: `rttAvg = rttAvg * 0.99 + rtt * 0.01`
- **RTT Delta**: `rttAvgDelta = rttAvgDelta * 0.8 + deltaRtt * 0.2`
- **Buffer Average**: `bufferAvg = bufferAvg * 0.99 + buffer * 0.01`
- **Throughput**: `throughput = throughput * 0.97 + newThroughput * 0.03`

## Performance Comparison

| Metric            | Default Regulator | BELABOX Regulator    |
| ----------------- | ----------------- | -------------------- |
| **Reaction Time** | Immediate binary  | Graduated response   |
| **Memory**        | Stateless         | Trend-aware          |
| **Smoothness**    | Can oscillate     | Smooth transitions   |
| **Network Types** | One-size-fits-all | Configurable presets |
| **Viewer Impact** | Can be jarring    | Minimal disruption   |
| **Complexity**    | Simple            | Sophisticated        |

## Configuration Parameters

### Core Settings

- `bitrateIncrMin`: Minimum increase amount (default: 100K bps)
- `bitrateIncrScale`: Increase scaling factor (default: 30)
- `bitrateDecrMin`: Minimum decrease amount (default: 100K bps)
- `bitrateDecrScale`: Decrease scaling factor (default: 10)

### Timing Controls

- `bitrateIncrInterval`: Time between increases (default: 400ms)
- `bitrateDecrInterval`: Time between normal decreases (default: 200ms)
- `bitrateDecrFastInterval`: Time between fast decreases (default: 250ms)

### Network Sensitivity

- `rttDiffHighFactor`: RTT spike sensitivity (default: 0.9)
- `rttDiffHighAllowedSpike`: RTT spike tolerance (default: 50ms)
- `packetsInFlightThreshold`: Buffer congestion limit (default: 200)

### Bounds

- `minimumBitrate`: Absolute minimum bitrate (default: 250K bps)
- `srtLatency`: SRT latency for calculations (default: 2000ms)
- `relaxedMode`: More lenient thresholds (default: false)

## When to Use Each Preset

### Conservative Preset

✅ **Use for:**

- Stable WiFi/Ethernet connections
- Professional streaming setups
- Viewers sensitive to quality changes
- Content where smooth experience is critical

### Aggressive Preset

✅ **Use for:**

- Variable network conditions
- Live events with changing environments
- Maximum bandwidth utilization priority
- Temporary connection issues expected

### Mobile Preset

✅ **Use for:**

- Cellular/mobile connections
- Battery-conscious scenarios
- Data usage limitations
- Intermittent connectivity

## Troubleshooting

### High Bitrate Oscillation

- Switch to conservative preset
- Increase `bitrateIncrInterval`
- Reduce `bitrateIncrScale`

### Slow Reaction to Congestion

- Switch to aggressive preset
- Reduce `bitrateDecrInterval`
- Lower `rttDiffHighAllowedSpike`

### Too Aggressive Decreases

- Enable `relaxedMode`
- Increase `rttDiffHighAllowedSpike`
- Reduce `bitrateDecrScale`

## Files Created

- `BelaboxSrtBitrateRegulator.kt`: Main algorithm implementation
- `BelaboxBitrateRegulatorConfig.kt`: Configuration data class with presets
- `BelaboxSrtBitrateRegulatorController.kt`: Controller factory
- `BelaboxBitrateRegulatorUsage.kt`: Usage examples and utilities

This implementation provides a production-ready adaptive bitrate solution that significantly improves upon basic packet-loss-based approaches.
