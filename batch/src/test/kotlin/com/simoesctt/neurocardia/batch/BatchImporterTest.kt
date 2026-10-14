package com.simoesctt.neurocardia.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchImporterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun from_folder_finds_supported_extensions_and_sorts() {
        val root = tmp.newFolder("root")
        File(root, "b.csv").writeText("0.1\n0.2\n")
        File(root, "a.csv").writeText("0.1\n0.2\n")
        File(root, "c.edf").writeText("not really edf")
        File(root, "readme.md").writeText("ignore me")

        val inputs = BatchImporter.fromFolder(root)
        assertEquals(3, inputs.size)
        assertEquals("a.csv", inputs[0].displayName)
        assertEquals("b.csv", inputs[1].displayName)
        assertEquals("c.edf", inputs[2].displayName)
    }

    @Test fun from_folder_ignores_unknown_extensions() {
        val root = tmp.newFolder("root2")
        File(root, "readme.md").writeText("x")
        File(root, "image.png").writeText("y")
        File(root, "notes.txt").writeText("z")
        val inputs = BatchImporter.fromFolder(root)
        assertEquals(1, inputs.size)
        assertEquals("notes.txt", inputs[0].displayName)
    }

    @Test fun from_folder_non_recursive_does_not_descend() {
        val root = tmp.newFolder("root3")
        File(root, "top.csv").writeText("x")
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "deep.csv").writeText("y")

        val recursive = BatchImporter.fromFolder(root, recursive = true)
        assertEquals(2, recursive.size)

        val flat = BatchImporter.fromFolder(root, recursive = false)
        assertEquals(1, flat.size)
        assertEquals("top.csv", flat[0].displayName)
    }

    @Test fun from_folder_infers_correct_format() {
        val root = tmp.newFolder("root4")
        File(root, "data.csv").writeText("x")
        File(root, "signal.edf").writeText("x")
        File(root, "record.hea").writeText("x")
        File(root, "record.dat").writeText("x")
        File(root, "record.atr").writeText("x")

        val inputs = BatchImporter.fromFolder(root)
        val byName = inputs.associateBy { it.displayName }

        // CSV and EDF files pass through.
        assertEquals(SourceFormat.CSV, byName["data.csv"]!!.inferredFormat)
        assertEquals(SourceFormat.EDF, byName["signal.edf"]!!.inferredFormat)

        // Only the .hea file of a WFDB record survives the filter.
        // The .dat and .atr siblings are discovered by WfdbReader
        // from the same directory.
        assertEquals(SourceFormat.WFDB, byName["record.hea"]!!.inferredFormat)
        assertTrue("record.dat should be filtered out", byName["record.dat"] == null)
        assertTrue("record.atr should be filtered out", byName["record.atr"] == null)
    }

    @Test fun from_folder_is_case_insensitive_on_extension() {
        val root = tmp.newFolder("root5")
        File(root, "DATA.CSV").writeText("x")
        File(root, "Signal.EDF").writeText("x")
        val inputs = BatchImporter.fromFolder(root)
        assertEquals(2, inputs.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun from_folder_rejects_file_argument() {
        val notAFolder = tmp.newFile("not-a-folder.txt")
        BatchImporter.fromFolder(notAFolder)
    }

    @Test fun from_manifest_reads_paths_ignores_comments_and_blanks() {
        val f1 = tmp.newFile("one.csv").apply { writeText("0.1") }
        val f2 = tmp.newFile("two.csv").apply { writeText("0.1") }
        val manifest = tmp.newFile("list.txt")
        manifest.writeText("""
            # this is a comment
            ${f1.absolutePath}

            ${f2.absolutePath}
        """.trimIndent())

        val inputs = BatchImporter.fromManifest(manifest)
        assertEquals(2, inputs.size)
    }

    @Test fun from_manifest_silently_skips_missing_paths() {
        val existing = tmp.newFile("exists.csv").apply { writeText("x") }
        val manifest = tmp.newFile("list2.txt")
        manifest.writeText("""
            ${existing.absolutePath}
            /path/that/does/not/exist.csv
        """.trimIndent())

        val inputs = BatchImporter.fromManifest(manifest)
        assertEquals(1, inputs.size)
        assertEquals("exists.csv", inputs[0].displayName)
    }

    @Test fun from_manifest_filters_unknown_extensions() {
        val csv = tmp.newFile("a.csv").apply { writeText("x") }
        val png = tmp.newFile("b.png").apply { writeText("x") }
        val manifest = tmp.newFile("list3.txt")
        manifest.writeText("""
            ${csv.absolutePath}
            ${png.absolutePath}
        """.trimIndent())

        val inputs = BatchImporter.fromManifest(manifest)
        assertEquals(1, inputs.size)
        assertEquals("a.csv", inputs[0].displayName)
    }

    @Test fun from_manifest_sorts_by_path() {
        val f1 = tmp.newFile("bbb.csv").apply { writeText("x") }
        val f2 = tmp.newFile("aaa.csv").apply { writeText("x") }
        val manifest = tmp.newFile("list4.txt")
        manifest.writeText("${f1.absolutePath}\n${f2.absolutePath}\n")

        val inputs = BatchImporter.fromManifest(manifest)
        assertEquals("aaa.csv", inputs[0].displayName)
        assertEquals("bbb.csv", inputs[1].displayName)
    }

    @Test fun input_file_carries_size_in_bytes() {
        val root = tmp.newFolder("size-check")
        val f = File(root, "size.csv")
        f.writeText("1234567890")
        val inputs = BatchImporter.fromFolder(root)
        assertEquals(1, inputs.size)
        assertEquals(10L, inputs[0].sizeBytes)
    }
}
