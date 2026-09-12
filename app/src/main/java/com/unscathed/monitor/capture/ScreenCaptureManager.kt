package com.unscathed.monitor.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * @param frameStampMs elapsedRealtime when the compositor delivered this frame. Android only
 * produces frames when something on screen changes, so an unchanged stamp means an unchanged screen.
 */
class CapturedFrame(val bitmap: Bitmap, val frameStampMs: Long)

/**
 * Mirrors the screen into an ImageReader and hands out the most recent frame on demand.
 *
 * We always hold on to the latest Image rather than copying every frame. A static screen
 * sends no new frames at all, so asking "give me a new frame" would block forever exactly
 * when the game freezes.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val scale: Float,
    private val onStopped: () -> Unit,
) {
    private val thread = HandlerThread("ScreenCapture").apply { start() }
    private val handler = Handler(thread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var heldImage: Image? = null
    private var heldStampMs = 0L
    private var cached: CapturedFrame? = null
    private var width = 0
    private var height = 0

    @Volatile private var released = false

    /** Must be called after the service is in the foreground with type mediaProjection. */
    fun start(resultCode: Int, data: Intent) {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection was not granted")

        // Android 14+ requires the callback to be registered before createVirtualDisplay.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { releaseSurfaces() }
                if (!released) onStopped()
            }
        }, handler)
        projection = mp

        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            try {
                ensureDisplay()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        failure?.let { throw it }
    }

    suspend fun grabFrame(): CapturedFrame? = suspendCancellableCoroutine { cont ->
        val posted = handler.post {
            val frame = try {
                ensureDisplay()
                val c = cached
                if (c != null && c.frameStampMs == heldStampMs) {
                    c
                } else {
                    heldImage?.let { img -> CapturedFrame(img.toBitmap(), heldStampMs).also { cached = it } }
                }
            } catch (t: Throwable) {
                Timber.w(t, "grabFrame failed")
                null
            }
            if (cont.isActive) cont.resume(frame)
        }
        if (!posted && cont.isActive) cont.resume(null)
    }

    fun release() {
        released = true
        handler.post {
            releaseSurfaces()
            runCatching { projection?.stop() }
            projection = null
            thread.quitSafely()
        }
    }

    /** Creates the virtual display, or resizes it when the device rotates. Runs on [handler]. */
    private fun ensureDisplay() {
        val mp = projection ?: return
        val metrics = realMetrics()
        val w = even(metrics.widthPixels * scale)
        val h = even(metrics.heightPixels * scale)
        if (virtualDisplay != null && w == width && h == height) return

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        newReader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)

        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = mp.createVirtualDisplay(
                "UnscathedMonitor", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, handler,
            )
        } else {
            // A projection may only create one virtual display on Android 14+, so resize in place.
            vd.resize(w, h, metrics.densityDpi)
            vd.setSurface(newReader.surface)
        }

        heldImage?.close()
        heldImage = null
        cached = null
        reader?.close()
        reader = newReader
        width = w
        height = h
        Timber.i("Capture surface %dx%d", w, h)
    }

    private fun onImageAvailable(r: ImageReader) {
        if (r !== reader) return
        val img = try {
            r.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        heldImage?.close()
        heldImage = img
        heldStampMs = SystemClock.elapsedRealtime()
    }

    private fun releaseSurfaces() {
        heldImage?.close()
        heldImage = null
        cached = null
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
    }

    private fun realMetrics(): DisplayMetrics = realDisplayMetrics(context)

    private fun even(v: Float): Int = (v.roundToInt() / 2 * 2).coerceAtLeast(2)

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val needed = rowStride * height

        // Some drivers leave off the padding after the last row; pad the buffer ourselves.
        val source = if (buffer.remaining() >= needed) buffer else ByteBuffer.allocate(needed).apply {
            put(buffer)
            rewind()
        }

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(source)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }
}

/** Full physical screen size in the current rotation. Also used for accessibility tap coordinates. */
fun realDisplayMetrics(context: Context): DisplayMetrics {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    return DisplayMetrics().also {
        @Suppress("DEPRECATION")
        display.getRealMetrics(it)
    }
}
