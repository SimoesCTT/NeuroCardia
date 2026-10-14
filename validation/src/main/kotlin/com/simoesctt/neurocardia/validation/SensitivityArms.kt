package com.simoesctt.neurocardia.validation

import com.simoesctt.kuromoto.engine.KuramotoConstants
import com.simoesctt.kuromoto.engine.KuramotoNetwork
import kotlin.math.sqrt

enum class Arm(val label: String) {
    A("Riemann-zero (production)"),
    B("Uniform random"),
    C("Gaussian"),
    D("Physiological scale"),
    E("Linear spacing");

    companion object {
        fun all(): List<Arm> = values().toList()
    }
}

object SensitivityArms {

    const val N = 16
    const val DEFAULT_K = 10.0

    fun build(
        arm: Arm,
        seed: Long = 0L,
        randomSeed: Long = 0L,
        k: Double = DEFAULT_K
    ): KuramotoNetwork {
        val network = KuramotoNetwork(n = N, randomSeed = randomSeed)
        val rng = java.util.Random(seed)

        val freqs = when (arm) {
            Arm.A -> riemannZeros()
            Arm.B -> uniformRandom(riemannZeros(), rng)
            Arm.C -> gaussian(riemannZeros(), rng)
            Arm.D -> physiologicalScale(rng)
            Arm.E -> linearSpacing(riemannZeros())
        }

        val normalized = normalize(freqs)

        for (i in network.oscillators.indices) {
            network.oscillators[i].naturalFreq = normalized[i]
            network.oscillators[i].couplingStrength = k
        }
        return network
    }

    private fun riemannZeros(): DoubleArray {
        val zeros = KuramotoConstants.RIEMANN_ZEROS
        return DoubleArray(N) { zeros[it] * 100.0 }
    }

    private fun uniformRandom(reference: DoubleArray, rng: java.util.Random): DoubleArray {
        val min = reference.min()
        val max = reference.max()
        return DoubleArray(N) { min + rng.nextDouble() * (max - min) }
    }

    private fun gaussian(reference: DoubleArray, rng: java.util.Random): DoubleArray {
        val mean = reference.average()
        val variance = reference.sumOf { (it - mean) * (it - mean) } / reference.size
        val sd = sqrt(variance)
        return DoubleArray(N) { mean + rng.nextGaussian() * sd }
    }

    /**
     * Arm D: physiological scale. Uniform in a narrow band around
     * 2*pi. This is deliberately a different distribution shape from
     * Arm C so that normalisation does not collapse them to the same
     * values.
     */
    private fun physiologicalScale(rng: java.util.Random): DoubleArray {
        return DoubleArray(N) { 2.0 * Math.PI + (rng.nextDouble() - 0.5) }
    }

    private fun linearSpacing(reference: DoubleArray): DoubleArray {
        val min = reference.min()
        val max = reference.max()
        val step = (max - min) / (N - 1)
        return DoubleArray(N) { min + it * step }
    }

    private fun normalize(freqs: DoubleArray): DoubleArray {
        val mean = freqs.average()
        val variance = freqs.sumOf { (it - mean) * (it - mean) } / freqs.size
        val sd = sqrt(variance).coerceAtLeast(1e-12)
        return DoubleArray(freqs.size) { (freqs[it] - mean) / sd }
    }
}
