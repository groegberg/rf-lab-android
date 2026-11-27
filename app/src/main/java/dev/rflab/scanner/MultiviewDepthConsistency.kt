package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Independent geometric cross-view validation of RGB-D observations.
 *
 * Reprojects a world-space sample into other calibrated keyframes and compares
 * optical Z with their raw depth or full depth. Uses camera poses and camera
 * intrinsics only; no network or neural inference.
 *
 * Unknown overlap is NOT a confirmation. Only matched observations count as
 * support; conflicts reject interpolated points, and strong raw/raw conflicts
 * are also rejected.
 */
internal object MultiviewDepthConsistency {
    enum class Result { CONFIRMED, UNOBSERVED, CONTRADICTED }

    data class Evidence(val matching: Int, val conflicting: Int, val tested: Int,
                        val rawMatches: Int, val rawConflicts: Int) {
        val verdict: Result get() = when {
            rawConflicts > 0 && rawMatches == 0 -> Result.CONTRADICTED
            matching > 0 -> Result.CONFIRMED
            conflicting > 0 -> Result.CONTRADICTED
            else -> Result.UNOBSERVED
        }
    }

    fun check(
        worldX: Float, worldY: Float, worldZ: Float,
        current: StoredRgbdKeyframe,
        neighbors: List<StoredRgbdKeyframe>,
        limit: Int = 4
    ): Evidence {
        var matching = 0
        var conflicting = 0
        var tested = 0
        var rawMatches = 0
        var rawConflicts = 0
        val position = current.cameraWorld
        for (other in neighbors) {
            if (tested >= limit) break
            if (other === current || other.timestampNs == current.timestampNs) continue
            if (other.cameraWorld.size != 16 || other.width <= 0 || other.height <= 0) continue
            val m = other.cameraWorld
            val baseline = sqrt(
                (position[12] - m[12]) * (position[12] - m[12]) +
                (position[13] - m[13]) * (position[13] - m[13]) +
                (position[14] - m[14]) * (position[14] - m[14]))
            if (baseline < .07f || baseline > 3.0f) continue
            val dx = worldX - m[12]
            val dy = worldY - m[13]
            val dz = worldZ - m[14]
            // Camera-to-world is column-major rigid, so invert using R^T.
            val localX = m[0] * dx + m[1] * dy + m[2] * dz
            val localY = m[4] * dx + m[5] * dy + m[6] * dz
            val localZ = m[8] * dx + m[9] * dy + m[10] * dz
            val depth = -localZ
            if (depth !in .35f..7.5f) continue
            if (other.fx <= 0f || other.fy <= 0f) continue
            val x = (other.fx * localX / depth + other.cx).roundToInt()
            val y = (other.cy - other.fy * localY / depth).roundToInt()
            if (x !in 0 until other.width || y !in 0 until other.height) continue
            val i = y * other.width + x
            val rawMillimeters = read16(other.rawDepth, i)
            val rawReliable = rawMillimeters in 350..7500 &&
                i < other.rawConfidence.size &&
                (other.rawConfidence[i].toInt() and 255) >= 170
            val millimeters = if (rawReliable) rawMillimeters
                              else read16(other.fullDepth, i)
            if (millimeters !in 350..7500) continue
            val observed = millimeters * .001f
            // Nearest pixel, pose drift, and ARCore estimation require a
            // tolerance. This gate is not a centimeter accuracy guarantee.
            val tolerance = if (rawReliable) max(.065f, depth * .035f)
                            else max(.13f, depth * .075f)
            val difference = abs(depth - observed)
            tested++
            if (difference <= tolerance) {
                matching++
                if (rawReliable) rawMatches++
            } else {
                conflicting++
                if (rawReliable) rawConflicts++
            }
        }
        return Evidence(matching, conflicting, tested, rawMatches, rawConflicts)
    }

    private fun read16(bytes: ByteArray, pixel: Int): Int {
        val index = pixel * 2
        if (index < 0 || index + 1 >= bytes.size) return 0
        return (bytes[index].toInt() and 255) or
            ((bytes[index + 1].toInt() and 255) shl 8)
    }
}
