package com.unscathed.monitor.util

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m.toString().padStart(2, '0')}m"
        m > 0 -> "${m}m ${s.toString().padStart(2, '0')}s"
        else -> "${s}s"
    }
}

fun formatAgo(ms: Long): String = if (ms < 1500) "just now" else "${formatDuration(ms)} ago"

fun formatClock(wallMs: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wallMs))

fun formatDateTime(wallMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(wallMs))

fun Bitmap.toJpeg(maxWidth: Int = 1280, quality: Int = 75): ByteArray {
    val scaled = if (width > maxWidth) {
        Bitmap.createScaledBitmap(this, maxWidth, (height * maxWidth / width.toFloat()).roundToInt(), true)
    } else {
        this
    }
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== this) scaled.recycle()
        out.toByteArray()
    }
}
