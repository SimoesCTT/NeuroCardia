package com.simoesctt.neurocardia.io.wfdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WfdbReaderTest {

    private val recordBase = File(System.getProperty("user.home"),
        "physionet/mitdb/100").absolutePath

    private fun haveRecord(): Boolean {
        return File("$recordBase.hea").isFile &&
               File("$recordBase.dat").isFile &&
               File("$recordBase.atr").isFile
    }

    @Test fun reads_header_of_real_record() {
        assumeTrue(haveRecord())
        val header = WfdbHeader.parse(File("$recordBase.hea"))
        assertEquals("100", header.recordName)
        assertTrue(header.nSignals >= 1)
        assertEquals(360.0, header.sampleRate, 1e-6)
        assertTrue(header.nSamples > 100_000)
    }

    @Test fun reads_signal_of_real_record() {
        assumeTrue(haveRecord())
        val record = WfdbReader.read(recordBase, attachAnnotations = false)
        assertEquals(360.0, record.sampleRate, 1e-6)
        assertTrue(record.samples.size > 100_000)
        val maxAbs = record.samples.maxOf { kotlin.math.abs(it) }
        assertTrue("max amplitude $maxAbs not plausible", maxAbs in 0.05..20.0)
    }

    @Test fun diagnostic_annotations() {
        assumeTrue(haveRecord())
        val atrFile = File("$recordBase.atr")
        val bytes = atrFile.readBytes()

        println("---- atr diagnostic ----")
        println("file size: ${bytes.size} bytes")
        println("first 32 bytes (hex):")
        val sb = StringBuilder()
        for (i in 0 until minOf(32, bytes.size)) {
            sb.append("%02x ".format(bytes[i].toInt() and 0xFF))
            if ((i + 1) % 16 == 0) {
                println("  $sb")
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) println("  $sb")

        val annotations = WfdbAnnotations.parse(bytes)
        println("parsed: ${annotations.size}")
        if (annotations.isNotEmpty()) {
            println("first: sample=${annotations.first().sample} code=${annotations.first().code} symbol=${annotations.first().symbol}")
            println("last:  sample=${annotations.last().sample} code=${annotations.last().code} symbol=${annotations.last().symbol}")
        }
        println("expected: ~2000-2300 for MIT-BIH record 100")
        println("------------------------")
    }

    @Test fun reads_annotations_of_real_record() {
        assumeTrue(haveRecord())
        val annotations = WfdbAnnotations.read(File("$recordBase.atr"))
        assertTrue("expected many beats, got ${annotations.size}",
            annotations.size > 100)
    }

    @Test fun reader_returns_record_with_annotations() {
        assumeTrue(haveRecord())
        val record = WfdbReader.read(recordBase, attachAnnotations = true)
        assertNotNull(record)
        assertTrue("expected annotations",
            record.annotations.size > 100)
    }
}
