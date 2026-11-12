package com.youramaryllis.holoscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Android pipeline (Kotlin resize + ONNX Runtime) must give the same
 * embedding as Python (PIL resize + the same ONNX model) for the same image,
 * or the phone's queries won't line up with the index built on the laptop.
 * Fixtures come from ml/export_android.py. They are card images, so they aren't
 * in the public repo; without them (or without the model) the test is skipped.
 */
class ParityTest {
    private val dir = javaClass.classLoader!!.getResource("parity/reference.txt")?.let { File(it.toURI()).parentFile }
    private val model = File("src/main/assets/embedder.onnx")

    @Test
    fun kotlinPipelineMatchesPython() {
        assumeTrue("parity fixtures not generated (ml/export_android.py)", dir != null)
        assumeTrue("model missing (scripts/fetch_models.sh)", model.exists())
        Embedder(model.absolutePath, useXnnpack = false).use { embedder ->
            File(dir, "reference.txt").readLines().filter { it.isNotBlank() }.forEach { line ->
                val parts = line.trim().split(" ")
                val id = parts[0]
                val ref = parts.drop(1).map { it.toFloat() }.toFloatArray()
                val (px, w, h) = readRgb(File(dir, "$id.rgb"))
                val resized = Preprocess.resize(px, w, h, Preprocess.WIDTH, Preprocess.HEIGHT)
                val emb = embedder.embed(Preprocess.toInput(resized))
                assertEquals(ref.size, emb.size)
                val cos = ref.indices.sumOf { (ref[it] * emb[it]).toDouble() }
                println("$id cosine vs python = $cos")
                assertTrue("$id: cosine $cos", cos > 0.995)
            }
        }
    }

    /** "<w> <h>\n" + raw RGB bytes -> ARGB pixels. */
    private fun readRgb(f: File): Triple<IntArray, Int, Int> {
        val bytes = f.readBytes()
        val nl = bytes.indexOf('\n'.code.toByte())
        val (w, h) = String(bytes, 0, nl).split(" ").map { it.toInt() }
        val px = IntArray(w * h) { i ->
            val o = nl + 1 + i * 3
            (0xFF shl 24) or ((bytes[o].toInt() and 0xFF) shl 16) or
                ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF)
        }
        return Triple(px, w, h)
    }

    @Test
    fun halfFloatDecoding() {
        assertEquals(1.0f, CardIndex.halfToFloat(0x3C00.toShort()))
        assertEquals(-2.0f, CardIndex.halfToFloat(0xC000.toShort()))
        assertEquals(0.5f, CardIndex.halfToFloat(0x3800.toShort()))
        assertEquals(5.9604645E-8f, CardIndex.halfToFloat(0x0001.toShort()))
    }
}
