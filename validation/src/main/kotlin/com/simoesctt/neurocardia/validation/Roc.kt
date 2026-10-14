package com.simoesctt.neurocardia.validation

object Roc {

    data class Point(val threshold: Double, val tpr: Double, val fpr: Double)

    data class Result(
        val auc: Double,
        val points: List<Point>,
        val nPositive: Int,
        val nNegative: Int
    )

    fun compute(scores: DoubleArray, labels: IntArray): Result {
        require(scores.size == labels.size)
        val nPos = labels.count { it == 1 }
        val nNeg = labels.count { it == 0 }
        if (nPos == 0 || nNeg == 0) {
            return Result(Double.NaN, emptyList(), nPos, nNeg)
        }

        val indices = scores.indices.sortedByDescending { scores[it] }
        val sortedScores = DoubleArray(indices.size) { scores[indices[it]] }
        val sortedLabels = IntArray(indices.size) { labels[indices[it]] }

        val points = mutableListOf<Point>()
        var tp = 0
        var fp = 0
        var prevScore = Double.NaN

        for (i in sortedScores.indices) {
            val s = sortedScores[i]
            if (s != prevScore && i > 0) {
                points.add(Point(
                    threshold = prevScore,
                    tpr = tp.toDouble() / nPos,
                    fpr = fp.toDouble() / nNeg
                ))
            }
            if (sortedLabels[i] == 1) tp++ else fp++
            prevScore = s
        }
        points.add(Point(prevScore, tp.toDouble() / nPos, fp.toDouble() / nNeg))

        val allPoints = mutableListOf(Point(Double.POSITIVE_INFINITY, 0.0, 0.0))
        allPoints.addAll(points)

        var auc = 0.0
        for (i in 1 until allPoints.size) {
            val prev = allPoints[i - 1]
            val curr = allPoints[i]
            auc += (curr.fpr - prev.fpr) * ((prev.tpr + curr.tpr) / 2.0)
        }

        return Result(auc, allPoints, nPos, nNeg)
    }
}
