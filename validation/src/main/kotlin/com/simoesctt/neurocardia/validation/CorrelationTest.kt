package com.simoesctt.neurocardia.validation

import com.simoesctt.neurocardia.dsp.Hrv
import com.simoesctt.neurocardia.dsp.PanTompkins
import com.simoesctt.neurocardia.dsp.SignalQuality
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.MetricKeys
import com.simoesctt.neurocardia.io.RrTachogram
import com.simoesctt.neurocardia.io.wfdb.WfdbReader
import java.io.File

object CorrelationTest {

    data class Row(
        val name: String,
        val label: Int,
        val csiMean: Double,
        val csiVar: Double,
        val csiSkew: Double,
        val csiKurt: Double,
        val csiLag1: Double,
        val heartRate: Double,
        val rmssd: Double,
        val sdnn: Double,
        val pnn50: Double,
        val sd1: Double,
        val sd2: Double,
        val sd1Sd2: Double,
        val qualityScore: Double,
        val snrDb: Double,
        val nBeats: Int,
        val meanRr: Double,
        val rrCv: Double,
        val ampMean: Double,
        val ampCv: Double,
        val qrsCorrMean: Double,
        val qrsWidthMean: Double
    )

    val csiFeatures = listOf("csi_mean", "csi_var", "csi_skew", "csi_kurt", "csi_lag1")

    val compareFeatures = listOf(
        "heart_rate", "rmssd", "sdnn", "pnn50", "sd1", "sd2", "sd1_sd2",
        "quality_score", "snr_db", "n_beats", "mean_rr", "rr_cv",
        "amp_mean", "amp_cv", "qrs_corr_mean", "qrs_width_mean"
    )

    fun run(
        folderA: File,
        folderB: File,
        labelA: Int,
        labelB: Int,
        arm: Arm,
        k: Double
    ): List<Row> {
        val rows = mutableListOf<Row>()
        for (rec in Corpus.findIn(folderA)) {
            val r = computeRow(rec, labelA, arm, k)
            if (r != null) rows.add(r)
        }
        for (rec in Corpus.findIn(folderB)) {
            val r = computeRow(rec, labelB, arm, k)
            if (r != null) rows.add(r)
        }
        return rows
    }

    private fun computeRow(rec: CorpusRecord, label: Int, arm: Arm, k: Double): Row? {
        val record = try {
            WfdbReader.read(rec.basePath, attachAnnotations = false)
        } catch (t: Throwable) { return null }

        val maxSamples = (record.sampleRate * 30 * 60).toInt()
        val trimmed = if (record.samples.size > maxSamples) {
            EcgRecord(
                samples = record.samples.copyOfRange(0, maxSamples),
                sampleRate = record.sampleRate,
                units = record.units,
                leadLabel = record.leadLabel,
                metadata = record.metadata
            )
        } else record

        val quality = SignalQuality.compute(trimmed.samples, trimmed.sampleRate)
        val detected = PanTompkins.detect(trimmed)
        val tachogram = RrTachogram.from(detected)
        val rrMs = tachogram.normalRr()
        if (rrMs.size < 20) return null

        val hrv = Hrv.computeAll(rrMs)
        val csiNet = SensitivityArms.build(arm = arm, k = k)
        val csi = CsiRunner.run(csiNet, rrMs) ?: return null

        val ampSeries = FeatureExtractors.extract(detected, Feature.AMP)
        val ampMean = ampSeries.average()
        val ampSd = if (ampSeries.size > 1) {
            kotlin.math.sqrt(ampSeries.sumOf { (it - ampMean) * (it - ampMean) } / (ampSeries.size - 1))
        } else 0.0
        val ampCv = if (ampMean > 1e-9) ampSd / ampMean else 0.0

        val corrSeries = FeatureExtractors.extract(detected, Feature.BEAT_CORR)
        val qrsCorrMean = if (corrSeries.isEmpty()) Double.NaN else corrSeries.average()

        val widthSeries = FeatureExtractors.extract(detected, Feature.QRS_WIDTH)
        val qrsWidthMean = if (widthSeries.isEmpty()) Double.NaN else widthSeries.average()

        val rrMean = rrMs.average()
        val rrSd = kotlin.math.sqrt(rrMs.sumOf { (it - rrMean) * (it - rrMean) } / (rrMs.size - 1))
        val rrCv = if (rrMean > 1e-9) rrSd / rrMean else 0.0

        return Row(
            name = rec.name,
            label = label,
            csiMean = csi.rMean,
            csiVar = csi.rVar,
            csiSkew = csi.rSkew,
            csiKurt = csi.rKurt,
            csiLag1 = csi.rLag1,
            heartRate = hrv[MetricKeys.HEART_RATE_BPM] ?: Double.NaN,
            rmssd = hrv[MetricKeys.RMSSD_MS] ?: Double.NaN,
            sdnn = hrv[MetricKeys.SDNN_MS] ?: Double.NaN,
            pnn50 = hrv[MetricKeys.PNN50] ?: Double.NaN,
            sd1 = hrv["sd1_ms"] ?: Double.NaN,
            sd2 = hrv["sd2_ms"] ?: Double.NaN,
            sd1Sd2 = hrv["sd1_sd2"] ?: Double.NaN,
            qualityScore = quality.score,
            snrDb = quality.snrDb,
            nBeats = detected.annotations.size,
            meanRr = rrMean,
            rrCv = rrCv,
            ampMean = ampMean,
            ampCv = ampCv,
            qrsCorrMean = qrsCorrMean,
            qrsWidthMean = qrsWidthMean
        )
    }

    fun extractCsi(rows: List<Row>, index: Int): DoubleArray =
        rows.map { r ->
            when (index) {
                0 -> r.csiMean
                1 -> r.csiVar
                2 -> r.csiSkew
                3 -> r.csiKurt
                4 -> r.csiLag1
                else -> Double.NaN
            }
        }.toDoubleArray()

    fun extractCompare(rows: List<Row>, index: Int): DoubleArray =
        rows.map { r ->
            when (index) {
                0 -> r.heartRate
                1 -> r.rmssd
                2 -> r.sdnn
                3 -> r.pnn50
                4 -> r.sd1
                5 -> r.sd2
                6 -> r.sd1Sd2
                7 -> r.qualityScore
                8 -> r.snrDb
                9 -> r.nBeats.toDouble()
                10 -> r.meanRr
                11 -> r.rrCv
                12 -> r.ampMean
                13 -> r.ampCv
                14 -> r.qrsCorrMean
                15 -> r.qrsWidthMean
                else -> Double.NaN
            }
        }.toDoubleArray()
}
