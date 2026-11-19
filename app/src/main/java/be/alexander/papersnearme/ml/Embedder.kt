package be.alexander.papersnearme.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Runs all-MiniLM-L6-v2 on the phone. Same pooling as pipeline/embedder.py:
 * mean over tokens, then L2-normalise. The text never leaves the device.
 */
class Embedder(modelFile: File, vocabFile: File) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    private val tokenizer = WordPieceTokenizer(vocabFile)
    private val inputNames = session.inputNames

    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val n = ids.size
        val shape = longArrayOf(1, n.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1L }), shape)
            if ("token_type_ids" in inputNames) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), shape)
            }
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (result[0].value as Array<Array<FloatArray>>)[0]
                val dim = hidden[0].size
                val v = FloatArray(dim)
                for (token in hidden) for (i in 0 until dim) v[i] += token[i]
                var norm = 0f
                for (i in 0 until dim) { v[i] /= n; norm += v[i] * v[i] }
                val len = sqrt(norm).coerceAtLeast(1e-12f)
                for (i in 0 until dim) v[i] /= len
                return v
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()
}
