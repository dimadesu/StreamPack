
package io.github.thibaultbee.streampack.app.ui.main

import android.content.Context
import android.util.Size
import android.view.Surface
import com.haishinkit.graphics.PixelTransform
import com.haishinkit.graphics.VideoGravity
import com.haishinkit.graphics.effect.VideoEffect
import com.haishinkit.media.MediaBuffer
import com.haishinkit.media.MediaOutputDataSource
import java.lang.ref.WeakReference

/**
 * Headless PixelTransform wrapper for offscreen rendering (no SurfaceView required).
 * Based on com.haishinkit.view.HkSurfaceView, but without the view
 */
class PixelTransformSurface(context: Context) {
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

    private val pixelTransform: PixelTransform = PixelTransform.create(context)

    /**
     * Set the output surface for PixelTransform rendering.
     */
    fun setSurface(surface: Surface?) {
        pixelTransform.surface = surface
    }

    /**
     * Set the image extent (resolution) for rendering.
     */
    fun setImageExtent(size: Size) {
        pixelTransform.imageExtent = size
    }

    /**
     * Set background color for rendering.
     */
    fun setBackgroundColor(color: Int) {
        pixelTransform.backgroundColor = color
    }

    /**
     * Append a buffer for rendering (if needed).
     */
    fun append(buffer: MediaBuffer) {
        // Implement frame rendering if needed
    }
}
