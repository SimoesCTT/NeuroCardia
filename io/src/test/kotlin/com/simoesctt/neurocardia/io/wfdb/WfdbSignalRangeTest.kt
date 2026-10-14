package com.simoesctt.neurocardia.io.wfdb

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WfdbSignalRangeTest {
    private val recordBase = File(System.getProperty("user.home"),
        "physionet/mitdb/100").absolutePath

    private fun haveRecord(): Boolean = File("$recordBase.hea").isFile

    @Test fun signal_is_centered_near_zero() {
        assumeTrue(haveRecord())
        val record = WfdbReader.read(recordBase, attachAnnotations = false)
        val min = record.samples.min()
        val max = record.samples.max()
        val mean = record.samples.average()
        println("signal min=$min max=$max mean=$mean")
        assertTrue("min should be around -2 to -3, got $min", min in -5.0..-0.5)
        assertTrue("max should be around 1 to 2, got $max", max in 0.5..5.0)
        assertTrue("mean should be near 0, got $mean", kotlin.math.abs(mean) < 1.0)
    }
}
