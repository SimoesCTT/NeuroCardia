package com.simoesctt.neurocardia.validation

import com.simoesctt.neurocardia.validation.modes.CohortMode
import com.simoesctt.neurocardia.validation.modes.CorrelateMode
import com.simoesctt.neurocardia.validation.modes.DiscriminateMode
import com.simoesctt.neurocardia.validation.modes.DriftCheckMode
import com.simoesctt.neurocardia.validation.modes.MechanismMode
import com.simoesctt.neurocardia.validation.modes.PwaveMode
import com.simoesctt.neurocardia.validation.modes.ResidualMode
import com.simoesctt.neurocardia.validation.modes.SensitivityMode
import com.simoesctt.neurocardia.validation.modes.SlidingResidualMode

fun main(args: Array<String>) {
    if (args.isEmpty()) { printUsage(); return }
    when (args[0]) {
        "sensitivity"      -> SensitivityMode.run(args.drop(1))
        "discriminate"     -> DiscriminateMode.run(args.drop(1))
        "correlate"        -> CorrelateMode.run(args.drop(1))
        "residual"         -> ResidualMode.run(args.drop(1))
        "mechanism"        -> MechanismMode.run(args.drop(1))
        "pwave"            -> PwaveMode.run(args.drop(1))
        "cohort"           -> CohortMode.run(args.drop(1))
        "drift-check"      -> DriftCheckMode.run(args.drop(1))
        "sliding-residual" -> SlidingResidualMode.run(args.drop(1))
        else -> { println("Unknown mode: ${args[0]}"); printUsage() }
    }
}

private fun printUsage() {
    println("""
        Usage:
          validation sensitivity      <corpus-folder> [k1,k2,...]
          validation discriminate     <sinus> <af> [arms] [k] [feature]
          validation correlate        <sinus> <af> [arm] [k]
          validation residual         <sinus> <af> [arm] [k]
          validation mechanism        <folder-a> <folder-b> [arm] [k]
          validation pwave            <folder-a> <folder-b> [arm] [k]
          validation cohort           <source-folder> <criterion> <out-a> <out-b>
          validation drift-check      <corpus-folder> <record> [<record> ...]
          validation sliding-residual <corpus-folder> <record> [arm] [K] [windowSec] [hopSec]
    """.trimIndent())
}
