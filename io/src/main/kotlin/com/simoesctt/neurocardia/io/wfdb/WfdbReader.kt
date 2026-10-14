package com.simoesctt.neurocardia.io.wfdb

import com.simoesctt.neurocardia.io.Annotation
import com.simoesctt.neurocardia.io.BeatType
import com.simoesctt.neurocardia.io.EcgRecord
import com.simoesctt.neurocardia.io.SignalUnit
import java.io.File

/**
 * Reads a PhysioNet WFDB record (header + signal, and optionally
 * annotations) and returns an EcgRecord suitable for the rest of the
 * NeuroCardia pipeline.
 *
 * The header file determines the number of signals and the sample
 * rate. The signal file is read by [WfdbSignalReader]. If a .atr file
 * is present alongside the header, its beat annotations are attached
 * to the returned record; otherwise the annotations list is empty.
 *
 * Only the first channel is returned, matching the single-lead
 * scope of the application. Records with multiple channels are
 * accepted; the extra channels are ignored.
 */
object WfdbReader {

    /**
     * Read a WFDB record.
     *
     * @param basePath the path to the record without the extension,
     *        e.g. "/path/to/100" for the record 100.hea / 100.dat.
     * @param attachAnnotations if true and a .atr file is present,
     *        its beat annotations are attached to the returned record.
     */
    fun read(basePath: String, attachAnnotations: Boolean = true): EcgRecord {
        val heaFile = File("$basePath.hea")
        require(heaFile.isFile) { "header not found: $heaFile" }

        val header = WfdbHeader.parse(heaFile)

        val signalSpec = header.signals.getOrNull(0)
            ?: error("record has no signals")

        val datFile = resolveSibling(heaFile, signalSpec.fileName)

        val adc = WfdbSignalReader.readChannel(
            datFile = datFile,
            spec = signalSpec,
            nSamples = header.nSamples,
            channelIndex = 0,
            nSignals = header.nSignals
        )
        val physical = WfdbSignalReader.toPhysical(adc, signalSpec)

        val annotations = if (attachAnnotations) {
            val atrFile = File("$basePath.atr")
            if (atrFile.isFile) {
                WfdbAnnotations.read(atrFile).map {
                    Annotation(
                        sampleIndex = it.sample.toInt(),
                        type = classifyBeat(it.symbol)
                    )
                }
            } else emptyList()
        } else emptyList()

        val metadata = mutableMapOf(
            "source" to File(basePath).name,
            "reader" to "WfdbReader",
            "record" to header.recordName,
            "format" to signalSpec.format.toString(),
            "channel" to (signalSpec.description.ifEmpty { "channel-0" })
        )

        return EcgRecord(
            samples = physical,
            sampleRate = header.sampleRate,
            units = SignalUnit.MV,
            leadLabel = signalSpec.description.ifEmpty { null },
            metadata = metadata,
            annotations = annotations
        )
    }

    /**
     * Map a WFDB beat symbol to our BeatType. The mapping is
     * conservative: everything that is not a normal beat is
     * classified as ectopic. This preserves the total number of
     * beats while marking arrhythmic events for exclusion in HRV.
     */
    private fun classifyBeat(symbol: String): BeatType {
        return when (symbol) {
            "N", "n" -> BeatType.NORMAL
            "|" -> BeatType.ARTIFACT
            else -> BeatType.ECTOPIC
        }
    }

    /**
     * The .dat file name in a header can be relative to the header
     * location or an absolute path. Resolve it accordingly.
     */
    private fun resolveSibling(heaFile: File, fileName: String): File {
        val direct = File(fileName)
        if (direct.isAbsolute) return direct
        return File(heaFile.parentFile, fileName)
    }
}
