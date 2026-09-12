package com.unscathed.monitor.analyzer

import android.graphics.Bitmap

/**
 * Shrinks a captured frame to the tiny grayscale grid the motion and fingerprint checks work on.
 *
 * 80x45 is small enough to diff thousands of times without touching the battery, and still large
 * enough that a character moving on screen changes several cells.
 */
fun Bitmap.toLumaGrid(width: Int = LumaGrid.WIDTH, height: Int = LumaGrid.HEIGHT): LumaGrid {
    val scaled = Bitmap.createScaledBitmap(this, width, height, true)
    val pixels = IntArray(width * height)
    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
    if (scaled !== this) scaled.recycle()

    val luma = IntArray(pixels.size)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        // Rec. 601 luma, integer arithmetic.
        luma[i] = (r * 299 + g * 587 + b * 114) / 1000
    }
    return LumaGrid(width, height, luma)
}
