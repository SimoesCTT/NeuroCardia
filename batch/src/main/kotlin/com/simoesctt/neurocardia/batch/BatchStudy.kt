package com.simoesctt.neurocardia.batch

import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.MetricKeys
import kotlin.math.sqrt

/**
 * Builder for [BatchStudy]. Aggregates a list of [EcgRecord] into
 * cohort statistics. Pure functions, no I/O, no state.
 */
object BatchStudyBuilder {

    fun summarize(
        records: List<EcgRecord>,
        failures: List<FileResult.Failure>,
        sources: List<String>
    ): BatchStudy {
        val rows = records.mapIndexed { i, rec ->
            StudyRow(
                source = sources.getOrNull(i) ?: "record-$i",
                durationS = rec.durationS,
                metrics = rec.analysis?.metrics ?: emptyMap()
            )
        }

        val allKeys = rows.flatMap { it.metrics.keys }.toSortedSet()
        val cohort = LinkedHashMap<String, CohortStats>(allKeys.size)
        for (key in allKeys) {
            val values = rows.mapNotNull { it.metrics[key] }
                .filter { !it.isNaN() }
                .sorted()
            if (values.isNotEmpty()) {
                cohort[key] = cohortStats(key, values)
            }
        }

        // Compute the cohort-level Kuramoto residual fit. Requires
        // at least two records with both csi_r and rr_cv present,
        // and nonzero variance in rr_cv across the cohort.
        val residualFit = computeResidualFit(rows)

        // Attach the residual to each row that has both inputs.
        val rowsWithResidual = if (residualFit.valid) {
            rows.map { row ->
                val csi = row.metrics[MetricKeys.CSI_R]
                val cv = row.metrics["rr_cv"]
                if (csi != null && cv != null && !csi.isNaN() && !cv.isNaN()) {
                    val predicted = residualFit.alpha + residualFit.beta * cv
                    val residual = csi - predicted
                    val newMetrics = row.metrics.toMutableMap()
                    newMetrics["csi_residual"] = residual
                    row.copy(metrics = newMetrics)
                } else {
                    row
                }
            }
        } else {
            rows
        }

        val summary = StudySummary(
            nRecords = records.size,
            nFailures = failures.size,
            totalDurationS = rowsWithResidual.sumOf { it.durationS },
            perRecord = rowsWithResidual,
            cohort = cohort,
            residualFit = residualFit
        )

        return BatchStudy(records, failures, summary)
    }

    /**
     * Compute the OLS regression of CSI on rr_cv across the rows.
     * Returns an invalid fit if the inputs are missing or degenerate.
     */
    private fun computeResidualFit(rows: List<StudyRow>): ResidualFit {
        val pairs = rows.mapNotNull { row ->
            val csi = row.metrics[MetricKeys.CSI_R]
            val cv = row.metrics["rr_cv"]
            if (csi == null || cv == null || csi.isNaN() || cv.isNaN()) null
            else cv to csi
        }
        if (pairs.size < 2) return ResidualFit.INVALID

        val n = pairs.size
        val meanCv = pairs.map { it.first }.average()
        val meanCsi = pairs.map { it.second }.average()

        var sxx = 0.0
        var sxy = 0.0
        for ((cv, csi) in pairs) {
            val dx = cv - meanCv
            val dy = csi - meanCsi
            sxx += dx * dx
            sxy += dx * dy
        }
        if (sxx < 1e-12) return ResidualFit.INVALID

        val beta = sxy / sxx
        val alpha = meanCsi - beta * meanCv

        return ResidualFit(
            valid = true,
            n = n,
            alpha = alpha,
            beta = beta,
            meanCv = meanCv,
            meanCsi = meanCsi
        )
    }

    private fun cohortStats(metric: String, sorted: List<Double>): CohortStats {
        val n = sorted.size
        if (n == 0) {
            return CohortStats(metric, 0,
                Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        }
        val mean = sorted.average()
        val sd = if (n > 1) {
            sqrt(sorted.sumOf { (it - mean) * (it - mean) } / (n - 1))
        } else 0.0
        return CohortStats(
            metric = metric,
            n = n,
            mean = mean,
            median = percentile(sorted, 0.50),
            sd = sd,
            min = sorted.first(),
            max = sorted.last(),
            p25 = percentile(sorted, 0.25),
            p75 = percentile(sorted, 0.75)
        )
    }

    private fun percentile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lower = pos.toInt().coerceIn(0, sorted.size - 1)
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lower
        return sorted[lower] * (1 - frac) + sorted[upper] * frac
    }
}
