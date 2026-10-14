package com.simoesctt.neurocardia.batch

import com.simoesctt.neurocardia.io.AnalysisResult
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.MetricKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchStudyTest {

    private fun recordWith(
        hr: Double,
        rmssd: Double,
        source: String
    ): EcgRecord = EcgRecord(
        samples = DoubleArray(1000),
        sampleRate = 250.0,
        metadata = mapOf("source" to source),
        analysis = AnalysisResult(
            metrics = mapOf(
                MetricKeys.HEART_RATE_BPM to hr,
                MetricKeys.RMSSD_MS to rmssd
            )
        )
    )

    // ─── Basic aggregation ──────────────────────────────────────────────

    @Test fun computes_cohort_statistics() {
        val records = listOf(
            recordWith(60.0, 40.0, "a.csv"),
            recordWith(70.0, 50.0, "b.csv"),
            recordWith(80.0, 60.0, "c.csv"),
            recordWith(90.0, 30.0, "d.csv")
        )
        val study = BatchStudyBuilder.summarize(
            records = records,
            failures = emptyList(),
            sources = records.map { it.metadata["source"] ?: "" }
        )

        assertEquals(4, study.summary.nRecords)
        assertEquals(0, study.summary.nFailures)
        assertEquals(4, study.summary.perRecord.size)

        val hr = study.summary.cohort[MetricKeys.HEART_RATE_BPM]!!
        assertEquals(4, hr.n)
        assertEquals(75.0, hr.mean, 1e-9)
        assertEquals(75.0, hr.median, 1e-9)
        assertEquals(60.0, hr.min, 1e-9)
        assertEquals(90.0, hr.max, 1e-9)
    }

    @Test fun per_record_rows_carry_source_and_metrics() {
        val records = listOf(
            recordWith(60.0, 40.0, "a.csv"),
            recordWith(70.0, 50.0, "b.csv")
        )
        val study = BatchStudyBuilder.summarize(
            records = records,
            failures = emptyList(),
            sources = listOf("a.csv", "b.csv")
        )
        assertEquals("a.csv", study.summary.perRecord[0].source)
        assertEquals("b.csv", study.summary.perRecord[1].source)
        assertEquals(60.0, study.summary.perRecord[0].metrics[MetricKeys.HEART_RATE_BPM]!!, 1e-9)
    }

    @Test fun total_duration_sums_records() {
        val records = listOf(
            EcgRecord(samples = DoubleArray(250), sampleRate = 250.0,
                metadata = mapOf("source" to "a.csv")),
            EcgRecord(samples = DoubleArray(500), sampleRate = 250.0,
                metadata = mapOf("source" to "b.csv"))
        )
        val study = BatchStudyBuilder.summarize(
            records = records,
            failures = emptyList(),
            sources = listOf("a.csv", "b.csv")
        )
        assertEquals(1.0 + 2.0, study.summary.totalDurationS, 1e-9)
    }

    // ─── Missing metrics and failures ───────────────────────────────────

    @Test fun handles_missing_metrics_per_record() {
        val r1 = recordWith(70.0, 50.0, "a.csv")
        val r2 = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            metadata = mapOf("source" to "b.csv"),
            analysis = AnalysisResult(
                metrics = mapOf(MetricKeys.HEART_RATE_BPM to 80.0)
            )
        )
        val study = BatchStudyBuilder.summarize(
            records = listOf(r1, r2),
            failures = emptyList(),
            sources = listOf("a.csv", "b.csv")
        )
        assertEquals(2, study.summary.cohort[MetricKeys.HEART_RATE_BPM]!!.n)
        assertEquals(1, study.summary.cohort[MetricKeys.RMSSD_MS]!!.n)
    }

    @Test fun records_without_analysis_contribute_no_metrics() {
        val withAnalysis = recordWith(70.0, 50.0, "a.csv")
        val withoutAnalysis = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            metadata = mapOf("source" to "b.csv")
        )
        val study = BatchStudyBuilder.summarize(
            records = listOf(withAnalysis, withoutAnalysis),
            failures = emptyList(),
            sources = listOf("a.csv", "b.csv")
        )
        assertEquals(1, study.summary.cohort[MetricKeys.HEART_RATE_BPM]!!.n)
    }

    @Test fun failures_are_carried_into_summary() {
        val failures = listOf(
            FileResult.Failure("/tmp/bad1.csv", "parse error"),
            FileResult.Failure("/tmp/bad2.csv", "sample rate zero")
        )
        val study = BatchStudyBuilder.summarize(
            records = emptyList(),
            failures = failures,
            sources = emptyList()
        )
        assertEquals(2, study.summary.nFailures)
        assertEquals(0, study.summary.nRecords)
        assertEquals(2, study.failures.size)
    }

    // ─── Percentile method ──────────────────────────────────────────────

    @Test fun percentile_matches_numpy_default() {
        // numpy.percentile([10, 20, 30, 40], 25) == 17.5
        // numpy.percentile([10, 20, 30, 40], 50) == 25.0
        // numpy.percentile([10, 20, 30, 40], 75) == 32.5
        val records = listOf(10.0, 20.0, 30.0, 40.0).mapIndexed { i, v ->
            EcgRecord(
                samples = DoubleArray(10),
                sampleRate = 100.0,
                metadata = mapOf("source" to "r$i"),
                analysis = AnalysisResult(
                    metrics = mapOf(MetricKeys.RMSSD_MS to v)
                )
            )
        }
        val study = BatchStudyBuilder.summarize(
            records,
            emptyList(),
            records.map { it.metadata["source"]!! }
        )
        val stats = study.summary.cohort[MetricKeys.RMSSD_MS]!!
        assertEquals(17.5, stats.p25, 1e-9)
        assertEquals(25.0, stats.median, 1e-9)
        assertEquals(32.5, stats.p75, 1e-9)
    }

    // ─── Standard deviation ─────────────────────────────────────────────

    @Test fun sd_uses_sample_denominator() {
        // [10, 20, 30, 40]: mean = 25, sum sq dev = 500,
        // sample variance = 500 / 3 = 166.666..., SD = 12.9099...
        val records = listOf(10.0, 20.0, 30.0, 40.0).mapIndexed { i, v ->
            EcgRecord(
                samples = DoubleArray(10),
                sampleRate = 100.0,
                metadata = mapOf("source" to "r$i"),
                analysis = AnalysisResult(
                    metrics = mapOf(MetricKeys.RMSSD_MS to v)
                )
            )
        }
        val study = BatchStudyBuilder.summarize(
            records,
            emptyList(),
            records.map { it.metadata["source"]!! }
        )
        val stats = study.summary.cohort[MetricKeys.RMSSD_MS]!!
        assertEquals(12.909944487358056, stats.sd, 1e-9)
    }

    @Test fun sd_is_zero_for_single_value() {
        val records = listOf(recordWith(70.0, 50.0, "only.csv"))
        val study = BatchStudyBuilder.summarize(
            records,
            emptyList(),
            listOf("only.csv")
        )
        assertEquals(0.0, study.summary.cohort[MetricKeys.RMSSD_MS]!!.sd, 1e-9)
    }

    // ─── Metric key discovery ───────────────────────────────────────────

    @Test fun cohort_includes_union_of_all_metric_keys() {
        val r1 = EcgRecord(
            samples = DoubleArray(10),
            sampleRate = 100.0,
            metadata = mapOf("source" to "a.csv"),
            analysis = AnalysisResult(metrics = mapOf("alpha" to 1.0, "beta" to 2.0))
        )
        val r2 = EcgRecord(
            samples = DoubleArray(10),
            sampleRate = 100.0,
            metadata = mapOf("source" to "b.csv"),
            analysis = AnalysisResult(metrics = mapOf("beta" to 3.0, "gamma" to 4.0))
        )
        val study = BatchStudyBuilder.summarize(
            listOf(r1, r2),
            emptyList(),
            listOf("a.csv", "b.csv")
        )
        assertTrue(study.summary.cohort.containsKey("alpha"))
        assertTrue(study.summary.cohort.containsKey("beta"))
        assertTrue(study.summary.cohort.containsKey("gamma"))
    }

    @Test fun cohort_keys_are_sorted() {
        val r = EcgRecord(
            samples = DoubleArray(10),
            sampleRate = 100.0,
            metadata = mapOf("source" to "a.csv"),
            analysis = AnalysisResult(metrics = mapOf("zebra" to 1.0, "alpha" to 2.0, "mike" to 3.0))
        )
        val study = BatchStudyBuilder.summarize(listOf(r), emptyList(), listOf("a.csv"))
        val keys = study.summary.cohort.keys.toList()
        assertEquals(listOf("alpha", "mike", "zebra"), keys)
    }

    // ─── Empty study ────────────────────────────────────────────────────

    @Test fun empty_study_has_zero_records_and_no_cohort() {
        val study = BatchStudyBuilder.summarize(
            records = emptyList(),
            failures = emptyList(),
            sources = emptyList()
        )
        assertEquals(0, study.summary.nRecords)
        assertEquals(0, study.summary.nFailures)
        assertTrue(study.summary.cohort.isEmpty())
        assertTrue(study.summary.perRecord.isEmpty())
    }

    @Test fun source_falls_back_when_sources_list_is_short() {
        val records = listOf(
            recordWith(70.0, 50.0, "a.csv"),
            recordWith(80.0, 60.0, "b.csv")
        )
        // Only provide one source; second should fall back to "record-1".
        val study = BatchStudyBuilder.summarize(
            records = records,
            failures = emptyList(),
            sources = listOf("a.csv")
        )
        assertEquals("a.csv", study.summary.perRecord[0].source)
        assertEquals("record-1", study.summary.perRecord[1].source)
    }
}
