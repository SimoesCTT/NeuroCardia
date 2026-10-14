package com.simoesctt.neurocardia.validation

import com.simoesctt.neurocardia.dsp.PanTompkins
import com.simoesctt.neurocardia.io.wfdb.WfdbReader
import java.io.File

object DiscriminativeTest {

    data class PerRecord(
        val name: String,
        val label: Int,
        val csi: Double,
        val nIntervals: Int
    )

    data class Result(
        val arm: Arm,
        val k: Double,
        val feature: Feature,
        val auc: Double,
        val records: List<PerRecord>,
        val nSinus: Int,
        val nAf: Int
    )

    fun run(
        sinusFolder: File,
        afFolder: File,
        arm: Arm,
        k: Double,
        feature: Feature = Feature.RR
    ): Result {
        val sinusRecords = Corpus.findIn(sinusFolder)
        val afRecords = Corpus.findIn(afFolder)

        println("  sinus records: ${sinusRecords.size}")
        println("  AF records:    ${afRecords.size}")

        val perRecord = mutableListOf<PerRecord>()

        for (rec in sinusRecords) {
            val csi = computeCsi(rec, arm, k, feature)
            if (csi != null) {
                perRecord.add(PerRecord(rec.name, 0, csi.csi, csi.nIntervals))
                println("    %-10s sinus csi=%.4f n=%d".format(rec.name, csi.csi, csi.nIntervals))
            } else {
                println("    %-10s sinus SKIPPED".format(rec.name))
            }
        }

        for (rec in afRecords) {
            val csi = computeCsi(rec, arm, k, feature)
            if (csi != null) {
                perRecord.add(PerRecord(rec.name, 1, csi.csi, csi.nIntervals))
                println("    %-10s AF    csi=%.4f n=%d".format(rec.name, csi.csi, csi.nIntervals))
            } else {
                println("    %-10s AF    SKIPPED".format(rec.name))
            }
        }

        val scores = perRecord.map { it.csi }.toDoubleArray()
        val labels = perRecord.map { it.label }.toIntArray()
        val roc = Roc.compute(scores, labels)

        return Result(arm, k, feature, roc.auc, perRecord, roc.nNegative, roc.nPositive)
    }

    private data class CsiValue(val csi: Double, val nIntervals: Int)

    private fun computeCsi(
        rec: CorpusRecord,
        arm: Arm,
        k: Double,
        feature: Feature
    ): CsiValue? {
        val record = try {
            WfdbReader.read(rec.basePath, attachAnnotations = false)
        } catch (t: Throwable) { return null }

        val maxSamples = (record.sampleRate * 30 * 60).toInt()
        val trimmed = if (record.samples.size > maxSamples) {
            com.simoesctt.neurocardia.io.EcgRecord(
                samples = record.samples.copyOfRange(0, maxSamples),
                sampleRate = record.sampleRate,
                units = record.units,
                leadLabel = record.leadLabel,
                metadata = record.metadata
            )
        } else record

        val detected = PanTompkins.detect(trimmed)

        // For RR, use the standard path directly (values are already
        // milliseconds).
        val series: List<Double> = if (feature == Feature.RR) {
            val rr = com.simoesctt.neurocardia.io.RrTachogram.from(detected).normalRr()
            if (rr.size < 10) return null
            rr
        } else {
            val raw = FeatureExtractors.extract(detected, feature)
            if (raw.size < 10) return null
            val mean = raw.average()
            if (mean < 1e-9) return null
            // Rescale so the mean matches the RR pipeline's expected
            // value (1000 ms). This preserves the relative variability
            // of the feature while satisfying the pipeline's
            // assumptions about scale.
            raw.map { it * (1000.0 / mean) }
        }

        val network = SensitivityArms.build(arm = arm, k = k)
        val result = CsiRunner.run(network, series) ?: return null

        return CsiValue(result.rMean, series.size)
    }
}
