package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaMixer
import com.haishinkit.media.MediaOutputDataSource
import java.lang.ref.WeakReference

/**
 * Custom view that displays video content of a MediaMixer object using SurfaceView.
 * Mimics HkSurfaceView but exposes the underlying Surface.
 */
class MyRtmpSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : SurfaceView(context, attrs, defStyleAttr, defStyleRes) {
    var dataSource: WeakReference<MediaOutputDataSource>? = null
        set(value) {
            field = value
            pixelTransform.screen = value?.get()?.screen
        }

    var videoGravity: VideoGravity
        get() = pixelTransform.videoGravity
        set(value) { pixelTransform.videoGravity = value }

    var frameRate: Int
        get() = pixelTransform.frameRate
        set(value) { pixelTransform.frameRate = value }

    var videoEffect: VideoEffect
        get() = pixelTransform.videoEffect
        set(value) { pixelTransform.videoEffect = value }

    private val pixelTransform: PixelTransform by lazy { PixelTransform.create(context) }

    init {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                pixelTransform.imageExtent = Size(width, height)
                pixelTransform.surface = holder.surface
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                pixelTransform.imageExtent = Size(width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pixelTransform.surface = null
            }
        })
    }

    override fun setBackgroundColor(color: Int) {
        pixelTransform.backgroundColor = color
    }

    fun getSurface(): Surface? {
        return holder.surface
    }
    
    fun setSurface(surface: Surface) {
        pixelTransform.surface = surface
    }

    fun append(buffer: MediaBuffer) {
        // Implement frame rendering if needed
    }
}
