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
package io.github.thibaultbee.streampack.app.ui.main

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureResult
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSettings
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoRotation
import android.util.Range
import android.util.Rational
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.databinding.Bindable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.thibaultbee.streampack.app.BR
import io.github.thibaultbee.streampack.app.R
import io.github.thibaultbee.streampack.app.data.rotation.RotationRepository
import io.github.thibaultbee.streampack.app.data.storage.DataStoreRepository
import io.github.thibaultbee.streampack.app.sources.audio.AudioRecordWrapper3
import io.github.thibaultbee.streampack.app.sources.audio.CustomAudioInput3
import io.github.thibaultbee.streampack.app.ui.main.usecases.BuildStreamerUseCase
import io.github.thibaultbee.streampack.app.utils.MediaProjectionHelper
import io.github.thibaultbee.streampack.app.utils.ObservableViewModel
import io.github.thibaultbee.streampack.app.utils.dataStore
import io.github.thibaultbee.streampack.app.utils.isEmpty
import io.github.thibaultbee.streampack.app.utils.setNextCameraId
import io.github.thibaultbee.streampack.app.utils.toggleBackToFront
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.UriMediaDescriptor
import io.github.thibaultbee.streampack.core.elements.endpoints.MediaSinkType
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.IAudioRecordSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrameRateSupported
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MediaProjectionAudioSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.DefaultSrtBitrateRegulatorController
import io.github.thibaultbee.streampack.core.interfaces.IWithAudioSource
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.configuration.mediadescriptor.MediaDescriptor
import io.github.thibaultbee.streampack.core.streamers.single.ISingleStreamer
import io.github.thibaultbee.streampack.app.services.CameraStreamerService
import io.github.thibaultbee.streampack.services.StreamerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)


class PreviewViewModel(private val application: Application) : ObservableViewModel() {
    private val storageRepository = DataStoreRepository(application, application.dataStore)
    private val rotationRepository = RotationRepository.getInstance(application)
    val mediaProjectionHelper = MediaProjectionHelper(application)
    private val buildStreamerUseCase = BuildStreamerUseCase(application, storageRepository)

    // Service binding for background streaming
    /**
     * Service reference for background streaming (using the service abstraction)
     */
    private var streamerService: CameraStreamerService? = null
    
    /**
     * Current streamer instance from the service
     */
    var serviceStreamer: SingleStreamer? = null
        private set
    private var serviceConnection: ServiceConnection? = null
    private val _serviceReady = MutableStateFlow(false)
    private val streamerFlow = MutableStateFlow<SingleStreamer?>(null)
    
    // Streamer access through service (with fallback for backward compatibility)
    val streamer: SingleStreamer?
        get() = serviceStreamer
    
    // Service readiness for UI binding
    val serviceReadyFlow = _serviceReady
    val streamerLiveData = serviceReadyFlow.map { ready ->
        if (ready) serviceStreamer else null
    }.asLiveData()

    /**
     * Test bitmap for [BitmapSource].
     */
    private val testBitmap =
        BitmapFactory.decodeResource(application.resources, R.drawable.img_test)

    /**
     * Camera settings.
     */
    val cameraSettings: CameraSettings?
        get() {
            val currentStreamer = serviceStreamer
            val videoSource = (currentStreamer as? IWithVideoSource)?.videoInput?.sourceFlow?.value
            return (videoSource as? ICameraSource)?.settings
        }

    val requiredPermissions: List<String>
        get() {
            val permissions = mutableListOf<String>()
            val currentStreamer = serviceStreamer
            if (currentStreamer?.videoInput?.sourceFlow is ICameraSource) {
                permissions.add(Manifest.permission.CAMERA)
            }
            if (currentStreamer?.audioInput?.sourceFlow?.value is IAudioRecordSource) {
                permissions.add(Manifest.permission.RECORD_AUDIO)
            }
            storageRepository.endpointDescriptorFlow.asLiveData().value?.let {
                if (it is UriMediaDescriptor) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            return permissions
        }

    /**
     * Determines if MediaProjection is required for the current streaming setup.
     * MediaProjection is needed when streaming from RTMP source for audio capture.
     */
    fun requiresMediaProjection(): Boolean {
        val currentVideoSource = serviceStreamer?.videoInput?.sourceFlow?.value
        // If video source is not a camera source, it's likely RTMP and needs MediaProjection for audio
        return currentVideoSource != null && currentVideoSource !is ICameraSource
    }

    // Streamer errors
    private val _streamerErrorLiveData: MutableLiveData<String> = MutableLiveData()
    val streamerErrorLiveData: LiveData<String> = _streamerErrorLiveData
    private val _endpointErrorLiveData: MutableLiveData<String> = MutableLiveData()
    val endpointErrorLiveData: LiveData<String> = _endpointErrorLiveData

    // Streamer states
    val isStreamingLiveData: LiveData<Boolean>
        get() = serviceReadyFlow.flatMapLatest { ready ->
            Log.d(TAG, "isStreamingLiveData: serviceReady = $ready, serviceStreamer = $serviceStreamer")
            if (ready && serviceStreamer != null) {
                val streamingFlow = serviceStreamer!!.isStreamingFlow
                Log.d(TAG, "isStreamingLiveData: using streamingFlow = $streamingFlow, current value = ${streamingFlow.value}")
                streamingFlow
            } else {
                Log.d(TAG, "isStreamingLiveData: service not ready, returning false")
                kotlinx.coroutines.flow.flowOf(false)
            }
        }.asLiveData()
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    var bufferVisualizerModel: BufferVisualizerModel? = null

    // MediaProjection session for streaming
    private var streamingMediaProjection: MediaProjection? = null

    override fun onCleared() {
        super.onCleared()
        
        // Always unbind from the service - since we started it independently, 
        // unbinding won't destroy it
        serviceConnection?.let { connection ->
            application.unbindService(connection)
            Log.i(TAG, "Unbound from CameraStreamerService")
        }
        streamerService = null
        serviceConnection = null
        _serviceReady.value = false
        
        // Clean up MediaProjection resources
        streamingMediaProjection?.stop()
        streamingMediaProjection = null
        mediaProjectionHelper.release()
        Log.i(TAG, "PreviewViewModel cleared and MediaProjection released")
    }

    init {
        // Bind to streaming service for background streaming capability
        bindToStreamerService()
        
        // Initialize LiveData flows
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady && serviceStreamer != null) {
                    Log.i(TAG, "Service ready and serviceStreamer available - initializing sources")
                    initializeStreamerSources()
                } else {
                    Log.i(TAG, "Service ready: $isReady, serviceStreamer: ${serviceStreamer != null}")
                }
            }
        }
    }

    /**
     * Helper functions to interact with streamer directly (service compatibility layer)
     */
    private suspend fun startServiceStreaming(descriptor: MediaDescriptor): Boolean {
        return try {
            Log.i(TAG, "startServiceStreaming: Opening streamer with descriptor: $descriptor")
            
            val currentStreamer = serviceStreamer
            if (currentStreamer == null) {
                Log.e(TAG, "startServiceStreaming: serviceStreamer is null!")
                _streamerErrorLiveData.postValue("Service streamer not available")
                return false
            }
            
            // Validate RTMP URL format
            val uri = descriptor.uri.toString()
            if (uri.startsWith("rtmp://")) {
                Log.i(TAG, "startServiceStreaming: Attempting RTMP connection to $uri")
                val host = uri.substringAfter("://").substringBefore("/")
                Log.i(TAG, "startServiceStreaming: RTMP host: $host")
            }
            
            Log.i(TAG, "startServiceStreaming: serviceStreamer available, calling open()...")
            
            // Add timeout to prevent hanging
            withTimeout(10000) { // 10 second timeout
                currentStreamer.open(descriptor)
            }
            Log.i(TAG, "startServiceStreaming: open() completed, calling startStream()...")
            currentStreamer.startStream()
            Log.i(TAG, "startServiceStreaming: Stream started successfully")
            true
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "startServiceStreaming failed: Timeout opening connection to ${descriptor.uri}")
            _streamerErrorLiveData.postValue("Connection timeout - check server address and network")
            false
        } catch (e: Exception) {
            Log.e(TAG, "startServiceStreaming failed: ${e.message}", e)
            _streamerErrorLiveData.postValue("Stream start failed: ${e.message}")
            false
        }
    }
    
    private suspend fun stopServiceStreaming(): Boolean {
        return try {
            Log.i(TAG, "stopServiceStreaming: Stopping stream...")
            serviceStreamer?.stopStream()
            Log.i(TAG, "stopServiceStreaming: Stream stopped successfully")
            
            // Stop the foreground service since streaming has ended
            val serviceIntent = Intent(application, CameraStreamerService::class.java)
            application.stopService(serviceIntent)
            Log.i(TAG, "stopServiceStreaming: Stopped CameraStreamerService foreground service")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopServiceStreaming failed: ${e.message}", e)
            false
        }
    }
    
    private fun setServiceAudioSource(audioSourceFactory: IAudioSourceInternal.Factory) {
        viewModelScope.launch {
            serviceStreamer?.setAudioSource(audioSourceFactory)
        }
    }
    
    private fun setServiceVideoSource(videoSourceFactory: IVideoSourceInternal.Factory) {
        viewModelScope.launch {
            serviceStreamer?.setVideoSource(videoSourceFactory)
        }
    }
    
    /**
     * Bind to the CameraStreamerService for background streaming.
     */
    private fun bindToStreamerService() {
        Log.i(TAG, "Binding to CameraStreamerService...")
        
        // Start the service explicitly so it runs independently of binding
        val serviceIntent = Intent(application, CameraStreamerService::class.java)
        application.startForegroundService(serviceIntent)
        Log.i(TAG, "Started CameraStreamerService as independent foreground service")
        
        serviceConnection = StreamerService.bindService(
            context = application,
            serviceClass = CameraStreamerService::class.java,
            onServiceCreated = { streamer ->
                serviceStreamer = streamer as SingleStreamer
                streamerFlow.value = serviceStreamer
                _serviceReady.value = true
                Log.i(TAG, "CameraStreamerService connected and ready")
            },
            onServiceDisconnected = { name ->
                Log.w(TAG, "CameraStreamerService disconnected: $name")
                serviceStreamer = null
                streamerFlow.value = null
                _serviceReady.value = false
            }
        )
    }

    /**
     * Initialize streamer sources after service is ready.
     */
    private suspend fun initializeStreamerSources() {
        val currentStreamer = serviceStreamer ?: return
        
        Log.i(TAG, "Initializing streamer sources - Audio enabled: ${currentStreamer.withAudio}, Video enabled: ${currentStreamer.withVideo}")
        
        // Set audio source and video source
        if (currentStreamer.withAudio) {
            Log.i(TAG, "Audio source is enabled. Setting audio source")
            setServiceAudioSource(MicrophoneSourceFactory())
        } else {
            Log.i(TAG, "Audio source is disabled")
        }
        
        if (currentStreamer.withVideo) {
            if (ActivityCompat.checkSelfPermission(
                    application,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Camera permission granted, setting video source")
                setServiceVideoSource(CameraSourceFactory())
            } else {
                Log.w(TAG, "Camera permission not granted")
            }
        } else {
            Log.i(TAG, "Video source is disabled")
        }
        
        // Set up flow observers for the service-based streamer
        observeStreamerFlows()
    }

    /**
     * Set up flow observers for streamer state changes.
     */
    private fun observeStreamerFlows() {
        val currentStreamer = serviceStreamer ?: return
        
        viewModelScope.launch {
            currentStreamer.videoInput?.sourceFlow?.collect {
                notifySourceChanged()
            }
        }
        
        viewModelScope.launch {
            currentStreamer.throwableFlow.filterNotNull().filter { !it.isClosedException }
                .map { "${it.javaClass.simpleName}: ${it.message}" }.collect {
                    _streamerErrorLiveData.postValue(it)
                }
        }
        
        viewModelScope.launch {
            currentStreamer.throwableFlow.filterNotNull().filter { it.isClosedException }
                .map { "Connection lost: ${it.message}" }.collect {
                    _endpointErrorLiveData.postValue(it)
                }
        }
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isOpenFlow?.collect {
                        Log.i(TAG, "Streamer is opened: $it")
                    }
                }
            }
        }
        viewModelScope.launch {
            serviceReadyFlow.collect { isReady ->
                if (isReady) {
                    serviceStreamer?.isStreamingFlow?.collect {
                        Log.i(TAG, "Streamer is streaming: $it")
                    }
                }
            }
        }
        viewModelScope.launch {
            rotationRepository.rotationFlow
                .collect {
                    serviceStreamer?.setTargetRotation(it)
                }
        }
        viewModelScope.launch {
            storageRepository.isAudioEnableFlow.combine(storageRepository.isVideoEnableFlow) { isAudioEnable, isVideoEnable ->
                Pair(isAudioEnable, isVideoEnable)
            }.drop(1).collect { (_, _) ->
                val previousStreamer = streamer
                streamerFlow.emit(buildStreamerUseCase(previousStreamer))
                if (previousStreamer != streamer) {
                    previousStreamer?.release()
                }
            }
        }
        viewModelScope.launch {
            storageRepository.audioConfigFlow
                .collect { config ->
                    if (ActivityCompat.checkSelfPermission(
                            application,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        config?.let {
                            //
                            serviceStreamer?.setAudioConfig(it)
                        } ?: Log.i(TAG, "Audio is disabled")
                    }
                }
        }
        viewModelScope.launch {
            storageRepository.videoConfigFlow
                .collect { config ->
                    config?.let {
                        serviceStreamer?.setVideoConfig(it)
                    } ?: Log.i(TAG, "Video is disabled")
                }
        }
    }

    fun onZoomRationOnPinchChanged() {
        notifyPropertyChanged(BR.zoomRatio)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun configureAudio() {
        viewModelScope.launch {
            try {
                storageRepository.audioConfigFlow.first()?.let { 
                    serviceStreamer?.setAudioConfig(it)
                }
                    ?: Log.i(
                        TAG,
                        "Audio is disabled"
                    )
            } catch (t: Throwable) {
                Log.e(TAG, "configureAudio failed", t)
                _streamerErrorLiveData.postValue("configureAudio: ${t.message ?: "Unknown error"}")
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initializeVideoSource() {
        viewModelScope.launch {
            val currentStreamer = serviceStreamer
            if (currentStreamer?.videoInput?.sourceFlow?.value == null) {
                currentStreamer?.setVideoSource(CameraSourceFactory())
            } else {
                Log.i(TAG, "Camera source already set")
            }
        }
    }

    fun startStream() {
        viewModelScope.launch {
            Log.i(TAG, "startStream() called")
            val currentStreamer = serviceStreamer
            val serviceReady = _serviceReady.value
            
            Log.i(TAG, "startStream: serviceStreamer = $currentStreamer, serviceReady = $serviceReady")
            
            if (currentStreamer == null) {
                Log.w(TAG, "Service streamer not ready, cannot start stream")
                _streamerErrorLiveData.postValue("Streaming service not ready")
                return@launch
            }
            
            // Check if sources are configured
            val hasVideoSource = currentStreamer.videoInput?.sourceFlow?.value != null
            val hasAudioSource = currentStreamer.audioInput?.sourceFlow?.value != null
            Log.i(TAG, "startStream: hasVideoSource = $hasVideoSource, hasAudioSource = $hasAudioSource")
            
            if (!hasVideoSource) {
                Log.w(TAG, "Video source not configured, initializing...")
                // Try to initialize sources before streaming
                initializeStreamerSources()
                // Small delay to let initialization complete
                kotlinx.coroutines.delay(500)
            }
            
            _isTryingConnectionLiveData.postValue(true)
            try {
                val descriptor = storageRepository.endpointDescriptorFlow.first()
                Log.i(TAG, "Starting stream with descriptor: $descriptor")
                val success = startServiceStreaming(descriptor)
                if (!success) {
                    Log.e(TAG, "Stream start failed - startServiceStreaming returned false")
                    _streamerErrorLiveData.postValue("Failed to start stream")
                    return@launch
                }
                Log.i(TAG, "Stream started successfully")

                if (descriptor.type.sinkType == MediaSinkType.SRT) {
                    val bitrateRegulatorConfig =
                        storageRepository.bitrateRegulatorConfigFlow.first()
                    if (bitrateRegulatorConfig != null) {
                        Log.i(TAG, "Add bitrate regulator controller")
                        currentStreamer.addBitrateRegulatorController(
                            DefaultSrtBitrateRegulatorController.Factory(
                                bitrateRegulatorConfig = bitrateRegulatorConfig
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "startStream failed", e)
                _streamerErrorLiveData.postValue("startStream: ${e.message ?: "Unknown error"}")
            } finally {
                _isTryingConnectionLiveData.postValue(false)
            }
        }
    }

    /**
     * Start streaming with MediaProjection support.
     * Request MediaProjection permission and keep it active during streaming.
     */
    fun startStreamWithMediaProjection(
        mediaProjectionLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        _isTryingConnectionLiveData.postValue(true)
        
        mediaProjectionHelper.requestProjection(mediaProjectionLauncher) { mediaProjection ->
            Log.i(TAG, "MediaProjection callback received - mediaProjection: ${if (mediaProjection != null) "SUCCESS" else "NULL"}")
            if (mediaProjection != null) {
                streamingMediaProjection = mediaProjection
                Log.i(TAG, "MediaProjection acquired for streaming session - starting setup...")
                
                viewModelScope.launch {
                    try {
                        Log.i(TAG, "About to check video source for audio setup...")
                        // Check if we're on RTMP source - only use MediaProjection audio for RTMP
                        val currentVideoSource = serviceStreamer?.videoInput?.sourceFlow?.value
                        Log.i(TAG, "Current video source: $currentVideoSource (isICameraSource: ${currentVideoSource is ICameraSource})")
                        if (currentVideoSource !is ICameraSource) {
                            // We're on RTMP source - use MediaProjection for audio capture
                            Log.i(TAG, "RTMP source detected - setting up MediaProjection audio capture")
                            try {
                                setServiceAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
                                Log.i(TAG, "MediaProjection audio source configured for RTMP streaming")
                            } catch (audioError: Exception) {
                                Log.w(TAG, "MediaProjection audio setup failed, falling back to microphone: ${audioError.message}")
                                // Fallback to microphone if MediaProjection audio fails
                                setServiceAudioSource(MicrophoneSourceFactory())
                            }
                        } else {
                            // We're on Camera source - use microphone for audio
                            Log.i(TAG, "Camera source detected - using microphone for audio")
                            setServiceAudioSource(MicrophoneSourceFactory())
                        }
                        
                        // Start the actual stream
                        startStreamInternal(onSuccess, onError)
                    } catch (e: Exception) {
                        _isTryingConnectionLiveData.postValue(false)
                        val error = "Failed to configure MediaProjection audio: ${e.message}"
                        Log.e(TAG, error, e)
                        onError(error)
                    }
                }
            } else {
                _isTryingConnectionLiveData.postValue(false)
                val error = "MediaProjection permission required for streaming"
                Log.e(TAG, error)
                onError(error)
            }
        }
    }
    
    private fun startStreamInternal(onSuccess: () -> Unit, onError: (String) -> Unit) {
        Log.i(TAG, "startStreamInternal called - beginning setup...")
        viewModelScope.launch {
            try {
                val descriptor = storageRepository.endpointDescriptorFlow.first()
                Log.i(TAG, "Starting stream with descriptor: $descriptor")
                Log.i(TAG, "About to call startServiceStreaming()...")
                startServiceStreaming(descriptor)
                Log.i(TAG, "startServiceStreaming() completed successfully")

                if (descriptor.type.sinkType == MediaSinkType.SRT) {
                    val bitrateRegulatorConfig = storageRepository.bitrateRegulatorConfigFlow.first()
                    if (bitrateRegulatorConfig != null) {
                        Log.i(TAG, "Add bitrate regulator controller")
                        val currentStreamer = serviceStreamer
                        currentStreamer?.addBitrateRegulatorController(
                            DefaultSrtBitrateRegulatorController.Factory(
                                bitrateRegulatorConfig = bitrateRegulatorConfig
                            )
                        )
                    }
                }
                
                Log.i(TAG, "Stream setup completed successfully, calling onSuccess()")
                onSuccess()
            } catch (e: Throwable) {
                val error = "Stream start failed: ${e.message ?: "Unknown error"}"
                Log.e(TAG, "STREAM START EXCEPTION: $error", e)
                onError(error)
            } finally {
                Log.i(TAG, "startStreamInternal finally block - setting isTryingConnection to false")
                _isTryingConnectionLiveData.postValue(false)
            }
        }
    }

    fun stopStream() {
        viewModelScope.launch {
            try {
                val currentStreamer = serviceStreamer
                
                if (currentStreamer == null) {
                    Log.w(TAG, "Service streamer not ready, cannot stop stream")
                    return@launch
                }
                
                val currentStreamingState = currentStreamer.isStreamingFlow.value
                Log.i(TAG, "stopStream() called - Current streaming state: $currentStreamingState")
                
                // If already stopped, don't do anything
                if (currentStreamingState != true) {
                    Log.i(TAG, "Stream is already stopped, skipping stop sequence")
                    _isTryingConnectionLiveData.postValue(false)
                    return@launch
                }
                
                Log.i(TAG, "Stopping stream...")
                
                // Release MediaProjection FIRST to interrupt any ongoing capture
                streamingMediaProjection?.let { mediaProjection ->
                    mediaProjection.stop()
                    Log.i(TAG, "MediaProjection stopped")
                }
                streamingMediaProjection = null
                
                // Stop streaming via helper method
                try {
                    stopServiceStreaming()
                    Log.i(TAG, "Stream stop command sent")
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping stream: ${e.message}", e)
                }
                
                // Clean up buffer visualizer
                bufferVisualizerModel?.let {
                    BufferVisualizerModel.circularPcmBuffer = null
                    bufferVisualizerModel = null
                    Log.i(TAG, "Buffer visualizer cleaned up")
                }
                
                // Remove bitrate regulator
                try {
                    currentStreamer.removeBitrateRegulatorController()
                    Log.i(TAG, "Bitrate regulator removed")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove bitrate regulator: ${e.message}")
                }
                
                // Reset audio source to clean state
                try {
                    val currentVideoSource = currentStreamer.videoInput?.sourceFlow?.value
                    if (currentVideoSource !is ICameraSource) {
                        // We're on RTMP source - reset audio to microphone for clean state
                        Log.i(TAG, "RTMP source detected after stop - resetting audio to microphone")
                        setServiceAudioSource(MicrophoneSourceFactory())
                    } else {
                        // Camera source - ensure microphone is set
                        Log.i(TAG, "Camera source detected after stop - ensuring microphone audio")
                        setServiceAudioSource(MicrophoneSourceFactory())
                    }
                    Log.i(TAG, "Audio source reset to microphone after stream stop")
                } catch (e: Exception) {
                    Log.w(TAG, "Error resetting audio source after stop: ${e.message}", e)
                }
                
                Log.i(TAG, "Stream stop completed successfully")
                
            } catch (e: Throwable) {
                Log.e(TAG, "stopStream failed", e)
                // Force clear state
                streamingMediaProjection?.stop()
                streamingMediaProjection = null
            } finally {
                _isTryingConnectionLiveData.postValue(false)
                // Clean up visualizer regardless
                BufferVisualizerModel.circularPcmBuffer = null
                bufferVisualizerModel = null
            }
        }
    }
    
    fun setMute(isMuted: Boolean) {
        streamer?.audioInput?.isMuted = isMuted
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun switchBackToFront(): Boolean {
        /**
         * If video frame rate is not supported by the new camera, streamer will throw an
         * exception instead of crashing. You can either catch the exception or check if the
         * configuration is valid for the new camera with [Context.isFrameRateSupported].
         */
        val videoSource = streamer?.videoInput?.sourceFlow?.value
        if (videoSource is ICameraSource) {
            viewModelScope.launch {
                streamer?.toggleBackToFront(application)
            }
        }
        return true
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleCamera() {
        /**
         * If video frame rate is not supported by the new camera, streamer will throw an
         * exception instead of crashing. You can either catch the exception or check if the
         * configuration is valid for the new camera with [Context.isFrameRateSupported].
         */
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "Streamer service not available for camera toggle")
            _streamerErrorLiveData.postValue("Service not available")
            return
        }
        
        val videoSource = currentStreamer.videoInput?.sourceFlow?.value
        if (videoSource is ICameraSource) {
            viewModelScope.launch {
                try {
                    currentStreamer.setNextCameraId(application)
                    Log.i(TAG, "Camera toggled successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle camera", e)
                    _streamerErrorLiveData.postValue("Camera toggle failed: ${e.message}")
                }
            }
        } else {
            Log.w(TAG, "Video source is not a camera source, cannot toggle")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleVideoSource(bufferVisualizer: BufferVisualizerView) {
        val currentStreamer = serviceStreamer
        if (currentStreamer == null) {
            Log.e(TAG, "Streamer service not available for video source toggle")
            _streamerErrorLiveData.postValue("Service not available")
            return
        }
        
        val videoSource = currentStreamer.videoInput?.sourceFlow?.value
        val isCurrentlyStreaming = isStreamingLiveData.value == true
        
        viewModelScope.launch {
            when (videoSource) {
                is ICameraSource -> {
                    Log.i(TAG, "Switching from Camera to RTMP source (streaming: $isCurrentlyStreaming)")
                    
                    // If we're currently streaming, temporarily stop to prepare for source switch
                    var wasStreaming = false
                    if (isCurrentlyStreaming) {
                        Log.i(TAG, "Temporarily stopping camera stream for source switch")
                        wasStreaming = true
                        try {
                            stopServiceStreaming()
                            // Brief delay to ensure clean stop
                            kotlinx.coroutines.delay(100)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
                        }
                    }
                    
                    // For ExoPlayer audio capture, check if we have MediaProjection from streaming session
                    val mediaProjection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                    if (mediaProjection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        Log.i(TAG, "Using MediaProjection-based ExoPlayer audio capture with optimized buffering")
                        setupExoPlayerWithMediaProjection(bufferVisualizer, mediaProjection)
                    } else {
                        Log.w(TAG, "MediaProjection not available - will be requested when streaming starts")
                        // Set up ExoPlayer without MediaProjection audio for now (with optimized buffering)
                        setupExoPlayerWithoutMediaProjection(bufferVisualizer)
                    }
                    
                    // If we were streaming before, restart with RTMP source
                    if (wasStreaming) {
                        Log.i(TAG, "Restarting stream with RTMP source")
                        try {
                            // Small delay to let RTMP source initialize
                            kotlinx.coroutines.delay(300)
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            startServiceStreaming(descriptor)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restarting stream with RTMP: ${e.message}")
                            _streamerErrorLiveData.postValue("Failed to restart stream with RTMP: ${e.message}")
                        }
                    }
                }
                else -> {
                    Log.i(TAG, "Switching from RTMP back to Camera source (streaming: $isCurrentlyStreaming)")
                    
                    // If we're currently streaming, we need to stop the current source first
                    var wasStreaming = false
                    if (isCurrentlyStreaming) {
                        Log.i(TAG, "Stopping RTMP streaming before switch")
                        wasStreaming = true
                        try {
                            stopServiceStreaming()
                            // Small delay to ensure stream stops properly
                            kotlinx.coroutines.delay(100)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
                        }
                    }
                    
                    // Clean up RTMP-related resources
                    bufferVisualizer.stopObserving()
                    BufferVisualizerModel.circularPcmBuffer = null
                    bufferVisualizerModel = null
                    
                    // Don't release streaming MediaProjection here - it's managed by stream lifecycle
                    if (streamingMediaProjection == null) {
                        mediaProjectionHelper.release()
                    }
                    
                    // Switch to camera source
                    currentStreamer.setVideoSource(CameraSourceFactory())
                    currentStreamer.setAudioSource(MicrophoneSourceFactory())
                    
                    // If we were streaming before, restart with camera
                    if (wasStreaming) {
                        Log.i(TAG, "Restarting stream with camera source")
                        try {
                            // Small delay to let camera source initialize
                            kotlinx.coroutines.delay(200)
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            startServiceStreaming(descriptor)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restarting stream with camera: ${e.message}")
                            _streamerErrorLiveData.postValue("Failed to restart stream with camera: ${e.message}")
                        }
                    }
                }
            }
            Log.i(TAG, "Switch video source completed")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleVideoSourceWithProjection(bufferVisualizer: BufferVisualizerView, mediaProjection: MediaProjection) {
        val videoSource = streamer?.videoInput?.sourceFlow?.value
        val isCurrentlyStreaming = isStreamingLiveData.value == true
        
        viewModelScope.launch {
            when (videoSource) {
                is ICameraSource -> {
                    Log.i(TAG, "Switching from Camera to RTMP with MediaProjection (streaming: $isCurrentlyStreaming)")
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        Log.i(TAG, "Using provided MediaProjection for ExoPlayer audio capture")
                        setupExoPlayerWithMediaProjection(bufferVisualizer, mediaProjection)
                    } else {
                        Log.w(TAG, "MediaProjection requires Android 10+ - falling back to complex buffer approach")
                        setupComplexAudioCapture(bufferVisualizer)
                    }
                }
                else -> {
                    Log.i(TAG, "Switching from RTMP back to Camera source with MediaProjection (streaming: $isCurrentlyStreaming)")
                    
                    // Track if we were streaming so we can resume after switch
                    var wasStreaming = false
                    
                    // If we're currently streaming, we need to stop the current source first
                    if (isCurrentlyStreaming) {
                        Log.i(TAG, "Stopping RTMP streaming before switch")
                        wasStreaming = true
                        try {
                            streamer?.stopStream()
                            // Small delay to ensure stream stops properly
                            kotlinx.coroutines.delay(100)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error stopping stream during source switch: ${e.message}")
                        }
                    }
                    
                    // Clean up RTMP-related resources
                    bufferVisualizer.stopObserving()
                    BufferVisualizerModel.circularPcmBuffer = null
                    bufferVisualizerModel = null
                    
                    // Release MediaProjection resources
                    mediaProjectionHelper.release()
                    
                    // Switch to camera source
                    streamer?.setVideoSource(CameraSourceFactory())
                    streamer?.setAudioSource(MicrophoneSourceFactory())
                    
                    // If we were streaming before, restart with camera
                    if (wasStreaming) {
                        Log.i(TAG, "Restarting stream with camera source")
                        try {
                            // Small delay to let camera source initialize
                            kotlinx.coroutines.delay(200)
                            val descriptor = storageRepository.endpointDescriptorFlow.first()
                            startServiceStreaming(descriptor)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error restarting stream with camera: ${e.message}")
                            _streamerErrorLiveData.postValue("Failed to restart stream with camera: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private suspend fun setupExoPlayerWithMediaProjection(bufferVisualizer: BufferVisualizerView, mediaProjection: MediaProjection) {
        // Create ExoPlayer for video with minimal buffering for immediate playback
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                250, // Start playback after only 250ms of buffering (default is 2500ms)
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        
        val exoPlayerInstance = ExoPlayer.Builder(application)
            .setLoadControl(loadControl)
            .build()
        
        // Set up RTMP media source for preview display
        val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(application)
        ).createMediaSource(mediaItem)
        
        exoPlayerInstance.setMediaSource(mediaSource)
        exoPlayerInstance.volume = 0f  // Normal volume for MediaProjection to capture
        
        // Add error listener to handle RTMP connection failures gracefully
        exoPlayerInstance.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "ExoPlayer RTMP error (preview may not work): ${error.message}")
                // Don't fail the entire setup - streaming can still work
            }
        })
        
        // Set video source - ExoPlayer video display
        val streamer = serviceStreamer
        if (streamer == null) {
            Log.e(TAG, "Streamer service not available")
            throw IllegalStateException("Service not available")
        }
        streamer?.setVideoSource(CustomStreamPackSourceInternal.Factory(exoPlayerInstance))
        
        // Set audio source - try MediaProjection, fallback to microphone if it fails
        try {
            streamer?.setAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
            Log.i(TAG, "MediaProjection audio source configured for ExoPlayer")
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection audio source failed, using microphone fallback: ${e.message}")
            streamer?.setAudioSource(MicrophoneSourceFactory())
        }
        
        Log.i(TAG, "ExoPlayer with MediaProjection setup completed")
    }

    /**
     * Set up ExoPlayer without MediaProjection - uses microphone for audio.
     * MediaProjection will be configured when streaming starts.
     */
    private suspend fun setupExoPlayerWithoutMediaProjection(bufferVisualizer: BufferVisualizerView) {
        // Create ExoPlayer for video with minimal buffering for immediate playback
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                250, // Start playback after only 250ms of buffering (default is 2500ms)
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        
        val exoPlayerInstance = ExoPlayer.Builder(application)
            .setLoadControl(loadControl)
            .build()
        
        // Set up RTMP media source for preview display
        val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(application)
        ).createMediaSource(mediaItem)
        
        exoPlayerInstance.setMediaSource(mediaSource)
        exoPlayerInstance.volume = 0f  // Mute for now
        
        // Add error listener to handle RTMP connection failures gracefully
        exoPlayerInstance.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "ExoPlayer RTMP error (preview may not work): ${error.message}")
                // Don't fail the entire setup - streaming can still work
            }
        })
        
        // Set video source - ExoPlayer video display
        val streamer = serviceStreamer
        if (streamer == null) {
            Log.e(TAG, "Streamer service not available")
            throw IllegalStateException("Service not available")
        }
        streamer?.setVideoSource(CustomStreamPackSourceInternal.Factory(exoPlayerInstance))
        
        // Use microphone for now - MediaProjection audio will be set when streaming starts
        streamer?.setAudioSource(MicrophoneSourceFactory())
        
        Log.i(TAG, "ExoPlayer setup completed with microphone audio (MediaProjection will be configured on stream start)")
    }

    /**
     * Fallback method for complex audio capture when MediaProjection is not available.
     * This maintains the existing CircularPcmBuffer + FakeAudioTrack approach.
     */
    private suspend fun setupComplexAudioCapture(bufferVisualizer: BufferVisualizerView) {
        storageRepository.audioConfigFlow
            .collect { config ->
                if (ActivityCompat.checkSelfPermission(
                        application,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    config?.let {
                        val bufferSize = AudioRecord.getMinBufferSize(
                            it.sampleRate,
                            it.channelConfig,
                            it.byteFormat
                        )
                        val pcmBuffer = CircularPcmBuffer(bufferSize * 2)

                        // Pre-initialize CircularPcmBuffer with correct format from AudioConfig
                        val channelCount = when (it.channelConfig) {
                            android.media.AudioFormat.CHANNEL_IN_MONO -> 1
                            android.media.AudioFormat.CHANNEL_IN_STEREO -> 2
                            else -> 2
                        }
                        val bytesPerSample = when (it.byteFormat) {
                            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                            android.media.AudioFormat.ENCODING_PCM_32BIT -> 4
                            else -> 2
                        }
                        pcmBuffer.updateFormat(it.sampleRate, channelCount, bytesPerSample)
                        Log.i(TAG, "Pre-initialized CircularPcmBuffer with StreamPack AudioConfig: sampleRate=${it.sampleRate}, channels=$channelCount, bytesPerSample=$bytesPerSample")

                        // Single ExoPlayer instance with pass-through FakeAudioTrack for both A/V
                        val renderersFactory = CustomAudioRenderersFactory(application, pcmBuffer)
                        val exoPlayerInstance = ExoPlayer
                            .Builder(application, renderersFactory)
                            .build()

                        val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
                        val mediaSource = ProgressiveMediaSource.Factory(
                            DefaultDataSource.Factory(application)
                        ).createMediaSource(mediaItem)

                        exoPlayerInstance.setMediaSource(mediaSource)
                        exoPlayerInstance.volume = 0f

                        val audioRecordWrapper = AudioRecordWrapper3(exoPlayerInstance, pcmBuffer)
                        BufferVisualizerModel.circularPcmBuffer = pcmBuffer
                        bufferVisualizerModel = BufferVisualizerModel
                        bufferVisualizer.startObserving()

                        streamer?.setVideoSource(CustomStreamPackSourceInternal.Factory(exoPlayerInstance))
                        streamer?.setAudioSource(CustomAudioInput3.Factory(
                            audioRecordWrapper,
                            bufferVisualizerModel as BufferVisualizerModel
                        ))
                    } ?: Log.i(TAG, "Audio is disabled")
                }
            }
    }

    val isCameraSource: LiveData<Boolean>
        get() = serviceReadyFlow.flatMapLatest { ready ->
            if (ready && serviceStreamer != null) {
                Log.d(TAG, "isCameraSource: serviceStreamer available, checking video source")
                serviceStreamer!!.videoInput?.sourceFlow?.map { source ->
                    val isCam = source is ICameraSource
                    Log.d(TAG, "isCameraSource: video source = $source, isCameraSource = $isCam")
                    isCam
                } ?: kotlinx.coroutines.flow.flowOf(false)
            } else {
                Log.d(TAG, "isCameraSource: service not ready or serviceStreamer null, returning false")
                kotlinx.coroutines.flow.flowOf(false)
            }
        }.asLiveData()

    val isFlashAvailable = MutableLiveData(false)
    fun toggleFlash() {
        cameraSettings?.let {
            it.flash.enable = !it.flash.enable
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    val isAutoWhiteBalanceAvailable = MutableLiveData(false)
    fun toggleAutoWhiteBalanceMode() {
        cameraSettings?.let { settings ->
            val awbModes = settings.whiteBalance.availableAutoModes
            val index = awbModes.indexOf(settings.whiteBalance.autoMode)
            settings.whiteBalance.autoMode = awbModes[(index + 1) % awbModes.size]
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    val showExposureSlider = MutableLiveData(false)
    fun toggleExposureSlider() {
        showExposureSlider.postValue(!(showExposureSlider.value)!!)
    }

    val isExposureCompensationAvailable = MutableLiveData(false)
    val exposureCompensationRange = MutableLiveData<Range<Int>>()
    val exposureCompensationStep = MutableLiveData<Rational>()
    var exposureCompensation: Float
        @Bindable get() {
            val settings = cameraSettings
            return if (settings != null && settings.isAvailableFlow.value) {
                settings.exposure.compensation * settings.exposure.availableCompensationStep.toFloat()
            } else {
                0f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                settings.exposure.let {
                    if (settings.isAvailableFlow.value) {
                        it.compensation = (value / it.availableCompensationStep.toFloat()).toInt()
                    }
                    notifyPropertyChanged(BR.exposureCompensation)
                }
            } ?: Log.e(TAG, "Camera settings is not accessible")
        }

    val showZoomSlider = MutableLiveData(false)
    fun toggleZoomSlider() {
        showZoomSlider.postValue(!(showZoomSlider.value)!!)
    }

    val isZoomAvailable = MutableLiveData(false)
    val zoomRatioRange = MutableLiveData<Range<Float>>()
    var zoomRatio: Float
        @Bindable get() {
            val settings = cameraSettings
            return if (settings != null && settings.isAvailableFlow.value) {
                settings.zoom.zoomRatio
            } else {
                1f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                if (settings.isAvailableFlow.value) {
                    settings.zoom.zoomRatio = value
                }
                notifyPropertyChanged(BR.zoomRatio)
            } ?: Log.e(TAG, "Camera settings is not accessible")
        }

    val isAutoFocusModeAvailable = MutableLiveData(false)
    fun toggleAutoFocusMode() {
        cameraSettings?.let {
            val afModes = it.focus.availableAutoModes
            val index = afModes.indexOf(it.focus.autoMode)
            it.focus.autoMode = afModes[(index + 1) % afModes.size]
            if (it.focus.autoMode == CaptureResult.CONTROL_AF_MODE_OFF) {
                showLensDistanceSlider.postValue(true)
            } else {
                showLensDistanceSlider.postValue(false)
            }
        } ?: Log.e(TAG, "Camera settings is not accessible")
    }

    val showLensDistanceSlider = MutableLiveData(false)
    val lensDistanceRange = MutableLiveData<Range<Float>>()
    var lensDistance: Float
        @Bindable get() {
            val settings = cameraSettings
            return if ((settings != null) &&
                settings.isAvailableFlow.value
            ) {
                settings.focus.lensDistance
            } else {
                0f
            }
        }
        set(value) {
            cameraSettings?.let { settings ->
                settings.focus.let {
                    if (settings.isAvailableFlow.value) {
                        it.lensDistance = value
                    }
                    notifyPropertyChanged(BR.lensDistance)
                }
            } ?: Log.e(TAG, "Camera settings is not accessible")
        }

    private fun notifySourceChanged() {
        val videoSource = streamer?.videoInput?.sourceFlow?.value ?: return
        if (videoSource is ICameraSource) {
            notifyCameraChanged(videoSource)
        } else {
            isFlashAvailable.postValue(false)
            isAutoWhiteBalanceAvailable.postValue(false)
            isExposureCompensationAvailable.postValue(false)
            isZoomAvailable.postValue(false)
            isAutoFocusModeAvailable.postValue(false)
        }
    }

    private fun notifyCameraChanged(videoSource: ICameraSource) {
        val settings = videoSource.settings
        // Set optical stabilization first
        // Do not set both video and optical stabilization at the same time
        if (settings.isAvailableFlow.value) {
            if (settings.stabilization.isOpticalAvailable) {
                settings.stabilization.enableOptical = true
            } else {
                settings.stabilization.enableVideo = true
            }
        }

        // Flash
        isFlashAvailable.postValue(settings.flash.isAvailable)

        // WB
        isAutoWhiteBalanceAvailable.postValue(settings.whiteBalance.availableAutoModes.size > 1)

        // Exposure
        isExposureCompensationAvailable.postValue(
            !settings.exposure.availableCompensationRange.isEmpty
        )
        exposureCompensationRange.postValue(
            Range(
                (settings.exposure.availableCompensationRange.lower * settings.exposure.availableCompensationStep.toFloat()).toInt(),
                (settings.exposure.availableCompensationRange.upper * settings.exposure.availableCompensationStep.toFloat()).toInt()
            )
        )
        exposureCompensationStep.postValue(settings.exposure.availableCompensationStep)
        exposureCompensation = 0f

        // Zoom
        isZoomAvailable.postValue(
            !settings.zoom.availableRatioRange.isEmpty
        )
        zoomRatioRange.postValue(settings.zoom.availableRatioRange)
        zoomRatio = 1.0f

        // Focus
        isAutoFocusModeAvailable.postValue(settings.focus.availableAutoModes.size > 1)

        // Lens distance
        showLensDistanceSlider.postValue(false)
        lensDistanceRange.postValue(settings.focus.availableLensDistanceRange)
        lensDistance = 0f
    }

    companion object {
        private const val TAG = "PreviewViewModel"
    }
}
