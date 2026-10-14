package com.simoesctt.neurocardia.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

object SignalQuality {

    enum class Verdict { GOOD, FAIR, POOR }

    data class Result(
        val snrDb: Double,
        val flatlineFraction: Double,
        val clippingFraction: Double,
        val baselineFraction: Double,
        val score: Double,
        val verdict: Verdict
    ) {
        fun summary(): String = when (verdict) {
            Verdict.GOOD ->
                "Signal quality is good (SNR %.1f dB, score %.2f)."
                    .format(snrDb, score)
            Verdict.FAIR ->
                "Signal quality is fair (SNR %.1f dB, score %.2f). " +
                "Metrics may be affected by noise.".format(snrDb, score)
            Verdict.POOR ->
                "Signal quality is poor (SNR %.1f dB, score %.2f). " +
                "Metric values may be unreliable.".format(snrDb, score)
        }
    }

    fun compute(samples: DoubleArray, sampleRate: Double): Result {
        if (samples.isEmpty() || sampleRate <= 0.0) {
            return Result(0.0, 1.0, 0.0, 0.0, 0.0, Verdict.POOR)
        }
        if (samples.size < sampleRate.toInt()) {
            return Result(0.0, 0.0, 0.0, 0.0, 0.0, Verdict.POOR)
        }

        val mean = samples.average()
        val centered = DoubleArray(samples.size) { samples[it] - mean }

        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        for (v in centered) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = maxV - minV
        if (range < 1e-9) {
            return Result(0.0, 1.0, 0.0, 0.0, 0.0, Verdict.POOR)
        }

        val snrDb = computeSnrDb(centered, sampleRate)
        val flatline = computeFlatlineFraction(centered, range)
        val clipping = computeClippingFraction(centered, minV, maxV, range)
        val baseline = computeBaselineFraction(centered, sampleRate)

        val snrScore = snrToScore(snrDb)
        val flatlineScore = 1.0 - flatline.coerceIn(0.0, 1.0)
        val clippingScore = 1.0 - clipping.coerceIn(0.0, 1.0)
        val baselineScore = baselineToScore(baseline)

        var score = (0.55 * snrScore + 0.15 * flatlineScore +
                     0.10 * clippingScore + 0.20 * baselineScore)
            .coerceIn(0.0, 1.0)

        // Hard floors: a dominant defect caps the composite score.
        if (baseline > 0.7) score = minOf(score, 0.45)
        if (snrDb < 0.0) score = minOf(score, 0.45)
        if (flatline > 0.5) score = minOf(score, 0.30)
        if (flatline > 0.9) score = 0.0

        val verdict = when {
            score >= 0.75 -> Verdict.GOOD
            score >= 0.50 -> Verdict.FAIR
            else -> Verdict.POOR
        }

        return Result(snrDb, flatline, clipping, baseline, score, verdict)
    }

    // ─── SNR ───────────────────────────────────────────────────────────

    /**
     * SNR estimated from QRS-band energy (5-15 Hz) vs high-frequency
     * noise (40-100 Hz). This is the Pan-Tompkins band split, and it
     * cleanly discriminates ECG from noise because the QRS complex has
     * substantial energy in 5-15 Hz while muscle artifact and
     * electrical noise dominate above 40 Hz.
     */
    private fun computeSnrDb(centered: DoubleArray, fs: Double): Double {
        val nyquist = fs / 2.0
        val signal = bandpassRms(centered, fs, 5.0, minOf(15.0, nyquist - 5.0))
        val noise = bandpassRms(centered, fs, minOf(40.0, nyquist - 10.0),
                                minOf(100.0, nyquist - 5.0))
        if (noise < 1e-12) {
            return if (signal < 1e-12) 0.0 else 40.0
        }
        if (signal < 1e-12) return -40.0
        val ratio = signal / noise
        if (ratio < 1e-6) return -40.0
        return (20.0 * log10(ratio)).coerceIn(-40.0, 40.0)
    }

    private fun bandpassRms(x: DoubleArray, fs: Double, low: Double, high: Double): Double {
        if (high <= low) return 0.0
        val lp = biquadLowpass(x, high, fs)
        val bp = biquadHighpass(lp, low, fs)
        var sumSq = 0.0
        for (v in bp) sumSq += v * v
        return sqrt(sumSq / bp.size)
    }

    private fun biquadLowpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 - cosW0) / 2.0
        val b1 = 1.0 - cosW0
        val b2 = (1.0 - cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquadHighpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquad(
        x: DoubleArray,
        b0: Double, b1: Double, b2: Double,
        a1: Double, a2: Double
    ): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0
        var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi
            y2 = y1; y1 = yi
            y[i] = yi
        }
        return y
    }

    private fun snrToScore(snrDb: Double): Double {
        return ((snrDb + 10.0) / 30.0).coerceIn(0.0, 1.0)
    }

    private fun computeFlatlineFraction(x: DoubleArray, range: Double): Double {
        if (x.size < 2) return 0.0
        // Absolute threshold in physical units. The WFDB standard
        // quantizes at 1/gain, so differences below half a
        // quantization step are identical samples, not flatlines.
        // 10 microvolts is well below any physiological ECG
        // variation and well above the quantization floor.
        val epsilon = 1e-5
        var flat = 0
        for (i in 1 until x.size) {
            if (abs(x[i] - x[i-1]) < epsilon) flat++
        }
        return flat.toDouble() / (x.size - 1)
    }

    private fun computeClippingFraction(
        x: DoubleArray,
        minV: Double,
        maxV: Double,
        range: Double
    ): Double {
        if (range <= 0.0) return 0.0
        val nearMin = minV + range * 0.01
        val nearMax = maxV - range * 0.01
        var clipped = 0
        for (v in x) {
            if (v <= nearMin || v >= nearMax) clipped++
        }
        return clipped.toDouble() / x.size
    }

    private fun computeBaselineFraction(x: DoubleArray, fs: Double): Double {
        val lp = biquadLowpass(x, 0.5, fs)
        var lpSumSq = 0.0
        var totalSumSq = 0.0
        for (i in x.indices) {
            lpSumSq += lp[i] * lp[i]
            totalSumSq += x[i] * x[i]
        }
        if (totalSumSq < 1e-20) return 0.0
        return sqrt(lpSumSq / totalSumSq)
    }

    private fun baselineToScore(fraction: Double): Double {
        return (1.0 - (fraction - 0.5) / 0.5).coerceIn(0.0, 1.0)
    }
}
