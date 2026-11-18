package com.example.robocontrol.conversation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Computes speaker embeddings with the local CAM++ model. Not thread-safe; call [close] when done.
 * @param context used to load the model from the assets
 */
class SpeakerEmbedder(context: Context) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val options: OrtSession.SessionOptions
    private val fbank = Fbank()

    init {
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(model, options)
    }

    /**
     * Computes the L2-normalised embedding of [samples].
     * @param samples 16 kHz mono audio (−1…1)
     * @param length number of samples to use
     * @return the embedding, or null below one second of audio
     */
    fun embed(samples: FloatArray, length: Int = samples.size): FloatArray? {
        if (length < MIN_SAMPLES) return null
        val features = fbank.compute(samples, length)
        if (features.size < MIN_FRAMES) return null
        fbank.cepstralMeanNormalise(features)
        val flat = FloatArray(features.size * fbank.bands)
        for (t in features.indices) features[t].copyInto(flat, t * fbank.bands)
        try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, features.size.toLong(), fbank.bands.toLong())).use { input ->
                session.run(mapOf(INPUT to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val embedding = (result.get(0).value as Array<FloatArray>)[0].copyOf()
                    return normalise(embedding)
                }
            }
        } finally {
            flat.fill(0f)
            for (row in features) row.fill(0f)
        }
    }

    /** Closes the model session. */
    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        const val MODEL_ASSET = "speaker/campplus_en_voxceleb.onnx"

        const val DIMENSIONS = 512

        const val MIN_SAMPLES = 16_000

        private const val MIN_FRAMES = 20
        private const val INPUT = "x"

        /**
         * Scales [v] to length 1, in place, and returns it. A zero vector is left as it is.
         * @param v vector to scale
         * @return the same vector
         */
        fun normalise(v: FloatArray): FloatArray {
            var sum = 0.0
            for (x in v) sum += x * x
            val norm = sqrt(sum).toFloat()
            if (norm > 0f) for (i in v.indices) v[i] /= norm
            return v
        }

        /**
         * Cosine similarity of two L2-normalised embeddings.
         * @param a first embedding
         * @param b second embedding
         * @return similarity, 1 = identical
         */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum.toFloat()
        }
    }
}
