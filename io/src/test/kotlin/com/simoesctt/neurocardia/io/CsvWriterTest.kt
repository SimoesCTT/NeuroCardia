package com.simoesctt.neurocardia.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class CsvWriterTest {

    @Test fun writes_header_and_one_row_per_annotation() {
        val record = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 250.0,
            annotations = listOf(
                Annotation(sampleIndex = 100),
                Annotation(sampleIndex = 350),
                Annotation(sampleIndex = 600, type = BeatType.ECTOPIC),
                Annotation(sampleIndex = 850)
            )
        )
        val out = ByteArrayOutputStream()
        CsvWriter.writeRrIntervals(record, out)
        val lines = out.toString(Charsets.UTF_8).trim().lines()

        assertEquals("beat_index,r_peak_sample,r_peak_time_s,rr_ms,beat_type", lines[0])
        assertEquals(5, lines.size)                          // header + 4 beats
        assertTrue(lines[1].startsWith("1,100,"))
        assertTrue(lines[1].endsWith(",0.00,NORMAL"))
        assertTrue(lines[2].startsWith("2,350,"))
        assertTrue(lines[2].endsWith(",1000.00,NORMAL"))     // (350-100)/250 * 1000
        assertTrue(lines[3].endsWith(",1000.00,ECTOPIC"))    // (600-350)/250 * 1000
        assertTrue(lines[4].endsWith(",1000.00,NORMAL"))     // (850-600)/250 * 1000
    }

    @Test fun handles_empty_annotation_list() {
        val record = EcgRecord(samples = DoubleArray(10), sampleRate = 100.0)
        val out = ByteArrayOutputStream()
        CsvWriter.writeRrIntervals(record, out)
        val text = out.toString(Charsets.UTF_8).trim()
        assertEquals("beat_index,r_peak_sample,r_peak_time_s,rr_ms,beat_type", text)
    }

    @Test fun sorts_annotations_by_sample_index() {
        val record = EcgRecord(
            samples = DoubleArray(1000),
            sampleRate = 100.0,
            annotations = listOf(
                Annotation(sampleIndex = 500),
                Annotation(sampleIndex = 100),
                Annotation(sampleIndex = 300)
            )
        )
        val out = ByteArrayOutputStream()
        CsvWriter.writeRrIntervals(record, out)
        val lines = out.toString(Charsets.UTF_8).trim().lines().drop(1)
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("1,100,"))
        assertTrue(lines[1].startsWith("2,300,"))
        assertTrue(lines[2].startsWith("3,500,"))
    }
}
