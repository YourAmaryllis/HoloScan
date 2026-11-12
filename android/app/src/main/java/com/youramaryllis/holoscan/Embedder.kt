package com.youramaryllis.holoscan

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** Runs embedder.onnx: [1,3,308,224] RGB in [0,1] -> L2-normalized embedding. */
class Embedder(modelPath: String, useXnnpack: Boolean = true) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            if (useXnnpack) {
                // Faster on phone CPUs; plain CPU execution is the fallback.
                runCatching { addXnnpack(mapOf("intra_op_num_threads" to "4")) }
            }
        }
        session = env.createSession(modelPath, opts)
    }

    fun embed(input: FloatArray): FloatArray {
        val shape = longArrayOf(1, 3, Preprocess.HEIGHT.toLong(), Preprocess.WIDTH.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf("image" to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                return (result[0].value as Array<FloatArray>)[0]
            }
        }
    }

    override fun close() = session.close()
}
