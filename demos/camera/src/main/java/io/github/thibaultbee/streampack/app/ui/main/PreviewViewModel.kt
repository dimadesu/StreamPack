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
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSettings
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.isFrameRateSupported
import io.github.thibaultbee.streampack.core.interfaces.IWithVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MediaProjectionAudioSourceFactory
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.utils.extensions.isClosedException
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.DefaultSrtBitrateRegulatorController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch


class PreviewViewModel(private val application: Application) : ObservableViewModel() {
    private val storageRepository = DataStoreRepository(application, application.dataStore)
    private val rotationRepository = RotationRepository.getInstance(application)
    val mediaProjectionHelper = MediaProjectionHelper(application)
    private val buildStreamerUseCase = BuildStreamerUseCase(application, storageRepository)

    private val streamerFlow = MutableStateFlow(buildStreamerUseCase())
    val streamer: SingleStreamer
        get() = streamerFlow.value
    val streamerLiveData = streamerFlow.asLiveData()

    /**
     * Test bitmap for [BitmapSource].
     */
    private val testBitmap =
        BitmapFactory.decodeResource(application.resources, R.drawable.img_test)

    /**
     * Camera settings.
     */
    private val cameraSettings: CameraSettings?
        get() {
            val videoSource = (streamer as? IWithVideoSource)?.videoInput?.sourceFlow?.value
            return (videoSource as? ICameraSource)?.settings
        }

    val requiredPermissions: List<String>
        get() {
            val permissions = mutableListOf<String>()
            if (streamer.videoInput?.sourceFlow is ICameraSource) {
                permissions.add(Manifest.permission.CAMERA)
            }
            if (streamer.audioInput?.sourceFlow?.value is IAudioRecordSource) {
                permissions.add(Manifest.permission.RECORD_AUDIO)
            }
            storageRepository.endpointDescriptorFlow.asLiveData().value?.let {
                if (it is UriMediaDescriptor) {
                    permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }

            return permissions
        }

    // Streamer errors
    private val _streamerErrorLiveData: MutableLiveData<String> = MutableLiveData()
    val streamerErrorLiveData: LiveData<String> = _streamerErrorLiveData
    private val _endpointErrorLiveData: MutableLiveData<String> = MutableLiveData()
    val endpointErrorLiveData: LiveData<String> = _endpointErrorLiveData

    // Streamer states
    val isStreamingLiveData: LiveData<Boolean>
        get() = streamer.isStreamingFlow.asLiveData()
    private val _isTryingConnectionLiveData = MutableLiveData<Boolean>()
    val isTryingConnectionLiveData: LiveData<Boolean> = _isTryingConnectionLiveData

    var bufferVisualizerModel: BufferVisualizerModel? = null

    // MediaProjection session for streaming
    private var streamingMediaProjection: MediaProjection? = null

    override fun onCleared() {
        super.onCleared()
//        application.unbindService(serviceConnection)
        // Clean up MediaProjection resources
        streamingMediaProjection?.stop()
        streamingMediaProjection = null
        mediaProjectionHelper.release()
        Log.i(TAG, "PreviewViewModel cleared and MediaProjection released")
    }

    init {
        viewModelScope.launch {
            streamerFlow.collect {
                // Set audio source and video source
                if (streamer.withAudio) {
                    Log.i(TAG, "Audio source is enabled. Setting audio source")
                    streamer.setAudioSource(MicrophoneSourceFactory())

                } else {
                    Log.i(TAG, "Audio source is disabled")
                }
                if (streamer.withVideo) {
                    if (ActivityCompat.checkSelfPermission(
                            application,
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        streamer.setVideoSource(CameraSourceFactory())
                    }
                } else {
                    Log.i(TAG, "Video source is disabled")
                }
            }
        }
        viewModelScope.launch {
            streamer.videoInput?.sourceFlow?.collect {
                notifySourceChanged()
            }
        }
        viewModelScope.launch {
            streamer.throwableFlow.filterNotNull().filter { !it.isClosedException }
                .map { "${it.javaClass.simpleName}: ${it.message}" }.collect {
                    _streamerErrorLiveData.postValue(it)
                }
        }
        viewModelScope.launch {
            streamer.throwableFlow.filterNotNull().filter { it.isClosedException }
                .map { "Connection lost: ${it.message}" }.collect {
                    _endpointErrorLiveData.postValue(it)
                }
        }
        viewModelScope.launch {
            streamer.isOpenFlow
                .collect {
                    Log.i(TAG, "Streamer is opened: $it")
                }
        }
        viewModelScope.launch {
            streamer.isStreamingFlow
                .collect {
                    Log.i(TAG, "Streamer is streaming: $it")
                }
        }
        viewModelScope.launch {
            rotationRepository.rotationFlow
                .collect {
                    streamer.setTargetRotation(it)
                }
        }
        viewModelScope.launch {
            storageRepository.isAudioEnableFlow.combine(storageRepository.isVideoEnableFlow) { isAudioEnable, isVideoEnable ->
                Pair(isAudioEnable, isVideoEnable)
            }.drop(1).collect { (_, _) ->
                val previousStreamer = streamer
                streamerFlow.emit(buildStreamerUseCase(previousStreamer))
                if (previousStreamer != streamer) {
                    previousStreamer.release()
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
                            streamer.setAudioConfig(it)
                        } ?: Log.i(TAG, "Audio is disabled")
                    }
                }
        }
        viewModelScope.launch {
            storageRepository.videoConfigFlow
                .collect { config ->
                    config?.let {
                        streamer.setVideoConfig(it)
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
                storageRepository.audioConfigFlow.first()?.let { streamer.setAudioConfig(it) }
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
            if (streamer.videoInput?.sourceFlow?.value == null) {
                streamer.setVideoSource(CameraSourceFactory())
            } else {
                Log.i(TAG, "Camera source already set")
            }
        }
    }

    fun startStream() {
        viewModelScope.launch {
            _isTryingConnectionLiveData.postValue(true)
            try {
                val descriptor = storageRepository.endpointDescriptorFlow.first()
                Log.i(TAG, "Calling streamer.startStream with descriptor: $descriptor")
                streamer.startStream(descriptor)

                if (descriptor.type.sinkType == MediaSinkType.SRT) {
                    val bitrateRegulatorConfig =
                        storageRepository.bitrateRegulatorConfigFlow.first()
                    if (bitrateRegulatorConfig != null) {
                        Log.i(TAG, "Add bitrate regulator controller")
                        streamer.addBitrateRegulatorController(
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
                        val currentVideoSource = streamer.videoInput?.sourceFlow?.value
                        Log.i(TAG, "Current video source: $currentVideoSource (isICameraSource: ${currentVideoSource is ICameraSource})")
                        if (currentVideoSource !is ICameraSource) {
                            // We're on RTMP source - use MediaProjection for audio capture
                            Log.i(TAG, "RTMP source detected - setting up MediaProjection audio capture")
                            try {
                                streamer.setAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
                                Log.i(TAG, "MediaProjection audio source configured for RTMP streaming")
                            } catch (audioError: Exception) {
                                Log.w(TAG, "MediaProjection audio setup failed, falling back to microphone: ${audioError.message}")
                                // Fallback to microphone if MediaProjection audio fails
                                streamer.setAudioSource(MicrophoneSourceFactory())
                            }
                        } else {
                            // We're on Camera source - use microphone for audio
                            Log.i(TAG, "Camera source detected - using microphone for audio")
                            streamer.setAudioSource(MicrophoneSourceFactory())
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
                Log.i(TAG, "About to call streamer.startStream()...")
                streamer.startStream(descriptor)
                Log.i(TAG, "streamer.startStream() completed successfully")

                if (descriptor.type.sinkType == MediaSinkType.SRT) {
                    val bitrateRegulatorConfig = storageRepository.bitrateRegulatorConfigFlow.first()
                    if (bitrateRegulatorConfig != null) {
                        Log.i(TAG, "Add bitrate regulator controller")
                        streamer.addBitrateRegulatorController(
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
                val currentStreamingState = streamer.isStreamingFlow.value
                Log.i(TAG, "stopStream() called - Current streaming state: $currentStreamingState")
                
                // If already stopped, don't do anything
                if (currentStreamingState != true) {
                    Log.i(TAG, "Stream is already stopped, skipping stop sequence")
                    _isTryingConnectionLiveData.postValue(false)
                    return@launch
                }
                
                Log.i(TAG, "Stopping stream... Current streaming state: $currentStreamingState")
                
                // Release MediaProjection FIRST to interrupt any ongoing capture
                streamingMediaProjection?.let { mediaProjection ->
                    mediaProjection.stop()
                    Log.i(TAG, "MediaProjection stopped - this should interrupt audio capture")
                }
                streamingMediaProjection = null
                
                // Stop streaming
                try {
                    if (streamer.isStreamingFlow.value == true) {
                        streamer.stopStream()
                        Log.i(TAG, "Stream stop command sent")
                    }
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
                    streamer.removeBitrateRegulatorController()
                    Log.i(TAG, "Bitrate regulator removed")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove bitrate regulator: ${e.message}")
                }
                
                // Reset audio source to clean state BEFORE closing streamer
                try {
                    val currentVideoSource = streamer.videoInput?.sourceFlow?.value
                    if (currentVideoSource !is ICameraSource) {
                        // We're on RTMP source - reset audio to microphone for clean state
                        Log.i(TAG, "RTMP source detected after stop - resetting audio to microphone")
                        streamer.setAudioSource(MicrophoneSourceFactory())
                    } else {
                        // Camera source - ensure microphone is set
                        Log.i(TAG, "Camera source detected after stop - ensuring microphone audio")
                        streamer.setAudioSource(MicrophoneSourceFactory())
                    }
                    Log.i(TAG, "Audio source reset to microphone after stream stop")
                } catch (e: Exception) {
                    Log.w(TAG, "Error resetting audio source after stop: ${e.message}", e)
                }
                
                // Wait a bit for cleanup
                kotlinx.coroutines.delay(200)

                // Close the streamer
                try {
                    streamer.close()
                    Log.i(TAG, "Streamer closed")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing streamer: ${e.message}", e)
                }
                
                // Wait a moment and check final state
                kotlinx.coroutines.delay(300)
                
                // Clear connection state regardless - this should trigger UI update
                _isTryingConnectionLiveData.postValue(false)
                
                // Force check streaming state after cleanup
                val finalStreamingState = streamer.isStreamingFlow.value
                Log.i(TAG, "Stream stop completed. Final streaming state: $finalStreamingState")
                
                // If somehow still streaming, log it but don't try complex recovery
                if (finalStreamingState == true) {
                    Log.w(TAG, "WARNING: Stream may still be active after stop procedure")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "stopStream failed", e)
                // Force clear state
                streamingMediaProjection?.stop()
                streamingMediaProjection = null
                _isTryingConnectionLiveData.postValue(false)
                BufferVisualizerModel.circularPcmBuffer = null
                bufferVisualizerModel = null
            }
        }
    }
    
    fun setMute(isMuted: Boolean) {
        streamer.audioInput?.isMuted = isMuted
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun switchBackToFront(): Boolean {
        /**
         * If video frame rate is not supported by the new camera, streamer will throw an
         * exception instead of crashing. You can either catch the exception or check if the
         * configuration is valid for the new camera with [Context.isFrameRateSupported].
         */
        val videoSource = streamer.videoInput?.sourceFlow?.value
        if (videoSource is ICameraSource) {
            viewModelScope.launch {
                streamer.toggleBackToFront(application)
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
        val videoSource = streamer.videoInput?.sourceFlow?.value
        if (videoSource is ICameraSource) {
            viewModelScope.launch {
                streamer.setNextCameraId(application)
            }
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleVideoSource(bufferVisualizer: BufferVisualizerView) {
        val videoSource = streamer.videoInput?.sourceFlow?.value
        viewModelScope.launch {
            val nextSource = when (videoSource) {
                is ICameraSource -> {
                    Log.i(TAG, "Switching from Camera to RTMP source")
                    // For ExoPlayer audio capture, check if we have MediaProjection from streaming session
                    val mediaProjection = streamingMediaProjection ?: mediaProjectionHelper.getMediaProjection()
                    if (mediaProjection != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        Log.i(TAG, "Using MediaProjection-based ExoPlayer audio capture")
                        setupExoPlayerWithMediaProjection(bufferVisualizer, mediaProjection)
                    } else {
                        Log.w(TAG, "MediaProjection not available - will be requested when streaming starts")
                        // Set up ExoPlayer without MediaProjection audio for now
                        setupExoPlayerWithoutMediaProjection(bufferVisualizer)
                    }
                }
                else -> {
                    Log.i(TAG, "Switching from RTMP back to Camera source")
                    // Switching back to camera - clean up MediaProjection and buffers
                    bufferVisualizer.stopObserving()
                    BufferVisualizerModel.circularPcmBuffer = null
                    bufferVisualizerModel = null
                    
                    // Don't release streaming MediaProjection here - it's managed by stream lifecycle
                    if (streamingMediaProjection == null) {
                        mediaProjectionHelper.release()
                    }
                    
                    streamer.setVideoSource(CameraSourceFactory())
                    streamer.setAudioSource(MicrophoneSourceFactory())
                }
            }
            Log.i(TAG, "Switch video source completed")
        }
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    fun toggleVideoSourceWithProjection(bufferVisualizer: BufferVisualizerView, mediaProjection: MediaProjection) {
        val videoSource = streamer.videoInput?.sourceFlow?.value
        viewModelScope.launch {
            val nextSource = when (videoSource) {
                is ICameraSource -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        Log.i(TAG, "Using provided MediaProjection for ExoPlayer audio capture")
                        setupExoPlayerWithMediaProjection(bufferVisualizer, mediaProjection)
                    } else {
                        Log.w(TAG, "MediaProjection requires Android 10+ - falling back to complex buffer approach")
                        setupComplexAudioCapture(bufferVisualizer)
                    }
                }
                else -> {
                    // Switching back to camera - clean up MediaProjection and buffers
                    bufferVisualizer.stopObserving()
                    BufferVisualizerModel.circularPcmBuffer = null
                    bufferVisualizerModel = null
                    
                    // Release MediaProjection resources
                    mediaProjectionHelper.release()
                    
                    streamer.setVideoSource(CameraSourceFactory())
                    streamer.setAudioSource(MicrophoneSourceFactory())
                }
            }
            Log.i(TAG, "Switch video source to $nextSource")
        }
    }

    private suspend fun setupExoPlayerWithMediaProjection(bufferVisualizer: BufferVisualizerView, mediaProjection: MediaProjection) {
        // Create ExoPlayer for video
        val exoPlayerInstance = ExoPlayer.Builder(application).build()
        val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(application)
        ).createMediaSource(mediaItem)
        
        exoPlayerInstance.setMediaSource(mediaSource)
        exoPlayerInstance.volume = 0f  // Normal volume for MediaProjection to capture
        
        // Set video source - ExoPlayer video display
        streamer.setVideoSource(CustomStreamPackSourceInternal.Factory(exoPlayerInstance))
        
        // Set audio source - try MediaProjection, fallback to microphone if it fails
        try {
            streamer.setAudioSource(MediaProjectionAudioSourceFactory(mediaProjection))
            Log.i(TAG, "MediaProjection audio source configured for ExoPlayer")
        } catch (e: Exception) {
            Log.w(TAG, "MediaProjection audio source failed, using microphone fallback: ${e.message}")
            streamer.setAudioSource(MicrophoneSourceFactory())
        }
        
        Log.i(TAG, "ExoPlayer with MediaProjection setup completed")
    }

    /**
     * Set up ExoPlayer without MediaProjection - uses microphone for audio.
     * MediaProjection will be configured when streaming starts.
     */
    private suspend fun setupExoPlayerWithoutMediaProjection(bufferVisualizer: BufferVisualizerView) {
        // Create ExoPlayer for video
        val exoPlayerInstance = ExoPlayer.Builder(application).build()
        val mediaItem = MediaItem.fromUri("rtmp://localhost:1935/publish/live")
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(application)
        ).createMediaSource(mediaItem)
        
        exoPlayerInstance.setMediaSource(mediaSource)
        exoPlayerInstance.volume = 0f  // Mute for now
        
        // Set video source - ExoPlayer video display
        streamer.setVideoSource(CustomStreamPackSourceInternal.Factory(exoPlayerInstance))
        
        // Use microphone for now - MediaProjection audio will be set when streaming starts
        streamer.setAudioSource(MicrophoneSourceFactory())
        
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

                        streamer.setVideoSource(CustomStreamPackSourceInternal.Factory(exoPlayerInstance))
                        streamer.setAudioSource(CustomAudioInput3.Factory(
                            audioRecordWrapper,
                            bufferVisualizerModel as BufferVisualizerModel
                        ))
                    } ?: Log.i(TAG, "Audio is disabled")
                }
            }
    }

    val isCameraSource = streamer.videoInput?.sourceFlow?.map { it is ICameraSource }?.asLiveData()

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
        val videoSource = streamer.videoInput?.sourceFlow?.value ?: return
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
