package com.example.robocontrol.conversation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Speech probability per 32 ms chunk with the Silero VAD model (v4, `assets/silero/silero_vad.onnx`), run locally with
 * ONNX Runtime. Keeps a recurrent state across chunks; [reset] clears it. Not thread-safe; call [close] when done.
 * @param context used to load the model from the assets
 * @param sampleRate sample rate in Hz
 */
class SileroVad(context: Context, val sampleRate: Int = 16_000) : AutoCloseable {

    /** Samples per call to [probability]. */
    val chunkSize = CHUNK_16K

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val options: OrtSession.SessionOptions
    private val h = FloatArray(STATE_SIZE)
    private val c = FloatArray(STATE_SIZE)
    private val input = FloatArray(chunkSize)

    init {
        require(sampleRate == 16_000) { "only 16 kHz is set up" }
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(model, options)
    }

    /** Speech probability (0…1) of [samples] (16-bit PCM, exactly [chunkSize] samples). Updates the recurrent state. */
    fun probability(samples: ShortArray): Float {
        require(samples.size == chunkSize)
        for (i in 0 until chunkSize) input[i] = samples[i] / 32768f
        val shapeState = longArrayOf(2, 1, 64)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, chunkSize.toLong())).use { tInput ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(sampleRate.toLong())), longArrayOf(1)).use { tSr ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(h), shapeState).use { tH ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(c), shapeState).use { tC ->
                        session.run(mapOf("input" to tInput, "sr" to tSr, "h" to tH, "c" to tC)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val probability = (result.get(0).value as Array<FloatArray>)[0][0]
                            copyState(result.get(1).value, h)
                            copyState(result.get(2).value, c)
                            input.fill(0f)
                            return probability
                        }
                    }
                }
            }
        }
    }

    /** Forgets the recurrent context (new conversation). */
    fun reset() {
        h.fill(0f)
        c.fill(0f)
    }

    override fun close() {
        reset()
        input.fill(0f)
        session.close()
        options.close()
    }

    /** Copies the model's recurrent state output into [target] for the next chunk. */
    private fun copyState(value: Any, target: FloatArray) {
        @Suppress("UNCHECKED_CAST")
        val state = value as Array<Array<FloatArray>>
        var k = 0
        for (layer in state) for (batch in layer) for (v in batch) target[k++] = v
    }

    private companion object {
        const val MODEL_ASSET = "silero/silero_vad.onnx"
        const val CHUNK_16K = 512
        const val STATE_SIZE = 2 * 1 * 64
    }
}
