package com.simoesctt.neurocardia.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RrTachogramTest {

    private fun uniformRecord(nBeats: Int, rrSamples: Int, sampleRate: Double = 250.0): EcgRecord {
        val annotations = (0 until nBeats).map {
            Annotation(sampleIndex = it * rrSamples)
        }
        return EcgRecord(
            samples = DoubleArray(nBeats * rrSamples + rrSamples),
            sampleRate = sampleRate,
            annotations = annotations,
            metadata = mapOf("source" to "test.csv")
        )
    }

    // ─── Construction from EcgRecord ────────────────────────────────────

    @Test fun from_uniform_record_produces_correct_sizes() {
        val record = uniformRecord(nBeats = 5, rrSamples = 250)
        val tach = RrTachogram.from(record)
        assertEquals(5, tach.nBeats)
        assertEquals(4, tach.nIntervals)
        assertEquals(5, tach.timeS.size)
        assertEquals(5, tach.beatType.size)
    }

    @Test fun from_uniform_record_produces_1000ms_intervals() {
        val record = uniformRecord(nBeats = 5, rrSamples = 250)
        val tach = RrTachogram.from(record)
        for (rr in tach.rrMs) assertEquals(1000.0, rr, 1e-9)
    }

    @Test fun from_uniform_record_produces_sequential_times() {
        val record = uniformRecord(nBeats = 5, rrSamples = 250)
        val tach = RrTachogram.from(record)
        assertEquals(0.0, tach.timeS[0], 1e-9)
        assertEquals(1.0, tach.timeS[1], 1e-9)
        assertEquals(2.0, tach.timeS[2], 1e-9)
        assertEquals(3.0, tach.timeS[3], 1e-9)
        assertEquals(4.0, tach.timeS[4], 1e-9)
    }

    @Test fun from_empty_annotations_produces_empty_tachogram() {
        val record = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            metadata = mapOf("source" to "empty.csv")
        )
        val tach = RrTachogram.from(record)
        assertEquals(0, tach.nBeats)
        assertEquals(0, tach.nIntervals)
        assertEquals("empty.csv", tach.source)
    }

    @Test fun from_single_beat_produces_zero_intervals() {
        val record = EcgRecord(
            samples = DoubleArray(500),
            sampleRate = 250.0,
            annotations = listOf(Annotation(sampleIndex = 100))
        )
        val tach = RrTachogram.from(record)
        assertEquals(1, tach.nBeats)
        assertEquals(0, tach.nIntervals)
    }

    @Test fun from_sorts_unsorted_annotations() {
        val record = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            annotations = listOf(
                Annotation(sampleIndex = 500),
                Annotation(sampleIndex = 0),
                Annotation(sampleIndex = 250)
            )
        )
        val tach = RrTachogram.from(record)
        assertEquals(3, tach.nBeats)
        assertEquals(0, tach.timeS[0].toInt())
        assertEquals(1, tach.timeS[1].toInt())
        assertEquals(2, tach.timeS[2].toInt())
    }

    @Test fun from_deduplicates_identical_sample_indices() {
        val record = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            annotations = listOf(
                Annotation(sampleIndex = 0),
                Annotation(sampleIndex = 250),
                Annotation(sampleIndex = 250),    // duplicate
                Annotation(sampleIndex = 500)
            )
        )
        val tach = RrTachogram.from(record)
        assertEquals(3, tach.nBeats)
        assertEquals(2, tach.nIntervals)
    }

    @Test fun from_preserves_metadata() {
        val record = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            annotations = (0..3).map { Annotation(sampleIndex = it * 250) },
            metadata = mapOf("source" to "patient-01.csv", "device" to "test")
        )
        val tach = RrTachogram.from(record)
        assertEquals("patient-01.csv", tach.source)
        assertEquals("test", tach.metadata["device"])
    }

    // ─── init validation ────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_wrong_rr_size() {
        RrTachogram(
            rrMs = doubleArrayOf(1000.0, 1000.0),           // 2
            timeS = doubleArrayOf(0.0, 1.0, 2.0, 3.0),      // 4 (should be 3)
            beatType = Array(4) { BeatType.NORMAL },
            sampleRate = 250.0
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructor_rejects_wrong_beat_type_size() {
        RrTachogram(
            rrMs = doubleArrayOf(1000.0),
            timeS = doubleArrayOf(0.0, 1.0),
            beatType = arrayOf(BeatType.NORMAL),            // 1 (should be 2)
            sampleRate = 250.0
        )
    }

    // ─── Derived metrics ────────────────────────────────────────────────

    @Test fun mean_rr_of_uniform_tachogram() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(1000.0, tach.meanRrMs(), 1e-9)
    }

    @Test fun mean_heart_rate_of_uniform_tachogram() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(60.0, tach.meanHeartRateBpm(), 1e-9)
    }

    @Test fun sdnn_of_uniform_tachogram_is_zero() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(0.0, tach.sdnnMs(), 1e-9)
    }

    @Test fun rmssd_of_uniform_tachogram_is_zero() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(0.0, tach.rmssdMs(), 1e-9)
    }

    @Test fun pnn50_of_uniform_tachogram_is_zero() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(0.0, tach.pnn50(), 1e-9)
    }

    @Test fun duration_of_uniform_tachogram() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(4.0, tach.durationS(), 1e-9)
    }

    @Test fun normal_fraction_with_all_normal_beats() {
        val tach = RrTachogram.from(uniformRecord(nBeats = 5, rrSamples = 250))
        assertEquals(1.0, tach.normalFraction(), 1e-9)
    }

    @Test fun normal_fraction_with_some_ectopic_beats() {
        val record = EcgRecord(
            samples = DoubleArray(2000),
            sampleRate = 250.0,
            annotations = listOf(
                Annotation(sampleIndex = 0),
                Annotation(sampleIndex = 250, type = BeatType.ECTOPIC),
                Annotation(sampleIndex = 500),
                Annotation(sampleIndex = 750)
            )
        )
        val tach = RrTachogram.from(record)
        assertEquals(3.0 / 4.0, tach.normalFraction(), 1e-9)
    }

    @Test fun normal_rr_filters_ectopic_intervals() {
        val record = EcgRecord(
            samples = DoubleArray(2000),
            sampleRate = 250.0,
            annotations = listOf(
                Annotation(sampleIndex = 0),
                Annotation(sampleIndex = 250),
                Annotation(sampleIndex = 500, type = BeatType.ECTOPIC),
                Annotation(sampleIndex = 750)
            )
        )
        val tach = RrTachogram.from(record)
        // 3 intervals total, only the last one (750-500) touches only
        // NORMAL beats? No — beat at index 2 is ectopic, so both
        // intervals touching it are dropped. Only interval between
        // beats 0 and 1 survives? Wait: intervals are:
        //   0-1 (both normal)  -> keep
        //   1-2 (2 is ectopic) -> drop
        //   2-3 (2 is ectopic) -> drop
        // So we expect 1 normal interval.
        assertEquals(1, tach.normalRr().size)
    }

    @Test fun metrics_nan_for_empty_tachogram() {
        val empty = RrTachogram(
            rrMs = DoubleArray(0),
            timeS = DoubleArray(0),
            beatType = emptyArray(),
            sampleRate = 250.0
        )
        assertTrue(empty.meanRrMs().isNaN())
        assertTrue(empty.meanHeartRateBpm().isNaN())
        assertTrue(empty.sdnnMs().isNaN())
        assertTrue(empty.rmssdMs().isNaN())
        assertTrue(empty.pnn50().isNaN())
        assertEquals(0.0, empty.durationS(), 1e-9)
        assertEquals(0.0, empty.normalFraction(), 1e-9)
    }

    @Test fun metrics_nan_for_single_beat() {
        val record = EcgRecord(
            samples = DoubleArray(500),
            sampleRate = 250.0,
            annotations = listOf(Annotation(sampleIndex = 100))
        )
        val tach = RrTachogram.from(record)
        assertTrue(tach.meanRrMs().isNaN())
        assertTrue(tach.sdnnMs().isNaN())
        assertTrue(tach.rmssdMs().isNaN())
    }

    @Test fun variable_rr_produces_nonzero_metrics() {
        // 500ms, 1000ms, 1500ms intervals: clear variability.
        val record = EcgRecord(
            samples = DoubleArray(2000),
            sampleRate = 250.0,
            annotations = listOf(
                Annotation(sampleIndex = 0),
                Annotation(sampleIndex = 125),    // 500 ms
                Annotation(sampleIndex = 375),    // 1000 ms
                Annotation(sampleIndex = 750)     // 1500 ms
            )
        )
        val tach = RrTachogram.from(record)
        assertEquals(3, tach.nIntervals)
        assertEquals(1000.0, tach.meanRrMs(), 1e-9)
        assertTrue(tach.sdnnMs() > 0.0)
        assertTrue(tach.rmssdMs() > 0.0)
    }

    @Test fun rr_ms_and_time_s_are_consistent() {
        val record = uniformRecord(nBeats = 5, rrSamples = 250)
        val tach = RrTachogram.from(record)
        for (i in tach.rrMs.indices) {
            val diffS = tach.timeS[i + 1] - tach.timeS[i]
            val diffMs = diffS * 1000.0
            assertEquals(diffMs, tach.rrMs[i], 1e-6)
        }
    }
}
