package com.simoesctt.neurocardia.validation

import kotlin.math.sqrt

/**
 * Spearman rank correlation coefficient and approximate two-sided
 * p-value.
 *
 * Spearman is Pearson correlation applied to the ranks of the two
 * variables. It is non-parametric: it detects any monotonic
 * relationship, not just linear ones, and it is robust to outliers.
 *
 * The p-value uses the t-approximation:
 *
 *     t = rho * sqrt((n - 2) / (1 - rho^2))
 *
 * with n - 2 degrees of freedom. This is a standard approximation
 * that is accurate for n > 10.
 */
object Spearman {

    data class Result(
        val rho: Double,
        val pValue: Double,
        val n: Int
    )

    fun compute(x: DoubleArray, y: DoubleArray): Result {
        require(x.size == y.size) { "x and y must have equal length" }
        val n = x.size
        if (n < 3) return Result(Double.NaN, Double.NaN, n)

        val rx = ranks(x)
        val ry = ranks(y)

        // Pearson on ranks.
        var sumX = 0.0
        var sumY = 0.0
        for (i in 0 until n) { sumX += rx[i]; sumY += ry[i] }
        val meanX = sumX / n
        val meanY = sumY / n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val a = rx[i] - meanX
            val b = ry[i] - meanY
            num += a * b
            da += a * a
            db += b * b
        }
        val den = sqrt(da * db)
        if (den < 1e-12) return Result(Double.NaN, Double.NaN, n)
        val rho = num / den

        // t-approximation for p-value.
        val pValue = if (kotlin.math.abs(rho) >= 1.0) {
            0.0
        } else {
            val t = rho * sqrt((n - 2).toDouble() / (1.0 - rho * rho))
            twoSidedPFromT(t, n - 2)
        }

        return Result(rho, pValue, n)
    }

    /**
     * Compute the ranks of a double array. Ties get the average of
     * the ranks they span.
     */
    private fun ranks(values: DoubleArray): DoubleArray {
        val n = values.size
        val indices = (0 until n).sortedBy { values[it] }
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && values[indices[j + 1]] == values[indices[i]]) j++
            val avgRank = (i + j) / 2.0 + 1.0
            for (k in i..j) out[indices[k]] = avgRank
            i = j + 1
        }
        return out
    }

    /**
     * Two-sided p-value from a t-statistic with df degrees of freedom.
     * Uses the incomplete beta function via a continued fraction
     * expansion (Lentz's method).
     */
    private fun twoSidedPFromT(t: Double, df: Int): Double {
        val x = df.toDouble() / (df + t * t)
        val a = df.toDouble() / 2.0
        val b = 0.5
        val ib = incompleteBeta(x, a, b)
        return ib
    }

    private fun incompleteBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lbeta = logGamma(a) + logGamma(b) - logGamma(a + b)
        val front = kotlin.math.exp(
            a * kotlin.math.ln(x) + b * kotlin.math.ln(1.0 - x) - lbeta
        ) / a
        // Use continued fraction.
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            front * betacf(x, a, b)
        } else {
            1.0 - front * betacf(1.0 - x, b, a)
        }
    }

    private fun betacf(x: Double, a: Double, b: Double): Double {
        val maxIter = 200
        val eps = 1e-12
        val fpmin = 1e-30
        var qab = a + b
        var qap = a + 1.0
        var qam = a - 1.0
        var c = 1.0
        var d = 1.0 - qab * x / qap
        if (kotlin.math.abs(d) < fpmin) d = fpmin
        d = 1.0 / d
        var h = d
        for (m in 1..maxIter) {
            val m2 = 2 * m
            var aa = m * (b - m) * x / ((qam + m2) * (a + m2))
            d = 1.0 + aa * d
            if (kotlin.math.abs(d) < fpmin) d = fpmin
            c = 1.0 + aa / c
            if (kotlin.math.abs(c) < fpmin) c = fpmin
            d = 1.0 / d
            h *= d * c
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2))
            d = 1.0 + aa * d
            if (kotlin.math.abs(d) < fpmin) d = fpmin
            c = 1.0 + aa / c
            if (kotlin.math.abs(c) < fpmin) c = fpmin
            d = 1.0 / d
            val del = d * c
            h *= del
            if (kotlin.math.abs(del - 1.0) < eps) break
        }
        return h
    }

    /**
     * Log gamma function via the Lanczos approximation. Accurate to
     * about 1e-10 for x > 0.
     */
    private fun logGamma(x: Double): Double {
        val cof = doubleArrayOf(
            76.18009172947146,
            -86.50532032941677,
            24.01409824083091,
            -1.231739572450155,
            0.1208650973866179e-2,
            -0.5395239384953e-5
        )
        var y = x
        val tmp = x + 5.5
        val tmp2 = tmp - (x + 0.5) * kotlin.math.ln(tmp)
        var ser = 1.000000000190015
        for (j in 0..5) {
            y += 1.0
            ser += cof[j] / y
        }
        return -tmp2 + kotlin.math.ln(2.5066282746310005 * ser / x)
    }
}
