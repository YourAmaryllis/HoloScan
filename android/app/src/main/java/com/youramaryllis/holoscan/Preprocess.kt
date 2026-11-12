package com.youramaryllis.holoscan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a cropped card image into the model input. Pure Kotlin (no Android
 * types) so the JVM unit test can check it against the Python pipeline.
 *
 * Contract (embeddings/<tag>/preprocess.json): RGB, stretched to 224x308 with
 * PIL-style bilinear resampling, scaled to [0,1], CHW. Mean/std and L2
 * normalization are inside the model.
 */
object Preprocess {
    const val WIDTH = 224
    const val HEIGHT = 308

    /** Resize ARGB pixels the way PIL's Image.resize(BILINEAR) does: a triangle
     *  filter whose support widens when shrinking, horizontal pass first, with
     *  8-bit rounding between passes. */
    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val tmp = IntArray(dw * sh)
        pass(src, sw, sh, tmp, dw, horizontal = true)
        val out = IntArray(dw * dh)
        pass(tmp, dw, sh, out, dh, horizontal = false)
        return out
    }

    private fun pass(src: IntArray, sw: Int, sh: Int, dst: IntArray, outSize: Int, horizontal: Boolean) {
        val inSize = if (horizontal) sw else sh
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = filterScale // bilinear filter support is 1.0
        val lines = if (horizontal) sh else sw
        for (o in 0 until outSize) {
            val center = (o + 0.5) * scale
            val lo = max((center - support + 0.5).toInt(), 0)
            val hi = min((center + support + 0.5).toInt(), inSize)
            val weights = DoubleArray(hi - lo) { i ->
                val x = abs((i + lo - center + 0.5) / filterScale)
                if (x < 1.0) 1.0 - x else 0.0
            }
            val total = weights.sum()
            if (total > 0) for (i in weights.indices) weights[i] /= total
            for (line in 0 until lines) {
                var r = 0.0; var g = 0.0; var b = 0.0
                for (i in weights.indices) {
                    val p = if (horizontal) src[line * sw + lo + i] else src[(lo + i) * sw + line]
                    val w = weights[i]
                    r += ((p shr 16) and 0xFF) * w
                    g += ((p shr 8) and 0xFF) * w
                    b += (p and 0xFF) * w
                }
                val px = (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
                if (horizontal) dst[line * outSize + o] = px else dst[o * sw + line] = px
            }
        }
    }

    private fun clamp(v: Double) = v.roundToInt().coerceIn(0, 255)

    /** ARGB pixels (already WIDTH x HEIGHT) -> CHW float RGB in [0,1]. */
    fun toInput(pixels: IntArray): FloatArray {
        val plane = WIDTH * HEIGHT
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = ((p shr 16) and 0xFF) / 255f
            out[plane + i] = ((p shr 8) and 0xFF) / 255f
            out[2 * plane + i] = (p and 0xFF) / 255f
        }
        return out
    }
}
