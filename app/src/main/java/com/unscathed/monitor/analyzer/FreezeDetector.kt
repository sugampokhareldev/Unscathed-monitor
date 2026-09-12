package com.unscathed.monitor.analyzer

import kotlin.math.abs

/** Downsampled grayscale frame. Kept Android-free so the diff logic is unit-testable. */
class LumaGrid(val width: Int, val height: Int, val luma: IntArray) {
    init {
        require(luma.size == width * height) { "luma size ${luma.size} != ${width}x$height" }
    }

    companion object {
        const val WIDTH = 80
        const val HEIGHT = 45
    }
}

/**
 * Screen regions to ignore when measuring motion, as fractions of each edge (0.1 = 10%).
 * Use it to exclude UI that animates on its own (timers, counters, chat) so a frozen
 * world behind a ticking clock still reads as frozen.
 */
data class RegionMask(
    val top: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f,
    val right: Float = 0f,
) {
    fun includes(x: Int, y: Int, width: Int, height: Int): Boolean {
        val fx = (x + 0.5f) / width
        val fy = (y + 0.5f) / height
        return fy >= top && fy <= 1f - bottom && fx >= left && fx <= 1f - right
    }
}

data class FreezeReading(
    /** Fraction (0..1) of unmasked cells whose brightness changed noticeably since the last frame. */
    val changedFraction: Double,
    val moving: Boolean,
    val stillForMs: Long,
    val lastChangeAtMs: Long,
)

/**
 * Compares consecutive frames and tracks how long the screen has been still.
 * A single still frame means nothing; [FreezeReading.stillForMs] is what the state machine uses.
 */
class FreezeDetector(private val pixelDelta: Int = 12) {
    private var previous: LumaGrid? = null
    private var lastChangeAtMs: Long? = null

    fun update(
        grid: LumaGrid,
        nowMs: Long,
        mask: RegionMask = RegionMask(),
        movingFraction: Double = 0.004,
    ): FreezeReading {
        val prev = previous
        previous = grid

        if (prev == null || prev.width != grid.width || prev.height != grid.height) {
            lastChangeAtMs = nowMs
            return FreezeReading(1.0, moving = true, stillForMs = 0, lastChangeAtMs = nowMs)
        }

        var counted = 0
        var changed = 0
        for (y in 0 until grid.height) {
            for (x in 0 until grid.width) {
                if (!mask.includes(x, y, grid.width, grid.height)) continue
                val i = y * grid.width + x
                counted++
                if (abs(grid.luma[i] - prev.luma[i]) > pixelDelta) changed++
            }
        }

        val fraction = if (counted == 0) 0.0 else changed.toDouble() / counted
        val moving = fraction >= movingFraction
        if (moving) lastChangeAtMs = nowMs
        val since = lastChangeAtMs ?: nowMs.also { lastChangeAtMs = it }
        return FreezeReading(fraction, moving, nowMs - since, since)
    }

    fun reset() {
        previous = null
        lastChangeAtMs = null
    }
}
