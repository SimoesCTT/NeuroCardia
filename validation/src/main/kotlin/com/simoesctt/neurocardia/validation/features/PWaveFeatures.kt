package com.simoesctt.neurocardia.validation.features

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * P-wave features derived from the raw ECG signal.
 *
 * The P wave is the atrial depolarization. It is absent in atrial
 * fibrillation and present in sinus rhythms. It carries information
 * about atrial activity that the RR series does not.
 *
 * Three features are provided:
 *
 *   pWaveEnergy      -- mean absolute amplitude of the P-wave window
 *                       across all beats
 *   prVariability    -- standard deviation of the P-to-R interval
 *                       across all beats, in milliseconds
 *   fibrillatoryAmp  -- RMS amplitude of the 4-10 Hz band in the TQ
 *                       segment, averaged across all beats
 *
 * All three are computed from a bandpassed version of the signal. The
 * methods are approximate; they are intended for comparative analysis
 * across recordings, not for clinical P-wave delineation.
 */
object PWaveFeatures {

    /**
     * P-wave energy. Measures the mean absolute amplitude of the
     * pre-QRS window (typically 60-200 ms before each R-peak) after
     * bandpassing the signal to 0.5-15 Hz.
     *
     * Low values are consistent with AF (absent P waves); high values
     * with sinus rhythm.
     */
    fun pWaveEnergy(
        samples: DoubleArray,
        sampleRate: Double,
        rPeaks: List<Int>
    ): Double {
        if (rPeaks.isEmpty() || samples.isEmpty()) return Double.NaN

        // Bandpass 0.5-15 Hz.
        val filtered = bandpass(samples, sampleRate, 0.5, 15.0)

        // For each beat, take the window 60-200 ms before the R-peak.
        val windowPreMs = 200.0
        val windowPostMs = 60.0
        val preSamples = (windowPreMs / 1000.0 * sampleRate).toInt()
        val postSamples = (windowPostMs / 1000.0 * sampleRate).toInt()

        var total = 0.0
        var count = 0
        for (r in rPeaks) {
            val start = (r - preSamples).coerceAtLeast(0)
            val end = (r - postSamples).coerceAtLeast(start + 1)
            if (end > samples.size) continue
            var sum = 0.0
            for (i in start until end) sum += abs(filtered[i])
            total += sum / (end - start)
            count++
        }
        return if (count == 0) Double.NaN else total / count
    }

    /**
     * PR interval variability. Approximates the P-to-R interval by
     * finding the largest peak in the 60-200 ms pre-QRS window and
     * measuring the time from that peak to the R-peak. Returns the
     * standard deviation across beats, in milliseconds.
     *
     * In sinus rhythm, the PR interval is stable and the SD is small.
     * In AF, the P wave is absent, so the "peak" in the pre-QRS
     * window is noise and the variability is high.
     */
    fun prVariability(
        samples: DoubleArray,
        sampleRate: Double,
        rPeaks: List<Int>
    ): Double {
        if (rPeaks.size < 5) return Double.NaN

        val filtered = bandpass(samples, sampleRate, 0.5, 15.0)
        val windowPreMs = 200.0
        val windowPostMs = 60.0
        val preSamples = (windowPreMs / 1000.0 * sampleRate).toInt()
        val postSamples = (windowPostMs / 1000.0 * sampleRate).toInt()

        val intervals = ArrayList<Double>(rPeaks.size)
        for (r in rPeaks) {
            val start = (r - preSamples).coerceAtLeast(0)
            val end = (r - postSamples).coerceAtLeast(start + 1)
            if (end > samples.size) continue

            // Find the local maximum in the window. For a real P
            // wave, this is the P peak. For AF, it's noise.
            var bestIdx = start
            var bestVal = abs(filtered[start])
            for (i in start until end) {
                val v = abs(filtered[i])
                if (v > bestVal) { bestVal = v; bestIdx = i }
            }

            // P-to-R interval in milliseconds.
            intervals.add((r - bestIdx).toDouble() / sampleRate * 1000.0)
        }

        if (intervals.size < 5) return Double.NaN
        val mean = intervals.average()
        val varSum = intervals.sumOf { (it - mean) * (it - mean) }
        return sqrt(varSum / (intervals.size - 1))
    }

    /**
     * Fibrillatory wave amplitude. Measures the RMS amplitude of the
     * 4-10 Hz band in the TQ segment (the interval between the end of
     * the T wave and the next P wave).
     *
     * High values are consistent with AF (fibrillatory waves in the
     * baseline); low values with sinus rhythm.
     */
    fun fibrillatoryAmplitude(
        samples: DoubleArray,
        sampleRate: Double,
        rPeaks: List<Int>
    ): Double {
        if (rPeaks.size < 5 || samples.isEmpty()) return Double.NaN

        // Bandpass 4-10 Hz to isolate fibrillatory waves.
        val filtered = bandpass(samples, sampleRate, 4.0, 10.0)

        // TQ segment: from 200 ms after R to 350 ms after R.
        val tqStartMs = 200.0
        val tqEndMs = 350.0
        val tqStartOffset = (tqStartMs / 1000.0 * sampleRate).toInt()
        val tqEndOffset = (tqEndMs / 1000.0 * sampleRate).toInt()

        var total = 0.0
        var count = 0
        for (i in 0 until rPeaks.size - 1) {
            val r = rPeaks[i]
            val nextR = rPeaks[i + 1]
            // TQ window ends before the next QRS.
            val start = (r + tqStartOffset).coerceAtMost(nextR - 1)
            val end = (r + tqEndOffset).coerceAtMost(nextR - 1)
            if (end <= start) continue
            var sumSq = 0.0
            for (j in start until end) sumSq += filtered[j] * filtered[j]
            total += sqrt(sumSq / (end - start))
            count++
        }
        return if (count == 0) Double.NaN else total / count
    }

    /**
     * Compute all three P-wave features at once.
     */
    fun computeAll(
        samples: DoubleArray,
        sampleRate: Double,
        rPeaks: List<Int>
    ): Map<String, Double> {
        return mapOf(
            "pwave_energy"     to pWaveEnergy(samples, sampleRate, rPeaks),
            "pwave_pr_var"     to prVariability(samples, sampleRate, rPeaks),
            "pwave_fib_amp"    to fibrillatoryAmplitude(samples, sampleRate, rPeaks)
        )
    }

    // ─── Signal processing helpers ──────────────────────────────────────

    private fun bandpass(
        x: DoubleArray,
        fs: Double,
        low: Double,
        high: Double
    ): DoubleArray {
        val lp = biquadLowpass(x, high, fs)
        return biquadHighpass(lp, low, fs)
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
}
