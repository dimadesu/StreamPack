/*
 * Copyright (C) 2021 Thibault B.
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
package io.github.thibaultbee.streampack.app.utils

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import io.github.thibaultbee.streampack.app.services.DemoMediaProjectionService

/**
 * Helper class to manage MediaProjection for audio capture.
 * This is needed to capture ExoPlayer audio output using AudioPlaybackCapture API.
 * Uses a foreground service to satisfy Android 14+ requirements.
 */
class MediaProjectionHelper(private val context: Context) {
    
    private val mediaProjectionManager: MediaProjectionManager by lazy {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    
    private var mediaProjectionService: DemoMediaProjectionService? = null
    private var isBound = false
    private var onProjectionReady: ((MediaProjection?) -> Unit)? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as DemoMediaProjectionService.LocalBinder
            mediaProjectionService = binder.getService()
            isBound = true
            Log.i(TAG, "Service connected")
            
            // Get MediaProjection from service and notify callback
            val projection = mediaProjectionService?.getMediaProjection()
            onProjectionReady?.invoke(projection)
            onProjectionReady = null
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mediaProjectionService = null
            isBound = false
            Log.i(TAG, "Service disconnected")
        }
    }
    
    companion object {
        private const val TAG = "MediaProjectionHelper"
    }
    
    /**
     * Register activity result launcher for MediaProjection permission.
     * Call this from Fragment.onCreate() or Activity.onCreate().
     */
    fun registerLauncher(fragment: Fragment): ActivityResultLauncher<Intent> {
        return fragment.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            handleProjectionResult(result.resultCode, result.data)
        }
    }
    
    /**
     * Register activity result launcher for MediaProjection permission.
     * Call this from Activity.onCreate().
     */
    fun registerLauncher(activity: Activity): ActivityResultLauncher<Intent> {
        if (activity is androidx.activity.ComponentActivity) {
            return activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                handleProjectionResult(result.resultCode, result.data)
            }
        } else {
            throw IllegalArgumentException("Activity must extend ComponentActivity")
        }
    }
    
    /**
     * Request MediaProjection permission and get the MediaProjection instance.
     * 
     * @param launcher The ActivityResultLauncher registered with registerLauncher()
     * @param callback Called when MediaProjection is ready (success) or null (failure)
     */
    fun requestProjection(
        launcher: ActivityResultLauncher<Intent>,
        callback: (MediaProjection?) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            onProjectionReady = callback
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            launcher.launch(intent)
        } else {
            Log.e(TAG, "MediaProjection audio capture requires Android 10 (API 29) or higher")
            callback(null)
        }
    }
    
    private fun handleProjectionResult(resultCode: Int, data: Intent?) {
        val callback = onProjectionReady
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                // Start foreground service to satisfy Android 14+ requirements
                startForegroundService(resultCode, data)
                
                // Bind to service to get MediaProjection
                bindToService()
                
                Log.i(TAG, "MediaProjection service started and binding initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaProjection service: ${e.message}", e)
                onProjectionReady = null
                callback?.invoke(null)
            }
        } else {
            Log.w(TAG, "MediaProjection permission denied by user")
            onProjectionReady = null
            callback?.invoke(null)
        }
    }
    
    private fun startForegroundService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(context, DemoMediaProjectionService::class.java).apply {
            putExtra("result_code", resultCode)
            putExtra("result_data", data)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
        Log.i(TAG, "Started MediaProjection foreground service")
    }
    
    private fun bindToService() {
        val serviceIntent = Intent(context, DemoMediaProjectionService::class.java)
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    /**
     * Get the current MediaProjection if available.
     */
    fun getMediaProjection(): MediaProjection? {
        return if (isBound) {
            mediaProjectionService?.getMediaProjection()
        } else {
            Log.w(TAG, "Service not bound, cannot get MediaProjection")
            null
        }
    }
    
    /**
     * Release the MediaProjection resources.
     * Call this when you're done with audio capture.
     */
    fun release() {
        // Unbind from service
        if (isBound) {
            context.unbindService(serviceConnection)
            isBound = false
        }
        
        // Stop the foreground service
        val serviceIntent = Intent(context, DemoMediaProjectionService::class.java)
        context.stopService(serviceIntent)
        
        mediaProjectionService = null
        
        Log.i(TAG, "MediaProjection released")
    }
}
