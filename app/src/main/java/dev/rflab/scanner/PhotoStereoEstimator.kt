package dev.rflab.scanner

import android.graphics.BitmapFactory
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CPU plane-sweep photometric stereo. Depth is estimated from the *RGB photos*
 * and the already recorded camera poses only; ARCore depth pixels are NEVER
 * consulted. This is an on-device experimental multi-view stereo baseline,
 * not independent camera tracking / global SfM.
 *
 * Bad texture, reflections, wrong poses and insufficient parallax are rejected
 * instead of producing confident invented surfaces.
 */
internal class PhotoStereoEstimator {
    data class Estimate(
        val width: Int, val height: Int, val meters: FloatArray,
        val validPixels: Int, val testedPixels: Int,
        val baselineM: Float, val elapsedMs: Long, val status: String
    )

    private data class Gray(val width: Int, val height: Int, val pixel: FloatArray) {
        fun sample(u: Float, v: Float): Float {
            if (!u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f)
                return Float.NaN
            val xx = u * (width - 1)
            val yy = v * (height - 1)
            val x = xx.toInt().coerceIn(0, width - 1)
            val y = yy.toInt().coerceIn(0, height - 1)
            return pixel[y * width + x]
        }
    }

    private fun gray(jpeg: ByteArray): Gray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var scale = 1
        while (max(bounds.outWidth, bounds.outHeight) / scale > 780) scale *= 2
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = scale }) ?: return null
        try {
            val pixels = IntArray(image.width * image.height)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val luminance = FloatArray(pixels.size) { i ->
                val p = pixels[i]
                0.2126f * ((p ushr 16) and 255) +
                    0.7152f * ((p ushr 8) and 255) +
                    0.0722f * (p and 255)
            }
            return Gray(image.width, image.height, luminance)
        } finally { image.recycle() }
    }

    fun estimate(frame: StoredRgbdKeyframe, neighbors: List<StoredRgbdKeyframe>,
                 width: Int = 160): Estimate {
        val started = SystemClock.elapsedRealtimeNanos()
        val outW = width.coerceIn(32, 200)
        val outH = (outW.toFloat() * frame.height / frame.width).toInt()
            .coerceIn(32, 180)
        fun failure(message: String): Estimate =
            Estimate(outW, outH, FloatArray(outW * outH), 0, 0, 0f,
                (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000, message)

        val selected = neighbors.asSequence()
            .filter { it.timestampNs != frame.timestampNs }
            .map { other ->
                val dx = frame.cameraWorld[12] - other.cameraWorld[12]
                val dy = frame.cameraWorld[13] - other.cameraWorld[13]
                val dz = frame.cameraWorld[14] - other.cameraWorld[14]
                other to sqrt(dx * dx + dy * dy + dz * dz)
            }
            .filter { (_, baseline) -> baseline in .12f..1.5f }
            .minByOrNull { (_, baseline) -> abs(baseline - .35f) }
            ?: return failure("Kein Kamerapaar mit ausreichend Parallaxe")
        val other = selected.first
        val baseline = selected.second
        val source = gray(frame.imageJpeg) ?: return failure("Quellfoto unlesbar")
        val target = gray(other.imageJpeg) ?: return failure("Zweites Foto unlesbar")
        if (frame.cameraWorld.size != 16 || other.cameraWorld.size != 16 ||
            frame.uvImageCorners.size != 8 || other.uvImageCorners.size != 8 ||
            frame.fx <= 0f || frame.fy <= 0f ||
            other.fx <= 0f || other.fy <= 0f)
            return failure("Ungültige Kamerakalibrierung")

        val depths = FloatArray(outW * outH)
        var tested = 0
        var good = 0
        // Sparse patch offsets in normalized image coordinates.
        val dx = 2f / source.width
        val dy = 2f / source.height
        for (y in 0 until outH) for (x in 0 until outW) {
            val u = (x + .5f) / outW
            val v = (y + .5f) / outH
            val a = StereoGeometry.depthToImage(frame.uvImageCorners, u, v)
            val src = floatArrayOf(
                source.sample(a[0], a[1]),
                source.sample(a[0] - dx, a[1]),
                source.sample(a[0] + dx, a[1]),
                source.sample(a[0], a[1] - dy),
                source.sample(a[0], a[1] + dy)
            )
            if (src.any { !it.isFinite() }) continue
            val localTexture = (src.maxOrNull()!! - src.minOrNull()!!)
            if (localTexture < 13f) continue // blank walls: no stereo claims
            tested++
            var best = Float.POSITIVE_INFINITY
            var bestIndex = -1
            val costs = FloatArray(52) { Float.POSITIVE_INFINITY }
            for (step in costs.indices) {
                // Uniform inverse depth preserves resolution for nearby objects.
                val inverse = 1f / 6.5f +
                    (step / 51f) * (1f / .45f - 1f / 6.5f)
                val depthM = 1f / inverse
                val p = StereoGeometry.point(
                    frame, u, v, depthM)
                val targetTex = StereoGeometry.project(other,
                    p[0], p[1], p[2]) ?: continue
                val b = StereoGeometry.depthToImage(
                    other.uvImageCorners, targetTex[0], targetTex[1])
                val targetDx = 2f / target.width
                val targetDy = 2f / target.height
                val dst = floatArrayOf(
                    target.sample(b[0], b[1]),
                    target.sample(b[0] - targetDx, b[1]),
                    target.sample(b[0] + targetDx, b[1]),
                    target.sample(b[0], b[1] - targetDy),
                    target.sample(b[0], b[1] + targetDy)
                )
                if (dst.any { !it.isFinite() }) continue
                // Subtract patch mean: moderate brightness differences do not
                // dominate the geometric comparison.
                val am = src.average().toFloat()
                val bm = dst.average().toFloat()
                var cost = 0f
                for (i in 0..4) {
                    cost += abs((src[i] - am) - (dst[i] - bm))
                }
                cost /= 5f
                costs[step] = cost
                if (cost < best) {
                    best = cost
                    bestIndex = step
                }
            }
            if (bestIndex < 0) continue
            var second = Float.POSITIVE_INFINITY
            for (i in costs.indices) {
                if (abs(i - bestIndex) <= 3) continue
                second = min(second, costs[i])
            }
            // Geometric ambiguity / texture / photometric consistency gate.
            if (best > 20f || second < best * 1.16f ||
                !second.isFinite()) continue
            val inverse = 1f / 6.5f +
                (bestIndex / 51f) * (1f / .45f - 1f / 6.5f)
            depths[y * outW + x] = 1f / inverse
            good++
        }
        return Estimate(outW, outH, depths, good, tested, baseline,
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000,
            if (good == 0) "Keine eindeutigen Bildübereinstimmungen" else "RGB-Stereo")
    }
}

/** Rigid camera geometry with optional non-square image crop mapping. */
internal object StereoGeometry {
    fun depthToImage(corners: FloatArray, u: Float, v: Float): FloatArray {
        require(corners.size == 8)
        fun coordinate(k: Int): Float {
            val top = corners[k] * (1f - u) + corners[2 + k] * u
            val bottom = corners[4 + k] * (1f - u) + corners[6 + k] * u
            return top * (1f - v) + bottom * v
        }
        return floatArrayOf(coordinate(0), coordinate(1))
    }

    fun point(frame: StoredRgbdKeyframe,
              u: Float, v: Float, depth: Float): FloatArray {
        val cx = frame.cx
        val px = (u * frame.width - cx) * depth / frame.fx
        val py = (frame.cy - v * frame.height) * depth / frame.fy
        val pz = -depth
        val m = frame.cameraWorld
        return floatArrayOf(
            m[0] * px + m[4] * py + m[8] * pz + m[12],
            m[1] * px + m[5] * py + m[9] * pz + m[13],
            m[2] * px + m[6] * py + m[10] * pz + m[14])
    }

    fun project(frame: StoredRgbdKeyframe,
                wx: Float, wy: Float, wz: Float): FloatArray? {
        val m = frame.cameraWorld
        val dx = wx - m[12]
        val dy = wy - m[13]
        val dz = wz - m[14]
        val localX = m[0] * dx + m[1] * dy + m[2] * dz
        val localY = m[4] * dx + m[5] * dy + m[6] * dz
        val localZ = m[8] * dx + m[9] * dy + m[10] * dz
        val depth = -localZ
        if (depth !in .45f..6.5f) return null
        val x = (frame.fx * localX / depth + frame.cx) / frame.width
        val y = (frame.cy - frame.fy * localY / depth) / frame.height
        if (!x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f)
            return null
        return floatArrayOf(x, y)
    }
}
