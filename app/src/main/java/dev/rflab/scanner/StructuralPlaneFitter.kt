package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * On-device structural geometry prior. Fits broad horizontal surfaces and
 * arbitrary-orientation vertical walls from high-confidence RAW samples.
 * Does not invent missing surfaces. Confidence/extent gates reduce the risk
 * of snapping furniture and interpolated artifacts to hypothetical walls.
 */
data class StructuralPlane(
    val kind: Kind,
    val nx: Float, val ny: Float, val nz: Float, val d: Float,
    val support: Int,
    val minX: Float, val maxX: Float,
    val minY: Float, val maxY: Float,
    val minZ: Float, val maxZ: Float
) {
    enum class Kind { HORIZONTAL, VERTICAL }
    fun distance(x: Float, y: Float, z: Float) =
        nx * x + ny * y + nz * z + d
    fun contains(x: Float, y: Float, z: Float): Boolean =
        x in (minX - .15f)..(maxX + .15f) &&
        y in (minY - .15f)..(maxY + .15f) &&
        z in (minZ - .15f)..(maxZ + .15f)
}

object StructuralPlaneFitter {
    private const val TOLERANCE = .048f

    fun detect(points: List<ScanPoint>, maxPlanes: Int = 6): List<StructuralPlane> {
        val reliable = points.asSequence()
            .filter { it.raw && it.confidence >= .62f &&
                it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
            .toList()
        val stride = (reliable.size / 6000).coerceAtLeast(1)
        val sampled = reliable.filterIndexed { i, _ -> i % stride == 0 }
        if (sampled.size < 120) return emptyList()

        val result = mutableListOf<StructuralPlane>()
        var remaining = sampled
        // First fit horizontal surfaces in 4 cm y bands.
        val levels = sampled.groupBy { floor(it.y / .04f).toInt() }
            .entries.sortedByDescending { it.value.size }
        for (level in levels) {
            if (result.size >= maxPlanes) break
            val center = (level.key + .5f) * .04f
            val matches = remaining.filter { abs(it.y - center) < TOLERANCE }
            if (matches.size < 120) continue
            val minX = matches.minOf { it.x }
            val maxX = matches.maxOf { it.x }
            val minZ = matches.minOf { it.z }
            val maxZ = matches.maxOf { it.z }
            if (hypot((maxX - minX).toDouble(), (maxZ - minZ).toDouble()) < 1.3)
                continue
            val y = matches.map { it.y }.average().toFloat()
            result += StructuralPlane(StructuralPlane.Kind.HORIZONTAL,
                0f, 1f, 0f, -y, matches.size,
                minX, maxX, y - .03f, y + .03f, minZ, maxZ)
            remaining = remaining.filter { abs(it.y - y) >= TOLERANCE }
        }

        // Two-point RANSAC in the ground plane; vertical-wall normal has ny=0.
        var seed = 0x4d595df4L
        fun random(n: Int): Int {
            seed = (1664525L * seed + 1013904223L) and 0xffffffffL
            return (seed % n).toInt()
        }
        while (remaining.size >= 120 && result.size < maxPlanes) {
            var best = emptyList<ScanPoint>()
            var bestNx = 0f
            var bestNz = 0f
            for (trial in 0 until 130) {
                val a = remaining[random(remaining.size)]
                val b = remaining[random(remaining.size)]
                val dx = b.x - a.x
                val dz = b.z - a.z
                val length = hypot(dx.toDouble(), dz.toDouble()).toFloat()
                if (length < .4f) continue
                val nx = dz / length
                val nz = -dx / length
                val d = -(nx * a.x + nz * a.z)
                val matches = remaining.filter {
                    abs(nx * it.x + nz * it.z + d) < TOLERANCE
                }
                if (matches.size <= best.size || matches.size < 120) continue
                val ySpan = matches.maxOf { it.y } - matches.minOf { it.y }
                val tangentMin = matches.minOf { -nz * it.x + nx * it.z }
                val tangentMax = matches.maxOf { -nz * it.x + nx * it.z }
                if (ySpan < 1.0f || tangentMax - tangentMin < 1.0f) continue
                best = matches
                bestNx = nx
                bestNz = nz
            }
            if (best.size < 120) break

            // Least-squares 2D PCA refits the RANSAC inliers.
            val cx = best.map { it.x }.average().toFloat()
            val cz = best.map { it.z }.average().toFloat()
            var xx = 0.0
            var zz = 0.0
            var xz = 0.0
            for (p in best) {
                val dx = (p.x - cx).toDouble()
                val dz = (p.z - cz).toDouble()
                xx += dx * dx
                zz += dz * dz
                xz += dx * dz
            }
            val angle = .5 * atan2(2.0 * xz, xx - zz)
            var nx = -sin(angle).toFloat()
            var nz = cos(angle).toFloat()
            if (nx * bestNx + nz * bestNz < 0f) {
                nx = -nx
                nz = -nz
            }
            val offset = -(nx * cx + nz * cz)
            val inliers = remaining.filter {
                abs(nx * it.x + nz * it.z + offset) < TOLERANCE
            }
            if (inliers.size < 120) break
            result += StructuralPlane(StructuralPlane.Kind.VERTICAL,
                nx, 0f, nz, offset, inliers.size,
                inliers.minOf { it.x }, inliers.maxOf { it.x },
                inliers.minOf { it.y }, inliers.maxOf { it.y },
                inliers.minOf { it.z }, inliers.maxOf { it.z })
            remaining = remaining.filter {
                abs(nx * it.x + nz * it.z + offset) >= TOLERANCE
            }
        }
        return result
    }

    /**
     * Regularizes only nearby *interpolated* geometry to well-supported
     * observed walls/floors. Measured Raw Depth vertices stay untouched.
     */
    fun snap(x: Float, y: Float, z: Float, planes: List<StructuralPlane>): FloatArray? {
        var nearest: StructuralPlane? = null
        var signed = 0f
        var smallest = .040f
        for (plane in planes) {
            if (plane.support < 120 || !plane.contains(x, y, z)) continue
            val distance = plane.distance(x, y, z)
            if (abs(distance) < smallest) {
                smallest = abs(distance)
                signed = distance
                nearest = plane
            }
        }
        val plane = nearest ?: return null
        return floatArrayOf(x - signed * plane.nx,
            y - signed * plane.ny, z - signed * plane.nz)
    }
}
