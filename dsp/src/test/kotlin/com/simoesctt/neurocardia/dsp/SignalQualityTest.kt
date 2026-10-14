package com.simoesctt.neurocardia.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class SignalQualityTest {

    private val fs = 250.0

    /**
     * Build a synthetic ECG with a QRS complex every `rrS` seconds.
     * The QRS is a narrow Gaussian. Optionally add baseline wander and
     * high-frequency noise.
     */
    private fun synthEcg(
        durationS: Double = 10.0,
        rrS: Double = 0.83,
        noise: Double = 0.0,
        baselineAmp: Double = 0.0,
        seed: Long = 42L
    ): DoubleArray {
        val n = (durationS * fs).toInt()
        val out = DoubleArray(n)
        val rng = java.util.Random(seed)

        var t = 0.5
        while (t < durationS - 0.5) {
            val qrs = (t * fs).toInt()
            for (k in -20..20) {
                val idx = qrs + k
                if (idx in 0 until n) {
                    out[idx] += exp(-(k * k) / 32.0) * (if (k < 0) -0.3 else 1.0)
                }
            }
            t += rrS
        }

        if (baselineAmp > 0.0) {
            for (i in 0 until n) {
                out[i] += baselineAmp * sin(2 * PI * 0.3 * i / fs)
            }
        }
        if (noise > 0.0) {
            for (i in 0 until n) {
                out[i] += noise * rng.nextGaussian()
            }
        }
        return out
    }

    // ─── Basic sanity ──────────────────────────────────────────────────

    @Test fun clean_synthetic_signal_scores_at_least_fair() {
        // The synthetic signal is a Gaussian approximation of a QRS,
        // which puts less energy in the 5-15 Hz band than a real ECG.
        // We assert FAIR or better; a real MIT-BIH record should
        // score GOOD (see real_record_scores_good below).
        val x = synthEcg(noise = 0.005, baselineAmp = 0.02)
        val q = SignalQuality.compute(x, fs)
        println("clean: snr=${q.snrDb} flat=${q.flatlineFraction} clip=${q.clippingFraction} base=${q.baselineFraction} score=${q.score} verdict=${q.verdict}")
        assertTrue("clean synthetic signal should score at least FAIR, got ${q.verdict} (score=${q.score})",
            q.verdict == SignalQuality.Verdict.GOOD || q.verdict == SignalQuality.Verdict.FAIR)
    }

    @Test fun real_record_scores_good() {
        // Load a real MIT-BIH record if available. Skip if the corpus
        // is not present on this machine.
        val recordBase = System.getProperty("user.home") + "/physionet/mitdb/100"
        val heaFile = java.io.File("$recordBase.hea")
        org.junit.Assume.assumeTrue(heaFile.isFile)
        val record = com.simoesctt.neurocardia.io.wfdb.WfdbReader
            .read(recordBase, attachAnnotations = false)
        val q = SignalQuality.compute(record.samples, record.sampleRate)
        println("MIT-BIH 100: snr=${q.snrDb} flat=${q.flatlineFraction} clip=${q.clippingFraction} base=${q.baselineFraction} score=${q.score} verdict=${q.verdict}")
        assertTrue("real MIT-BIH record should score GOOD, got ${q.verdict} (score=${q.score})",
            q.verdict == SignalQuality.Verdict.GOOD)
    }

    @Test fun noisy_signal_scores_fair_or_poor() {
        val x = synthEcg(noise = 0.5, baselineAmp = 0.02)
        val q = SignalQuality.compute(x, fs)
        println("noisy: snr=${q.snrDb} score=${q.score} verdict=${q.verdict}")
        assertTrue("noisy signal should not score GOOD, got ${q.verdict}",
            q.verdict != SignalQuality.Verdict.GOOD)
    }

    @Test fun very_noisy_signal_scores_poor() {
        // Pure noise: no ECG, no baseline.
        val rng = java.util.Random(42L)
        val x = DoubleArray((fs * 10).toInt()) { rng.nextGaussian() * 0.5 }
        val q = SignalQuality.compute(x, fs)
        println("pure noise: snr=${q.snrDb} score=${q.score} verdict=${q.verdict}")
        assertTrue("pure noise should not score GOOD, got ${q.verdict}",
            q.verdict != SignalQuality.Verdict.GOOD)
    }

    // ─── Flatline detection ────────────────────────────────────────────

    @Test fun flatline_detects_dropout() {
        val x = synthEcg()
        // Kill a contiguous chunk in the middle.
        val start = x.size / 2
        val end = start + (fs * 2).toInt()   // 2 seconds of flat
        for (i in start until end) x[i] = x[start]
        val q = SignalQuality.compute(x, fs)
        println("flatline: flat=${q.flatlineFraction}")
        assertTrue("flatline fraction should be > 0.1, got ${q.flatlineFraction}",
            q.flatlineFraction > 0.1)
    }

    @Test fun no_flatline_in_normal_signal() {
        val x = synthEcg(noise = 0.005)
        val q = SignalQuality.compute(x, fs)
        println("no flatline: flat=${q.flatlineFraction}")
        assertTrue("flatline fraction should be tiny, got ${q.flatlineFraction}",
            q.flatlineFraction < 0.01)
    }

    // ─── Clipping detection ────────────────────────────────────────────

    @Test fun clipping_detects_saturation() {
        val x = synthEcg(noise = 0.0)
        // Clip the top 30% of the signal to a fixed value.
        val maxV = x.max()
        val threshold = maxV * 0.7
        for (i in x.indices) {
            if (x[i] > threshold) x[i] = threshold
        }
        val q = SignalQuality.compute(x, fs)
        println("clipping: clip=${q.clippingFraction}")
        assertTrue("clipping fraction should be > 0.01, got ${q.clippingFraction}",
            q.clippingFraction > 0.01)
    }

    // ─── Baseline wander ───────────────────────────────────────────────

    @Test fun heavy_baseline_reduces_score() {
        val clean = synthEcg(noise = 0.005, baselineAmp = 0.02)
        val wandering = synthEcg(noise = 0.005, baselineAmp = 1.5)

        val qClean = SignalQuality.compute(clean, fs)
        val qWander = SignalQuality.compute(wandering, fs)

        println("clean baseline: base=${qClean.baselineFraction} score=${qClean.score}")
        println("heavy baseline: base=${qWander.baselineFraction} score=${qWander.score}")

        assertTrue("heavy baseline should have higher baseline fraction, " +
            "got ${qWander.baselineFraction} vs ${qClean.baselineFraction}",
            qWander.baselineFraction > qClean.baselineFraction)
        assertTrue("heavy baseline should have lower score",
            qWander.score < qClean.score)
    }

    // ─── Edge cases ────────────────────────────────────────────────────

    @Test fun empty_input_scores_poor() {
        val q = SignalQuality.compute(DoubleArray(0), fs)
        assertEquals(SignalQuality.Verdict.POOR, q.verdict)
        assertEquals(0.0, q.score, 0.0)
    }

    @Test fun very_short_input_scores_poor() {
        // Less than 1 second.
        val x = synthEcg(durationS = 0.5)
        val q = SignalQuality.compute(x, fs)
        assertEquals(SignalQuality.Verdict.POOR, q.verdict)
        assertEquals(0.0, q.score, 0.0)
    }

    @Test fun flat_signal_scores_poor() {
        val x = DoubleArray((fs * 5).toInt())   // all zeros
        val q = SignalQuality.compute(x, fs)
        // A perfectly flat signal has 100% flatline, no noise, no
        // signal. SNR is undefined but our code returns 60 dB for a
        // zero-noise input... which would be wrong. Let's make sure
        // the flatline term dominates.
        println("flat signal: flat=${q.flatlineFraction} score=${q.score} verdict=${q.verdict}")
        assertTrue("flat signal should have flatline fraction ~1, got ${q.flatlineFraction}",
            q.flatlineFraction > 0.99)
        assertTrue("flat signal should not score GOOD, got ${q.verdict}",
            q.verdict != SignalQuality.Verdict.GOOD)
    }
}
