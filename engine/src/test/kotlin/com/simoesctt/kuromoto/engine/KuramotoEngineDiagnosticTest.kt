package com.simoesctt.kuromoto.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Diagnostic tests for the Kuramoto engine. These are not part of the
 * release test suite — they exist to isolate specific numerical issues
 * from the app-level behavior. Run with `--info` to see the printed
 * trajectories.
 */
class KuramotoEngineDiagnosticTest {

    /**
     * Minimal two-oscillator system. Both oscillators have the same
     * natural frequency, so the only thing that can change the order
     * parameter is the coupling. With positive coupling and time, they
     * should phase-lock and r should approach 1.
     */
    @Test fun two_same_frequency_oscillators_lock_together() {
        val net = KuramotoNetwork(n = 2)
        net.setCouplingTopology(CouplingTopology.ALL_TO_ALL)
        for (osc in net.oscillators) {
            osc.naturalFreq = 1.0
            osc.couplingStrength = 5.0
        }
        // Start them opposite each other. Worst-case initial condition.
        net.oscillators[0].phase = 0.0
        net.oscillators[1].phase = PI

        val rStart = net.orderParameter()
        println("two_osc: r at start = $rStart")

        repeat(10_000) { net.step(0.001) }

        val rEnd = net.orderParameter()
        println("two_osc: r at end   = $rEnd")

        assertTrue(
            "two same-frequency oscillators should lock, r at end = $rEnd",
            rEnd > 0.9
        )
    }

    /**
     * Two oscillators at different frequencies but with strong coupling.
     * Above the critical coupling K_c, they should still lock. Below it,
     * they should drift and r should oscillate.
     */
    @Test fun two_different_frequencies_lock_above_Kc() {
        val net = KuramotoNetwork(n = 2)
        net.setCouplingTopology(CouplingTopology.ALL_TO_ALL)
        // Frequency difference of 1 rad/s. Critical coupling for two
        // oscillators with delta-omega = 1 is K_c = delta-omega = 1
        // (for the symmetric two-oscillator case). Use K = 10 to be safe.
        net.oscillators[0].naturalFreq = 0.0
        net.oscillators[1].naturalFreq = 1.0
        for (osc in net.oscillators) {
            osc.couplingStrength = 10.0
        }
        net.oscillators[0].phase = 0.0
        net.oscillators[1].phase = 0.0

        repeat(20_000) { net.step(0.001) }

        val rEnd = net.orderParameter()
        println("two_diff: r at end = $rEnd")
        assertTrue(
            "two oscillators with K >> K_c should stay locked, r = $rEnd",
            rEnd > 0.8
        )
    }

    /**
     * The reference configuration: 16 oscillators with the Riemann-zero
     * frequency distribution and unnormalized carrier. If this test
     * shows r stuck near 1/sqrt(16) = 0.25, the numerical aliasing
     * problem is confirmed.
     */
    @Test fun reference_frequencies_with_reasonable_dt() {
        val net = KuramotoNetwork(n = 16)
        net.setCouplingTopology(CouplingTopology.ALL_TO_ALL)
        for (osc in net.oscillators) {
            osc.couplingStrength = 1.0
        }

        val rng = java.util.Random(42L)
        for (osc in net.oscillators) {
            osc.phase = rng.nextDouble() * 2.0 * PI
        }

        val rStart = net.orderParameter()
        println("reference: r at start = $rStart")

        repeat(10_000) { net.step(1e-3) }

        val rEnd = net.orderParameter()
        println("reference: r at end   = $rEnd")

        // Do NOT assert on the value. Just print it. The point of this
        // test is to see what the raw engine does with the reference
        // parameters, without any app-level intervention.
    }

    /**
     * Same as above, but with normalized frequencies (subtract the mean,
     * divide by the standard deviation). This is what the app does. If
     * this test produces a different r than the reference, we know the
     * normalization is doing something.
     */
    @Test fun reference_frequencies_normalized() {
        val net = KuramotoNetwork(n = 16)
        net.setCouplingTopology(CouplingTopology.ALL_TO_ALL)

        // Extract the gamma values (Riemann zeros) by subtracting OMEGA_T.
        val rawFreqs = net.oscillators.map {
            it.naturalFreq - KuramotoConstants.OMEGA_T
        }
        val mean = rawFreqs.average()
        val centered = rawFreqs.map { it - mean }
        val sd = kotlin.math.sqrt(centered.map { it * it }.average())

        for (i in net.oscillators.indices) {
            net.oscillators[i].naturalFreq = centered[i] / sd
            net.oscillators[i].couplingStrength = 1.0
        }

        val rng = java.util.Random(42L)
        for (osc in net.oscillators) {
            osc.phase = rng.nextDouble() * 2.0 * PI
        }

        val rStart = net.orderParameter()
        println("normalized: r at start = $rStart")

        // Now dt can be small. Use dt = 0.1 to let the phases evolve
        // over a manageable number of steps.
        repeat(10_000) { net.step(0.1) }

        val rEnd = net.orderParameter()
        println("normalized: r at end   = $rEnd")
    }

    /**
     * Same as above but with a much stronger coupling. If the network
     * locks, r should rise sharply.
     */
    @Test fun reference_frequencies_normalized_strong_coupling() {
        val net = KuramotoNetwork(n = 16)
        net.setCouplingTopology(CouplingTopology.ALL_TO_ALL)

        val rawFreqs = net.oscillators.map {
            it.naturalFreq - KuramotoConstants.OMEGA_T
        }
        val mean = rawFreqs.average()
        val centered = rawFreqs.map { it - mean }
        val sd = kotlin.math.sqrt(centered.map { it * it }.average())

        for (i in net.oscillators.indices) {
            net.oscillators[i].naturalFreq = centered[i] / sd
            net.oscillators[i].couplingStrength = 10.0
        }

        val rng = java.util.Random(42L)
        for (osc in net.oscillators) {
            osc.phase = rng.nextDouble() * 2.0 * PI
        }

        val rStart = net.orderParameter()
        println("norm-strong: r at start = $rStart")

        repeat(10_000) { net.step(0.1) }

        val rEnd = net.orderParameter()
        println("norm-strong: r at end   = $rEnd")
    }
}
