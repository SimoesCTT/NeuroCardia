package com.simoesctt.neurocardia.io

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object CsvReader {

    fun read(
        file: File,
        expectedSampleRate: Double? = null,
        fallbackSampleRate: Double = 250.0,
        channelName: String? = null
    ): EcgRecord {
        file.inputStream().use { input ->
            return read(input, file.name, expectedSampleRate, fallbackSampleRate, channelName)
        }
    }

    fun read(
        input: InputStream,
        sourceName: String,
        expectedSampleRate: Double? = null,
        fallbackSampleRate: Double = 250.0,
        channelName: String? = null
    ): EcgRecord {
        val reader = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
        return read(reader, sourceName, expectedSampleRate, fallbackSampleRate, channelName)
    }

    private fun read(
        reader: BufferedReader,
        sourceName: String,
        expectedSampleRate: Double?,
        fallbackSampleRate: Double,
        channelName: String?
    ): EcgRecord {
        var delimiter: Char? = null
        var header: List<String>? = null
        var valueColumnIndex = -1
        val timeValues = ArrayList<Double>(4096)
        val samples = ArrayList<Double>(4096)
        var hasTimeColumn = false

        reader.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) continue

                if (delimiter == null) {
                    delimiter = sniffDelimiter(line)
                }
                val parts = splitLine(line, delimiter)

                if (header == null) {
                    val looksLikeHeader = parts.any { !isNumeric(it) }
                    if (looksLikeHeader) {
                        header = parts
                        valueColumnIndex = selectValueColumn(header, channelName)
                        hasTimeColumn = valueColumnIndex > 0
                        continue
                    } else {
                        valueColumnIndex = if (parts.size >= 2) 1 else 0
                        hasTimeColumn = valueColumnIndex > 0
                    }
                }

                if (parts.size <= valueColumnIndex) continue

                val value = parseDoubleOrNull(parts[valueColumnIndex]) ?: continue
                if (hasTimeColumn) {
                    val t = parseDoubleOrNull(parts[0])
                    if (t != null) timeValues.add(t)
                }
                samples.add(value)
            }
        }

        require(samples.isNotEmpty()) { "no numeric data found in $sourceName" }

        val sampleRate = expectedSampleRate
            ?: inferSampleRate(timeValues, fallbackSampleRate)

        val units = inferUnits(samples)

        val metadata = mutableMapOf(
            "source" to sourceName,
            "reader" to "CsvReader",
            "columns" to (header?.joinToString("|") ?: "")
        )

        return EcgRecord(
            samples = samples.toDoubleArray(),
            sampleRate = sampleRate,
            units = units,
            leadLabel = header?.getOrNull(valueColumnIndex)?.takeIf { hasTimeColumn },
            metadata = metadata
        )
    }

    private fun sniffDelimiter(line: String): Char {
        val candidates = charArrayOf(',', ';', '\t')
        var best: Char = ' '
        var bestCount = 0
        for (c in candidates) {
            val count = line.count { it == c }
            if (count > bestCount) { bestCount = count; best = c }
        }
        return if (bestCount > 0) best else ' '
    }

    private fun splitLine(line: String, delimiter: Char): List<String> {
        val tokens = if (delimiter == ' ') {
            line.split(Regex("\\s+"))
        } else {
            line.split(delimiter)
        }
        return tokens.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isNumeric(s: String): Boolean = parseDoubleOrNull(s) != null

    private fun parseDoubleOrNull(s: String): Double? = try {
        s.toDouble()
    } catch (_: NumberFormatException) {
        null
    }

    private fun selectValueColumn(header: List<String>, requested: String?): Int {
        if (requested != null) {
            val idx = header.indexOfFirst { it.equals(requested, ignoreCase = true) }
            if (idx >= 0) return idx
        }
        val first = header.firstOrNull()?.lowercase() ?: ""
        val firstIsTime = first in setOf("t", "time", "timestamp", "sec", "seconds", "sample")
        return if (firstIsTime) 1 else 0
    }

    private fun inferSampleRate(timeValues: List<Double>, fallback: Double): Double {
        if (timeValues.size < 3) return fallback
        val diffs = ArrayList<Double>(timeValues.size - 1)
        for (i in 1 until timeValues.size) {
            val d = timeValues[i] - timeValues[i - 1]
            if (d > 0) diffs.add(d)
        }
        if (diffs.isEmpty()) return fallback
        diffs.sort()
        val medianDt = diffs[diffs.size / 2]
        return if (medianDt > 0) 1.0 / medianDt else fallback
    }

    private fun inferUnits(samples: List<Double>): SignalUnit {
        val m = samples.map { kotlin.math.abs(it) }.sorted()
        val median = m[m.size / 2]
        return when {
            median < 10.0    -> SignalUnit.MV
            median < 10_000  -> SignalUnit.UV
            else             -> SignalUnit.ADC
        }
    }
}
