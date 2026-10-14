package com.simoesctt.neurocardia.batch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Runs a caller-supplied pipeline over a list of input files.
 *
 * The pipeline is a suspend function that takes an [InputFile] and
 * returns a [FileResult]. It is injected, so this class knows nothing
 * about parsing, DSP, or export — it is pure orchestration.
 *
 * Sequential by default. Cancellation is honoured at every file
 * boundary, so a long-running batch can be stopped cleanly.
 *
 * Failures are isolated. If the pipeline throws, the exception is
 * caught and converted to a [FileResult.Failure] with the error
 * message. The batch continues with the next file. Only a real
 * CancellationException propagates.
 */
class BatchProcessor(
    private val pipeline: suspend (InputFile) -> FileResult
) {

    /**
     * Process every input. Emits progress on each file boundary via
     * [onProgress]. Returns one [FileResult] per input, in the same
     * order as the input list.
     *
     * Runs on [Dispatchers.Default]. Callers on Android should invoke
     * this from a coroutine tied to a ViewModel scope so it is
     * cancelled automatically when the UI goes away.
     */
    suspend fun process(
        inputs: List<InputFile>,
        onProgress: (BatchProgress) -> Unit = {}
    ): List<FileResult> = withContext(Dispatchers.Default) {
        val total = inputs.size
        val results = ArrayList<FileResult>(total)
        val startMs = System.currentTimeMillis()

        inputs.forEachIndexed { index, input ->
            coroutineContext.ensureActive()
            val result = runOne(input)
            results.add(result)
            onProgress(
                BatchProgress(
                    total = total,
                    completed = index + 1,
                    currentFile = input.displayName,
                    elapsedMs = System.currentTimeMillis() - startMs
                )
            )
        }

        results
    }

    private suspend fun runOne(input: InputFile): FileResult {
        return try {
            pipeline(input)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FileResult.Failure(
                sourcePath = input.file.absolutePath,
                error = t.message ?: t::class.simpleName ?: "unknown error"
            )
        }
    }
}
