package com.simoesctt.neurocardia.validation

import com.simoesctt.neurocardia.io.EcgRecord
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Per-beat scalar features extracted from an ECG record. Each feature
 * produces a series with one value per detected beat. These series
 * are fed into the CSI pipeline in place of the RR interval series.
 *
 * The features are:
 *
 *   RR          -- interval between successive R-peaks, milliseconds
 *   AMP         -- signal amplitude at the R-peak, absolute value
 *   QRS_ENERGY  -- integral of squared bandpassed signal over +/-80 ms
 *                  around each R-peak
 *   BEAT_CORR   -- Pearson correlation of the QRS window around
 *                  consecutive R-peaks
 *   QRS_WIDTH   -- number of samples within a fraction of the peak
 *                  amplitude, in the QRS window
 *   HILBERT_DP  -- phase increment between consecutive R-peaks computed
 *                  from a Hilbert transform of the bandpassed signal
 */
enum class Feature(val label: String) {
    RR("RR intervals"),
    AMP("R-peak amplitude"),
    QRS_ENERGY("QRS energy"),
    BEAT_CORR("beat-to-beat correlation"),
    QRS_WIDTH("QRS width"),
    HILBERT_DP("Hilbert phase increment");
}

object FeatureExtractors {

    /**
     * Extract the given feature from the record. Returns a list of
     * values, one per beat (for most features) or one per pair of
     * beats (for BEAT_CORR and HILBERT_DP).
     *
     * Returns an empty list if the feature cannot be computed for the
     * record (too few beats, or missing prerequisites).
     */
    fun extract(record: EcgRecord, feature: Feature): List<Double> {
        val peaks = record.annotations.map { it.sampleIndex }.sorted()
        if (peaks.size < 3) return emptyList()

        return when (feature) {
            Feature.RR -> rrIntervals(peaks, record.sampleRate)
            Feature.AMP -> amplitudes(peaks, record.samples)
            Feature.QRS_ENERGY -> qrsEnergy(peaks, record.samples, record.sampleRate)
            Feature.BEAT_CORR -> beatCorrelation(peaks, record.samples, record.sampleRate)
            Feature.QRS_WIDTH -> qrsWidth(peaks, record.samples, record.sampleRate)
            Feature.HILBERT_DP -> hilbertPhaseIncrements(peaks, record.samples, record.sampleRate)
        }
    }

    // ─── RR intervals ──────────────────────────────────────────────────

    private fun rrIntervals(peaks: List<Int>, fs: Double): List<Double> {
        val out = ArrayList<Double>(peaks.size - 1)
        for (i in 1 until peaks.size) {
            out.add((peaks[i] - peaks[i - 1]) * 1000.0 / fs)
        }
        return out
    }

    // ─── Amplitude ─────────────────────────────────────────────────────

    private fun amplitudes(peaks: List<Int>, samples: DoubleArray): List<Double> {
        val out = ArrayList<Double>(peaks.size)
        for (p in peaks) {
            if (p in samples.indices) out.add(abs(samples[p]))
        }
        return out
    }

    // ─── QRS energy ────────────────────────────────────────────────────

    private fun qrsEnergy(peaks: List<Int>, samples: DoubleArray, fs: Double): List<Double> {
        val halfWindow = (0.040 * fs).toInt().coerceAtLeast(2)
        val out = ArrayList<Double>(peaks.size)
        for (p in peaks) {
            val start = (p - halfWindow).coerceAtLeast(0)
            val end = (p + halfWindow).coerceAtMost(samples.size - 1)
            if (start >= end) continue
            var e = 0.0
            for (i in start..end) e += samples[i] * samples[i]
            out.add(sqrt(e / (end - start + 1)))
        }
        return out
    }

    // ─── Beat-to-beat correlation ──────────────────────────────────────

    private fun beatCorrelation(peaks: List<Int>, samples: DoubleArray, fs: Double): List<Double> {
        val halfWindow = (0.050 * fs).toInt().coerceAtLeast(2)
        val out = ArrayList<Double>(peaks.size - 1)
        for (i in 1 until peaks.size) {
            val a = windowed(samples, peaks[i - 1], halfWindow)
            val b = windowed(samples, peaks[i], halfWindow)
            val n = minOf(a.size, b.size)
            if (n < 4) continue
            out.add(pearsonOf(a, b, n))
        }
        return out
    }

    private fun windowed(samples: DoubleArray, center: Int, halfWindow: Int): DoubleArray {
        val start = (center - halfWindow).coerceAtLeast(0)
        val end = (center + halfWindow).coerceAtMost(samples.size - 1)
        if (start >= end) return DoubleArray(0)
        return samples.copyOfRange(start, end + 1)
    }

    private fun pearsonOf(a: DoubleArray, b: DoubleArray, n: Int): Double {
        var sa = 0.0; var sb = 0.0
        for (i in 0 until n) { sa += a[i]; sb += b[i] }
        val ma = sa / n; val mb = sb / n
        var num = 0.0; var da = 0.0; var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma; val y = b[i] - mb
            num += x * y; da += x * x; db += y * y
        }
        val den = sqrt(da * db)
        return if (den < 1e-12) 0.0 else num / den
    }

    // ─── QRS width ─────────────────────────────────────────────────────

    private fun qrsWidth(peaks: List<Int>, samples: DoubleArray, fs: Double): List<Double> {
        val halfWindow = (0.060 * fs).toInt().coerceAtLeast(2)
        val out = ArrayList<Double>(peaks.size)
        for (p in peaks) {
            if (p !in samples.indices) continue
            val peakAmp = abs(samples[p])
            val threshold = peakAmp * 0.3
            if (peakAmp < 1e-9) { out.add(0.0); continue }

            // Walk left. Stop at index 0 or when the signal drops
            // below the threshold.
            var left = p
            val leftLimit = (p - halfWindow).coerceAtLeast(0)
            while (left > leftLimit && abs(samples[left]) > threshold) left--

            // Walk right. Stop at the last index or when the signal
            // drops below the threshold.
            var right = p
            val rightLimit = (p + halfWindow).coerceAtMost(samples.size - 1)
            while (right < rightLimit && abs(samples[right]) > threshold) right++

            out.add((right - left).toDouble() / fs * 1000.0)
        }
        return out
    }

    // ─── Hilbert phase increments ──────────────────────────────────────

    /**
     * Compute the analytic signal of the bandpassed ECG via a Hilbert
     * transform and measure the phase difference between consecutive
     * R-peaks.
     *
     * The Hilbert transform is implemented as a simple FIR (the discrete
     * Hilbert kernel), good enough for a phase increment estimate at
     * R-peak spacing.
     */
    private fun hilbertPhaseIncrements(
        peaks: List<Int>,
        samples: DoubleArray,
        fs: Double
    ): List<Double> {
        val bandpassed = bandpass5to15(samples, fs)
        val analytic = hilbertTransform(bandpassed)
        val phases = DoubleArray(analytic.size) { Math.atan2(analytic[it].second, analytic[it].first) }
        val out = ArrayList<Double>(peaks.size - 1)
        for (i in 1 until peaks.size) {
            val p0 = peaks[i - 1].coerceIn(0, phases.size - 1)
            val p1 = peaks[i].coerceIn(0, phases.size - 1)
            var d = phases[p1] - phases[p0]
            // Unwrap to (-pi, pi]
            while (d > Math.PI) d -= 2.0 * Math.PI
            while (d <= -Math.PI) d += 2.0 * Math.PI
            out.add(abs(d))
        }
        return out
    }

    private fun bandpass5to15(x: DoubleArray, fs: Double): DoubleArray {
        val lp = biquadLowpass(x, 15.0, fs)
        return biquadHighpass(lp, 5.0, fs)
    }

    private fun biquadLowpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0); val sinW0 = Math.sin(w0)
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
        val cosW0 = Math.cos(w0); val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquad(x: DoubleArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi; y[i] = yi
        }
        return y
    }

    /**
     * Hilbert transform via a simple 65-tap FIR kernel. The kernel is
     * derived from the standard ideal Hilbert impulse response
     * h[n] = 2 / (pi * n) for odd n, zero for even n, windowed by a
     * Hamming window. Good enough for phase estimation at R-peak
     * spacing.
     */
    private fun hilbertTransform(x: DoubleArray): Array<Pair<Double, Double>> {
        val taps = 65
        val half = taps / 2
        val h = DoubleArray(taps)
        for (i in 0 until taps) {
            val n = i - half
            if (n == 0) { h[i] = 0.0; continue }
            if (n % 2 == 0) { h[i] = 0.0; continue }
            val w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (taps - 1))
            h[i] = (2.0 / (Math.PI * n)) * w
        }

        val real = x
        val imag = DoubleArray(x.size)
        for (i in x.indices) {
            var sum = 0.0
            for (k in 0 until taps) {
                val j = i - half + k
                if (j in x.indices) sum += h[k] * x[j]
            }
            imag[i] = sum
        }
        return Array(x.size) { Pair(real[it], imag[it]) }
    }
}
