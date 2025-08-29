package io.github.thibaultbee.streampack.app.sources.audio

import android.content.Context
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory

/**
 * A simplified custom audio input implementation for StreamPack that uses the device's microphone to capture audio.
 */
class CustomAudioInput : MicrophoneSource() {
    companion object {
        private const val TAG = "CustomAudioInput"
    }

    class Factory : MicrophoneSourceFactory() {
        override suspend fun createImpl(context: Context): MicrophoneSource {
            return CustomAudioInput()
        }

        override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
            return source is CustomAudioInput
        }
    }
}
