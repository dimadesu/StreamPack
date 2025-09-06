package io.github.thibaultbee.streampack.core.elements.encoders

/**
 * A minimal runtime provider for audio format reported by the decoder/sink.
 * Demo code can set these values when the sink reports its actual output format.
 * The encoder will prefer these runtime values when building MediaFormat to avoid
 * mismatches between decoded PCM and encoder configuration.
 */
object RuntimeAudioFormat {
    @Volatile
    var sampleRate: Int? = null

    @Volatile
    var channelCount: Int? = null

    @Volatile
    var bytesPerSample: Int? = null

    fun set(sampleRate: Int, channelCount: Int, bytesPerSample: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.bytesPerSample = bytesPerSample
    }

    fun clear() {
        sampleRate = null
        channelCount = null
        bytesPerSample = null
    }
}
