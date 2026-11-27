package dev.rflab.scanner

import kotlin.math.floor

/** Confidence-weighted 5 cm voxel point fusion with raw-over-estimated precedence. */
internal object VoxelFusion {
    const val VOXEL_M = 0.05f
    const val MAX_POINTS = 80_000
    private const val BIAS = 1_048_576L
    private const val MASK = 2_097_151L

    fun key(x: Float, y: Float, z: Float): Long? {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() ||
            maxOf(kotlin.math.abs(x), kotlin.math.abs(y), kotlin.math.abs(z)) > 50f)
            return null
        fun cell(v: Float) = floor(v / VOXEL_M).toLong() + BIAS
        return ((cell(x) and MASK) shl 42) or
            ((cell(y) and MASK) shl 21) or (cell(z) and MASK)
    }

    fun point(p: SurveyDepth) =
        ScanPoint(p.x, p.y, p.z, p.confidence, p.argb, 1, p.rawDepth)

    fun merge(old: ScanPoint, p: SurveyDepth): ScanPoint {
        if (old.raw && !p.rawDepth) return old
        if (!old.raw && p.rawDepth) return point(p)
        val w0 = old.observations.coerceAtMost(16) * old.confidence.coerceAtLeast(.01f)
        val w1 = p.confidence.coerceAtLeast(.01f)
        val alpha = w1 / (w0 + w1)
        fun blend(a: Float, b: Float) = a + (b - a) * alpha
        val color = when {
            p.argb == 0 -> old.argb
            old.argb == 0 -> p.argb
            else -> {
                fun channel(shift: Int): Int {
                    val a = (old.argb ushr shift) and 255
                    val b = (p.argb ushr shift) and 255
                    return (a + (b - a) * alpha).toInt().coerceIn(0, 255)
                }
                (255 shl 24) or (channel(16) shl 16) or
                    (channel(8) shl 8) or channel(0)
            }
        }
        return ScanPoint(blend(old.x, p.x), blend(old.y, p.y), blend(old.z, p.z),
            blend(old.confidence, p.confidence), color,
            (old.observations + 1).coerceAtMost(16), old.raw)
    }
}
