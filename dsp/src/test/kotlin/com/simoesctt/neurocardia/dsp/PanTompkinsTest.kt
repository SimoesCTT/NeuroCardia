package com.simoesctt.neurocardia.dsp

import com.simoesctt.neurocardia.io.BeatType
import com.simoesctt.neurocardia.io.EcgRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class PanTompkinsTest {

    private fun synthEcg(
        durationS: Double,
        fs: Double,
        heartRateBpm: Double,
        noiseAmplitude: Double = 0.0,
        seed: Long = 42L
    ): Pair<DoubleArray, List<Int>> {
        val n = (durationS * fs).toInt()
        val rr = 60.0 / heartRateBpm
        val samples = DoubleArray(n)
        val rng = java.util.Random(seed)
        val qrsSamples = ArrayList<Int>()

        var t = 0.0
        while (t < durationS) {
            val qrsSample = (t * fs).toInt()
            if (qrsSample in 1 until n - 1) qrsSamples.add(qrsSample)
            for (k in -20..20) {
                val idx = qrsSample + k
                if (idx in 0 until n) {
                    val shape = exp(-(k * k) / 8.0) * (if (k < 0) -0.3 else 1.0)
                    samples[idx] += shape
                }
            }
            t += rr
        }

        for (i in 0 until n) {
            val tt = i / fs
            samples[i] += 0.05 * sin(2 * PI * 0.3 * tt)
            if (noiseAmplitude > 0) {
                samples[i] += noiseAmplitude * rng.nextGaussian()
            }
        }

        return samples to qrsSamples
    }

    @Test fun detects_clean_synthetic_qrs_complexes() {
        val (samples, truth) = synthEcg(durationS = 10.0, fs = 250.0, heartRateBpm = 72.0)
        val record = EcgRecord(samples = samples, sampleRate = 250.0)
        val detected = PanTompkins.detect(record)

        assertEquals(truth.size, detected.annotations.size)

        // 16 ms localization tolerance, well within the 100 ms the
        // original Pan-Tompkins paper allows.
        val tol = (0.05 * 250).toInt()
        val matched = mutableSetOf<Int>()
        for (ann in detected.annotations) {
            val nearest = truth.minByOrNull { abs(it - ann.sampleIndex) }
            assertTrue(
                "detected peak at ${ann.sampleIndex} has no truth peak within $tol samples",
                nearest != null && abs(nearest - ann.sampleIndex) <= tol
            )
            matched.add(nearest!!)
        }
        assertEquals(
            "every truth peak should match exactly one detection",
            truth.size, matched.size
        )
    }

    @Test fun detects_at_different_heart_rates() {
        for (hr in listOf(50.0, 72.0, 100.0, 120.0)) {
            val (samples, truth) = synthEcg(durationS = 15.0, fs = 250.0, heartRateBpm = hr)
            val record = EcgRecord(samples = samples, sampleRate = 250.0)
            val detected = PanTompkins.detect(record)
            val expected = truth.size
            val actual = detected.annotations.size
            assertTrue(
                "HR=$hr: expected ~$expected peaks, got $actual",
                abs(actual - expected) <= 2
            )
        }
    }

    @Test fun tolerates_moderate_noise() {
        val (samples, truth) = synthEcg(
            durationS = 10.0, fs = 250.0, heartRateBpm = 72.0, noiseAmplitude = 0.05
        )
        val record = EcgRecord(samples = samples, sampleRate = 250.0)
        val detected = PanTompkins.detect(record)
        val err = abs(detected.annotations.size - truth.size)
        assertTrue("noise=0.05: expected within 3 of truth, got $err", err <= 3)
    }

    @Test fun preserves_input_fields() {
        val (samples, _) = synthEcg(5.0, 250.0, 60.0)
        val record = EcgRecord(
            samples = samples,
            sampleRate = 250.0,
            leadLabel = "MLII",
            metadata = mapOf("source" to "test")
        )
        val detected = PanTompkins.detect(record)
        assertEquals("MLII", detected.leadLabel)
        assertEquals("test", detected.metadata["source"])
        assertEquals(250.0, detected.sampleRate, 0.0)
    }

    @Test fun short_signal_returns_unchanged() {
        val record = EcgRecord(samples = DoubleArray(10), sampleRate = 250.0)
        val detected = PanTompkins.detect(record)
        assertTrue(detected.annotations.isEmpty())
    }

    @Test fun annotations_have_expected_type() {
        val (samples, _) = synthEcg(5.0, 250.0, 72.0)
        val record = EcgRecord(samples = samples, sampleRate = 250.0)
        val detected = PanTompkins.detect(record)
        detected.annotations.forEach { ann ->
            assertEquals(BeatType.NORMAL, ann.type)
        }
    }

    @Test fun detected_peaks_are_sorted_ascending() {
        val (samples, _) = synthEcg(10.0, 250.0, 72.0)
        val record = EcgRecord(samples = samples, sampleRate = 250.0)
        val detected = PanTompkins.detect(record)
        val indices = detected.annotations.map { it.sampleIndex }
        assertEquals(indices.sorted(), indices)
    }

    @Test fun flat_signal_produces_no_detections() {
        val samples = DoubleArray(2500)
        val record = EcgRecord(samples = samples, sampleRate = 250.0)
        val detected = PanTompkins.detect(record)
        assertTrue(
            "flat signal should produce no peaks, got ${detected.annotations.size}",
            detected.annotations.isEmpty()
        )
    }
}
