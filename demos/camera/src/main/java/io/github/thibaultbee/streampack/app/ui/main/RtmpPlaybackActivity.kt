package io.github.thibaultbee.streampack.app.ui.main

import android.net.Uri
import android.os.Bundle
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
        surfaceView = HkSurfaceView(this)
        setContentView(surfaceView)

        // Register RTMP factory (only needs to be done once)
        StreamSession.Builder.registerFactory(RtmpStreamSessionFactory)

        // Build the session for playback
        val rtmpUrl = "rtmp://localhost:1935/mystream" // TODO: Replace with your RTMP URL
        val uri = Uri.parse(rtmpUrl)
        streamSession = StreamSession.Builder(this, uri).build()

        // Attach the stream to the view
        surfaceView.dataSource = streamSession?.stream?.let { WeakReference(it) }

        // Start playback (coroutine context required)
        lifecycleScope.launch {
            streamSession?.connect(StreamSession.Method.PLAYBACK)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            streamSession?.close()
        }
    }
}
