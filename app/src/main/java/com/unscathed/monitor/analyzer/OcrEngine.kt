package com.unscathed.monitor.analyzer

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device ML Kit OCR (bundled model, so it works offline and needs no Play Services download).
 * Only the center of the frame is read, which is where Roblox shows its dialogs.
 */
class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, region: NormBox = DIALOG_REGION): List<OcrLine> {
        val fw = bitmap.width.toFloat()
        val fh = bitmap.height.toFloat()
        val left = (region.left * fw).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.top * fh).toInt().coerceIn(0, bitmap.height - 1)
        val width = ((region.right - region.left) * fw).toInt().coerceIn(1, bitmap.width - left)
        val height = ((region.bottom - region.top) * fh).toInt().coerceIn(1, bitmap.height - top)

        val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
        try {
            val result = recognizer.process(InputImage.fromBitmap(crop, 0)).await()
            return result.textBlocks.flatMap { block ->
                block.lines.map { line ->
                    val box = line.boundingBox?.let { r ->
                        NormBox(
                            left = (left + r.left) / fw,
                            top = (top + r.top) / fh,
                            right = (left + r.right) / fw,
                            bottom = (top + r.bottom) / fh,
                        )
                    }
                    OcrLine(line.text, box)
                }
            }
        } finally {
            if (crop !== bitmap) crop.recycle()
        }
    }

    fun close() = recognizer.close()

    companion object {
        val DIALOG_REGION = NormBox(left = 0.15f, top = 0.10f, right = 0.85f, bottom = 0.92f)
        val FULL_FRAME = NormBox(left = 0f, top = 0f, right = 1f, bottom = 1f)

        fun inDialogRegion(line: OcrLine): Boolean {
            val box = line.box ?: return true
            val r = DIALOG_REGION
            return box.centerX in r.left..r.right && box.centerY in r.top..r.bottom
        }
    }
}
