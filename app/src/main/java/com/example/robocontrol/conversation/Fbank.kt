package com.example.robocontrol.conversation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 80-dimensional log-mel filterbank features in the Kaldi layout, the input of [SpeakerEmbedder]. Per 25 ms frame (10 ms
 * hop): DC removal, pre-emphasis 0.97, Povey window, 512-point FFT, power spectrum, 80 mel filters from 20 Hz to 8 kHz
 * and natural logarithm. [cepstralMeanNormalise] subtracts the mean over time. Not thread-safe.
 */
class Fbank(
    private val sampleRate: Int = 16_000,
    private val frameLength: Int = 400,
    private val frameShift: Int = 160,
    private val fftSize: Int = 512,
    val bands: Int = 80,
    private val lowHz: Double = 20.0,
    private val highHz: Double = 8_000.0,
    private val preEmphasis: Double = 0.97
) {

    /** Povey window: a Hamming-like window raised to 0.85, as Kaldi uses for speech features. */
    private val window = DoubleArray(frameLength) { (0.5 - 0.5 * cos(2 * PI * it / (frameLength - 1))).pow(0.85) }

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val frame = DoubleArray(frameLength)
    private val filters: Array<DoubleArray> = buildFilterBank()

    /** Frames that [compute] will produce for [samples] samples (Kaldi's "snip edges": no partial frame at the end). */
    fun frameCount(samples: Int): Int = if (samples < frameLength) 0 else 1 + (samples - frameLength) / frameShift

    /**
     * Features of [samples] (−1…1; the scale does not matter after mean normalisation) as one row per frame.
     * @return array of [frameCount] rows with [bands] values each
     */
    fun compute(samples: FloatArray, length: Int = samples.size): Array<FloatArray> {
        val frames = frameCount(length)
        val out = Array(frames) { FloatArray(bands) }
        for (t in 0 until frames) {
            val start = t * frameShift
            // Remove the DC offset of this frame.
            var mean = 0.0
            for (i in 0 until frameLength) mean += samples[start + i]
            mean /= frameLength
            for (i in 0 until frameLength) frame[i] = samples[start + i] - mean
            // Pre-emphasis; the first sample uses itself, like Kaldi.
            for (i in frameLength - 1 downTo 1) frame[i] -= preEmphasis * frame[i - 1]
            frame[0] -= preEmphasis * frame[0]

            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until frameLength) re[i] = frame[i] * window[i]
            fft(re, im)

            val row = out[t]
            for (b in 0 until bands) {
                val filter = filters[b]
                var energy = 0.0
                for (k in filter.indices) {
                    if (filter[k] == 0.0) continue
                    energy += filter[k] * (re[k] * re[k] + im[k] * im[k])
                }
                row[b] = ln(max(energy, FLOOR)).toFloat()
            }
        }
        return out
    }

    /** Subtracts each band's mean over time, in place (what the 3D-Speaker pipeline feeds to the model). */
    fun cepstralMeanNormalise(features: Array<FloatArray>) {
        if (features.isEmpty()) return
        for (b in 0 until bands) {
            var sum = 0.0
            for (row in features) sum += row[b]
            val mean = (sum / features.size).toFloat()
            for (row in features) row[b] -= mean
        }
    }

    /** Triangular filters, equally spaced on the mel scale, over the FFT power bins. */
    private fun buildFilterBank(): Array<DoubleArray> {
        fun mel(hz: Double) = 1127.0 * ln(1.0 + hz / 700.0)
        val bins = fftSize / 2 + 1
        val melLow = mel(lowHz)
        val melHigh = mel(highHz)
        val delta = (melHigh - melLow) / (bands + 1)
        val melOfBin = DoubleArray(bins) { mel(it * sampleRate.toDouble() / fftSize) }
        return Array(bands) { b ->
            val left = melLow + b * delta
            val center = left + delta
            val right = center + delta
            DoubleArray(bins) { k ->
                val m = melOfBin[k]
                if (m <= left || m >= right) 0.0
                else max(0.0, min((m - left) / (center - left), (right - m) / (right - center)))
            }
        }
    }

    private companion object {
        /** Kaldi floors the mel energies at the float epsilon before taking the log. */
        val FLOOR = 1.1920929e-7

        /** In-place iterative radix-2 FFT (same as [Mfcc]'s; kept here so the two front-ends stay independent). */
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
    }
}
