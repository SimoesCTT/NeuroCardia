package com.simoesctt.neurocardia.validation

import com.simoesctt.kuromoto.engine.KuramotoNetwork
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs the Kuramoto network over a per-beat scalar series and returns
 * the CSI trajectory and summary statistics.
 *
 * For the standard pipeline the series is the RR interval series in
 * milliseconds. For experimental runs the series can be any positive
 * per-beat scalar (amplitude, QRS energy, correlation, etc.). The
 * phase mapping formula is scale-invariant: each beat advances the
 * phase by 2*pi * (x_k / mean_x), so the mean value of the series
 * cancels out.
 */
object CsiRunner {

    private const val DT = 0.05

    data class Result(
        val rMean: Double,
        val rVar: Double,
        val rSkew: Double,
        val rKurt: Double,
        val rLag1: Double,
        val trajectory: DoubleArray
    )

    fun run(network: KuramotoNetwork, series: List<Double>): Result? {
        if (series.size < 10) return null

        // Make sure the series is positive. Negative values cannot be
        // used as phase advances. Zero values are clamped to a small
        // epsilon to avoid division by zero.
        val positive = series.map { abs(it).coerceAtLeast(1e-9) }
        val mean = positive.average()
        if (mean <= 0.0) return null

        // Cumulative phase at each beat, in [0, 2*pi).
        val phaseAtBeat = DoubleArray(positive.size)
        var cumulative = 0.0
        for (i in positive.indices) {
            cumulative += 2.0 * PI * (positive[i] / mean)
            phaseAtBeat[i] = cumulative % (2.0 * PI)
        }

        // Beat duration in the integration: the phase advances at a
        // rate of one unit per beat on average. We use a nominal beat
        // duration of 1.0 and pick steps accordingly.
        val beatDuration = 1.0
        val stepsPerBeat = (beatDuration / DT).toInt().coerceIn(1, 200)
        val actualDt = beatDuration / stepsPerBeat

        val trajectory = ArrayList<Double>(positive.size * stepsPerBeat)

        for (beat in phaseAtBeat.indices) {
            network.resetHeartPhase(oscIndex = 0, phase = phaseAtBeat[beat])
            repeat(stepsPerBeat) {
                network.step(actualDt)
                trajectory.add(network.orderParameter())
            }
        }

        if (trajectory.isEmpty()) return null

        val m = trajectory.average()
        val variance = trajectory.sumOf { (it - m) * (it - m) } / (trajectory.size - 1)
        val sd = sqrt(variance)
        val skew = if (sd > 1e-12 && trajectory.size >= 3) {
            val n = trajectory.size.toDouble()
            (n / ((n - 1) * (n - 2))) *
                trajectory.sumOf { Math.pow((it - m) / sd, 3.0) }
        } else 0.0
        val kurt = if (sd > 1e-12 && trajectory.size >= 4) {
            val n = trajectory.size.toDouble()
            trajectory.sumOf { Math.pow((it - m) / sd, 4.0) } / n - 3.0
        } else 0.0
        val lag1 = if (trajectory.size >= 2) {
            var num = 0.0
            var den = 0.0
            for (i in trajectory.indices) {
                val d = trajectory[i] - m
                den += d * d
                if (i > 0) num += (trajectory[i] - m) * (trajectory[i - 1] - m)
            }
            if (den > 1e-12) num / den else 0.0
        } else 0.0

        return Result(
            rMean = m,
            rVar = variance,
            rSkew = skew,
            rKurt = kurt,
            rLag1 = lag1,
            trajectory = trajectory.toDoubleArray()
        )
    }
}
