package com.simoesctt.neurocardia.validation

/**
 * Residual analysis. Fits a linear regression of a target feature
 * (usually csi_mean) against a predictor feature (usually rr_cv), then
 * correlates the residuals against every comparison feature.
 *
 * The point: if the CSI correlates strongly with rr_cv (as the
 * correlation matrix shows), then most of the CSI is explained by
 * RR variability alone. The residual is what the CSI adds. If the
 * residual correlates with nothing, the CSI is redundant with HRV.
 * If it correlates with something — especially with the diagnosis
 * label — then the CSI carries incremental information.
 */
object ResidualAnalysis {

    data class Fit(
        val a: Double,         // intercept
        val b: Double,         // slope
        val residuals: DoubleArray
    )

    /**
     * Fit y = a + b*x by ordinary least squares. Returns the
     * coefficients and the residuals (y - predicted).
     */
    fun fit(x: DoubleArray, y: DoubleArray): Fit {
        require(x.size == y.size)
        val n = x.size
        var sx = 0.0; var sy = 0.0
        for (i in 0 until n) { sx += x[i]; sy += y[i] }
        val mx = sx / n; val my = sy / n
        var num = 0.0; var den = 0.0
        for (i in 0 until n) {
            num += (x[i] - mx) * (y[i] - my)
            den += (x[i] - mx) * (x[i] - mx)
        }
        val b = if (den < 1e-12) 0.0 else num / den
        val a = my - b * mx
        val residuals = DoubleArray(n) { y[it] - (a + b * x[it]) }
        return Fit(a, b, residuals)
    }

    data class Result(
        val feature: String,
        val rho: Double,
        val pValue: Double,
        val n: Int
    )

    /**
     * Correlate residuals against each comparison feature. Skips
     * features with too many NaN values.
     */
    fun correlateResiduals(
        residuals: DoubleArray,
        rows: List<CorrelationTest.Row>
    ): List<Result> {
        val out = mutableListOf<Result>()
        val featureNames = CorrelationTest.compareFeatures
        for ((j, name) in featureNames.withIndex()) {
            val compVec = CorrelationTest.extractCompare(rows, j)
            val pairs = residuals.indices.filter {
                !residuals[it].isNaN() && !compVec[it].isNaN()
            }
            if (pairs.size < 3) continue
            val x = DoubleArray(pairs.size) { residuals[pairs[it]] }
            val y = DoubleArray(pairs.size) { compVec[pairs[it]] }
            val s = Spearman.compute(x, y)
            out.add(Result(name, s.rho, s.pValue, s.n))
        }
        return out
    }

    /**
     * Correlate residuals against the diagnosis label (0 = sinus, 1 = AF).
     * A non-zero correlation here means the CSI residual separates the
     * two cohorts.
     */
    fun correlateAgainstLabel(
        residuals: DoubleArray,
        rows: List<CorrelationTest.Row>
    ): Result {
        val labels = DoubleArray(rows.size) { rows[it].label.toDouble() }
        val pairs = residuals.indices.filter {
            !residuals[it].isNaN() && !labels[it].isNaN()
        }
        val x = DoubleArray(pairs.size) { residuals[pairs[it]] }
        val y = DoubleArray(pairs.size) { labels[pairs[it]] }
        val s = Spearman.compute(x, y)
        return Result("label", s.rho, s.pValue, s.n)
    }

    /**
     * Compute the AUC of the residual as a classifier of the label.
     * If the residual separates sinus from AF, the AUC is high.
     */
    fun residualAuc(residuals: DoubleArray, rows: List<CorrelationTest.Row>): Double {
        val scores = residuals
        val labels = IntArray(rows.size) { rows[it].label }
        val validIdx = residuals.indices.filter { !residuals[it].isNaN() }
        val scoresClean = DoubleArray(validIdx.size) { scores[validIdx[it]] }
        val labelsClean = IntArray(validIdx.size) { labels[validIdx[it]] }
        return Roc.compute(scoresClean, labelsClean).auc
    }
}
