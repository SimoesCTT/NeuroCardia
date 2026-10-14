package com.simoesctt.kuromoto.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class KuramotoNetworkTest {

    @Test fun constants_match_reference() {
        assertEquals(1.618033988749895, KuramotoConstants.PHI, 1e-12)
        assertEquals(0.07658726, KuramotoConstants.ALPHA_RH, 1e-7)
        assertEquals(587000.0, KuramotoConstants.OMEGA_T, 0.0)
        assertEquals(0.7473, KuramotoConstants.GRAVITY_WELL, 0.0)
        assertEquals(3.141613597541024, KuramotoConstants.PI_T, 0.0)
        assertEquals(24, KuramotoConstants.RIEMANN_ZEROS.size)
        assertEquals(14.134725, KuramotoConstants.RIEMANN_ZEROS[0], 0.0)
        assertEquals(87.425275, KuramotoConstants.RIEMANN_ZEROS[23], 0.0)
    }

    @Test fun natural_frequencies_follow_riemann_zeros() {
        val net = KuramotoNetwork(n = 16)
        for (i in 0 until 16) {
            val expected = KuramotoConstants.OMEGA_T +
                           KuramotoConstants.RIEMANN_ZEROS[i] * 100.0
            assertEquals("osc $i freq", expected, net.oscillators[i].naturalFreq, 0.0)
        }
    }

    @Test fun order_parameter_is_one_when_all_phases_equal() {
        val net = KuramotoNetwork(n = 8)
        net.setPhases(DoubleArray(8) { 1.234 })
        assertEquals(1.0, net.orderParameter(), 1e-12)
    }

    @Test fun order_parameter_is_zero_for_uniformly_distributed_phases() {
        val n = 8
        val net = KuramotoNetwork(n = n)
        net.setPhases(DoubleArray(n) { 2.0 * PI * it / n })
        assertEquals(0.0, net.orderParameter(), 1e-12)
    }

    @Test fun two_networks_with_identical_state_stay_identical() {
        val a = KuramotoNetwork(n = 16, randomSeed = 42L)
        val b = KuramotoNetwork(n = 16, randomSeed = 42L)
        val init = DoubleArray(16) { 0.1 * it }
        a.setPhases(init); b.setPhases(init)
        a.setCouplingTopology(CouplingTopology.ALL_TO_ALL)
        b.setCouplingTopology(CouplingTopology.ALL_TO_ALL)

        repeat(2000) {
            a.step(1e-3); b.step(1e-3)
        }
        assertEquals(a.orderParameter(), b.orderParameter(), 1e-15)
        for (i in 0 until 16)
            assertEquals(a.oscillators[i].phase, b.oscillators[i].phase, 1e-15)
    }

    /**
     * Tests the coupling term with a random symmetry-breaking perturbation.
     *
     * Two subtleties matter here:
     *
     * 1. The uniform distribution is a fixed point of Kuramoto dynamics at
     *    any coupling because the sum-of-sines is exactly zero by symmetry.
     *    A structured perturbation (e.g., sinusoidal in i) may preserve a
     *    residual symmetry that also gives sumSin = 0. The only safe way to
     *    break the symmetry is to use a random, non-symmetric perturbation.
     *
     * 2. Convergence to synchrony takes simulated time proportional to the
     *    inverse of the coupling strength. We use 20_000 steps at dt=1e-3
     *    (20 s simulated) with K=10, which is ample for the transition.
     *
     * The carrier (OMEGA_T) is set to zero so the only phase advance comes
     * from the coupling sum.
     */
    @Test fun strong_coupling_raises_order_parameter() {
        val weak = KuramotoNetwork(n = 16, randomSeed = 7L)
        val strong = KuramotoNetwork(n = 16, randomSeed = 7L)

        // Random perturbation around uniform. Fixed seed for reproducibility.
        val rng = java.util.Random(42L)
        val init = DoubleArray(16) { i ->
            2.0 * PI * i / 16 + 0.3 * (rng.nextDouble() - 0.5)
        }
        weak.setPhases(init); strong.setPhases(init)
        weak.setCouplingTopology(CouplingTopology.ALL_TO_ALL)
        strong.setCouplingTopology(CouplingTopology.ALL_TO_ALL)

        for (o in weak.oscillators)   { o.naturalFreq = 0.0; o.couplingStrength = 0.0 }
        for (o in strong.oscillators) { o.naturalFreq = 0.0; o.couplingStrength = 10.0 }

        repeat(20_000) { weak.step(1e-3); strong.step(1e-3) }

        assertTrue(
            "strong coupling should out-synchronize weak " +
            "(weak=${weak.orderParameter()}, strong=${strong.orderParameter()})",
            strong.orderParameter() > weak.orderParameter()
        )
        assertTrue(
            "strong coupling should produce meaningful synchronization, " +
            "got r=${strong.orderParameter()}",
            strong.orderParameter() > 0.5
        )
    }

    @Test fun star_topology_produces_single_hub_cluster() {
        val net = KuramotoNetwork(n = 8, randomSeed = 3L)
        net.setCouplingTopology(CouplingTopology.STAR)
        net.oscillators[0].phase = 0.0
        net.oscillators[1].phase = 0.1
        val clusters = net.getClusters(thresholdRad = 0.5)
        assertTrue("hub+leaf cluster present",
            clusters.any { it.contains(0) && it.contains(1) })
    }

    @Test fun reset_heart_phase_pins_oscillator() {
        val net = KuramotoNetwork(n = 16)
        net.oscillators[0].phase = 3.0
        net.resetHeartPhase(oscIndex = 0, phase = 0.0)
        assertEquals(0.0, net.oscillators[0].phase, 0.0)
    }

    @Test fun phase_distance_is_circular() {
        assertEquals(0.1, KuramotoNetwork.phaseDistance(0.1, 0.2), 1e-12)
        assertEquals(0.2,
            KuramotoNetwork.phaseDistance(0.1, 2 * PI - 0.1), 1e-12)
    }
}
