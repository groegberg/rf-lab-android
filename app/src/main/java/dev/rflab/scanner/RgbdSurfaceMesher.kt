package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CPU triangulation of one calibrated RGB-D keyframe. Every vertex is in the
 * SAME ARCore world frame; UVs reference the corresponding stored camera JPEG.
 *
 * Deliberately conservative: never bridge a missing-depth region or a large
 * depth discontinuity. Mesh patches from multiple keyframes can overlap.
 * This is NOT volumetric TSDF fusion and is not watertight.
 */
data class RgbdSurfaceMesh(
    val positions: FloatArray,
    val uvs: FloatArray,
    val triangles: ShortArray,
    val measuredVertices: Int,
    val estimatedVertices: Int
) {
    val vertexCount get() = positions.size / 3
    val triangleCount get() = triangles.size / 3
}

object RgbdSurfaceMesher {
    fun build(frame: StoredRgbdKeyframe, targetVertices: Int = 11_000): RgbdSurfaceMesh {
        val w = frame.width
        val h = frame.height
        if (w < 2 || h < 2 || !frame.fx.isFinite() || !frame.fy.isFinite() ||
            frame.fx <= 0f || frame.fy <= 0f || frame.cameraWorld.size != 16 ||
            frame.uvImageCorners.size != 8 ||
            frame.fullDepth.size != w * h * 2 ||
            frame.rawDepth.size != w * h * 2 ||
            frame.rawConfidence.size != w * h)
            return RgbdSurfaceMesh(floatArrayOf(), floatArrayOf(), shortArrayOf(), 0, 0)

        val step = ceil(sqrt(w.toDouble() * h / targetVertices.coerceIn(256, 14_000)))
            .toInt().coerceAtLeast(1)
        val nx = (w + step - 1) / step
        val ny = (h + step - 1) / step
        if (nx * ny > 60_000) return RgbdSurfaceMesh(
            floatArrayOf(), floatArrayOf(), shortArrayOf(), 0, 0)

        val grid = IntArray(nx * ny) { -1 }
        val pos = FloatArray(nx * ny * 3)
        val uv = FloatArray(nx * ny * 2)
        val distances = FloatArray(nx * ny)
        val m = frame.cameraWorld
        val corners = frame.uvImageCorners
        var count = 0
        var measured = 0
        var estimated = 0

        fun millimeters(bytes: ByteArray, pixel: Int): Int {
            val i = pixel * 2
            return (bytes[i].toInt() and 255) or
                ((bytes[i + 1].toInt() and 255) shl 8)
        }

        for (gy in 0 until ny) for (gx in 0 until nx) {
            val x = min(w - 1, gx * step + step / 2)
            val y = min(h - 1, gy * step + step / 2)
            val pixel = y * w + x
            val raw = millimeters(frame.rawDepth, pixel)
            val rawValid = raw in 350..7500 &&
                (frame.rawConfidence[pixel].toInt() and 255) >= 128
            val full = millimeters(frame.fullDepth, pixel)
            val d = if (rawValid) raw else full
            if (d !in 350..7500) continue

            val depth = d / 1000f
            val px = (x - frame.cx) * depth / frame.fx
            val py = (frame.cy - y) * depth / frame.fy
            val pz = -depth
            val wx = m[0] * px + m[4] * py + m[8] * pz + m[12]
            val wy = m[1] * px + m[5] * py + m[9] * pz + m[13]
            val wz = m[2] * px + m[6] * py + m[10] * pz + m[14]
            if (!wx.isFinite() || !wy.isFinite() || !wz.isFinite()) continue

            grid[gy * nx + gx] = count
            val vi = count * 3
            pos[vi] = wx
            pos[vi + 1] = wy
            pos[vi + 2] = wz
            distances[count] = depth

            // 4 calibrated texture->CPU-image corner correspondences.
            // The mapping is bilinear to accommodate rotation/crop changes.
            val s = (x + .5f) / w
            val t = (y + .5f) / h
            fun interpolate(offset: Int): Float {
                val top = corners[offset] * (1f - s) + corners[2 + offset] * s
                val bottom = corners[4 + offset] * (1f - s) + corners[6 + offset] * s
                return top * (1f - t) + bottom * t
            }
            uv[count * 2] = interpolate(0)
            uv[count * 2 + 1] = interpolate(1)
            count++
            if (rawValid) measured++ else estimated++
        }

        if (count == 0) return RgbdSurfaceMesh(
            floatArrayOf(), floatArrayOf(), shortArrayOf(), 0, 0)

        val faces = ShortArray((nx - 1) * (ny - 1) * 6)
        var faceCount = 0
        fun compatible(a: Int, b: Int): Boolean {
            if (a < 0 || b < 0) return false
            val da = distances[a]
            val db = distances[b]
            if (abs(da - db) > max(.085f, min(da, db) * .055f)) return false
            val ia = a * 3
            val ib = b * 3
            val dx = pos[ia] - pos[ib]
            val dy = pos[ia + 1] - pos[ib + 1]
            val dz = pos[ia + 2] - pos[ib + 2]
            val edge = sqrt(dx * dx + dy * dy + dz * dz)
            return edge < max(.16f, min(da, db) * .12f)
        }
        fun triangle(a: Int, b: Int, c: Int) {
            if (!compatible(a, b) || !compatible(b, c) || !compatible(a, c)) return
            faces[faceCount++] = a.toShort()
            faces[faceCount++] = b.toShort()
            faces[faceCount++] = c.toShort()
        }
        for (y in 0 until ny - 1) for (x in 0 until nx - 1) {
            val tl = grid[y * nx + x]
            val tr = grid[y * nx + x + 1]
            val bl = grid[(y + 1) * nx + x]
            val br = grid[(y + 1) * nx + x + 1]
            triangle(tl, bl, tr)
            triangle(tr, bl, br)
        }
        return RgbdSurfaceMesh(pos.copyOf(count * 3), uv.copyOf(count * 2),
            faces.copyOf(faceCount), measured, estimated)
    }
}
