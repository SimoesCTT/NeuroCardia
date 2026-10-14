package com.simoesctt.neurocardia.io.wfdb

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the binary .dat signal file for a WFDB record.
 *
 * We support two formats, which between them cover the overwhelming
 * majority of PhysioNet records:
 *
 *   Format 16: 16-bit two's-complement little-endian samples.
 *              One sample per 2 bytes. Simple.
 *
 *   Format 212: 12-bit two's-complement samples, two per 3 bytes,
 *               packed as follows. Given three bytes b0, b1, b2 (in
 *               that order), the first sample is
 *                   (b0 & 0xFF) | ((b1 & 0x0F) << 8)
 *               and the second sample is
 *                   ((b2 & 0xFF) << 4) | ((b1 & 0xF0) >> 4)
 *               Both are sign-extended from bit 11.
 *
 * Both formats store ADC integer values. Physical units (mV) are
 * recovered via (adc - baseline) / gain.
 */
object WfdbSignalReader {

    /**
     * Read the samples of one channel from the .dat file.
     *
     * @param datFile the .dat file
     * @param spec the signal specification for the channel
     * @param nSamples the total number of samples to read
     * @param channelIndex which channel (0-based). For format 212,
     *        channels are interleaved sample by sample.
     * @param nSignals total number of signals in the record
     * @return an array of Double holding ADC values (not yet
     *         converted to physical units)
     */
    fun readChannel(
        datFile: File,
        spec: WfdbHeader.SignalSpec,
        nSamples: Int,
        channelIndex: Int,
        nSignals: Int
    ): DoubleArray {
        require(datFile.isFile) { "dat file not found: $datFile" }
        require(nSamples > 0) { "nSamples must be positive" }
        require(channelIndex in 0 until nSignals) { "channelIndex out of range" }

        return when (spec.format) {
            16 -> readFormat16(datFile, nSamples, channelIndex, nSignals)
            212 -> readFormat212(datFile, nSamples, channelIndex, nSignals)
            else -> throw UnsupportedOperationException(
                "WFDB format ${spec.format} not supported. " +
                "Supported formats: 16, 212."
            )
        }
    }

    /**
     * Convert raw ADC values to physical units (mV) via the standard
     * WFDB formula: physical = (adc - baseline) / gain.
     */
    fun toPhysical(adc: DoubleArray, spec: WfdbHeader.SignalSpec): DoubleArray {
        val out = DoubleArray(adc.size)
        for (i in adc.indices) {
            out[i] = (adc[i] - spec.baseline) / spec.gain
        }
        return out
    }

    // ─── Format 16 ─────────────────────────────────────────────────────

    private fun readFormat16(
        datFile: File,
        nSamples: Int,
        channelIndex: Int,
        nSignals: Int
    ): DoubleArray {
        RandomAccessFile(datFile, "r").use { raf ->
            val out = DoubleArray(nSamples)
            val totalSamples = nSamples.toLong() * nSignals
            val bytesNeeded = totalSamples * 2
            val fileLength = raf.length()
            if (fileLength < bytesNeeded) {
                throw IllegalStateException(
                    "dat file too short: ${fileLength} bytes, need $bytesNeeded"
                )
            }

            val buf = ByteArray(2)
            for (i in 0 until nSamples) {
                // Skip to the sample for this channel at position i.
                val sampleOffset = (i.toLong() * nSignals + channelIndex) * 2
                raf.seek(sampleOffset)
                raf.readFully(buf)
                val lo = buf[0].toInt() and 0xFF
                val hi = buf[1].toInt() and 0xFF
                var v = (hi shl 8) or lo
                if (v and 0x8000 != 0) v = v or -0x10000   // sign extend
                out[i] = v.toDouble()
            }
            return out
        }
    }

    // ─── Format 212 ────────────────────────────────────────────────────

    private fun readFormat212(
        datFile: File,
        nSamples: Int,
        channelIndex: Int,
        nSignals: Int
    ): DoubleArray {
        require(nSignals == 1 || nSignals == 2) {
            "format 212 supports 1 or 2 signals, got $nSignals"
        }

        RandomAccessFile(datFile, "r").use { raf ->
            val out = DoubleArray(nSamples)
            val fileLength = raf.length()

            // For a two-channel 212 record, samples are packed
            // alternately: for every 3-byte group, sample A0 and A1
            // belong to signal 0, and the group pairs of successive
            // groups hold signal 1. The exact convention: within a
            // 3-byte group, the two 12-bit samples are the samples
            // for signal 0 and signal 1 respectively (in that order)
            // for one time index.
            //
            // So for two-channel 212, byte offset for time index i is
            // i * 3. For one-channel 212, two samples are stored per
            // 3 bytes, both from the same signal.

            if (nSignals == 2) {
                val bytesNeeded = nSamples.toLong() * 3
                if (fileLength < bytesNeeded) {
                    throw IllegalStateException(
                        "dat file too short for 2ch 212: " +
                        "${fileLength} bytes, need $bytesNeeded"
                    )
                }
                val buf = ByteArray(3)
                for (i in 0 until nSamples) {
                    raf.seek(i.toLong() * 3)
                    raf.readFully(buf)
                    val b0 = buf[0].toInt() and 0xFF
                    val b1 = buf[1].toInt() and 0xFF
                    val b2 = buf[2].toInt() and 0xFF
                    val s0 = signExtend12((b0) or ((b1 and 0x0F) shl 8))
                    val s1 = signExtend12(((b2 and 0xFF) shl 4) or ((b1 and 0xF0) shr 4))
                    out[i] = if (channelIndex == 0) s0.toDouble()
                             else s1.toDouble()
                }
            } else {
                // Single-channel 212: two samples per 3 bytes, both
                // from signal 0.
                val samplesPerGroup = 2
                val groupsNeeded = (nSamples + samplesPerGroup - 1) / samplesPerGroup
                val bytesNeeded = groupsNeeded.toLong() * 3
                if (fileLength < bytesNeeded) {
                    throw IllegalStateException(
                        "dat file too short for 1ch 212: " +
                        "${fileLength} bytes, need $bytesNeeded"
                    )
                }
                val buf = ByteArray(3)
                var i = 0
                var group = 0
                while (i < nSamples) {
                    raf.seek(group.toLong() * 3)
                    raf.readFully(buf)
                    val b0 = buf[0].toInt() and 0xFF
                    val b1 = buf[1].toInt() and 0xFF
                    val b2 = buf[2].toInt() and 0xFF
                    val s0 = signExtend12((b0) or ((b1 and 0x0F) shl 8))
                    out[i++] = s0.toDouble()
                    if (i < nSamples) {
                        val s1 = signExtend12(((b2 and 0xFF) shl 4) or ((b1 and 0xF0) shr 4))
                        out[i++] = s1.toDouble()
                    }
                    group++
                }
            }
            return out
        }
    }

    private fun signExtend12(v: Int): Int {
        return if (v and 0x800 != 0) v or -0x1000 else v
    }
}
