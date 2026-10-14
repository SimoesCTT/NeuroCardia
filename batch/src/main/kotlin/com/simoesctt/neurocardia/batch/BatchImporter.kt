package com.simoesctt.neurocardia.batch

import java.io.File
import java.util.zip.ZipFile

/**
 * Collects a list of [InputFile] from a folder, a zip archive, or a
 * manifest file. No processing, no parsing, no side effects except
 * reading the file system to enumerate candidates.
 *
 * Files are always sorted by absolute path. Two runs over the same
 * source produce byte-identical lists. This determinism is required
 * for IEC 62304 reproducibility: a batch study must be re-runnable
 * and produce the same output for the same input.
 */
object BatchImporter {

    private val CSV_EXT = setOf("csv", "txt")
    private val EDF_EXT = setOf("edf")
    private val WFDB_EXT = setOf("hea", "dat", "atr")

    /**
     * Walk a folder and return all files with a supported extension.
     *
     * If [recursive] is true, subdirectories are traversed. Results are
     * sorted by absolute path for reproducibility.
     */
    fun fromFolder(folder: File, recursive: Boolean = true): List<InputFile> {
        require(folder.isDirectory) { "$folder is not a directory" }
        val files = if (recursive) {
            folder.walkTopDown()
        } else {
            folder.listFiles()?.asSequence() ?: emptySequence()
        }
        return files
            .filter { it.isFile }
            .map { it.toInputFile() }
            .filter { it.inferredFormat != SourceFormat.UNKNOWN }
            // For WFDB records, only the .hea file is processed as a
            // candidate. The .dat and .atr siblings are discovered by
            // the reader from the same folder.
            .filter { input ->
                input.inferredFormat != SourceFormat.WFDB ||
                    input.file.extension.equals("hea", ignoreCase = true)
            }
            .sortedBy { it.file.absolutePath }
            .toList()
    }

    /**
     * Extract a zip archive into [tempDir] and return the supported
     * files it contains. The caller owns [tempDir] and is responsible
     * for cleaning it up after the batch completes.
     *
     * The zip is read fully before returning; the ZipFile handle is
     * closed before this function returns.
     */
    fun fromZip(zipFile: File, tempDir: File): List<InputFile> {
        require(zipFile.isFile) { "$zipFile is not a file" }
        tempDir.mkdirs()
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val out = File(tempDir, entry.name)
                // Reject zip entries that escape tempDir (zip-slip).
                val canonicalOut = out.canonicalPath
                val canonicalRoot = tempDir.canonicalPath
                require(canonicalOut.startsWith(canonicalRoot)) {
                    "zip entry escapes tempDir: ${entry.name}"
                }
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return fromFolder(tempDir)
    }

    /**
     * Read a manifest file: a newline-delimited list of absolute paths.
     * Blank lines and lines starting with '#' are ignored.
     *
     * Paths that do not resolve to an existing file are silently
     * skipped. This is intentional: a manifest may reference files
     * that have been removed, and the batch should proceed with what
     * is available rather than abort.
     */
    fun fromManifest(manifest: File): List<InputFile> {
        require(manifest.isFile) { "$manifest is not a file" }
        return manifest.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { File(it) }
            .filter { it.exists() && it.isFile }
            .map { it.toInputFile() }
            .filter { it.inferredFormat != SourceFormat.UNKNOWN }
            .sortedBy { it.file.absolutePath }
    }

    /**
     * Infer the source format from the file extension. Case-insensitive.
     * Unknown extensions are mapped to [SourceFormat.UNKNOWN] and are
     * filtered out by the callers above.
     */
    private fun File.toInputFile(): InputFile {
        val ext = extension.lowercase()
        val format = when {
            ext in CSV_EXT  -> SourceFormat.CSV
            ext in EDF_EXT  -> SourceFormat.EDF
            ext in WFDB_EXT -> SourceFormat.WFDB
            else            -> SourceFormat.UNKNOWN
        }
        return InputFile(this, inferredFormat = format)
    }
}
