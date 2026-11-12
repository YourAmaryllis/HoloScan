package com.youramaryllis.holoscan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/** Card outline on screen: card-shaped (63x88mm), centered a little above middle. */
fun guideRect(viewW: Float, viewH: Float): RectF {
    var w = viewW * 0.78f
    var h = w * 88f / 63f
    if (h > viewH * 0.62f) { h = viewH * 0.62f; w = h * 63f / 88f }
    val cx = viewW / 2f
    val cy = viewH * 0.40f
    return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}

data class ScanResult(
    val matches: List<Match>,
    val input: Bitmap,   // exactly what the model saw (224x308)
    val millis: Long,
)

/**
 * Crops the guide region from each camera frame, embeds it and looks it up.
 * The preview uses FILL_CENTER, so the on-screen guide maps to the frame by
 * the same scale/offset the preview applies.
 */
class CardAnalyzer(
    private val embedder: AtomicReference<Embedder>,
    private val index: CardIndex,
    private val viewSize: AtomicReference<Pair<Int, Int>>,
    private val paused: AtomicBoolean,
    private val onResult: (ScanResult) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        image.use {
            val (vw, vh) = viewSize.get()
            if (paused.get() || vw == 0 || vh == 0) return
            val start = SystemClock.elapsedRealtime()
            val frame = upright(image)
            val crop = cropGuide(frame, vw.toFloat(), vh.toFloat()) ?: return
            val pixels = IntArray(crop.width * crop.height)
            crop.getPixels(pixels, 0, crop.width, 0, 0, crop.width, crop.height)
            val resized = Preprocess.resize(pixels, crop.width, crop.height, Preprocess.WIDTH, Preprocess.HEIGHT)
            val matches = index.search(embedder.get().embed(Preprocess.toInput(resized)), k = 5)
            val input = Bitmap.createBitmap(resized, Preprocess.WIDTH, Preprocess.HEIGHT, Bitmap.Config.ARGB_8888)
            onResult(ScanResult(matches, input, SystemClock.elapsedRealtime() - start))
        }
    }

    private fun upright(image: ImageProxy): Bitmap {
        val bmp = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        if (rot == 0) return bmp
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun cropGuide(frame: Bitmap, vw: Float, vh: Float): Bitmap? {
        val scale = max(vw / frame.width, vh / frame.height)
        val dx = (vw - frame.width * scale) / 2f
        val dy = (vh - frame.height * scale) / 2f
        val g = guideRect(vw, vh)
        val left = ((g.left - dx) / scale).roundToInt().coerceIn(0, frame.width - 1)
        val top = ((g.top - dy) / scale).roundToInt().coerceIn(0, frame.height - 1)
        val right = ((g.right - dx) / scale).roundToInt().coerceIn(left + 1, frame.width)
        val bottom = ((g.bottom - dy) / scale).roundToInt().coerceIn(top + 1, frame.height)
        if (right - left < 32 || bottom - top < 32) return null
        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }
}
