package com.simoesctt.neurocardia.dsp

import com.simoesctt.neurocardia.io.Annotation
import com.simoesctt.neurocardia.io.BeatType
import kotlin.math.sqrt

/**
 * Heart rate variability metrics computed from an RR interval series.
 *
 * All functions are pure. Input is a list of RR intervals in milliseconds.
 * Output is a scalar metric or a small data class. No side effects, no
 * state, no dependencies beyond the Kotlin standard library.
 *
 * Reference: Task Force of the European Society of Cardiology and the
 * North American Society of Pacing and Electrophysiology. Heart rate
 * variability: standards of measurement, physiological interpretation,
 * and clinical use. Circulation, 93(5):1043-1065, 1996.
 *
 * All RR intervals used for HRV computation must be NORMAL beats. Ectopic
 * beats and artifacts must be excluded before calling these functions.
 * The helper [normalRrIntervalsFrom] performs this filtering.
 */
object Hrv {

    /**
     * Compute RR intervals (in milliseconds) from a list of beat annotations.
     *
     * Only NORMAL beats are used. Ectopic beats and artifacts are dropped,
     * along with the intervals that would have spanned them. This is the
     * standard practice: an ectopic beat corrupts both the interval before
     * it and the interval after it, so both are discarded.
     *
     * Returns an empty list if fewer than two valid beats are present.
     */
    fun normalRrIntervalsFrom(
        annotations: List<Annotation>,
        sampleRate: Double
    ): List<Double> {
        if (annotations.size < 2 || sampleRate <= 0.0) return emptyList()

        val sorted = annotations.sortedBy { it.sampleIndex }
        val rr = ArrayList<Double>(sorted.size - 1)

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            // Skip intervals that touch an ectopic or artifact beat.
            if (prev.type != BeatType.NORMAL) continue
            if (curr.type != BeatType.NORMAL) continue

            val deltaSamples = curr.sampleIndex - prev.sampleIndex
            if (deltaSamples <= 0) continue
            rr.add(deltaSamples * 1000.0 / sampleRate)
        }
        return rr
    }

    /**
     * Mean RR interval in milliseconds.
     */
    fun meanRrMs(rrMs: List<Double>): Double {
        if (rrMs.isEmpty()) return Double.NaN
        return rrMs.average()
    }

    /**
     * Mean heart rate in beats per minute.
     *
     * Uses the mean RR interval, not the mean of instantaneous heart rates.
     * These are not the same. The Task Force recommends the mean-RR method
     * because it is less biased by short intervals.
     */
    fun meanHeartRateBpm(rrMs: List<Double>): Double {
        val meanRr = meanRrMs(rrMs)
        if (meanRr.isNaN() || meanRr <= 0.0) return Double.NaN
        return 60_000.0 / meanRr
    }

    /**
     * SDNN: standard deviation of normal-to-normal intervals, in ms.
     *
     * Uses the sample standard deviation (N-1 denominator), matching the
     * Task Force definition. Requires at least 2 intervals.
     */
    fun sdnnMs(rrMs: List<Double>): Double {
        if (rrMs.size < 2) return Double.NaN
        val mean = rrMs.average()
        val sumSq = rrMs.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (rrMs.size - 1))
    }

    /**
     * RMSSD: root mean square of successive differences, in ms.
     *
     * Computed from the differences between consecutive RR intervals.
     * Requires at least 2 intervals (i.e. at least 3 beats).
     */
    fun rmssdMs(rrMs: List<Double>): Double {
        if (rrMs.size < 2) return Double.NaN
        var sumSq = 0.0
        for (i in 1 until rrMs.size) {
            val d = rrMs[i] - rrMs[i - 1]
            sumSq += d * d
        }
        return sqrt(sumSq / (rrMs.size - 1))
    }

    /**
     * pNN50: proportion of successive RR differences that exceed 50 ms.
     *
     * Returns a fraction in [0, 1], not a percentage. Callers that want
     * a percentage should multiply by 100.
     */
    fun pnn50(rrMs: List<Double>): Double {
        if (rrMs.size < 2) return Double.NaN
        var count = 0
        for (i in 1 until rrMs.size) {
            val d = kotlin.math.abs(rrMs[i] - rrMs[i - 1])
            if (d > 50.0) count++
        }
        return count.toDouble() / (rrMs.size - 1)
    }

    /**
     * pNN20: proportion of successive RR differences that exceed 20 ms.
     *
     * Less common than pNN50 but sometimes used in short recordings where
     * pNN50 is too sparse. Same convention: fraction, not percentage.
     */
    fun pnn20(rrMs: List<Double>): Double {
        if (rrMs.size < 2) return Double.NaN
        var count = 0
        for (i in 1 until rrMs.size) {
            val d = kotlin.math.abs(rrMs[i] - rrMs[i - 1])
            if (d > 20.0) count++
        }
        return count.toDouble() / (rrMs.size - 1)
    }

    /**
     * SD1: standard deviation of the Poincaré plot perpendicular to the
     * line of identity. Reflects short-term variability.
     *
     * SD1^2 = 0.5 * Var(RR_{n+1} - RR_n)
     */
    fun sd1Ms(rrMs: List<Double>): Double {
        if (rrMs.size < 2) return Double.NaN
        val diffs = ArrayList<Double>(rrMs.size - 1)
        for (i in 1 until rrMs.size) diffs.add(rrMs[i] - rrMs[i - 1])
        val variance = diffs.sumOf { it * it } / (diffs.size - 1)
        return sqrt(0.5 * variance)
    }

    /**
     * SD2: standard deviation of the Poincaré plot along the line of
     * identity. Reflects long-term variability.
     *
     * SD2^2 = 2 * SDNN^2 - 0.5 * Var(RR_{n+1} - RR_n)
     */
    fun sd2Ms(rrMs: List<Double>): Double {
        if (rrMs.size < 2) return Double.NaN
        val sdnn = sdnnMs(rrMs)
        if (sdnn.isNaN()) return Double.NaN
        val diffs = ArrayList<Double>(rrMs.size - 1)
        for (i in 1 until rrMs.size) diffs.add(rrMs[i] - rrMs[i - 1])
        val diffVariance = diffs.sumOf { it * it } / (diffs.size - 1)
        val sd2Sq = 2.0 * sdnn * sdnn - 0.5 * diffVariance
        return if (sd2Sq < 0.0) Double.NaN else sqrt(sd2Sq)
    }

    /**
     * All standard HRV metrics in one call.
     *
     * Returns a map keyed by the constants in MetricKeys. Any metric that
     * cannot be computed (e.g. too few intervals) is omitted from the map
     * rather than set to NaN. Callers should test for key presence.
     */
    fun computeAll(rrMs: List<Double>): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()

        if (rrMs.isEmpty()) return m

        val meanRr = meanRrMs(rrMs)
        if (!meanRr.isNaN()) {
            m[com.simoesctt.neurocardia.io.MetricKeys.HEART_RATE_BPM] =
                meanHeartRateBpm(rrMs)
            m["mean_rr_ms"] = meanRr
        }

        val sdnn = sdnnMs(rrMs)
        if (!sdnn.isNaN()) m[com.simoesctt.neurocardia.io.MetricKeys.SDNN_MS] = sdnn

        val rmssd = rmssdMs(rrMs)
        if (!rmssd.isNaN()) m[com.simoesctt.neurocardia.io.MetricKeys.RMSSD_MS] = rmssd

        val pnn50 = pnn50(rrMs)
        if (!pnn50.isNaN()) m[com.simoesctt.neurocardia.io.MetricKeys.PNN50] = pnn50

        val pnn20 = pnn20(rrMs)
        if (!pnn20.isNaN()) m["pnn20"] = pnn20

        val sd1 = sd1Ms(rrMs)
        if (!sd1.isNaN()) m["sd1_ms"] = sd1

        val sd2 = sd2Ms(rrMs)
        if (!sd2.isNaN()) m["sd2_ms"] = sd2

        if (!sd1.isNaN() && !sd2.isNaN() && sd2 > 0.0) {
            m["sd1_sd2"] = sd1 / sd2
        }

        return m
    }
}
