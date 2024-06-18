/*
 * Copyright (C) 2022 Thibault B.
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
package io.github.thibaultbee.streampack.ext.rtmp.internal.endpoints.composites.sinks

import io.github.thibaultbee.krtmp.rtmp.client.publish.RtmpPublishClient
import io.github.thibaultbee.krtmp.rtmp.messages.Command
import io.github.thibaultbee.streampack.core.data.VideoConfig
import io.github.thibaultbee.streampack.core.data.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.internal.data.Packet
import io.github.thibaultbee.streampack.core.internal.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.internal.endpoints.composites.sinks.EndpointConfiguration
import io.github.thibaultbee.streampack.core.internal.endpoints.composites.sinks.ISink
import io.github.thibaultbee.streampack.core.internal.utils.extensions.toByteArray
import io.github.thibaultbee.streampack.core.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class RtmpSink(
    private val dispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor()
        .asCoroutineDispatcher()
) : ISink {
    private val factory = RtmpPublishClient.Factory()
    private var client: RtmpPublishClient? = null

    private var isOnError = false

    private val _isOpened = MutableStateFlow(false)
    override val isOpened: StateFlow<Boolean> = _isOpened

    override val metrics: Any
        get() = TODO("Not yet implemented")

    override fun configure(config: EndpointConfiguration) {
        val videoConfig = config.streamConfigs.firstOrNull { it is VideoConfig }
        if (videoConfig != null) {
            //  client.supportedVideoCodecs = listOf(videoConfig.mimeType)
        }
    }

    override suspend fun open(mediaDescriptor: MediaDescriptor) {
        require(!isOpened.value) { "SrtEndpoint is already opened" }
        require(mediaDescriptor.type.sinkType == MediaSinkType.RTMP) { "MediaDescriptor must be a rtmp Uri" }

        withContext(dispatcher) {
            try {
                isOnError = false
                client = factory.create("${mediaDescriptor.uri}").apply {
                    connect()
                }
                _isOpened.emit(true)
            } catch (e: Exception) {
                client = null
                _isOpened.emit(false)
                throw e
            }
        }
    }


    override suspend fun write(packet: Packet) = withContext(dispatcher) {
        val client = client ?: throw IllegalStateException("Rtmp client is not initialized")
        if (isOnError) {
            return@withContext
        }

        if (!(isOpened.value)) {
            Logger.w(TAG, "Socket is not connected, dropping packet")
            return@withContext
        }

        try {
            /* if (packet.isAudio || packet.isVideo) {
                 client.writeFrame(packet.buffer.toByteArray())
             }*/
            client.writeFrame(packet.buffer.toByteArray())
            /* if (packet.isAudio) {
                 client.writeAudio(packet.ts.toInt(), packet.buffer.toByteArray())
             } else if (packet.isVideo) {
                 client.writeVideo(packet.ts.toInt(), packet.buffer.toByteArray())
             } else {
                client.writeSetDataFrame(packet.buffer.array())
             }*/
        } catch (e: Exception) {
            close()
            isOnError = true
            _isOpened.emit(false)
            Logger.e(TAG, "Error while writing packet to socket", e)
            throw e
        }
    }

    override suspend fun startStream() {
        val client = client!!
        withContext(dispatcher) {
            client.createStream()
            client.publish(Command.Publish.Type.LIVE)
        }
    }

    override suspend fun stopStream() {
        withContext(dispatcher) {
            try {
                client?.deleteStream()
            } catch (e: Exception) {
                Logger.e(TAG, "Error while stopping stream", e)
            }
        }
    }

    override suspend fun close() {
        if (client == null) {
            return
        }
        withContext(dispatcher) {
            client?.close()
            _isOpened.emit(false)
            client = null
        }
    }

    companion object {
        private const val TAG = "RtmpSink"
    }
}
