package com.simoesctt.neurocardia.validation.modes

import com.simoesctt.neurocardia.io.wfdb.WfdbAnnotations
import com.simoesctt.neurocardia.validation.Corpus
import java.io.File

object CohortMode {

    /**
     * Criterion for splitting a corpus. Each criterion produces a
     * scalar per record; records in the bottom quartile go to folder A,
     * the top quartile to folder B.
     *
     * `labelA` and `labelB` are integers written to a `_label.txt` file
     * in each output directory, so the downstream analysis knows which
     * cohort is "positive" and which is "negative".
     */
    enum class Criterion(val metricName: String, val labelA: Int, val labelB: Int) {
        NORMAL_FRACTION("normal_fraction", 1, 0),
        PVC_FRACTION("pvc_fraction", 0, 1),
        APC_FRACTION("apc_fraction", 0, 1),
        ECTOPIC_FRACTION("ectopic_fraction", 0, 1);

        companion object {
            fun fromName(name: String): Criterion? {
                val norm = name.lowercase().replace('-', '_')
                return values().firstOrNull { it.metricName == norm }
            }
        }
    }

    fun run(args: List<String>) {
        if (args.size < 4) {
            println("cohort requires: <source-folder> <criterion> <out-a> <out-b>")
            println("  criterion: normal-fraction | pvc-fraction | apc-fraction | ectopic-fraction")
            return
        }

        val sourceFolder = File(args[0])
        val criterion = Criterion.fromName(args[1])
        val outA = File(args[2])
        val outB = File(args[3])

        if (!sourceFolder.isDirectory) {
            println("Not a directory: $sourceFolder"); return
        }
        if (criterion == null) {
            println("Unknown criterion: ${args[1]}")
            println("Valid: normal-fraction, pvc-fraction, apc-fraction, ectopic-fraction")
            return
        }

        println("Cohort split")
        println("  source:    ${sourceFolder.absolutePath}")
        println("  criterion: ${criterion.metricName}")
        println("  out A:     ${outA.absolutePath}  (label ${criterion.labelA})")
        println("  out B:     ${outB.absolutePath}  (label ${criterion.labelB})")
        println()

        // Compute the criterion value for every record.
        val records = Corpus.findIn(sourceFolder)
        println("Scanning ${records.size} records...")

        val scored = ArrayList<Pair<com.simoesctt.neurocardia.validation.CorpusRecord, Double>>()
        for (rec in records) {
            val score = scoreRecord(rec, criterion)
            if (score != null && !score.isNaN()) {
                scored.add(rec to score)
            } else {
                println("  ${rec.name}: skipped (no annotations or no score)")
            }
        }

        if (scored.size < 8) {
            println("Not enough scored records to split: ${scored.size}")
            return
        }

        // Sort by score.
        scored.sortBy { it.second }
        val n = scored.size
        val q1 = n / 4
        val q3 = 3 * n / 4

        println()
        println("Score distribution (${criterion.metricName}):")
        println("%-10s %10s".format("record", "score"))
        for ((rec, score) in scored) {
            println("%-10s %10.4f".format(rec.name, score))
        }
        println()

        // Bottom quartile to A, top quartile to B.
        val bottom = scored.take(q1)
        val top = scored.drop(q3)

        outA.mkdirs()
        outB.mkdirs()

        copyRecords(bottom, outA)
        copyRecords(top, outB)

        // Write a label file to each output.
        File(outA, "_label.txt").writeText("${criterion.labelA}\n")
        File(outB, "_label.txt").writeText("${criterion.labelB}\n")

        println("Cohort A (label ${criterion.labelA}): ${bottom.size} records")
        for ((rec, score) in bottom) {
            println("  ${rec.name}  score = %.4f".format(score))
        }
        println()
        println("Cohort B (label ${criterion.labelB}): ${top.size} records")
        for ((rec, score) in top) {
            println("  ${rec.name}  score = %.4f".format(score))
        }
    }

    // ─── Scoring ────────────────────────────────────────────────────────

    private fun scoreRecord(
        rec: com.simoesctt.neurocardia.validation.CorpusRecord,
        criterion: Criterion
    ): Double? {
        val atrFile = File("${rec.basePath}.atr")
        if (!atrFile.isFile) return null

        val annotations = try {
            WfdbAnnotations.read(atrFile)
        } catch (t: Throwable) {
            return null
        }
        if (annotations.isEmpty()) return null

        val total = annotations.size.toDouble()
        var normalCount = 0
        var pvcCount = 0
        var apcCount = 0
        var ectopicCount = 0

        for (a in annotations) {
            when (a.symbol) {
                "N" -> normalCount++
                "V" -> { pvcCount++; ectopicCount++ }
                "A", "a", "S", "J" -> { apcCount++; ectopicCount++ }
                "F", "f", "r" -> { ectopicCount++ }
                else -> { /* rhythm markers, artifacts, etc. */ }
            }
        }

        return when (criterion) {
            Criterion.NORMAL_FRACTION -> normalCount / total
            Criterion.PVC_FRACTION -> pvcCount / total
            Criterion.APC_FRACTION -> apcCount / total
            Criterion.ECTOPIC_FRACTION -> ectopicCount / total
        }
    }

    // ─── File copy ──────────────────────────────────────────────────────

    private fun copyRecords(
        records: List<Pair<com.simoesctt.neurocardia.validation.CorpusRecord, Double>>,
        dest: File
    ) {
        val extensions = listOf("hea", "dat", "atr")
        for ((rec, _) in records) {
            for (ext in extensions) {
                val src = File("${rec.basePath}.$ext")
                if (src.isFile) {
                    src.copyTo(File(dest, src.name), overwrite = true)
                }
            }
        }
    }
}
