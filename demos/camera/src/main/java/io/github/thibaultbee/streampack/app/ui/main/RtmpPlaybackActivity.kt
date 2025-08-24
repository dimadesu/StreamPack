package io.github.thibaultbee.streampack.app.ui.main

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.haishinkit.rtmp.RtmpStreamSessionFactory
import com.haishinkit.stream.StreamSession
import com.haishinkit.view.HkSurfaceView
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class RtmpPlaybackActivity : AppCompatActivity() {
    private lateinit var surfaceView: HkSurfaceView
    private var streamSession: StreamSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("RtmpPlaybackActivity", "onCreate called")
        surfaceView = HkSurfaceView(this)
        setContentView(surfaceView)

        // Register RTMP factory (only needs to be done once)
        StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)
        Log.d("RtmpPlaybackActivity", "RTMP factory registered")

        // Build the session for playback
        val rtmpUrl = "rtmp://localhost:1935/publish/mystream" // TODO: Replace with your RTMP URL
        Log.d("RtmpPlaybackActivity", "RTMP URL: $rtmpUrl")
        val uri = Uri.parse(rtmpUrl)
        streamSession = StreamSession.Builder(this, uri).build()
        Log.d("RtmpPlaybackActivity", "StreamSession built: $streamSession")

        // Attach the stream to the view
        surfaceView.dataSource = streamSession?.stream?.let { WeakReference(it) }
        Log.d("RtmpPlaybackActivity", "SurfaceView attached to stream")

        // Start playback (coroutine context required)
        lifecycleScope.launch {
            Log.d("RtmpPlaybackActivity", "Starting playback...")
            val result = streamSession?.connect(StreamSession.Method.PLAYBACK)
            Log.d("RtmpPlaybackActivity", "Playback connect result: $result")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("RtmpPlaybackActivity", "onDestroy called")
        lifecycleScope.launch {
            Log.d("RtmpPlaybackActivity", "Closing stream session...")
            streamSession?.close()
        }
    }
}
