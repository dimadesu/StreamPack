package io.github.thibaultbee.streampack.ext.rtmp.elements.endpoints

import android.content.Context
import android.media.AudioFormat
import android.media.MediaFormat
import io.github.thibaultbee.krtmp.flv.config.AudioMediaType
import io.github.thibaultbee.krtmp.flv.config.FLVAudioConfig
import io.github.thibaultbee.krtmp.flv.config.FLVConfig
import io.github.thibaultbee.krtmp.flv.config.FLVVideoConfig
import io.github.thibaultbee.krtmp.flv.config.SoundRate
import io.github.thibaultbee.krtmp.flv.config.SoundSize
import io.github.thibaultbee.krtmp.flv.config.SoundType
import io.github.thibaultbee.krtmp.flv.config.VideoMediaType
import io.github.thibaultbee.krtmp.flv.sources.ByteBufferBackedRawSource
import io.github.thibaultbee.krtmp.flv.tags.FLVData
import io.github.thibaultbee.krtmp.flv.tags.script.OnMetadata
import io.github.thibaultbee.krtmp.flv.tags.video.AVCPacketType
import io.github.thibaultbee.krtmp.flv.tags.video.RawVideoTagBody
import io.github.thibaultbee.krtmp.flv.tags.video.VideoData
import io.github.thibaultbee.krtmp.flv.tags.video.VideoFrameType
import io.github.thibaultbee.krtmp.flv.tags.video.avcVideoData
import io.github.thibaultbee.krtmp.rtmp.client.RtmpClient
import io.github.thibaultbee.krtmp.rtmp.messages.command.StreamPublishType
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.elements.data.Frame
import io.github.thibaultbee.streampack.core.elements.encoders.AudioCodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.CodecConfig
import io.github.thibaultbee.streampack.core.elements.encoders.VideoCodecConfig
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpoint
import io.github.thibaultbee.streampack.core.elements.endpoints.IEndpointInternal
import io.github.thibaultbee.streampack.core.elements.endpoints.composites.CompositeEndpoint.EndpointInfo
import io.github.thibaultbee.streampack.core.elements.utils.av.video.avc.AVCDecoderConfigurationRecord
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isAudio
import io.github.thibaultbee.streampack.core.elements.utils.extensions.isVideo
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

class RtmpEndpoint : IEndpointInternal {
    private val streams = mutableListOf<FlvStream>()
    private var rtmpClient: RtmpClient? = null

    override val metrics: Any
        get() = TODO("Not yet implemented")

    private val _isOpenFlow = MutableStateFlow(false)
    override val isOpenFlow = _isOpenFlow.asStateFlow()

    override val info: IEndpoint.IEndpointInfo = EndpointInfo(FlvMuxerInfo)

    override fun getInfo(type: MediaDescriptor.Type) = EndpointInfo(FlvMuxerInfo)

    override suspend fun open(descriptor: MediaDescriptor) {
        if (rtmpClient?.isClosed != false) {
            Logger.i(TAG, "Already opened")
        }

        rtmpClient = RtmpClient(descriptor.uri.toString()).apply {
            socketContext.invokeOnCompletion {
                _isOpenFlow.tryEmit(false)
            }

            _isOpenFlow.emit(true)

            connect()
        }
    }

    private fun populateVideoData(
        frame: Frame
    ): List<FLVData> {
        require(frame.isVideo) { "Frame must be a video frame" }

        val flvDatas = mutableListOf<FLVData>()
        if (frame.mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
            val videoFrameType = if (frame.isKeyFrame) {
                VideoFrameType.KEY
            } else {
                VideoFrameType.INTER
            }
            if (frame.isKeyFrame) {
                val avcDecoderConfigurationRecord = AVCDecoderConfigurationRecord.fromParameterSets(
                    frame.extra!![0],
                    frame.extra!![1]
                )
                val avcDecoderConfigurationRecordBuffer =
                    avcDecoderConfigurationRecord.toByteBuffer()
                flvDatas.add(
                    avcVideoData(
                        VideoFrameType.KEY,
                        AVCPacketType.SEQUENCE_HEADER,
                        ByteBufferBackedRawSource(avcDecoderConfigurationRecordBuffer),
                        avcDecoderConfigurationRecordBuffer.remaining()
                    )
                )
            }
            flvDatas.add(
                avcVideoData(
                    videoFrameType,
                    AVCPacketType.NALU,
                    ByteBufferBackedRawSource(frame.buffer),
                    frame.buffer.remaining()
                )
            )
        }

        return flvDatas
    }

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    override suspend fun write(frame: Frame, streamPid: Int) {
        val rtmpClient = requireNotNull(rtmpClient) { "Not opened" }
        mutableListOf<FLVData>()
        val flvDatas = if (frame.isVideo) {
            populateVideoData(frame)
        } else if (frame.isAudio) {
            //flvDatas.add(aacAudioData(frame.format))
            throw NotImplementedError("Audio frames are not implemented yet")
        } else {
            throw IllegalArgumentException("Frame must be a video or audio frame")
        }

        flvDatas.forEach { flvData ->

            withContext(dispatcher) {
                rtmpClient.write((frame.ptsInUs / 1000).toInt(), flvData)
                if (flvData is VideoData) {
                    (flvData.body as RawVideoTagBody).data.close()
                }
            }
        }
    }

    override fun addStreams(streamConfigs: List<CodecConfig>): Map<CodecConfig, Int> {
        val streamMap = mutableMapOf<CodecConfig, Int>()
        streams.addAll(streamConfigs.map {
            FlvStream(it)
        })
        requireStreams()
        streams.forEachIndexed { index, stream -> streamMap[stream.codecConfig] = index }
        return streamMap
    }

    override fun addStream(streamConfig: CodecConfig): Int {
        streams.add(
            FlvStream(
                streamConfig
            )
        )
        requireStreams()
        return streams.size - 1
    }

    override suspend fun startStream() {
        val rtmpClient = requireNotNull(rtmpClient) { "Not opened" }
        rtmpClient.createStream()
        rtmpClient.publish(StreamPublishType.LIVE)
        val flvConfigs = streams.map { it.flvConfig }
        val audioFLVConfig =
            flvConfigs.firstOrNull { it.mediaType is AudioMediaType } as FLVAudioConfig?
        val videoFLVConfig =
            flvConfigs.firstOrNull { it.mediaType is VideoMediaType } as FLVVideoConfig?
        rtmpClient.writeSetDataFrame(
            OnMetadata.Metadata.fromConfigs(
                audioFLVConfig,
                videoFLVConfig
            )
        )
    }

    override suspend fun stopStream() {
        rtmpClient?.deleteStream()
    }

    override suspend fun close() {
        rtmpClient?.close()
    }

    /**
     * Check that there shall be no more than one audio and one video stream
     */
    private fun requireStreams() {
        val audioStreams = streams.filter { it.codecConfig.mimeType.isAudio }
        require(audioStreams.size <= 1) { "Only one audio stream is supported by FLV but got $audioStreams" }
        val videoStreams = streams.filter { it.codecConfig.mimeType.isVideo }
        require(videoStreams.size <= 1) { "Only one video stream is supported by FLV but got $videoStreams" }
    }

    /*
    private class AudioFLVStream(
        codecConfig: AudioCodecConfig
    ) : FlvStream(codecConfig) {
        private val frameWriter: AudioFrameWriter = AACFrameWriter(flvConfig)
        private val sendHeader = true

        fun addSequenceHeader(extras: List<ByteBuffer>) {

        }

        fun populateData(frame: Frame): List<AudioData> {
            return populateAudioData(frame)
        }

        private class AACFrameWriter(private val flvConfig: FLVAudioConfig) : AudioFrameWriter() {
            override fun write(buffer: ByteBuffer): AudioData {
                return aacAudioData(
                    flvConfig.soundRate,
                    flvConfig.soundSize,
                    flvConfig.soundType,
                    aacPacketType = AACPacketType.RAW,
                    ByteBufferBackedRawSource(frame.buffer),
                    frame.buffer.remaining()
                )
            }
        }

        private sealed class AudioFrameWriter {
            abstract fun write(buffer: ByteBuffer): List<AudioData>
        }
    }*/

    private data class FlvStream(val codecConfig: CodecConfig) {
        val flvConfig by lazy {
            codecConfig.toFLVConfig()
        }

        private fun CodecConfig.toFLVConfig(): FLVConfig<*> {
            return when (this) {
                is AudioCodecConfig -> toFLVConfig()
                is VideoCodecConfig -> toFLVConfig()
            }
        }

        private fun audioMediaTypeFromMimeType(mimeType: String) = when {
            mimeType == MediaFormat.MIMETYPE_AUDIO_RAW -> AudioMediaType.PCM
            mimeType == MediaFormat.MIMETYPE_AUDIO_G711_ALAW -> AudioMediaType.G711_ALAW
            mimeType == MediaFormat.MIMETYPE_AUDIO_G711_MLAW -> AudioMediaType.G711_MLAW
            AudioCodecConfig.isAacMimeType(mimeType) -> AudioMediaType.AAC
            mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS -> AudioMediaType.OPUS
            else -> throw IOException("MimeType is not supported: $mimeType")
        }

        private fun AudioCodecConfig.toFLVConfig(): FLVAudioConfig {
            return FLVAudioConfig(
                audioMediaTypeFromMimeType(mimeType),
                startBitrate,
                SoundRate.fromSampleRate(sampleRate),
                when (byteFormat) {
                    AudioFormat.ENCODING_PCM_8BIT -> SoundSize.S_8BITS
                    AudioFormat.ENCODING_PCM_16BIT -> SoundSize.S_16BITS
                    else -> throw IllegalArgumentException("Unsupported byte format: $byteFormat")
                },
                when (channelConfig) {
                    AudioFormat.CHANNEL_IN_MONO -> SoundType.MONO
                    AudioFormat.CHANNEL_IN_STEREO -> SoundType.STEREO
                    else -> throw IllegalArgumentException("Unsupported channel configuration: $channelConfig")
                }
            )
        }

        private fun videoMediaTypeFromMimeType(mimeType: String) = when (mimeType) {
            MediaFormat.MIMETYPE_VIDEO_H263 -> VideoMediaType.SORENSON_H263
            MediaFormat.MIMETYPE_VIDEO_AVC -> VideoMediaType.AVC
            MediaFormat.MIMETYPE_VIDEO_HEVC -> VideoMediaType.HEVC
            MediaFormat.MIMETYPE_VIDEO_VP8 -> VideoMediaType.VP8
            MediaFormat.MIMETYPE_VIDEO_VP9 -> VideoMediaType.VP9
            MediaFormat.MIMETYPE_VIDEO_AV1 -> VideoMediaType.AV1
            else -> throw IOException("MimeType is not supported: $mimeType")
        }

        private fun VideoCodecConfig.toFLVConfig(): FLVVideoConfig {
            return FLVVideoConfig(
                videoMediaTypeFromMimeType(mimeType),
                startBitrate,
                resolution.width,
                resolution.height,
                fps
            )
        }
    }

    companion object {
        private const val TAG = "RtmpEndpoint"
    }
}

/**
 * A factory to build a [RtmpEndpoint].
 */
class RtmpEndpointFactory : IEndpointInternal.Factory {
    override fun create(context: Context): IEndpointInternal = RtmpEndpoint()
}
