package com.simoesctt.neurocardia.validation.features

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Temporal structure features of the RR interval series.
 *
 * Each function takes the RR intervals in milliseconds and returns a
 * scalar. The features describe the arrangement of the series, not its
 * variance. They are used by MechanismMode to test whether the CSI
 * residual correlates with the temporal structure of the rhythm.
 *
 * Features:
 *
 *   sampleEntropy      -- regularity / predictability of the series
 *   dfaAlpha1          -- short-range fractal scaling exponent
 *   rrDiffAutocorr1    -- lag-1 autocorrelation of successive RR diffs
 *   largeDiffFraction  -- proportion of successive RR diffs > threshold
 *
 * References:
 *
 *   Richman & Moorman (2000), "Physiological time-series analysis using
 *   approximate entropy and sample entropy." Am J Physiol Heart Circ
 *   Physiol 278: H2039-H2049.
 *
 *   Peng et al. (1995), "Quantification of scaling exponents and
 *   crossover phenomena in nonstationary heartbeat time series."
 *   Chaos 5(1): 82-87.
 */
object TemporalStructure {

    /**
     * Sample entropy of the RR series. Parameters m = 2 (embedding
     * dimension), r = 0.2 * SD (tolerance).
     *
     * Returns NaN if the series has fewer than 2 * (m + 1) intervals
     * or zero variance.
     */
    fun sampleEntropy(rrMs: List<Double>): Double {
        val m = 2
        if (rrMs.size < 2 * (m + 1)) return Double.NaN
        val n = rrMs.size

        val mean = rrMs.average()
        val sd = sqrt(rrMs.sumOf { (it - mean) * (it - mean) } / (n - 1))
        if (sd < 1e-9) return Double.NaN
        val r = 0.2 * sd

        val a = countMatches(rrMs, m + 1, r)
        val b = countMatches(rrMs, m, r)
        if (b == 0 || a == 0) return Double.NaN

        return -ln(a.toDouble() / b.toDouble())
    }

    private fun countMatches(rrMs: List<Double>, m: Int, r: Double): Int {
        val n = rrMs.size - m
        if (n < 2) return 0
        var count = 0
        for (i in 0..n - 1) {
            for (j in i + 1 until n) {
                var match = true
                for (k in 0 until m) {
                    if (abs(rrMs[i + k] - rrMs[j + k]) > r) {
                        match = false
                        break
                    }
                }
                if (match) count += 2
            }
        }
        return count
    }

    /**
     * Detrended fluctuation analysis, short-range exponent alpha-1.
     * Standard box sizes: 4 to 16 intervals, logarithmically spaced.
     *
     * Returns NaN if the series is too short.
     */
    fun dfaAlpha1(rrMs: List<Double>): Double {
        if (rrMs.size < 64) return Double.NaN

        // Integrate: subtract mean, cumulative sum.
        val mean = rrMs.average()
        val y = DoubleArray(rrMs.size)
        var cum = 0.0
        for (i in rrMs.indices) {
            cum += rrMs[i] - mean
            y[i] = cum
        }

        // Box sizes log-spaced between 4 and 16.
        val boxSizes = listOf(4, 6, 8, 10, 12, 14, 16)
        val logN = ArrayList<Double>(boxSizes.size)
        val logF = ArrayList<Double>(boxSizes.size)

        for (box in boxSizes) {
            val nBoxes = y.size / box
            if (nBoxes < 2) continue

            val f2 = ArrayList<Double>(nBoxes)
            for (b in 0 until nBoxes) {
                val start = b * box
                val end = start + box

                // Least-squares fit of a line to y[start..end).
                var sx = 0.0; var sy = 0.0
                for (i in start until end) {
                    sx += (i - start).toDouble()
                    sy += y[i]
                }
                val n = box.toDouble()
                val mx = sx / n
                val my = sy / n
                var num = 0.0; var den = 0.0
                for (i in start until end) {
                    val x = (i - start).toDouble()
                    num += (x - mx) * (y[i] - my)
                    den += (x - mx) * (x - mx)
                }
                val slope = if (den > 1e-12) num / den else 0.0
                val intercept = my - slope * mx

                // Residual sum of squares.
                var ss = 0.0
                for (i in start until end) {
                    val x = (i - start).toDouble()
                    val pred = intercept + slope * x
                    val resid = y[i] - pred
                    ss += resid * resid
                }
                f2.add(ss / n)
            }

            val f = sqrt(f2.average())
            if (f > 0.0) {
                logN.add(ln(box.toDouble()))
                logF.add(ln(f))
            }
        }

        if (logN.size < 3) return Double.NaN

        // Slope of log(F) vs log(n).
        val mx = logN.average()
        val my = logF.average()
        var num = 0.0; var den = 0.0
        for (i in logN.indices) {
            num += (logN[i] - mx) * (logF[i] - my)
            den += (logN[i] - mx) * (logN[i] - mx)
        }
        return if (den < 1e-12) Double.NaN else num / den
    }

    /**
     * Lag-1 autocorrelation of the RR differences. High values
     * indicate compensatory pauses after ectopic beats; low values
     * indicate memoryless irregularity.
     */
    fun rrDiffAutocorr1(rrMs: List<Double>): Double {
        if (rrMs.size < 5) return Double.NaN
        val diff = DoubleArray(rrMs.size - 1) { rrMs[it + 1] - rrMs[it] }
        val mean = diff.average()
        var num = 0.0; var den = 0.0
        for (i in diff.indices) {
            val d = diff[i] - mean
            den += d * d
            if (i > 0) num += (diff[i] - mean) * (diff[i - 1] - mean)
        }
        return if (den < 1e-12) Double.NaN else num / den
    }

    /**
     * Fraction of successive RR differences exceeding a threshold.
     * Default threshold 50 ms, matching pNN50.
     */
    fun largeDiffFraction(rrMs: List<Double>, thresholdMs: Double = 50.0): Double {
        if (rrMs.size < 3) return Double.NaN
        var count = 0
        for (i in 1 until rrMs.size) {
            if (abs(rrMs[i] - rrMs[i - 1]) > thresholdMs) count++
        }
        return count.toDouble() / (rrMs.size - 1)
    }

    /**
     * Compute all four features at once. Returns a map keyed by the
     * feature name (the map keys match the column names used in the
     * report CSVs).
     */
    fun computeAll(rrMs: List<Double>): Map<String, Double> {
        return mapOf(
            "temporal_sampen"     to sampleEntropy(rrMs),
            "temporal_dfa_alpha1" to dfaAlpha1(rrMs),
            "temporal_rrdiff_ac1" to rrDiffAutocorr1(rrMs),
            "temporal_largediff"  to largeDiffFraction(rrMs)
        )
    }
}
