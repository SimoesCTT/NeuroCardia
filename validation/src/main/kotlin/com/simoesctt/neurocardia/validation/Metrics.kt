package com.simoesctt.neurocardia.validation

import kotlin.math.abs
import kotlin.math.sqrt

object Metrics {

    fun pearson(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size || a.size < 2) return Double.NaN
        val meanA = a.average()
        val meanB = b.average()
        var num = 0.0
        var sumSqA = 0.0
        var sumSqB = 0.0
        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            sumSqA += da * da
            sumSqB += db * db
        }
        val den = sqrt(sumSqA * sumSqB)
        return if (den < 1e-12) Double.NaN else num / den
    }

    fun dtw(a: DoubleArray, b: DoubleArray, bandFraction: Double = 0.10): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.NaN

        val maxPoints = 10_000
        val aa = if (a.size > maxPoints) DoubleArray(maxPoints) { a[it * a.size / maxPoints] } else a
        val bb = if (b.size > maxPoints) DoubleArray(maxPoints) { b[it * b.size / maxPoints] } else b

        val N = aa.size
        val M = bb.size
        val band = (maxOf(N, M) * bandFraction).toInt().coerceAtLeast(1)
        val inf = Double.MAX_VALUE / 4

        val dp = Array(N) { DoubleArray(M) { inf } }
        dp[0][0] = abs(aa[0] - bb[0])

        for (i in 0 until N) {
            val jStart = maxOf(0, i - band)
            val jEnd = minOf(M - 1, i + band)
            for (j in jStart..jEnd) {
                if (i == 0 && j == 0) continue
                val cost = abs(aa[i] - bb[j])
                var best = inf
                if (i > 0) best = minOf(best, dp[i - 1][j])
                if (j > 0) best = minOf(best, dp[i][j - 1])
                if (i > 0 && j > 0) best = minOf(best, dp[i - 1][j - 1])
                dp[i][j] = cost + best
            }
        }

        val raw = dp[N - 1][M - 1]
        return raw / maxOf(N, M)
    }
}
