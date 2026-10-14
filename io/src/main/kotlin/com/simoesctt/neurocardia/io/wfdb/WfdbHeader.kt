package com.simoesctt.neurocardia.io.wfdb

import java.io.File

/**
 * Parsed representation of a WFDB .hea header file.
 *
 * The format is documented at https://physionet.org/physiotools/wag/header-5.htm
 *
 * First line (space-separated):
 *   record_name n_signals sample_rate [n_samples]
 *
 * Subsequent lines, one per signal:
 *   file_name format [x_gain[(baseline)]/units] [adc_res] [adc_zero] [init_value] [checksum] [block_size] [description]
 *
 * We parse only the fields we need: the number of signals, the sample
 * rate, the number of samples, and for each signal the file name,
 * format code, gain, and units.
 */
data class WfdbHeader(
    val recordName: String,
    val nSignals: Int,
    val sampleRate: Double,
    val nSamples: Int,
    val signals: List<SignalSpec>,
    val sourcePath: String
) {
    data class SignalSpec(
        val fileName: String,
        val format: Int,
        val gain: Double,
        val baseline: Int,
        val units: String,
        val description: String
    )

    companion object {
        /**
         * Parse a .hea file.
         *
         * @param heaFile the header file (e.g. /path/to/100.hea)
         * @return the parsed header
         * @throws IllegalArgumentException if the header is malformed
         */
        fun parse(heaFile: File): WfdbHeader {
            require(heaFile.isFile) { "header not found: $heaFile" }
            val lines = heaFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            require(lines.isNotEmpty()) { "empty header: $heaFile" }

            // First line
            val first = lines[0].split(Regex("\\s+"))
            require(first.size >= 3) {
                "malformed header first line: ${lines[0]}"
            }

            val recordName = first[0]
            val nSignals = first[1].toInt()
            val sampleRate = parseSampleRate(first[2])
            val nSamples = if (first.size >= 4) {
                first[3].toIntOrNull() ?: 0
            } else 0

            // Signal specification lines
            val signals = ArrayList<SignalSpec>(nSignals)
            for (i in 1..nSignals) {
                require(i < lines.size) {
                    "header declares $nSignals signals but only ${lines.size - 1} present"
                }
                signals.add(parseSignalLine(lines[i]))
            }

            return WfdbHeader(
                recordName = recordName,
                nSignals = nSignals,
                sampleRate = sampleRate,
                nSamples = nSamples,
                signals = signals,
                sourcePath = heaFile.absolutePath
            )
        }

        /**
         * The sample rate field can be a plain integer ("360"), a
         * decimal ("250.0"), or a fraction of the form "num/den".
         * Parse all three.
         */
        private fun parseSampleRate(s: String): Double {
            if (s.contains("/")) {
                val parts = s.split("/")
                val num = parts[0].toDouble()
                val den = parts[1].toDouble()
                return num / den
            }
            return s.toDouble()
        }

        /**
         * Parse a signal specification line. The format field
         * determines whether the file is format 16, 212, 310, etc.
         * We parse the file name, format, and gain/units fields. The
         * remaining fields are optional and ignored.
         *
         * Example lines:
         *   100.dat 212 200 11 1024 995 -22131 0 MLII
         *   100.dat 212 200 11 1024 1011 20052 0 V5
         *   100.dat 16 1000 16 0 0 0 0 ECG
         */
        private fun parseSignalLine(line: String): SignalSpec {
            val tokens = line.split(Regex("\\s+"))
            require(tokens.size >= 2) { "malformed signal line: $line" }

            val fileName = tokens[0]
            val format = tokens[1].toInt()

            // WFDB signal specification line:
            //   filename format [gain[(baseline)][/units]] [adc_res] [adc_zero] [init_value] [checksum] [block_size] [description]
            //
            // The full format puts gain in field 2, adc_res in field 3,
            // and adc_zero (the baseline) in field 4. Some simplified
            // headers pack gain and baseline as "gain(baseline)" in
            // field 2. We handle both cases.
            //
            // Defaults if fields are absent:
            //   gain = 200, baseline = 0, units = "mV", adc_res = 11
            var gain = 200.0
            var baseline = 0
            var units = "mV"
            var adcRes = 11

            if (tokens.size >= 3) {
                val g = tokens[2]
                var gainPart = g
                var unitsPart: String? = null
                if (g.contains("/")) {
                    val parts = g.split("/")
                    gainPart = parts[0]
                    unitsPart = parts.getOrNull(1)
                }
                if (gainPart.isNotEmpty()) {
                    // Handle "gain(baseline)" form.
                    if (gainPart.contains("(")) {
                        val p = gainPart.split("(", ")")
                        gain = p[0].toDoubleOrNull() ?: 200.0
                        baseline = p.getOrNull(1)?.toIntOrNull() ?: 0
                    } else {
                        gain = gainPart.toDoubleOrNull() ?: 200.0
                    }
                }
                if (unitsPart != null && unitsPart.isNotEmpty()) {
                    units = unitsPart
                }
            }

            // Field 3 is adc_res, field 4 is adc_zero (baseline). If
            // the gain field already carried a baseline (via the
            // "gain(baseline)" form), we don't override it.
            if (tokens.size >= 5 && !tokens[2].contains("(")) {
                adcRes = tokens[3].toIntOrNull() ?: 11
                baseline = tokens[4].toIntOrNull() ?: 0
            }

            // The description is the last field if there is one, and it
            // is the only field that can contain spaces. We take
            // everything from the last token, which is a rough but
            // good-enough heuristic.
            val description = if (tokens.size >= 9) tokens.last() else ""

            return SignalSpec(
                fileName = fileName,
                format = format,
                gain = gain,
                baseline = baseline,
                units = units,
                description = description
            )
        }
    }
}
