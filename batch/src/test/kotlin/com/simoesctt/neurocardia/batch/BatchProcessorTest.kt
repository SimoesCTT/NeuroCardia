package com.simoesctt.neurocardia.batch

import com.simoesctt.neurocardia.io.EcgRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchProcessorTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fakeInput(name: String): InputFile {
        val f = File(tmp.root, "$name.csv").apply { writeText("0.1\n0.2\n") }
        return InputFile(f, inferredFormat = SourceFormat.CSV)
    }

    private fun dummyRecord(): EcgRecord =
        EcgRecord(samples = DoubleArray(100), sampleRate = 250.0)

    // ─── Basic behaviour ────────────────────────────────────────────────

    @Test fun processes_all_inputs_and_fires_progress() = runTest {
        val inputs = (1..5).map { fakeInput("in$it") }
        val progressUpdates = mutableListOf<BatchProgress>()

        val processor = BatchProcessor(pipeline = { input ->
            FileResult.Success(dummyRecord(), input.file.absolutePath)
        })

        val results = processor.process(inputs) { progressUpdates.add(it) }

        assertEquals(5, results.size)
        assertTrue(results.all { it is FileResult.Success })
        assertEquals(5, progressUpdates.size)
        assertEquals(5, progressUpdates.last().completed)
        assertEquals(1.0f, progressUpdates.last().fraction, 1e-6f)
    }

    @Test fun preserves_input_order_in_results() = runTest {
        val inputs = (1..5).map { fakeInput("in$it") }
        val processor = BatchProcessor(pipeline = { input ->
            FileResult.Success(dummyRecord(), input.file.absolutePath)
        })
        val results = processor.process(inputs)
        assertEquals(5, results.size)
        for (i in inputs.indices) {
            val r = results[i] as FileResult.Success
            assertEquals(inputs[i].file.absolutePath, r.sourcePath)
        }
    }

    @Test fun handles_empty_input_list() = runTest {
        val processor = BatchProcessor(pipeline = { input ->
            FileResult.Success(dummyRecord(), input.file.absolutePath)
        })
        val progressUpdates = mutableListOf<BatchProgress>()
        val results = processor.process(emptyList()) { progressUpdates.add(it) }
        assertEquals(0, results.size)
        assertEquals(0, progressUpdates.size)
    }

    // ─── Failure isolation ──────────────────────────────────────────────

    @Test fun captures_failures_without_aborting_batch() = runTest {
        val inputs = (1..4).map { fakeInput("in$it") }

        val processor = BatchProcessor(pipeline = { input ->
            val idx = input.displayName.filter { it.isDigit() }.toInt()
            if (idx % 2 == 0) {
                FileResult.Failure(input.file.absolutePath, "synthetic failure")
            } else {
                FileResult.Success(dummyRecord(), input.file.absolutePath)
            }
        })

        val results = processor.process(inputs)
        assertEquals(4, results.size)
        assertEquals(2, results.count { it is FileResult.Success })
        assertEquals(2, results.count { it is FileResult.Failure })
    }

    @Test fun captures_exceptions_as_failures() = runTest {
        val inputs = listOf(fakeInput("in1"), fakeInput("in2"))
        val processor = BatchProcessor(pipeline = { input ->
            if (input.displayName.contains("1")) error("boom")
            else FileResult.Success(dummyRecord(), input.file.absolutePath)
        })
        val results = processor.process(inputs)
        assertEquals(2, results.size)
        val failures = results.filterIsInstance<FileResult.Failure>()
        assertEquals(1, failures.size)
        assertEquals("boom", failures[0].error)
    }

    @Test fun captures_runtime_exceptions_with_class_name_when_message_null() = runTest {
        val inputs = listOf(fakeInput("no-message"))
        val processor = BatchProcessor(pipeline = {
            throw RuntimeException()   // no message
        })
        val results = processor.process(inputs)
        assertEquals(1, results.size)
        val f = results[0] as FileResult.Failure
        assertEquals("RuntimeException", f.error)
    }

    // ─── Progress reporting ─────────────────────────────────────────────

    @Test fun progress_completed_increments_monotonically() = runTest {
        val inputs = (1..5).map { fakeInput("in$it") }
        val progressUpdates = mutableListOf<BatchProgress>()
        val processor = BatchProcessor(pipeline = { input ->
            FileResult.Success(dummyRecord(), input.file.absolutePath)
        })
        processor.process(inputs) { progressUpdates.add(it) }

        assertEquals(5, progressUpdates.size)
        for (i in progressUpdates.indices) {
            assertEquals(i + 1, progressUpdates[i].completed)
            assertEquals(5, progressUpdates[i].total)
        }
    }

    @Test fun progress_fraction_ranges_from_0_to_1() = runTest {
        val inputs = (1..4).map { fakeInput("in$it") }
        val progressUpdates = mutableListOf<BatchProgress>()
        val processor = BatchProcessor(pipeline = { input ->
            FileResult.Success(dummyRecord(), input.file.absolutePath)
        })
        processor.process(inputs) { progressUpdates.add(it) }

        val fractions = progressUpdates.map { it.fraction }
        assertEquals(0.25f, fractions[0], 1e-6f)
        assertEquals(0.50f, fractions[1], 1e-6f)
        assertEquals(0.75f, fractions[2], 1e-6f)
        assertEquals(1.00f, fractions[3], 1e-6f)
    }

    @Test fun progress_carries_current_file_name() = runTest {
        val inputs = listOf(fakeInput("alpha"), fakeInput("beta"))
        val progressUpdates = mutableListOf<BatchProgress>()
        val processor = BatchProcessor(pipeline = { input ->
            FileResult.Success(dummyRecord(), input.file.absolutePath)
        })
        processor.process(inputs) { progressUpdates.add(it) }

        assertEquals("alpha.csv", progressUpdates[0].currentFile)
        assertEquals("beta.csv", progressUpdates[1].currentFile)
    }
}
