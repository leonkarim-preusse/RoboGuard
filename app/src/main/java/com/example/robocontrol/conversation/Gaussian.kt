package com.example.robocontrol.conversation

import kotlin.math.ln

/**
 * A full-covariance Gaussian fitted to a block of feature frames, plus the two comparisons change detection needs:
 * the symmetric KL divergence (KL2) and ΔBIC.
 *
 * Only the summary (mean, covariance) is kept, never the frames. Plain Kotlin, no library.
 *
 * References: KL2 as used for speaker change detection by Siegler et al. (1997); ΔBIC by Chen & Gopalakrishnan (1998);
 * the two-pass combination (distance proposes, BIC confirms) by Delacourt & Wellekens (2000), "DISTBIC".
 */
class Gaussian private constructor(
    val dim: Int,
    val count: Int,
    val mean: DoubleArray,
    /** Row-major d×d covariance (maximum likelihood, plus [REGULARIZATION] on the diagonal). */
    val covariance: DoubleArray,
    /** Lower Cholesky factor of [covariance], row-major. */
    private val cholesky: DoubleArray,
    val logDet: Double
) {
    /** Solves covariance · x = b (b is not modified). */
    fun solve(b: DoubleArray): DoubleArray {
        val y = DoubleArray(dim)
        for (i in 0 until dim) {
            var s = b[i]
            for (k in 0 until i) s -= cholesky[i * dim + k] * y[k]
            y[i] = s / cholesky[i * dim + i]
        }
        val x = DoubleArray(dim)
        for (i in dim - 1 downTo 0) {
            var s = y[i]
            for (k in i + 1 until dim) s -= cholesky[k * dim + i] * x[k]
            x[i] = s / cholesky[i * dim + i]
        }
        return x
    }

    /** tr(this⁻¹ · other.covariance). */
    private fun traceInverseTimes(other: Gaussian): Double {
        var trace = 0.0
        val column = DoubleArray(dim)
        for (j in 0 until dim) {
            for (i in 0 until dim) column[i] = other.covariance[i * dim + j]
            trace += solve(column)[j]
        }
        return trace
    }

    companion object {
        const val REGULARIZATION = 1e-3

        /**
         * Fits a Gaussian to [frameCount] frames of dimension [dim] stored consecutively in [frames] starting at
         * frame index [firstFrame], where frame i occupies [frames] at ((firstFrame + i) mod capacity) · dim, i.e. a
         * ring buffer of `frames.size / dim` frames. Returns null if the covariance is not positive definite.
         */
        fun fit(frames: DoubleArray, dim: Int, firstFrame: Int, frameCount: Int): Gaussian? {
            require(frameCount > 1)
            val capacity = frames.size / dim
            val mean = DoubleArray(dim)
            for (f in 0 until frameCount) {
                val base = ((firstFrame + f) % capacity) * dim
                for (i in 0 until dim) mean[i] += frames[base + i]
            }
            for (i in 0 until dim) mean[i] /= frameCount.toDouble()

            val cov = DoubleArray(dim * dim)
            val centered = DoubleArray(dim)
            for (f in 0 until frameCount) {
                val base = ((firstFrame + f) % capacity) * dim
                for (i in 0 until dim) centered[i] = frames[base + i] - mean[i]
                for (i in 0 until dim) {
                    val ci = centered[i]
                    for (k in 0..i) cov[i * dim + k] += ci * centered[k]
                }
            }
            for (i in 0 until dim) {
                for (k in 0..i) {
                    val v = cov[i * dim + k] / frameCount
                    cov[i * dim + k] = v
                    cov[k * dim + i] = v
                }
                cov[i * dim + i] += REGULARIZATION
            }

            val chol = DoubleArray(dim * dim)
            var logDet = 0.0
            for (i in 0 until dim) {
                for (k in 0..i) {
                    var s = cov[i * dim + k]
                    for (m in 0 until k) s -= chol[i * dim + m] * chol[k * dim + m]
                    if (i == k) {
                        if (s <= 0.0) return null
                        val d = kotlin.math.sqrt(s)
                        chol[i * dim + i] = d
                        logDet += 2 * ln(d)
                    } else {
                        chol[i * dim + k] = s / chol[k * dim + k]
                    }
                }
            }
            return Gaussian(dim, frameCount, mean, cov, chol, logDet)
        }

        /**
         * Symmetric Kullback-Leibler divergence between two Gaussians:
         * ½·tr(Σa⁻¹Σb + Σb⁻¹Σa − 2I) + ½·(μa−μb)ᵀ(Σa⁻¹ + Σb⁻¹)(μa−μb). 0 = identical; grows with difference.
         */
        fun kl2(a: Gaussian, b: Gaussian): Double {
            val d = a.dim
            val diff = DoubleArray(d) { a.mean[it] - b.mean[it] }
            val sa = a.solve(diff)
            val sb = b.solve(diff)
            var quadratic = 0.0
            for (i in 0 until d) quadratic += diff[i] * (sa[i] + sb[i])
            return 0.5 * (a.traceInverseTimes(b) + b.traceInverseTimes(a) - 2 * d) + 0.5 * quadratic
        }

        /**
         * ΔBIC for "one Gaussian for both blocks" versus "one per block":
         * ½·(N·log|Σ| − N₁·log|Σ₁| − N₂·log|Σ₂|) − λ·½·(d + ½·d·(d+1))·log N.
         * Positive means two separate distributions explain the audio better, i.e. a change between the blocks.
         * [lambda] trades misses (higher) against false alarms (lower); 1.0 is the theoretical value.
         */
        fun deltaBic(whole: Gaussian, first: Gaussian, second: Gaussian, lambda: Double): Double =
            bicGain(whole, first, second) - lambda * bicPenalty(whole.dim, whole.count)

        /** The likelihood part of ΔBIC (independent of λ). */
        fun bicGain(whole: Gaussian, first: Gaussian, second: Gaussian): Double =
            0.5 * (whole.count * whole.logDet - first.count * first.logDet - second.count * second.logDet)

        /** The model-size penalty of ΔBIC for λ = 1: ½·(d + ½·d·(d+1))·log N. */
        fun bicPenalty(dim: Int, count: Int): Double {
            val d = dim.toDouble()
            return 0.5 * (d + 0.5 * d * (d + 1)) * ln(count.toDouble())
        }
    }
}
