package com.simoesctt.neurocardia.io

import kotlin.math.sqrt

class RrTachogram(
    val rrMs: DoubleArray,
    val timeS: DoubleArray,
    val beatType: Array<BeatType>,
    val sampleRate: Double,
    val source: String = "",
    val metadata: Map<String, String> = emptyMap()
) {
    val nBeats: Int get() = timeS.size
    val nIntervals: Int get() = rrMs.size

    init {
        // Empty tachogram: all arrays empty. Valid state.
        if (timeS.isEmpty()) {
            require(rrMs.isEmpty()) { "rrMs must be empty when timeS is empty" }
            require(beatType.isEmpty()) { "beatType must be empty when timeS is empty" }
        } else {
            require(rrMs.size == timeS.size - 1) {
                "rrMs.size (${rrMs.size}) must equal timeS.size - 1 (${timeS.size - 1})"
            }
            require(beatType.size == timeS.size) {
                "beatType.size (${beatType.size}) must equal timeS.size (${timeS.size})"
            }
        }
    }

    fun meanRrMs(): Double {
        val normal = normalRr()
        if (normal.isEmpty()) return Double.NaN
        return normal.average()
    }

    fun meanHeartRateBpm(): Double {
        val mean = meanRrMs()
        if (mean.isNaN() || mean <= 0.0) return Double.NaN
        return 60_000.0 / mean
    }

    fun normalRr(): List<Double> {
        val result = ArrayList<Double>(rrMs.size)
        for (i in rrMs.indices) {
            if (beatType[i] != BeatType.NORMAL) continue
            if (beatType[i + 1] != BeatType.NORMAL) continue
            result.add(rrMs[i])
        }
        return result
    }

    fun sdnnMs(): Double {
        val normal = normalRr()
        if (normal.size < 2) return Double.NaN
        val mean = normal.average()
        val sumSq = normal.sumOf { (it - mean) * (it - mean) }
        return sqrt(sumSq / (normal.size - 1))
    }

    fun rmssdMs(): Double {
        val normal = normalRr()
        if (normal.size < 2) return Double.NaN
        var sumSq = 0.0
        for (i in 1 until normal.size) {
            val d = normal[i] - normal[i - 1]
            sumSq += d * d
        }
        return sqrt(sumSq / (normal.size - 1))
    }

    fun pnn50(): Double {
        val normal = normalRr()
        if (normal.size < 2) return Double.NaN
        var count = 0
        for (i in 1 until normal.size) {
            if (kotlin.math.abs(normal[i] - normal[i - 1]) > 50.0) count++
        }
        return count.toDouble() / (normal.size - 1)
    }

    fun durationS(): Double {
        if (timeS.size < 2) return 0.0
        return timeS.last() - timeS.first()
    }

    fun normalFraction(): Double {
        if (beatType.isEmpty()) return 0.0
        val normal = beatType.count { it == BeatType.NORMAL }
        return normal.toDouble() / beatType.size
    }

    companion object {
        fun from(record: EcgRecord): RrTachogram {
            val anns = record.annotations
                .sortedBy { it.sampleIndex }
                .distinctBy { it.sampleIndex }

            if (anns.isEmpty()) {
                return RrTachogram(
                    rrMs = DoubleArray(0),
                    timeS = DoubleArray(0),
                    beatType = emptyArray(),
                    sampleRate = record.sampleRate,
                    source = record.metadata["source"] ?: "",
                    metadata = record.metadata
                )
            }

            val n = anns.size
            val time = DoubleArray(n) { anns[it].sampleIndex / record.sampleRate }
            val types = Array(n) { anns[it].type }
            val rr = DoubleArray(n - 1)
            for (i in 1 until n) {
                rr[i - 1] = (anns[i].sampleIndex - anns[i - 1].sampleIndex) *
                            1000.0 / record.sampleRate
            }

            return RrTachogram(
                rrMs = rr,
                timeS = time,
                beatType = types,
                sampleRate = record.sampleRate,
                source = record.metadata["source"] ?: "",
                metadata = record.metadata
            )
        }
    }
}
