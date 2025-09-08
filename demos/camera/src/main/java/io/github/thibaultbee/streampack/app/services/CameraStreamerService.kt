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
package io.github.thibaultbee.streampack.app.services

import android.app.Notification
import android.content.Context
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import io.github.thibaultbee.streampack.app.R
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.services.StreamerService
import io.github.thibaultbee.streampack.services.utils.SingleStreamerFactory
import io.github.thibaultbee.streampack.services.utils.StreamerFactory
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import io.github.thibaultbee.streampack.services.utils.NotificationUtils
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * CameraStreamerService extending StreamerService for camera streaming
 */
class CameraStreamerService : StreamerService<ISingleStreamer>(
    streamerFactory = SingleStreamerFactory(
        withAudio = true, 
        withVideo = true, 
        defaultRotation = 0  // Provide default rotation since service context has no display
    ),
    notificationId = 1001,
    channelId = "camera_streaming_channel", 
    channelNameResourceId = R.string.streaming_channel_name,
    channelDescriptionResourceId = R.string.streaming_channel_description,
    notificationIconResourceId = R.drawable.ic_baseline_linked_camera_24
) {
    companion object {
        const val TAG = "CameraStreamerService"
    }

    private val _serviceReady = MutableStateFlow(false)
    
    // Create our own NotificationUtils instance for custom notifications
    private val customNotificationUtils: NotificationUtils by lazy {
        NotificationUtils(this, "camera_streaming_channel", 1001)
    }

    /**
     * Override onCreate to use both camera and mediaProjection service types
     */
    override fun onCreate() {
        // Let the base class handle most of the setup first
        super.onCreate()
        
        // The base class already calls startForeground with MEDIA_PROJECTION type,
        // but we need to update it with CAMERA type for Android 14+ to enable background camera access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ supports both camera and media projection service types
            ServiceCompat.startForeground(
                this,
                1001, // Use the same notification ID as specified in constructor
                onCreateNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        }
        // For Android 13 and below, the base class MEDIA_PROJECTION type should work
        // Camera access in background may be more limited but should still work with proper manifest declaration
        
        Log.i(TAG, "CameraStreamerService created and configured for background camera access")
    }

    override fun onStreamingStop() {
        // Override the base class behavior to NOT stop the service when streaming stops
        // This allows the service to remain running for quick restart of streaming
        Log.i(TAG, "Streaming stopped but service remains active for background operation")
        
        // Update notification to show stopped state
        onCloseNotification()?.let { notification ->
            customNotificationUtils.notify(notification)
        }
        // Intentionally NOT calling stopSelf() here - let the service stay alive
    }

    /**
     * Required implementation of abstract method
     */
    override suspend fun onExtra(extras: Bundle) {
        // Handle extras if needed
        _serviceReady.value = true
    }

    /**
     * Get the SingleStreamer instance for ViewModel compatibility
     */
    fun getSingleStreamer(): SingleStreamer = streamer as SingleStreamer

    /**
     * Alternative getter that ViewModel uses
     */
    fun getStreamer(): SingleStreamer = getSingleStreamer()

    /**
     * Get MediaProjection for audio capture - not needed for camera service
     */
    fun getMediaProjection(): MediaProjection? = null

    /**
     * Video input access for ViewModel compatibility
     */
    val videoInput get() = (streamer as? IWithVideoSource)?.videoInput

    /**
     * Audio input access for ViewModel compatibility
     */
    val audioInput get() = (streamer as? IWithAudioSource)?.audioInput

    /**
     * Service ready StateFlow for ViewModel compatibility
     */
    val serviceReady: StateFlow<Boolean> = _serviceReady

    /**
     * Service ready callback helper
     */
    fun serviceReady(callback: (SingleStreamer) -> Unit) {
        callback(getSingleStreamer())
    }

    /**
     * Get streaming flow for ViewModel compatibility
     */
    val isStreamingFlow: StateFlow<Boolean> get() = streamer.isStreamingFlow

    /**
     * Get streamer flow for ViewModel compatibility
     */
    val streamerFlow: StateFlow<SingleStreamer?> by lazy { 
        MutableStateFlow(getSingleStreamer())
    }

    /**
     * Track streaming state for restore functionality
     */
    var wasStreaming: Boolean = false

    /**
     * Set video source method for ViewModel compatibility
     */
    fun setVideoSource(videoSourceFactory: IVideoSourceInternal.Factory) {
        lifecycleScope.launch {
            (streamer as? IWithVideoSource)?.setVideoSource(videoSourceFactory)
        }
    }

    /**
     * Set audio source method for ViewModel compatibility  
     */
    fun setAudioSource(audioSourceFactory: IAudioSourceInternal.Factory) {
        lifecycleScope.launch {
            (streamer as? IWithAudioSource)?.setAudioSource(audioSourceFactory)
        }
    }

    /**
     * Start streaming with MediaDescriptor for ViewModel compatibility
     */
    suspend fun startStreaming(descriptor: MediaDescriptor): Boolean {
        return try {
            streamer.open(descriptor)
            streamer.startStream()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Stop streaming method
     */
    suspend fun stopStreaming(): Boolean {
        return try {
            streamer.stopStream()
            true
        } catch (e: Exception) {
            false
        }
    }
    
    override fun onCreateNotification(): Notification {
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            getString(R.string.service_notification_text_created),
            R.drawable.ic_baseline_linked_camera_24
        )
    }

    override fun onOpenNotification(): Notification? {
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            getString(R.string.service_notification_text_streaming),
            R.drawable.ic_baseline_linked_camera_24
        )
    }

    override fun onErrorNotification(t: Throwable): Notification? {
        val errorMessage = getString(R.string.service_notification_text_error, t.message ?: "Unknown error")
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            errorMessage,
            R.drawable.ic_baseline_linked_camera_24
        )
    }

    override fun onCloseNotification(): Notification? {
        return customNotificationUtils.createNotification(
            getString(R.string.service_notification_title),
            getString(R.string.service_notification_text_stopped),
            R.drawable.ic_baseline_linked_camera_24
        )
    }
}
