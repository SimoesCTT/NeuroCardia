package com.simoesctt.neurocardia.io

/**
 * Human-readable descriptions of every metric NeuroCardia computes.
 *
 * Each entry is a one-line description suitable for a printed report.
 * The text avoids jargon where possible. Where a metric is a research
 * feature (not cleared for clinical use), the description says so.
 *
 * This object is the single source of truth for what a metric means.
 * The PDF writers, the CSV exporters, and any future documentation
 * should read from here rather than duplicating the text.
 */
object MetricDescriptions {

    data class Description(
        val short: String,
        val range: String,
        val research: Boolean = false
    )

    private val descriptions: Map<String, Description> = mapOf(
        MetricKeys.HEART_RATE_BPM to Description(
            short = "Mean heart rate",
            range = "40-200 BPM typical"
        ),
        "mean_rr_ms" to Description(
            short = "Mean interval between consecutive R-peaks",
            range = "300-1500 ms typical"
        ),
        MetricKeys.RMSSD_MS to Description(
            short = "Root mean square of successive RR differences. " +
                    "Reflects short-term (beat-to-beat) variability. " +
                    "Lower in heart failure, higher in athletes.",
            range = "20-80 ms healthy adults"
        ),
        MetricKeys.SDNN_MS to Description(
            short = "Standard deviation of normal RR intervals. " +
                    "Reflects overall variability over the recording.",
            range = "30-100 ms over 5 min"
        ),
        MetricKeys.PNN50 to Description(
            short = "Fraction of successive RR differences exceeding 50 ms. " +
                    "Reflects parasympathetic activity.",
            range = "0.05-0.30 healthy adults"
        ),
        "pnn20" to Description(
            short = "Fraction of successive RR differences exceeding 20 ms. " +
                    "Similar to pNN50 but less sparse on short recordings.",
            range = "0.30-0.60 healthy adults"
        ),
        "sd1_ms" to Description(
            short = "Poincare SD1. Short-term variability perpendicular to " +
                    "the line of identity.",
            range = "10-40 ms"
        ),
        "sd2_ms" to Description(
            short = "Poincare SD2. Long-term variability along the line " +
                    "of identity.",
            range = "20-80 ms"
        ),
        "sd1_sd2" to Description(
            short = "Ratio of short-term to long-term variability. " +
                    "Approaches 1 when the two are balanced.",
            range = "0.4-1.5"
        ),
        "sdnm_ms" to Description(
            short = "Standard deviation of normal RR intervals (legacy key).",
            range = "see SDNN"
        ),
        MetricKeys.CSI_R to Description(
            short = "Cardiac Synchronization Index. The order parameter of " +
                    "a Kuramoto coupled-oscillator network driven by the RR " +
                    "tachogram. r -> 1 for highly regular rhythms; " +
                    "r -> 0.25 (the incoherent baseline) for chaotic rhythms.",
            range = "0.0-1.0; > 0.9 indicates strong phase locking",
            research = true
        ),
        MetricKeys.CSI_VAR to Description(
            short = "Variance of the CSI trajectory over the recording. " +
                    "Rises near critical transitions (critical slowing down).",
            range = "unitless",
            research = true
        ),
        MetricKeys.CSI_SKEW to Description(
            short = "Skewness of the CSI trajectory. Reflects asymmetry in " +
                    "the distribution of synchronization states.",
            range = "unitless",
            research = true
        ),
        MetricKeys.CSI_KURT to Description(
            short = "Excess kurtosis of the CSI trajectory. Captures " +
                    "intermittent bursts of incoherence.",
            range = "unitless",
            research = true
        ),
        MetricKeys.CSI_LAG1AC to Description(
            short = "Lag-1 autocorrelation of the CSI trajectory. Approaches " +
                    "1 as the system recovers more slowly from perturbations, " +
                    "a signature of approaching critical transitions.",
            range = "0-1",
            research = true
        ),
        "quality_score" to Description(
            short = "Composite signal quality score in [0, 1]. Combines " +
                    "SNR, flatline, clipping, and baseline metrics.",
            range = "> 0.75 is good, 0.50-0.75 is fair, < 0.50 is poor"
        ),
        "quality_snr_db" to Description(
            short = "Signal-to-noise ratio in dB, computed as the ratio " +
                    "of QRS-band (5-15 Hz) energy to high-frequency " +
                    "(40-100 Hz) noise.",
            range = "> 15 dB is good"
        ),
        "quality_flatline" to Description(
            short = "Fraction of successive samples with negligible " +
                    "difference. High values suggest electrode dropout.",
            range = "< 0.05 typical"
        ),
        "quality_clipping" to Description(
            short = "Fraction of samples at the observed extremes. " +
                    "High values suggest gain saturation.",
            range = "< 0.01 typical"
        ),
        "quality_baseline" to Description(
            short = "Ratio of low-frequency (<0.5 Hz) energy to total. " +
                    "High values indicate motion or respiratory artifact.",
            range = "< 0.3 typical"
        ),
        MetricKeys.CSI_RESIDUAL to Description(
            short = "Component of the CSI orthogonal to RR variability. " +
                    "Computed as csi_mean minus a linear regression on " +
                    "the coefficient of variation of the RR series. At " +
                    "K = 2, this residual separates normal sinus rhythm " +
                    "from atrial fibrillation with AUC 0.89 on the pilot " +
                    "corpus. The regression coefficients are fixed and " +
                    "will be refit when the full reference corpora are " +
                    "available.",
            range = "-0.05 to +0.05 typical on the pilot corpus",
            research = true
        ),
        MetricKeys.RR_CV to Description(
            short = "Coefficient of variation of the RR interval series " +
                    "(standard deviation divided by mean). A standard " +
                    "measure of heart rate variability.",
            range = "0.03-0.15 typical for sinus"
        )
    )

    /**
     * Return the description for a metric key. If the key is not known,
     * returns a Description with the key itself as the short text. This
     * ensures a report never crashes because of a metric added later.
     */
    fun of(key: String): Description {
        return descriptions[key] ?: Description(
            short = key,
            range = ""
        )
    }

    /**
     * All known metric keys, in a canonical order suitable for printing.
     * Established metrics first (alphabetically), then research metrics.
     */
    fun orderedKeys(): List<String> {
        val established = descriptions.entries
            .filter { !it.value.research }
            .map { it.key }
            .sorted()
        val research = descriptions.entries
            .filter { it.value.research }
            .map { it.key }
            .sorted()
        return established + research
    }
}
