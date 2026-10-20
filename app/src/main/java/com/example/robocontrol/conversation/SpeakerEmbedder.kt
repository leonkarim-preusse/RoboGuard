package com.example.robocontrol.conversation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Computes speaker embeddings with the CAM++ model (`assets/speaker/campplus_en_voxceleb.onnx`, 512 dimensions) via
 * ONNX Runtime. Callers zero the input samples and the returned vector after use. Not thread-safe; call [close] when done.
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
     * The embedding of [samples] (16 kHz mono, −1…1), L2-normalised so that [similarity] is a plain dot product.
     * Needs at least [MIN_SAMPLES]; returns null below that, because short pieces give unreliable vectors.
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

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        const val MODEL_ASSET = "speaker/campplus_en_voxceleb.onnx"

        /** Dimensions of the embedding this model produces. */
        const val DIMENSIONS = 512

        /** Shortest piece of speech that is embedded at all: 1 s at 16 kHz. */
        const val MIN_SAMPLES = 16_000

        private const val MIN_FRAMES = 20
        private const val INPUT = "x"

        /** Scales [v] to length 1, in place, and returns it. A zero vector is left as it is. */
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
