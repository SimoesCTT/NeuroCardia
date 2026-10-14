package com.simoesctt.neurocardia.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class CsvReaderTest {

    @Test fun reads_single_column_csv() {
        val csv = """
            0.1
            0.2
            0.3
            0.4
        """.trimIndent()
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv",
            expectedSampleRate = 250.0
        )
        assertEquals(4, record.samples.size)
        assertEquals(250.0, record.sampleRate, 0.0)
        assertEquals(0.1, record.samples[0], 1e-9)
        assertEquals(0.4, record.samples[3], 1e-9)
    }

    @Test fun reads_time_value_csv_and_infers_sample_rate() {
        val csv = """
            t,v
            0.000,0.10
            0.004,0.20
            0.008,0.30
            0.012,0.40
            0.016,0.50
        """.trimIndent()
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv"
        )
        assertEquals(5, record.samples.size)
        assertEquals(250.0, record.sampleRate, 1.0)
        assertEquals("v", record.leadLabel)
    }

    @Test fun skips_comments_and_blanks() {
        val csv = """
            # header comment
            t,v

            0.000,0.10
            # inline comment
            0.004,0.20
            0.008,0.30
        """.trimIndent()
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv"
        )
        assertEquals(3, record.samples.size)
    }

    @Test fun handles_semicolon_delimiter() {
        val csv = "t;v\n0.000;1.0\n0.004;2.0\n0.008;3.0"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv"
        )
        assertEquals(3, record.samples.size)
        assertEquals(1.0, record.samples[0], 1e-9)
    }

    @Test fun handles_tab_delimiter() {
        val csv = "t\tv\n0.000\t1.5\n0.004\t2.5\n0.008\t3.5"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv"
        )
        assertEquals(3, record.samples.size)
        assertEquals(1.5, record.samples[0], 1e-9)
    }

    @Test fun handles_whitespace_delimiter() {
        val csv = "0.000 1.5\n0.004 2.5\n0.008 3.5"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv"
        )
        // Whitespace splits into two columns, first is treated as time.
        assertEquals(3, record.samples.size)
        assertEquals(1.5, record.samples[0], 1e-9)
    }

    @Test fun infers_units_from_magnitude() {
        val mvCsv = "0.1\n0.2\n0.15\n0.12"
        val mv = CsvReader.read(
            ByteArrayInputStream(mvCsv.toByteArray()),
            "mv.csv",
            expectedSampleRate = 100.0
        )
        assertEquals(SignalUnit.MV, mv.units)

        val uvCsv = "100.0\n200.0\n150.0\n120.0"
        val uv = CsvReader.read(
            ByteArrayInputStream(uvCsv.toByteArray()),
            "uv.csv",
            expectedSampleRate = 100.0
        )
        assertEquals(SignalUnit.UV, uv.units)

        val adcCsv = "20000.0\n21000.0\n19500.0\n20100.0"
        val adc = CsvReader.read(
            ByteArrayInputStream(adcCsv.toByteArray()),
            "adc.csv",
            expectedSampleRate = 100.0
        )
        assertEquals(SignalUnit.ADC, adc.units)
    }

    @Test fun records_source_metadata() {
        val csv = "0.1\n0.2\n0.3"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "myfile.csv",
            expectedSampleRate = 100.0
        )
        assertEquals("myfile.csv", record.metadata["source"])
        assertEquals("CsvReader", record.metadata["reader"])
        assertTrue(record.metadata.containsKey("columns"))
    }

    @Test fun uses_fallback_sample_rate_when_no_time_column() {
        val csv = "0.1\n0.2\n0.3"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv",
            fallbackSampleRate = 500.0
        )
        assertEquals(500.0, record.sampleRate, 0.0)
    }

    @Test fun expected_sample_rate_overrides_inference() {
        val csv = "t,v\n0.000,1.0\n0.004,2.0\n0.008,3.0"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv",
            expectedSampleRate = 500.0
        )
        // Even though the time column implies 250 Hz, we asked for 500.
        assertEquals(500.0, record.sampleRate, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun throws_when_no_numeric_data() {
        val csv = "# only comments\n# nothing else"
        CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "empty.csv"
        )
    }

    @Test fun handles_negative_values() {
        val csv = "-0.1\n-0.5\n0.2\n-0.3"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv",
            expectedSampleRate = 100.0
        )
        assertEquals(4, record.samples.size)
        assertEquals(-0.1, record.samples[0], 1e-9)
        assertEquals(-0.5, record.samples[1], 1e-9)
    }

    @Test fun handles_scientific_notation() {
        val csv = "1e-3\n2e-3\n3e-3"
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv",
            expectedSampleRate = 100.0
        )
        assertEquals(3, record.samples.size)
        assertEquals(0.001, record.samples[0], 1e-12)
    }

    @Test fun selects_named_channel_from_multicolumn_file() {
        val csv = """
            t,lead1,lead2
            0.000,1.0,10.0
            0.004,2.0,20.0
            0.008,3.0,30.0
        """.trimIndent()
        val record = CsvReader.read(
            ByteArrayInputStream(csv.toByteArray()),
            sourceName = "test.csv",
            channelName = "lead2"
        )
        assertEquals(3, record.samples.size)
        assertEquals(10.0, record.samples[0], 1e-9)
        assertEquals(20.0, record.samples[1], 1e-9)
    }
}
