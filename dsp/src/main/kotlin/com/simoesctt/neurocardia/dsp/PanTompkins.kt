package com.simoesctt.neurocardia.dsp

import com.simoesctt.neurocardia.io.Annotation
import com.simoesctt.neurocardia.io.BeatType
import com.simoesctt.neurocardia.io.EcgRecord
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pan-Tompkins QRS detector (Pan & Tompkins, IEEE TBME 1985).
 *
 * Stages:
 *   1. Bandpass 5-15 Hz (cascaded low-pass + high-pass biquads)
 *   2. Five-point derivative
 *   3. Squaring
 *   4. Moving-window integration (150 ms)
 *   5. Adaptive threshold with search-back
 *
 * Localization: the integrated signal carries a fixed group delay from
 * the filter cascade plus the integration window. Rather than trying to
 * compute that delay analytically, we shift back by half the integration
 * window, then refine by searching for the local maximum of the absolute
 * bandpassed signal within a small window around that position. This is
 * what the original algorithm recommends and it generalizes across
 * sample rates and filter parameters.
 */
object PanTompkins {

    /** Set to true to print per-stage statistics to stdout. */
    var debug = false

    fun detect(record: EcgRecord): EcgRecord {
        val fs = record.sampleRate
        require(fs > 0) { "sample rate must be positive" }
        if (record.samples.size < (0.5 * fs).toInt()) {
            return record
        }

        if (debug) {
            val min = record.samples.min()
            val max = record.samples.max()
            val mean = record.samples.average()
            println("PT: input n=${record.samples.size} fs=$fs min=$min max=$max mean=$mean")
        }

        val bandpassed = bandpass5to15(record.samples, fs)
        val derivative = derivative5(bandpassed)
        val squared = DoubleArray(derivative.size) { derivative[it] * derivative[it] }
        val integrated = movingWindowIntegrate(squared, (0.150 * fs).roundToInt())

        if (debug) {
            val minB = bandpassed.min(); val maxB = bandpassed.max()
            val minI = integrated.min(); val maxI = integrated.max()
            println("PT: bandpassed range [$minB, $maxB]")
            println("PT: integrated range [$minI, $maxI]")
        }

        val peaksInIntegrated = adaptiveThreshold(integrated, fs)
        if (debug) {
            println("PT: peaks in integrated = ${peaksInIntegrated.size}")
        }
        val qrsSamples = refinePeaks(peaksInIntegrated, bandpassed, integrated, fs)

        val annotations = qrsSamples.map {
            Annotation(sampleIndex = it, type = BeatType.NORMAL)
        }

        return EcgRecord(
            samples = record.samples,
            sampleRate = record.sampleRate,
            units = record.units,
            leadLabel = record.leadLabel,
            metadata = record.metadata,
            annotations = annotations,
            analysis = record.analysis
        )
    }

    private fun bandpass5to15(x: DoubleArray, fs: Double): DoubleArray {
        val low = biquadLowpass(x, cutoff = 15.0, fs = fs)
        return biquadHighpass(low, cutoff = 5.0, fs = fs)
    }

    private fun biquadLowpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 - cosW0) / 2.0
        val b1 = 1.0 - cosW0
        val b2 = (1.0 - cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquadHighpass(x: DoubleArray, cutoff: Double, fs: Double): DoubleArray {
        val w0 = 2.0 * Math.PI * cutoff / fs
        val cosW0 = Math.cos(w0)
        val sinW0 = Math.sin(w0)
        val alpha = sinW0 / (2.0 * 0.7071)
        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha
        return biquad(x, b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    }

    private fun biquad(
        x: DoubleArray,
        b0: Double, b1: Double, b2: Double,
        a1: Double, a2: Double
    ): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = 0.0; var x2 = 0.0
        var y1 = 0.0; var y2 = 0.0
        for (i in x.indices) {
            val xi = x[i]
            val yi = b0 * xi + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = xi
            y2 = y1; y1 = yi
            y[i] = yi
        }
        return y
    }

    private fun derivative5(x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        for (n in 4 until x.size) {
            y[n] = (2.0 * x[n] + x[n - 1] - x[n - 3] - 2.0 * x[n - 4]) / 8.0
        }
        return y
    }

    private fun movingWindowIntegrate(x: DoubleArray, window: Int): DoubleArray {
        if (window < 1) return x.copyOf()
        val y = DoubleArray(x.size)
        var sum = 0.0
        for (n in x.indices) {
            sum += x[n]
            if (n >= window) sum -= x[n - window]
            val count = if (n >= window) window else n + 1
            y[n] = sum / count
        }
        return y
    }

    private fun adaptiveThreshold(integrated: DoubleArray, fs: Double): IntArray {
        val refractorySamples = (0.2 * fs).roundToInt()
        val tWaveWindowSamples = (0.36 * fs).roundToInt()
        val initWindowSamples = (2.0 * fs).roundToInt()

        var spki = 0.0
        var npki = 0.0
        var rrAverage1 = 0.0
        var rrMiss = 0.0
        var lastQrsSample = -1

        val peaks = ArrayList<Int>()

        val initEnd = minOf(initWindowSamples, integrated.size)
        if (initEnd > 0) {
            val init = integrated.copyOfRange(0, initEnd).sorted()
            spki = init.last() * 0.5
            npki = init[init.size / 2] * 0.5
        }
        if (debug) {
            println("PT: init spki=$spki npki=$npki initEnd=$initEnd")
        }

        var n = 0
        while (n < integrated.size) {
            if (n > 0 && n < integrated.size - 1 &&
                integrated[n] > integrated[n - 1] &&
                integrated[n] >= integrated[n + 1]) {

                val peak = integrated[n]
                val threshold1 = npki + 0.25 * (spki - npki)
                val threshold2 = 0.5 * threshold1

                if (peak > threshold1) {
                    if (lastQrsSample < 0 || (n - lastQrsSample) > refractorySamples) {
                        val withinTwave = lastQrsSample >= 0 &&
                                          (n - lastQrsSample) < tWaveWindowSamples
                        if (withinTwave) {
                            npki = 0.125 * peak + 0.875 * npki
                        } else {
                            peaks.add(n)
                            if (lastQrsSample >= 0) {
                                val rr = (n - lastQrsSample).toDouble() / fs
                                rrAverage1 = if (rrAverage1 == 0.0) rr
                                             else 0.125 * rr + 0.875 * rrAverage1
                                rrMiss = rrAverage1 * 1.66
                            }
                            lastQrsSample = n
                            spki = 0.125 * peak + 0.875 * spki
                        }
                    }
                } else if (peak > threshold2) {
                    npki = 0.125 * peak + 0.875 * npki
                } else {
                    npki = 0.125 * peak + 0.875 * npki
                }
            }
            n++
        }

        return searchBack(peaks, integrated, fs, rrAverage1, rrMiss).toIntArray()
    }

    private fun searchBack(
        peaks: MutableList<Int>,
        integrated: DoubleArray,
        fs: Double,
        rrAverage: Double,
        rrMiss: Double
    ): List<Int> {
        if (peaks.size < 2 || rrMiss <= 0.0) return peaks
        val result = ArrayList<Int>(peaks.size)
        result.add(peaks[0])
        for (i in 1 until peaks.size) {
            val prev = result.last()
            val curr = peaks[i]
            val gapS = (curr - prev).toDouble() / fs
            if (gapS > rrMiss) {
                val gapStart = prev + (0.2 * fs).roundToInt()
                val gapEnd = curr - (0.2 * fs).roundToInt()
                var bestIdx = -1
                var bestVal = 0.0
                for (k in gapStart until gapEnd) {
                    if (k in integrated.indices && integrated[k] > bestVal) {
                        bestVal = integrated[k]
                        bestIdx = k
                    }
                }
                if (bestIdx >= 0) result.add(bestIdx)
            }
            result.add(curr)
        }
        return result
    }

    /**
     * Refine peak positions back to the ECG signal.
     *
     * The integrated signal lags the ECG by the group delay of the
     * filter cascade plus the moving-window integration. We first shift
     * back by half the integration window (the moving average of a
     * symmetric window adds a delay of window/2), then search for the
     * local maximum of the absolute bandpassed signal within a small
     * window around that position.
     *
     * This is the two-step localization described in the original paper
     * and it generalizes across sample rates and filter choices.
     */
    private fun refinePeaks(
        peaksInIntegrated: IntArray,
        bandpassed: DoubleArray,
        integrated: DoubleArray,
        fs: Double
    ): List<Int> {
        val halfWindow = (0.075 * fs).roundToInt()
        val searchRadius = (0.05 * fs).roundToInt().coerceAtLeast(1)
        val out = ArrayList<Int>(peaksInIntegrated.size)

        for (p in peaksInIntegrated) {
            val approx = p - halfWindow
            if (approx < 0) continue

            val lo = (approx - searchRadius).coerceAtLeast(0)
            val hi = (approx + searchRadius).coerceAtMost(bandpassed.size - 1)

            var bestIdx = approx.coerceIn(0, bandpassed.size - 1)
            var bestVal = abs(bandpassed[bestIdx])
            for (k in lo..hi) {
                val v = abs(bandpassed[k])
                if (v > bestVal) {
                    bestVal = v
                    bestIdx = k
                }
            }
            out.add(bestIdx)
        }

        // Deduplicate and sort.
        val distinct = LinkedHashSet(out)
        return distinct.sorted()
    }
}
