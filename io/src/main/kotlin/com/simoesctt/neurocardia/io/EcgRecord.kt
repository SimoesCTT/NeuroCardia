package com.simoesctt.neurocardia.io

enum class SignalUnit { MV, UV, ADC }

enum class BeatType { NORMAL, ECTOPIC, ARTIFACT }

/**
 * A single R-peak or other beat annotation.
 *
 * Time is NOT stored — it is derived from [sampleIndex] and the record's
 * sample rate. This prevents a class of bugs where a stored time and a
 * computed time disagree after a round-trip through a file format.
 */
data class Annotation(
    val sampleIndex: Int,
    val type: BeatType = BeatType.NORMAL,
    val manual: Boolean = false
) {
    fun timeS(sampleRate: Double): Double = sampleIndex / sampleRate
}

/**
 * Canonical metric keys. One source of truth for metric names. The same
 * key appears in the CSV export header, the JSON schema, and the PDF report.
 */
object MetricKeys {
    const val HEART_RATE_BPM = "heart_rate_bpm"
    const val RMSSD_MS       = "rmssd_ms"
    const val SDNN_MS        = "sdnn_ms"
    const val PNN50          = "pnn50"
    const val CSI_R          = "csi_r"
    const val CSI_SKEW       = "csi_skew"
    const val CSI_KURT       = "csi_kurt"
    const val CSI_LAG1AC     = "csi_lag1ac"
    const val CSI_VAR        = "csi_var"
    const val CSI_RESIDUAL   = "csi_residual"
    const val RR_CV          = "rr_cv"
}

/**
 * Analysis results for a record. All metrics live in a single map keyed
 * by the constants in [MetricKeys]. No named fields that duplicate the map.
 */
data class AnalysisResult(
    val metrics: Map<String, Double> = emptyMap()
)

/**
 * Canonical in-memory representation of an ECG recording.
 *
 * This is a data carrier, not a value object. It deliberately does NOT
 * override equals/hashCode — two records with identical fields are not
 * interchangeable, because the analysis they carry may have been produced
 * under different algorithm versions. Callers that need to compare records
 * compare fields explicitly.
 */
class EcgRecord(
    val samples: DoubleArray,
    val sampleRate: Double,
    val units: SignalUnit = SignalUnit.MV,
    val leadLabel: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val annotations: List<Annotation> = emptyList(),
    val analysis: AnalysisResult? = null
) {
    val durationS: Double get() = samples.size / sampleRate

    fun sampleTimeS(index: Int): Double = index / sampleRate
}
