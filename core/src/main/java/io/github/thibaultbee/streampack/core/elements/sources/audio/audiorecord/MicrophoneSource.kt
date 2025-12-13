/*
 * Copyright (C) 2024 Thibault B.
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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import java.util.UUID

/**
 * The [MicrophoneSource] class is an implementation of [AudioRecordSource] that captures audio
 * from the microphone.
 *
 * @param isUnprocessed If true, uses [MediaRecorder.AudioSource.UNPROCESSED] for raw audio capture
 *                      without any system DSP processing. This is ideal for USB audio devices with
 *                      their own preamps. If false (default), uses [MediaRecorder.AudioSource.DEFAULT]
 *                      which benefits from system audio processing.
 */
internal class MicrophoneSource(val isUnprocessed: Boolean = false) : AudioRecordSource() {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun buildAudioRecord(config: AudioSourceConfig, bufferSize: Int): AudioRecord {
        val audioSource = if (isUnprocessed) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.DEFAULT
        }
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioFormat = AudioFormat.Builder()
                .setEncoding(config.byteFormat)
                .setSampleRate(config.sampleRate)
                .setChannelMask(config.channelConfig)
                .build()

            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioSource(audioSource)
                .build()
        } else {
            AudioRecord(
                audioSource,
                config.sampleRate,
                config.channelConfig,
                config.byteFormat,
                bufferSize
            )
        }
    }
}

/**
 * A factory to create a [MicrophoneSource].
 *
 * @param unprocessed If true, uses UNPROCESSED audio source for raw capture (ideal for USB audio).
 *                    When true, effects default to empty. When false (default), uses DEFAULT audio
 *                    source with AEC and NS effects.
 * @param effects a set of audio effects to apply to the audio source. Defaults to AEC+NS when
 *                unprocessed=false, or empty when unprocessed=true.
 */
class MicrophoneSourceFactory(
    private val unprocessed: Boolean = false,
    effects: Set<UUID> = if (unprocessed) emptySet() else defaultAudioEffects
) :
    AudioRecordSourceFactory(effects) {
    override suspend fun createImpl(context: Context) = MicrophoneSource(unprocessed)

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        if (source !is MicrophoneSource) return false
        return source.isUnprocessed == unprocessed
    }

    override fun toString(): String {
        return "MicrophoneSourceFactory(unprocessed=$unprocessed, effects=$effects)"
    }
}