package com.simoesctt.neurocardia.dsp

import com.simoesctt.neurocardia.io.Annotation
import com.simoesctt.neurocardia.io.BeatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateTest {

    // ─── instantaneousBpmFrom ───────────────────────────────────────────

    @Test fun instantaneous_bpm_from_uniform_1hz_beats() {
        val anns = (0..4).map { Annotation(sampleIndex = it * 250) }
        val bpm = HeartRate.instantaneousBpmFrom(anns, 250.0)
        assertEquals(4, bpm.size)
        bpm.forEach { assertEquals(60.0, it, 1e-9) }
    }

    @Test fun instantaneous_bpm_varies_with_interval() {
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),   // 60 BPM
            Annotation(sampleIndex = 375)    // 120 BPM
        )
        val bpm = HeartRate.instantaneousBpmFrom(anns, 250.0)
        assertEquals(2, bpm.size)
        assertEquals(60.0, bpm[0], 1e-9)
        assertEquals(120.0, bpm[1], 1e-9)
    }

    @Test fun instantaneous_bpm_skips_ectopic_intervals() {
        // Five beats, middle one ectopic. Four intervals total:
        //   0-1: both NORMAL     -> keep
        //   1-2: 2 is ectopic    -> skip
        //   2-3: 2 is ectopic    -> skip
        //   3-4: both NORMAL     -> keep
        // Two valid intervals survive.
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),
            Annotation(sampleIndex = 500, type = BeatType.ECTOPIC),
            Annotation(sampleIndex = 750),
            Annotation(sampleIndex = 1000)
        )
        val bpm = HeartRate.instantaneousBpmFrom(anns, 250.0)
        assertEquals(2, bpm.size)
        assertEquals(60.0, bpm[0], 1e-9)
        assertEquals(60.0, bpm[1], 1e-9)
    }

    @Test fun instantaneous_bpm_returns_empty_for_single_beat() {
        val anns = listOf(Annotation(sampleIndex = 0))
        assertEquals(0, HeartRate.instantaneousBpmFrom(anns, 250.0).size)
    }

    @Test fun instantaneous_bpm_returns_empty_for_invalid_sample_rate() {
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250)
        )
        assertEquals(0, HeartRate.instantaneousBpmFrom(anns, 0.0).size)
        assertEquals(0, HeartRate.instantaneousBpmFrom(anns, -1.0).size)
    }

    // ─── meanBpm, minBpm, maxBpm ────────────────────────────────────────

    @Test fun mean_bpm_of_uniform_series() {
        val anns = (0..10).map { Annotation(sampleIndex = it * 250) }
        val mean = HeartRate.meanBpm(anns, 250.0)
        assertEquals(60.0, mean, 1e-9)
    }

    @Test fun min_max_bpm_of_varying_series() {
        // Intervals: 250 (60), 125 (120), 500 (30).
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),
            Annotation(sampleIndex = 375),
            Annotation(sampleIndex = 875)
        )
        assertEquals(30.0, HeartRate.minBpm(anns, 250.0), 1e-9)
        assertEquals(120.0, HeartRate.maxBpm(anns, 250.0), 1e-9)
    }

    @Test fun min_max_bpm_return_nan_for_no_intervals() {
        assertTrue(HeartRate.minBpm(emptyList(), 250.0).isNaN())
        assertTrue(HeartRate.maxBpm(emptyList(), 250.0).isNaN())
    }

    // ─── rollingMeanBpm ─────────────────────────────────────────────────

    @Test fun rolling_mean_causal_of_uniform_series() {
        val anns = (0..10).map { Annotation(sampleIndex = it * 250) }
        val rolling = HeartRate.rollingMeanBpm(anns, 250.0, windowBeats = 3, centered = false)
        // 10 intervals, window 3 -> 8 outputs, all 60.
        assertEquals(8, rolling.size)
        rolling.forEach { assertEquals(60.0, it, 1e-9) }
    }

    @Test fun rolling_mean_returns_empty_when_window_too_large() {
        val anns = (0..2).map { Annotation(sampleIndex = it * 250) }
        val rolling = HeartRate.rollingMeanBpm(anns, 250.0, windowBeats = 10)
        assertEquals(0, rolling.size)
    }

    @Test fun rolling_mean_causal_values_are_averages_of_windows() {
        // Intervals: 60, 120, 30, 60 BPM. window=2 causal.
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 250),   // 60
            Annotation(sampleIndex = 375),   // 120
            Annotation(sampleIndex = 875),   // 30
            Annotation(sampleIndex = 1125)   // 60
        )
        val rolling = HeartRate.rollingMeanBpm(anns, 250.0, windowBeats = 2, centered = false)
        assertEquals(3, rolling.size)
        assertEquals((60.0 + 120.0) / 2.0, rolling[0], 1e-9)
        assertEquals((120.0 + 30.0) / 2.0, rolling[1], 1e-9)
        assertEquals((30.0 + 60.0) / 2.0, rolling[2], 1e-9)
    }

    // ─── classify ───────────────────────────────────────────────────────

    @Test fun classify_thresholds() {
        assertEquals(HeartRate.RateClass.SEVERE_BRADY, HeartRate.classify(35.0))
        assertEquals(HeartRate.RateClass.BRADY,        HeartRate.classify(50.0))
        assertEquals(HeartRate.RateClass.NORMAL,       HeartRate.classify(75.0))
        assertEquals(HeartRate.RateClass.TACHY,        HeartRate.classify(120.0))
        assertEquals(HeartRate.RateClass.SEVERE_TACHY, HeartRate.classify(180.0))
    }

    @Test fun classify_boundaries() {
        assertEquals(HeartRate.RateClass.SEVERE_BRADY, HeartRate.classify(39.9))
        assertEquals(HeartRate.RateClass.BRADY,        HeartRate.classify(40.0))
        assertEquals(HeartRate.RateClass.BRADY,        HeartRate.classify(59.9))
        assertEquals(HeartRate.RateClass.NORMAL,       HeartRate.classify(60.0))
        assertEquals(HeartRate.RateClass.NORMAL,       HeartRate.classify(100.0))
        assertEquals(HeartRate.RateClass.TACHY,        HeartRate.classify(100.1))
        assertEquals(HeartRate.RateClass.TACHY,        HeartRate.classify(150.0))
        assertEquals(HeartRate.RateClass.SEVERE_TACHY, HeartRate.classify(150.1))
    }

    // ─── rateClassHistogram ─────────────────────────────────────────────

    @Test fun histogram_counts_beats_per_class() {
        // At 250 Hz: 300 samples = 1200 ms = 50 BPM (brady),
        //            200 samples = 800 ms = 75 BPM (normal),
        //            125 samples = 500 ms = 120 BPM (tachy).
        val anns = listOf(
            Annotation(sampleIndex = 0),
            Annotation(sampleIndex = 300),    // 50 BPM
            Annotation(sampleIndex = 500),    // 75 BPM
            Annotation(sampleIndex = 625),    // 120 BPM
            Annotation(sampleIndex = 825)     // 75 BPM
        )
        val hist = HeartRate.rateClassHistogram(anns, 250.0)
        assertEquals(1, hist[HeartRate.RateClass.BRADY])
        assertEquals(2, hist[HeartRate.RateClass.NORMAL])
        assertEquals(1, hist[HeartRate.RateClass.TACHY])
    }

    @Test fun histogram_empty_for_empty_series() {
        val hist = HeartRate.rateClassHistogram(emptyList(), 250.0)
        assertTrue(hist.isEmpty())
    }
}
