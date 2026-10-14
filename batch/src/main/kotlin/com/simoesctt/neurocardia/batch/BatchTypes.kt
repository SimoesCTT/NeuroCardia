package com.simoesctt.neurocardia.batch

import com.simoesctt.neurocardia.io.EcgRecord
import java.io.File

/**
 * One file to process. The importer produces a list of these; the
 * processor consumes them one at a time.
 */
data class InputFile(
    val file: File,
    val displayName: String = file.name,
    val inferredFormat: SourceFormat = SourceFormat.UNKNOWN,
    val sizeBytes: Long = file.length()
)

/**
 * Source format inferred from the file extension. The magic-byte check
 * is a later concern; for batch discovery, extension is enough.
 */
enum class SourceFormat { CSV, EDF, WFDB, UNKNOWN }

/**
 * Result of processing one input file. Never throws. Failures carry
 * the error message so a study can report what went wrong without
 * aborting the batch.
 */
sealed class FileResult {
    data class Success(
        val record: EcgRecord,
        val sourcePath: String
    ) : FileResult()

    data class Failure(
        val sourcePath: String,
        val error: String
    ) : FileResult()
}

/**
 * Progress callback payload. Fired on every file boundary. Callers
 * render this as a progress bar; the fraction is convenient for that.
 */
data class BatchProgress(
    val total: Int,
    val completed: Int,
    val currentFile: String?,
    val elapsedMs: Long
) {
    val fraction: Float
        get() = if (total == 0) 0f else completed.toFloat() / total
}

/**
 * A batch study: the collected records plus the summary statistics.
 * Failures are carried forward so the study reports them honestly.
 */
data class BatchStudy(
    val records: List<EcgRecord>,
    val failures: List<FileResult.Failure>,
    val summary: StudySummary
)

/**
 * Cross-record summary. One row per record, one set of cohort stats
 * per metric.
 */
/**
 * The cohort-level Kuramoto residual fit.
 *
 * The residual is defined by ordinary least squares regression of the
 * Cardiac Synchronization Index (CSI, key csi_r) on the coefficient
 * of variation of the RR series (key rr_cv), across all records in
 * the batch. The residual for each record is the difference between
 * its observed CSI and the value predicted by the fit.
 *
 * If fewer than two records have both inputs, or if rr_cv has zero
 * variance across the cohort, the fit is undefined and valid is false.
 */
data class ResidualFit(
    val valid: Boolean,
    val n: Int,
    val alpha: Double,
    val beta: Double,
    val meanCv: Double,
    val meanCsi: Double
) {
    companion object {
        val INVALID = ResidualFit(false, 0, 0.0, 0.0, 0.0, 0.0)
    }
}

data class StudySummary(
    val nRecords: Int,
    val nFailures: Int,
    val totalDurationS: Double,
    val perRecord: List<StudyRow>,
    val cohort: Map<String, CohortStats>,
    val residualFit: ResidualFit = ResidualFit.INVALID
)

/**
 * One row of the per-record table.
 */
data class StudyRow(
    val source: String,
    val durationS: Double,
    val metrics: Map<String, Double>
)

/**
 * Cohort statistics for a single metric across all records.
 * n is the number of records that contributed a non-NaN value for
 * this metric. It may be smaller than [StudySummary.nRecords] when
 * some records lack the metric (e.g. CSI requires a long recording).
 */
data class CohortStats(
    val metric: String,
    val n: Int,
    val mean: Double,
    val median: Double,
    val sd: Double,
    val min: Double,
    val max: Double,
    val p25: Double,
    val p75: Double
)
