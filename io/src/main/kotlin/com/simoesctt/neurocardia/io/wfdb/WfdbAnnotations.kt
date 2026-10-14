package com.simoesctt.neurocardia.io.wfdb

import java.io.File

/**
 * Reads the WFDB .atr annotation file.
 *
 * The file is a stream of byte pairs (little-endian 16-bit values).
 * Each annotation record is encoded as:
 *
 *   byte0 = low bits of the sample offset (or the full low byte of
 *           the offset when combined with the low 2 bits of byte1)
 *   byte1 = (code << 2) | (high 2 bits of offset)
 *
 * so:
 *   code      = byte1 >> 2
 *   offset    = byte0 | ((byte1 & 0x03) << 8)
 *
 * The offset is the number of samples since the previous annotation.
 *
 * A special "skip" record is signalled when byte1 >> 2 == 59. In that
 * case byte0 is unused and the next two byte pairs encode a 32-bit
 * signed little-endian time jump.
 *
 * After the core record, a variable number of "extra fields" may
 * follow. Each begins with a byte pair whose code (byte1 >> 2) is
 * greater than 59. Those fields carry subtype, channel, number, and
 * auxiliary text. We skip them here.
 *
 * Reference: https://physionet.org/physiotools/wag/annot-5.htm
 * This implementation mirrors the reference parser in wfdb-python's
 * annotation.py (proc_core_fields and proc_ann_bytes).
 */
object WfdbAnnotations {

    data class Annotation(
        val sample: Long,
        val code: Int,
        val symbol: String
    )

    /**
     * Standard WFDB annotation codes. Only codes that represent beats
     * or artifacts are included; rhythm markers, comments, and other
     * non-beat annotations are ignored by the reader.
     */
    private val beatCodes = mapOf(
        1 to "N",   // Normal beat
        2 to "L",   // Left bundle branch block beat
        3 to "R",   // Right bundle branch block beat
        4 to "a",   // Aberrated atrial premature beat
        5 to "V",   // Premature ventricular contraction
        6 to "F",   // Fusion of ventricular and normal beat
        7 to "J",   // Nodal (junctional) premature beat
        8 to "A",   // Atrial premature beat
        9 to "S",   // Supraventricular premature or ectopic beat
        10 to "E",  // Ventricular escape beat
        11 to "j",  // Nodal (junctional) escape beat
        12 to "/",  // Paced beat
        13 to "Q",  // Unclassifiable beat
        16 to "|",  // Isolated QRS-like artifact
        25 to "B",  // Left or right bundle branch block
        30 to "?",  // Learning (used as a beat marker by some corpora)
        34 to "e",  // Atrial escape beat
        35 to "n",  // Supraventricular escape beat
        38 to "f",  // Fusion of paced and normal beat
        41 to "r"   // R-on-T premature ventricular contraction
    )

    fun read(atrFile: File): List<Annotation> {
        require(atrFile.isFile) { "atr file not found: $atrFile" }
        return parse(atrFile.readBytes())
    }

    /**
     * Parse an annotation byte stream. The stream is interpreted as a
     * sequence of byte pairs, which is equivalent to interpreting the
     * file as an array of little-endian 16-bit values. Reading raw
     * bytes and indexing in pairs of 2 matches the reference parser.
     */
    fun parse(bytes: ByteArray): List<Annotation> {
        // Convert the byte array to an Nx2 array of ints in [0, 255].
        val nPairs = bytes.size / 2
        val pairs = Array(nPairs) { i ->
            intArrayOf(
                bytes[i * 2].toInt() and 0xFF,
                bytes[i * 2 + 1].toInt() and 0xFF
            )
        }

        val result = ArrayList<Annotation>()
        var sampleTotal = 0L
        var bpi = 0

        // The final byte pair is a terminating (0, 0) sentinel.
        while (bpi < nPairs - 1) {
            // ── Skip records ────────────────────────────────────
            // A skip is signalled when byte1 >> 2 == 59. The next two
            // byte pairs encode a 32-bit signed little-endian time.
            var sampleDiff = 0L
            while (bpi < nPairs && (pairs[bpi][1] shr 2) == 59) {
                if (bpi + 2 >= nPairs) break
                val skipDiff = (
                    (pairs[bpi + 1][0].toLong() shl 16) +
                    (pairs[bpi + 1][1].toLong() shl 24) +
                    (pairs[bpi + 2][0].toLong() shl 0) +
                    (pairs[bpi + 2][1].toLong() shl 8)
                )
                val signed = if (skipDiff > 2147483647L) skipDiff - 4294967296L
                             else skipDiff
                sampleDiff += signed
                bpi += 3
            }

            if (bpi >= nPairs - 1) break

            // ── Core record ─────────────────────────────────────
            val byte0 = pairs[bpi][0]
            val byte1 = pairs[bpi][1]
            val code = byte1 shr 2
            sampleDiff += byte0.toLong() + 256L * (byte1 and 0x03).toLong()
            bpi += 1

            sampleTotal += sampleDiff

            val symbol = beatCodes[code]
            if (symbol != null) {
                result.add(Annotation(sampleTotal, code, symbol))
            }

            // ── Extra fields ────────────────────────────────────
            // While the next pair's code is > 59, it belongs to this
            // annotation. Skip it. The final pair of the file is
            // (0, 0), which terminates.
            while (bpi < nPairs && (pairs[bpi][1] shr 2) > 59) {
                val extraCode = pairs[bpi][1] shr 2
                when (extraCode) {
                    60 -> bpi += 2   // aux_note: byte0 = length, next pair(s) = text
                    61 -> bpi += 2   // chan
                    62 -> bpi += 2   // num
                    63 -> bpi += 2   // subtype
                    else -> bpi += 1
                }
            }
        }

        return result
    }
}
