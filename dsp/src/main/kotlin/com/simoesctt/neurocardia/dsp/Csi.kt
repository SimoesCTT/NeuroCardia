package com.simoesctt.neurocardia.dsp

import com.simoesctt.kuromoto.engine.CouplingTopology
import com.simoesctt.kuromoto.engine.KuramotoNetwork
import com.simoesctt.neurocardia.io.MetricKeys
import com.simoesctt.neurocardia.io.RrTachogram
import kotlin.math.PI
import kotlin.math.sqrt

object Csi {

    private const val N = 16

    /**
     * Coupling strength. K = 2 is the value at which the residual
     * diagnostic information is maximal on the reference corpus.
     * Higher values saturate the network; lower values invert the
     * residual. See whitepaper Section 5.4 for the empirical basis.
     */
    private const val K = 2.0

    private const val DT = 0.05

    /**
     * Regression coefficients for the residual, fitted on the pilot
     * corpus (9 MIT-BIH NSR records, 9 MIT-BIH AF records) at K = 2
     * with the Riemann-zero frequency distribution:
     *
     *     csi_mean = INTERCEPT + SLOPE * rr_cv
     *
     * These coefficients will be refit when the full reference
     * corpora are available. Until then they define the residual
     * against a fixed reference distribution.
     */
    private const val RESIDUAL_INTERCEPT = 0.6730
    private const val RESIDUAL_SLOPE = -0.0184

    fun compute(tachogram: RrTachogram): Map<String, Double> {
        val rrMs = tachogram.normalRr()
        if (rrMs.size < 10) return emptyMap()

        val rrS = rrMs.map { it / 1000.0 }
        val meanRrS = rrS.average()
        if (meanRrS <= 0.0) return emptyMap()

        val phaseAtBeat = DoubleArray(rrS.size)
        var cumulative = 0.0
        for (i in rrS.indices) {
            cumulative += 2.0 * PI * (meanRrS / rrS[i])
            phaseAtBeat[i] = cumulative % (2.0 * PI)
        }

        val network = KuramotoNetwork(n = N)
        network.setCouplingTopology(CouplingTopology.ALL_TO_ALL)

        val rawFreqs = network.oscillators.map {
            it.naturalFreq - com.simoesctt.kuromoto.engine.KuramotoConstants.OMEGA_T
        }
        val meanFreq = rawFreqs.average()
        val centered = rawFreqs.map { it - meanFreq }
        val sd = sqrt(centered.map { it * it }.average()).coerceAtLeast(1e-12)

        for (i in network.oscillators.indices) {
            network.oscillators[i].naturalFreq = centered[i] / sd
            network.oscillators[i].couplingStrength = K
        }

        val stepsPerBeat = (meanRrS / DT).toInt().coerceIn(1, 200)
        val actualDt = meanRrS / stepsPerBeat

        val rTrajectory = ArrayList<Double>(rrS.size * stepsPerBeat)

        for (beat in phaseAtBeat.indices) {
            network.resetHeartPhase(oscIndex = 0, phase = phaseAtBeat[beat])
            repeat(stepsPerBeat) {
                network.step(actualDt)
                rTrajectory.add(network.orderParameter())
            }
        }

        if (rTrajectory.isEmpty()) return emptyMap()

        val rMean = rTrajectory.average()
        val rVar = variance(rTrajectory)
        val rSd = sqrt(rVar)
        val rSkew = if (rSd > 1e-12) skewness(rTrajectory, rMean, rSd) else 0.0
        val rKurt = if (rSd > 1e-12) kurtosis(rTrajectory, rMean, rSd) else 0.0
        val rLag1 = lag1Autocorr(rTrajectory)

        // Coefficient of variation of the RR series.
        val rrMean = rrS.average()
        val rrSd = sqrt(rrS.sumOf { (it - rrMean) * (it - rrMean) } / (rrS.size - 1))
        val rrCv = if (rrMean > 1e-9) rrSd / rrMean else 0.0

        // Residual against the fixed reference regression.
        val predicted = RESIDUAL_INTERCEPT + RESIDUAL_SLOPE * rrCv
        val residual = rMean - predicted

        return buildMap {
            put(MetricKeys.CSI_R, rMean)
            put(MetricKeys.CSI_VAR, rVar)
            put(MetricKeys.CSI_SKEW, rSkew)
            put(MetricKeys.CSI_KURT, rKurt)
            put(MetricKeys.CSI_LAG1AC, rLag1)
            put(MetricKeys.RR_CV, rrCv)
            put(MetricKeys.CSI_RESIDUAL, residual)
        }
    }

    private fun variance(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        return x.sumOf { (it - m) * (it - m) } / (x.size - 1)
    }

    private fun skewness(x: List<Double>, mean: Double, sd: Double): Double {
        if (x.size < 3) return 0.0
        val n = x.size
        return (n.toDouble() / ((n - 1) * (n - 2))) *
            x.sumOf { Math.pow((it - mean) / sd, 3.0) }
    }

    private fun kurtosis(x: List<Double>, mean: Double, sd: Double): Double {
        if (x.size < 4) return 0.0
        val n = x.size
        val m4 = x.sumOf { Math.pow((it - mean) / sd, 4.0) } / n
        return m4 - 3.0
    }

    private fun lag1Autocorr(x: List<Double>): Double {
        if (x.size < 2) return 0.0
        val m = x.average()
        var num = 0.0
        var den = 0.0
        for (i in x.indices) {
            val d = x[i] - m
            den += d * d
            if (i > 0) num += (x[i] - m) * (x[i - 1] - m)
        }
        return if (den > 1e-12) num / den else 0.0
    }
}
