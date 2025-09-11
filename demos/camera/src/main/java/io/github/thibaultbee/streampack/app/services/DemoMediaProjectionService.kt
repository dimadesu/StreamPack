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
package io.github.thibaultbee.streampack.app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.thibaultbee.streampack.app.R

/**
 * Simple foreground service that creates MediaProjection for audio capture.
 * This satisfies Android 14+ requirements for MediaProjection usage.
 */
class DemoMediaProjectionService : Service() {
    
    private var mediaProjection: MediaProjection? = null
    private val binder = LocalBinder()
    
    companion object {
        private const val TAG = "DemoMediaProjectionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "media_projection_channel"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): DemoMediaProjectionService = this@DemoMediaProjectionService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service started")
        
        // Start as foreground service with MEDIA_PROJECTION type
        startForeground()
        
        // Create MediaProjection if we have the data
        intent?.let { serviceIntent ->
            val resultCode = serviceIntent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                serviceIntent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                serviceIntent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            
            if (resultCode != 0 && resultData != null) {
                createMediaProjection(resultCode, resultData)
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "Service bound")
        return binder
    }
    
    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }
    
    private fun startForeground() {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        Log.i(TAG, "Started as foreground service with MEDIA_PROJECTION type")
    }
    
    private fun createMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            val mediaProjectionManager = 
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            Log.i(TAG, "MediaProjection created successfully in foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MediaProjection in service: ${e.message}", e)
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Projection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "MediaProjection audio capture service"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Capture Active")
            .setContentText("Capturing ExoPlayer audio for streaming")
            .setSmallIcon(R.drawable.ic_baseline_linked_camera_24)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Get the MediaProjection instance created by this service.
     */
    fun getMediaProjection(): MediaProjection? = mediaProjection
}
