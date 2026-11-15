package dev.rflab.scanner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class EchoAnalysis(
    val directArrivalMs: Double,
    val strongestEchoAfterMs: Double,
    val relativeEchoStrength: Double,
    val envelope: List<Double>
)

/**
 * Experimental audible-sweep matched filter. Not calibrated ranging:
 * speaker/microphone loopback and Android audio latency are unknown.
 */
object AcousticSignal {
    fun chirp(rate: Int = 48_000, durationS: Double = .10): ShortArray {
        val n = (rate * durationS).toInt()
        val out = ShortArray(n)
        val start = 1300.0
        val end = 6500.0
        val k = (end - start) / durationS
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val w = .5 - .5 * cos(2.0 * PI * i / max(1, n - 1))
            out[i] = (sin(2.0 * PI * (start * t + .5 * k * t * t)) *
                w * Short.MAX_VALUE * .22).toInt().toShort()
        }
        return out
    }

    fun analyze(audio: ShortArray, chirp: ShortArray,
                rate: Int = 48_000): EchoAnalysis {
        require(audio.isNotEmpty() && chirp.isNotEmpty() && rate > 0)
        val n = nextPowerOfTwo(audio.size + chirp.size - 1)
        require(n <= 262_144) { "Too much audio data" }
        val ar = DoubleArray(n)
        val ai = DoubleArray(n)
        val br = DoubleArray(n)
        val bi = DoubleArray(n)
        for (i in audio.indices) ar[i] = audio[i].toDouble() / Short.MAX_VALUE
        for (i in chirp.indices) {
            br[i] = chirp[chirp.size - 1 - i].toDouble() / Short.MAX_VALUE
        }
        fft(ar, ai, inverse = false)
        fft(br, bi, inverse = false)
        for (i in 0 until n) {
            val real = ar[i] * br[i] - ai[i] * bi[i]
            val imag = ar[i] * bi[i] + ai[i] * br[i]
            ar[i] = real; ai[i] = imag
        }
        fft(ar, ai, inverse = true)
        val maxLag = min(audio.size - chirp.size,
            (rate * .65).toInt()).coerceAtLeast(0)
        var maxPeak = 0.0
        for (offset in 0..maxLag) {
            maxPeak = max(maxPeak, abs(ar[offset + chirp.size - 1]))
        }
        if (maxPeak < 1e-7) return EchoAnalysis(0.0, 0.0, 0.0, List(64) { 0.0 })
        // Earliest reasonably strong direct/speaker-leakage match.
        var directIndex = 0
        for (offset in 0..maxLag) {
            if (abs(ar[offset + chirp.size - 1]) >= maxPeak * .55) {
                directIndex = offset; break
            }
        }
        val bins = 64
        val window = (rate * .32).toInt()
        val envelope = List(bins) { b ->
            val from = directIndex + (window * b / bins)
            val to = min(maxLag, directIndex + (window * (b + 1) / bins))
            var peak = 0.0
            for (offset in from..to) {
                if (offset <= maxLag)
                    peak = max(peak, abs(ar[offset + chirp.size - 1]))
            }
            (peak / maxPeak).coerceIn(0.0, 1.0)
        }
        val skip = (rate * .006).toInt()
        var echoLag = 0
        var echoPeak = 0.0
        for (offset in (directIndex + skip)..min(maxLag, directIndex + window)) {
            val peak = abs(ar[offset + chirp.size - 1])
            if (peak > echoPeak) {
                echoPeak = peak
                echoLag = offset - directIndex
            }
        }
        return EchoAnalysis(
            directIndex * 1000.0 / rate,
            echoLag * 1000.0 / rate,
            (echoPeak / maxPeak).coerceIn(0.0, 1.0),
            envelope
        )
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var result = 1
        while (result < n) result = result shl 1
        return result
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val a = re[i]; re[i] = re[j]; re[j] = a
                val b = im[i]; im[i] = im[j]; im[j] = b
            }
        }
        var width = 2
        while (width <= n) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / width
            val wr = cos(angle)
            val wi = sin(angle)
            var start = 0
            while (start < n) {
                var ur = 1.0
                var ui = 0.0
                for (k in 0 until width / 2) {
                    val a = start + k
                    val b = a + width / 2
                    val tr = ur * re[b] - ui * im[b]
                    val ti = ur * im[b] + ui * re[b]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val nextR = ur * wr - ui * wi
                    ui = ur * wi + ui * wr
                    ur = nextR
                }
                start += width
            }
            width = width shl 1
        }
        if (inverse) {
            for (i in re.indices) { re[i] /= n; im[i] /= n }
        }
    }
}
