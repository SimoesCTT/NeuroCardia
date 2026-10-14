package com.simoesctt.neurocardia.validation

import java.io.File

data class CorpusRecord(
    val basePath: String,
    val name: String
)

object Corpus {

    fun findIn(folder: File): List<CorpusRecord> {
        require(folder.isDirectory) { "not a directory: $folder" }
        val records = mutableListOf<CorpusRecord>()
        folder.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            if (!file.name.endsWith(".hea", ignoreCase = true)) return@forEach
            val base = file.absolutePath.substringBeforeLast('.')
            val dat = File("$base.dat")
            if (!dat.isFile) return@forEach
            records.add(CorpusRecord(basePath = base, name = file.nameWithoutExtension))
        }
        return records.sortedBy { it.name }
    }
}
