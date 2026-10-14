package com.simoesctt.neurocardia.dsp

import com.simoesctt.neurocardia.io.Annotation
import com.simoesctt.neurocardia.io.BeatType
import com.simoesctt.neurocardia.io.MetricKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class HrvTest {

    // ─── normalRrIntervalsFrom ──────────────────────────────────────────

    @Test fun normal_rr_intervals_from_simple_series() {
        // 4 beats at 250 Hz, 1000 ms apart = 250 samples apart.
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),
            Annotation(sampleIndex = 500),
            Annotation(sampleIndex = 750)
        )
        val rr = Hrv.normalRrIntervalsFrom(anns, 250.0)
        assertEquals(3, rr.size)
        assertEquals(1000.0, rr[0], 1e-9)
        assertEquals(1000.0, rr[1], 1e-9)
        assertEquals(1000.0, rr[2], 1e-9)
    }

    @Test fun normal_rr_drops_intervals_touching_ectopics() {
        // Beats at 0, 250, 500, 750. Mark beat 2 (index 500) as ectopic.
        // Expect only the interval between beats 3 and 4 to survive.
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),
            Annotation(sampleIndex = 500, type = BeatType.ECTOPIC),
            Annotation(sampleIndex = 750)
        )
        val rr = Hrv.normalRrIntervalsFrom(anns, 250.0)
        assertEquals(1, rr.size)
        assertEquals(1000.0, rr[0], 1e-9)
    }

    @Test fun normal_rr_drops_intervals_touching_artifacts() {
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),
            Annotation(sampleIndex = 500, type = BeatType.ARTIFACT),
            Annotation(sampleIndex = 750),
            Annotation(sampleIndex = 1000)
        )
        val rr = Hrv.normalRrIntervalsFrom(anns, 250.0)
        // Intervals: 0-1 (ok), 1-2 (touches artifact), 2-3 (touches artifact), 3-4 (ok)
        assertEquals(2, rr.size)
    }

    @Test fun normal_rr_sorts_unsorted_input() {
        val anns = listOf(
            Annotation(sampleIndex = 750),
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 500),
            Annotation(sampleIndex = 250)
        )
        val rr = Hrv.normalRrIntervalsFrom(anns, 250.0)
        assertEquals(3, rr.size)
        rr.forEach { assertEquals(1000.0, it, 1e-9) }
    }

    @Test fun normal_rr_returns_empty_for_fewer_than_two_beats() {
        assertEquals(0, Hrv.normalRrIntervalsFrom(emptyList(), 250.0).size)
        assertEquals(0, Hrv.normalRrIntervalsFrom(
            listOf(Annotation(sampleIndex = 0)), 250.0
        ).size)
    }

    @Test fun normal_rr_returns_empty_for_invalid_sample_rate() {
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250)
        )
        assertEquals(0, Hrv.normalRrIntervalsFrom(anns, 0.0).size)
        assertEquals(0, Hrv.normalRrIntervalsFrom(anns, -1.0).size)
    }

    // ─── Scalar metrics ─────────────────────────────────────────────────

    @Test fun mean_rr_from_uniform_intervals() {
        val rr = listOf(1000.0, 1000.0, 1000.0, 1000.0)
        assertEquals(1000.0, Hrv.meanRrMs(rr), 1e-9)
    }

    @Test fun mean_heart_rate_from_uniform_intervals() {
        val rr = listOf(1000.0, 1000.0, 1000.0)
        assertEquals(60.0, Hrv.meanHeartRateBpm(rr), 1e-9)
    }

    @Test fun mean_heart_rate_from_mixed_intervals() {
        // Mean RR = (800 + 1000 + 1200) / 3 = 1000 ms, HR = 60 BPM.
        val rr = listOf(800.0, 1000.0, 1200.0)
        assertEquals(60.0, Hrv.meanHeartRateBpm(rr), 1e-9)
    }

    @Test fun sdnn_of_known_values() {
        // [1000, 1000, 1000, 1000]: SDNN = 0.
        val flat = listOf(1000.0, 1000.0, 1000.0, 1000.0)
        assertEquals(0.0, Hrv.sdnnMs(flat), 1e-9)

        // [900, 1000, 1100]: mean = 1000, sum sq dev = 20000,
        // sample variance = 10000, SD = 100.
        val spread = listOf(900.0, 1000.0, 1100.0)
        assertEquals(100.0, Hrv.sdnnMs(spread), 1e-9)
    }

    @Test fun rmssd_of_known_values() {
        // [1000, 1000, 1000]: successive diffs all zero, RMSSD = 0.
        val flat = listOf(1000.0, 1000.0, 1000.0)
        assertEquals(0.0, Hrv.rmssdMs(flat), 1e-9)

        // [900, 1000, 1100, 1200]: diffs = [100, 100, 100].
        // RMSSD = sqrt((100^2 * 3) / 3) = 100.
        val ramp = listOf(900.0, 1000.0, 1100.0, 1200.0)
        assertEquals(100.0, Hrv.rmssdMs(ramp), 1e-9)
    }

    @Test fun pnn50_of_known_values() {
        // Diffs: [100, 100, 100]. All exceed 50. Fraction = 1.0.
        val all = listOf(900.0, 1000.0, 1100.0, 1200.0)
        assertEquals(1.0, Hrv.pnn50(all), 1e-9)

        // Diffs: [10, 10, 10]. None exceed 50. Fraction = 0.0.
        val none = listOf(990.0, 1000.0, 1010.0, 1020.0)
        assertEquals(0.0, Hrv.pnn50(none), 1e-9)

        // Diffs: [100, 10, 100]. 2 of 3 exceed 50. Fraction = 2/3.
        val mixed = listOf(900.0, 1000.0, 1010.0, 1110.0)
        assertEquals(2.0 / 3.0, Hrv.pnn50(mixed), 1e-9)
    }

    @Test fun pnn20_of_known_values() {
        // Diffs: [30, 30, 30]. All exceed 20. Fraction = 1.0.
        val all = listOf(970.0, 1000.0, 1030.0, 1060.0)
        assertEquals(1.0, Hrv.pnn20(all), 1e-9)

        // Diffs: [10, 10, 10]. None exceed 20. Fraction = 0.0.
        val none = listOf(990.0, 1000.0, 1010.0, 1020.0)
        assertEquals(0.0, Hrv.pnn20(none), 1e-9)
    }

    @Test fun poincare_sd1_sd2_for_flat_series() {
        // All RR equal: SD1 = 0, SD2 = 0.
        val flat = listOf(1000.0, 1000.0, 1000.0, 1000.0, 1000.0)
        assertEquals(0.0, Hrv.sd1Ms(flat), 1e-9)
        assertEquals(0.0, Hrv.sd2Ms(flat), 1e-9)
    }

    @Test fun poincare_sd1_sd2_relationship() {
        // For any RR series, SD1^2 + SD2^2 = 2 * SDNN^2.
        val rr = listOf(850.0, 920.0, 1010.0, 980.0, 1050.0, 900.0)
        val sd1 = Hrv.sd1Ms(rr)
        val sd2 = Hrv.sd2Ms(rr)
        val sdnn = Hrv.sdnnMs(rr)
        val lhs = sd1 * sd1 + sd2 * sd2
        val rhs = 2.0 * sdnn * sdnn
        assertEquals(rhs, lhs, 1e-6)
    }

    // ─── Edge cases ─────────────────────────────────────────────────────

    @Test fun metrics_return_nan_for_empty_input() {
        assertTrue(Hrv.meanRrMs(emptyList()).isNaN())
        assertTrue(Hrv.meanHeartRateBpm(emptyList()).isNaN())
        assertTrue(Hrv.sdnnMs(emptyList()).isNaN())
        assertTrue(Hrv.rmssdMs(emptyList()).isNaN())
        assertTrue(Hrv.pnn50(emptyList()).isNaN())
        assertTrue(Hrv.sd1Ms(emptyList()).isNaN())
        assertTrue(Hrv.sd2Ms(emptyList()).isNaN())
    }

    @Test fun metrics_return_nan_for_single_interval() {
        val one = listOf(1000.0)
        assertFalse(Hrv.meanRrMs(one).isNaN())     // mean of one is fine
        assertFalse(Hrv.meanHeartRateBpm(one).isNaN())
        assertTrue(Hrv.sdnnMs(one).isNaN())         // needs >= 2
        assertTrue(Hrv.rmssdMs(one).isNaN())        // needs >= 2
        assertTrue(Hrv.pnn50(one).isNaN())          // needs >= 2
    }

    // ─── computeAll ─────────────────────────────────────────────────────

    @Test fun compute_all_populates_expected_keys() {
        val rr = listOf(900.0, 1000.0, 1100.0, 1000.0, 950.0)
        val m = Hrv.computeAll(rr)
        assertTrue(m.containsKey(MetricKeys.HEART_RATE_BPM))
        assertTrue(m.containsKey(MetricKeys.SDNN_MS))
        assertTrue(m.containsKey(MetricKeys.RMSSD_MS))
        assertTrue(m.containsKey(MetricKeys.PNN50))
        assertTrue(m.containsKey("mean_rr_ms"))
        assertTrue(m.containsKey("pnn20"))
        assertTrue(m.containsKey("sd1_ms"))
        assertTrue(m.containsKey("sd2_ms"))
        assertTrue(m.containsKey("sd1_sd2"))
    }

    @Test fun compute_all_omits_nan_metrics() {
        val m = Hrv.computeAll(emptyList())
        assertTrue(m.isEmpty())
    }

    @Test fun compute_all_values_consistent_with_scalar_calls() {
        val rr = listOf(900.0, 1000.0, 1100.0, 1000.0, 950.0)
        val m = Hrv.computeAll(rr)
        assertEquals(Hrv.meanHeartRateBpm(rr), m[MetricKeys.HEART_RATE_BPM]!!, 1e-9)
        assertEquals(Hrv.sdnnMs(rr), m[MetricKeys.SDNN_MS]!!, 1e-9)
        assertEquals(Hrv.rmssdMs(rr), m[MetricKeys.RMSSD_MS]!!, 1e-9)
        assertEquals(Hrv.pnn50(rr), m[MetricKeys.PNN50]!!, 1e-9)
    }
}
