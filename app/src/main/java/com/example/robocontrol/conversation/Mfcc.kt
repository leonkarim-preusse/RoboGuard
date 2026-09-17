package com.example.robocontrol.conversation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mel-frequency cepstral coefficients (MFCCs) for one audio frame, in plain Kotlin (no library).
 *
 * MFCCs describe the short-term spectral shape of a sound, i.e. roughly "what the vocal tract looked like" in the
 * last 25 ms. They do not contain the words; a sequence of them is still speech-derived data, so callers keep them
 * only in a short ring buffer and never log or store them.
 *
 * Pipeline per frame: pre-emphasis → Hamming window → FFT power spectrum → triangular mel filter bank → log → DCT-II.
 * Coefficient 0 (overall loudness) is dropped, so a speaker getting louder or quieter is not a "change".
 *
 * Not thread-safe: reuses internal buffers. One instance per audio thread.
 */
class Mfcc(
    val sampleRate: Int = 16_000,
    val frameLength: Int = 400,      // 25 ms at 16 kHz
    val fftSize: Int = 512,
    private val melBands: Int = 26,
    /** Number of coefficients returned (c1 … c[coefficients]). */
    val coefficients: Int = 12,
    private val minHz: Double = 100.0,
    private val maxHz: Double = 7_600.0,
    private val preEmphasis: Double = 0.97
) {
    init {
        require(fftSize >= frameLength && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two ≥ frameLength" }
    }

    private val window = DoubleArray(frameLength) { 0.54 - 0.46 * cos(2 * PI * it / (frameLength - 1)) }
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val melEnergies = DoubleArray(melBands)
    private val filters: Array<DoubleArray> = buildFilterBank()
    private val dct = Array(coefficients) { n ->
        DoubleArray(melBands) { k -> cos(PI * (n + 1) * (k + 0.5) / melBands) }
    }

    /**
     * Computes the MFCCs of [frame] (samples scaled to −1…1, length [frameLength]) into [out] (length [coefficients]).
     * @return the frame's energy in dBFS (−∞ for digital silence), used for speech/silence decisions
     */
    fun compute(frame: DoubleArray, out: DoubleArray): Double {
        var sumSquares = 0.0
        var previous = 0.0
        for (i in 0 until fftSize) {
            if (i < frameLength) {
                val x = frame[i]
                sumSquares += x * x
                re[i] = (x - preEmphasis * previous) * window[i]
                previous = x
            } else {
                re[i] = 0.0
            }
            im[i] = 0.0
        }
        fft(re, im)

        for (b in 0 until melBands) {
            val weights = filters[b]
            var e = 0.0
            for (k in weights.indices) {
                val w = weights[k]
                if (w != 0.0) e += w * (re[k] * re[k] + im[k] * im[k])
            }
            melEnergies[b] = ln(max(e, 1e-12))
        }
        for (n in 0 until coefficients) {
            var c = 0.0
            val basis = dct[n]
            for (k in 0 until melBands) c += basis[k] * melEnergies[k]
            out[n] = c
        }
        val meanSquare = sumSquares / frameLength
        return if (meanSquare <= 0.0) Double.NEGATIVE_INFINITY else 10 * log10(meanSquare)
    }

    private fun buildFilterBank(): Array<DoubleArray> {
        fun hzToMel(hz: Double) = 2595.0 * log10(1 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1)
        val bins = fftSize / 2 + 1
        val lowMel = hzToMel(minHz)
        val highMel = hzToMel(maxHz)
        val centers = DoubleArray(melBands + 2) { i ->
            floor(melToHz(lowMel + (highMel - lowMel) * i / (melBands + 1)) * fftSize / sampleRate)
        }
        return Array(melBands) { b ->
            val left = centers[b]
            val center = centers[b + 1]
            val right = centers[b + 2]
            DoubleArray(bins) { k ->
                when {
                    k < left || k > right -> 0.0
                    k <= center -> if (center == left) 1.0 else (k - left) / (center - left)
                    else -> if (right == center) 1.0 else (right - k) / (right - center)
                }
            }
        }
    }

    private companion object {
        /** In-place iterative radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wRe = cos(angle)
                val wIm = sin(angle)
                var start = 0
                while (start < n) {
                    var curRe = 1.0
                    var curIm = 0.0
                    for (k in 0 until len / 2) {
                        val a = start + k
                        val b = a + len / 2
                        val tRe = re[b] * curRe - im[b] * curIm
                        val tIm = re[b] * curIm + im[b] * curRe
                        re[b] = re[a] - tRe
                        im[b] = im[a] - tIm
                        re[a] += tRe
                        im[a] += tIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    start += len
                }
                len = len shl 1
            }
        }

        @Suppress("unused")
        fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
    }
}
