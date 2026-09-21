package com.example.robocontrol.conversation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Speech / non-speech per audio chunk with the Silero VAD neural network (v4, MIT license, file
 * `robocontrol/assets/silero/silero_vad.onnx` + `silero_vad_LICENSE.txt`, taken from the android-vad 2.0.10 AAR), run with
 * Microsoft ONNX Runtime entirely on the robot. No network.
 *
 * Answers only "is this speech?" (a probability). It does not recognise words or people. Replaces the loudness-only
 * speech gate, which let claps, doors and other loud noise count as speech.
 *
 * Model I/O (read from the android-vad wrapper's bytecode): inputs `input` float[1, N] (samples −1…1), `sr` int64[1],
 * `h` and `c` float[2, 1, 64] (recurrent state); outputs 0 = probability float[1, 1], 1 = `hn`, 2 = `cn`.
 * At 16 kHz, N = 512 (32 ms). The recurrent state carries ~100 ms of context and is zeroed by [reset].
 *
 * Not thread-safe; one instance per audio thread. [close] when done.
 */
class SileroVad(context: Context, val sampleRate: Int = 16_000) : AutoCloseable {

    /** Samples per call to [probability]. */
    val chunkSize = CHUNK_16K

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val h = FloatArray(STATE_SIZE)
    private val c = FloatArray(STATE_SIZE)
    private val input = FloatArray(chunkSize)

    init {
        require(sampleRate == 16_000) { "only 16 kHz is set up" }
        val model = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
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
    }

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
