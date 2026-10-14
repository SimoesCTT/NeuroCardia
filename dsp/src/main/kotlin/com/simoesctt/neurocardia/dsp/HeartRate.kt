package com.simoesctt.neurocardia.dsp

import com.simoesctt.neurocardia.io.Annotation
import com.simoesctt.neurocardia.io.BeatType

/**
 * Heart rate and RR interval utilities.
 *
 * Companion to [Hrv]. Where [Hrv] computes variability metrics over a
 * whole recording, this file computes per-beat quantities and rolling
 * statistics suitable for display on a timeline.
 *
 * All functions are pure. No state, no side effects.
 */
object HeartRate {

    /**
     * Instantaneous heart rate in BPM at each beat, computed from the
     * interval between that beat and the previous one.
     *
     * The first beat has no preceding beat, so it produces no entry.
     * Result size = (number of NORMAL beats) - 1.
     *
     * Only NORMAL beats contribute. Ectopic and artifact beats are
     * skipped, and the intervals that span them are not reported. This
     * means the result may be shorter than the input annotation list.
     */
    fun instantaneousBpmFrom(
        annotations: List<Annotation>,
        sampleRate: Double
    ): DoubleArray {
        if (annotations.size < 2 || sampleRate <= 0.0) return DoubleArray(0)

        val sorted = annotations.sortedBy { it.sampleIndex }
        val bpm = ArrayList<Double>(sorted.size - 1)

        for (i in 1 until sorted.size) {
            val prev = sorted[i - 1]
            val curr = sorted[i]
            if (prev.type != BeatType.NORMAL) continue
            if (curr.type != BeatType.NORMAL) continue

            val deltaSamples = curr.sampleIndex - prev.sampleIndex
            if (deltaSamples <= 0) continue

            val rrS = deltaSamples / sampleRate
            bpm.add(60.0 / rrS)
        }
        return bpm.toDoubleArray()
    }

    /**
     * Mean heart rate over the recording, using the mean-RR method
     * recommended by the Task Force. Returns NaN if fewer than two
     * valid intervals are present.
     */
    fun meanBpm(annotations: List<Annotation>, sampleRate: Double): Double {
        val rr = Hrv.normalRrIntervalsFrom(annotations, sampleRate)
        return Hrv.meanHeartRateBpm(rr)
    }

    /**
     * Minimum instantaneous heart rate over the recording (slowest beat).
     * Returns NaN if no valid intervals are present.
     */
    fun minBpm(annotations: List<Annotation>, sampleRate: Double): Double {
        val bpm = instantaneousBpmFrom(annotations, sampleRate)
        if (bpm.isEmpty()) return Double.NaN
        return bpm.min()
    }

    /**
     * Maximum instantaneous heart rate over the recording (fastest beat).
     * Returns NaN if no valid intervals are present.
     */
    fun maxBpm(annotations: List<Annotation>, sampleRate: Double): Double {
        val bpm = instantaneousBpmFrom(annotations, sampleRate)
        if (bpm.isEmpty()) return Double.NaN
        return bpm.max()
    }

    /**
     * Rolling mean heart rate over a sliding window of beats.
     *
     * [windowBeats] is the number of beats in the window. Common choices
     * are 5 (short-term smoothing for display) and 30 (long-term trend).
     *
     * Result size = (number of instantaneous BPM values) - windowBeats + 1.
     * Returns an empty array if fewer than windowBeats instantaneous values
     * are available.
     *
     * The window is centered on each position by default. Set
     * [centered] = false for a causal (trailing) window, which is useful
     * for real-time display.
     */
    fun rollingMeanBpm(
        annotations: List<Annotation>,
        sampleRate: Double,
        windowBeats: Int = 5,
        centered: Boolean = true
    ): DoubleArray {
        val bpm = instantaneousBpmFrom(annotations, sampleRate)
        if (windowBeats < 1 || bpm.size < windowBeats) return DoubleArray(0)

        val out = ArrayList<Double>(bpm.size - windowBeats + 1)

        if (centered) {
            val half = windowBeats / 2
            for (i in 0 until bpm.size) {
                val start = (i - half).coerceAtLeast(0)
                val end = (i + half + 1).coerceAtMost(bpm.size)
                if (end - start < windowBeats) continue
                var sum = 0.0
                for (k in start until end) sum += bpm[k]
                out.add(sum / (end - start))
            }
        } else {
            for (i in 0..(bpm.size - windowBeats)) {
                var sum = 0.0
                for (k in i until (i + windowBeats)) sum += bpm[k]
                out.add(sum / windowBeats)
            }
        }

        return out.toDoubleArray()
    }

    /**
     * Classify a heart rate in BPM against adult resting thresholds.
     *
     * These thresholds are for screening and display only, not diagnosis.
     * They apply to adults at rest and are meaningless during exercise,
     * sleep, or in pediatric populations.
     */
    enum class RateClass { SEVERE_BRADY, BRADY, NORMAL, TACHY, SEVERE_TACHY }

    fun classify(bpm: Double): RateClass {
        return when {
            bpm.isNaN()   -> RateClass.NORMAL   // caller should handle NaN separately
            bpm < 40.0    -> RateClass.SEVERE_BRADY
            bpm < 60.0    -> RateClass.BRADY
            bpm <= 100.0  -> RateClass.NORMAL
            bpm <= 150.0  -> RateClass.TACHY
            else          -> RateClass.SEVERE_TACHY
        }
    }

    /**
     * Count of beats in each rate class over a recording.
     * The map omits classes with zero count.
     */
    fun rateClassHistogram(
        annotations: List<Annotation>,
        sampleRate: Double
    ): Map<RateClass, Int> {
        val bpm = instantaneousBpmFrom(annotations, sampleRate)
        val hist = LinkedHashMap<RateClass, Int>()
        for (b in bpm) {
            val c = classify(b)
            hist[c] = (hist[c] ?: 0) + 1
        }
        return hist
    }
}
