package com.simoesctt.kuromoto.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 🌀 KURAMOTO ENGINE — Coupled Oscillator Network (Kotlin port)
 * Based on: CTT Quantum Engine (Simões, 2024-2026)
 *
 * Phase-coupled oscillators with temporal resonance.
 * No matrices. No amplitudes. Pure phase synchronization.
 *
 * Ported verbatim from the reference Python implementation. The core
 * update rule, constants, and frequency distribution are unchanged.
 */
object KuramotoConstants {
    val PHI: Double = (1.0 + sqrt(5.0)) / 2.0
    val ALPHA_RH: Double = ln(PHI) / (2.0 * PI)          // ≈ 0.0765872
    const val OMEGA_T: Double = 587_000.0
    const val GRAVITY_WELL: Double = 0.7473
    const val PI_T: Double = 3.141613597541024

    /** First 24 non-trivial Riemann zeros, exactly as in the reference. */
    val RIEMANN_ZEROS: DoubleArray = doubleArrayOf(
        14.134725, 21.022040, 25.010858, 30.424876, 32.935062,
        37.586178, 40.918719, 48.005151, 49.773832, 52.970321,
        56.446248, 59.347044, 60.831779, 65.112544, 67.079811,
        69.546402, 72.067158, 75.704691, 77.144840, 79.337375,
        82.910381, 84.735493, 86.970000, 87.425275
    )
}

enum class CouplingTopology { ALL_TO_ALL, NEAREST_NEIGHBOR, SMALL_WORLD, STAR }

class Oscillator(
    var phase: Double = 0.0,
    var naturalFreq: Double = KuramotoConstants.OMEGA_T,
    var couplingStrength: Double = 1.0,
    var damping: Double = 0.01,
    var amplitude: Double = 1.0
) {
    /**
     * Kuramoto equation: dθ/dt = ω + (K/N) * Σ sin(θ_j − θ_i)
     * [sumSin] is the already-weighted Σ term provided by the network.
     */
    fun update(dt: Double, sumSin: Double) {
        val coupling = couplingStrength * sumSin
        phase += (naturalFreq + coupling) * dt
        phase %= (2.0 * PI)
        if (phase < 0.0) phase += 2.0 * PI
    }
}

class KuramotoNetwork(
    val n: Int = 16,
    var mode: String = "normal",
    randomSeed: Long = 0L
) {
    val oscillators: MutableList<Oscillator> = ArrayList(n)
    var time: Double = 0.0
        private set

    val history: ArrayDeque<Pair<Double, Double>> = ArrayDeque()

    var couplingMatrix: DoubleArray = DoubleArray(n * n) { 1.0 / n }

    private val rng: java.util.Random = java.util.Random(randomSeed)

    init {
        require(n > 0) { "n must be > 0" }
        for (i in 0 until n) {
            val osc = Oscillator()
            osc.naturalFreq = if (i < KuramotoConstants.RIEMANN_ZEROS.size) {
                KuramotoConstants.OMEGA_T + KuramotoConstants.RIEMANN_ZEROS[i] * 100.0
            } else {
                KuramotoConstants.OMEGA_T + rng.nextGaussian() * 1000.0
            }
            oscillators.add(osc)
        }
    }

    fun setCouplingTopology(topology: CouplingTopology) {
        val m = DoubleArray(n * n)
        when (topology) {
            CouplingTopology.ALL_TO_ALL -> {
                for (i in 0 until n) for (j in 0 until n) if (i != j) m[i * n + j] = 1.0 / n
            }
            CouplingTopology.NEAREST_NEIGHBOR -> {
                for (i in 0 until n) {
                    m[i * n + ((i + 1) % n)] = 0.5
                    m[i * n + ((i - 1 + n) % n)] = 0.5
                }
            }
            CouplingTopology.SMALL_WORLD -> {
                for (i in 0 until n) {
                    m[i * n + ((i + 1) % n)] = 0.5
                    m[i * n + ((i - 1 + n) % n)] = 0.5
                }
                val extra = (n * 0.1).toInt().coerceAtLeast(1)
                repeat(extra) {
                    val a = rng.nextInt(n)
                    val b = rng.nextInt(n)
                    if (a != b) { m[a * n + b] = 0.25; m[b * n + a] = 0.25 }
                }
            }
            CouplingTopology.STAR -> {
                val hub = 0
                for (j in 1 until n) { m[hub * n + j] = 1.0 / (n - 1); m[j * n + hub] = 1.0 }
            }
        }
        couplingMatrix = m
    }

    fun step(dt: Double = 0.001) {
        val phases = DoubleArray(n) { oscillators[it].phase }

        for (i in 0 until n) {
            var sumSin = 0.0
            val thetaI = phases[i]
            val rowOff = i * n
            for (j in 0 until n) {
                if (i == j) continue
                val w = couplingMatrix[rowOff + j]
                sumSin += w * sin(phases[j] - thetaI)
            }
            oscillators[i].update(dt, sumSin)
        }
        time += dt

        if (history.size > HISTORY_MAX) history.removeFirst()
        history.addLast(time to orderParameter())
    }

    fun orderParameter(): Double {
        var re = 0.0
        var im = 0.0
        for (osc in oscillators) {
            re += cos(osc.phase)
            im += sin(osc.phase)
        }
        return sqrt(re * re + im * im) / n
    }

    fun getClusters(thresholdRad: Double = 0.5): List<List<Int>> {
        val clusters = ArrayList<MutableList<Int>>()
        val assigned = BooleanArray(n)
        for (i in 0 until n) {
            if (assigned[i]) continue
            val cluster = mutableListOf(i)
            assigned[i] = true
            for (j in i + 1 until n) {
                if (assigned[j]) continue
                if (phaseDistance(oscillators[i].phase, oscillators[j].phase) < thresholdRad) {
                    cluster.add(j); assigned[j] = true
                }
            }
            clusters.add(cluster)
        }
        return clusters
    }

    fun isSynchronized(): Boolean = orderParameter() > 0.9

    fun resetHeartPhase(oscIndex: Int, phase: Double = 0.0) {
        require(oscIndex in 0 until n)
        oscillators[oscIndex].phase = phase
    }

    fun snapshotPhases(): DoubleArray = DoubleArray(n) { oscillators[it].phase }

    fun setPhases(phases: DoubleArray) {
        require(phases.size == n)
        for (i in 0 until n) oscillators[i].phase = phases[i] % (2.0 * PI)
    }

    companion object {
        private const val HISTORY_MAX = 4096

        fun phaseDistance(a: Double, b: Double): Double {
            val d = abs(a - b) % (2.0 * PI)
            return if (d > PI) 2.0 * PI - d else d
        }
    }
}
