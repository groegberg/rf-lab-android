package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Pure JVM tests for the on-device RGB-D surface triangulator. */
class RgbdSurfaceMesherTest {
    private fun depthMap(w: Int, h: Int, value: (x: Int, y: Int) -> Int): ByteArray {
        val out = ByteArray(w * h * 2)
        for (y in 0 until h) for (x in 0 until w) {
            val d = value(x, y)
            val k = (y * w + x) * 2
            out[k] = (d and 255).toByte()
            out[k + 1] = (d ushr 8).toByte()
        }
        return out
    }

    private fun frame(w: Int = 8, h: Int = 8,
                      value: (Int, Int) -> Int = { _, _ -> 1000 },
                      rawAt: Pair<Int, Int>? = null): StoredRgbdKeyframe {
        val raw = depthMap(w, h) { x, y ->
            if (rawAt?.first == x && rawAt.second == y) 900 else 0
        }
        val conf = ByteArray(w * h)
        if (rawAt != null) conf[rawAt.second * w + rawAt.first] = 255.toByte()
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return StoredRgbdKeyframe(
            1L, identity, w, h, 100f, 100f, (w - 1) / 2f, (h - 1) / 2f,
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            byteArrayOf(), depthMap(w, h, value), raw, conf)
    }

    @Test fun aFlatMetricSurfaceMakesRealTrianglesAndMappedUvs() {
        val mesh = RgbdSurfaceMesher.build(frame())
        assertEquals(64, mesh.vertexCount)
        assertEquals(98, mesh.triangleCount)
        assertEquals(0, mesh.measuredVertices)
        assertEquals(64, mesh.estimatedVertices)
        assertEquals(-1f, mesh.positions[2], 0.0001f)
        assertTrue(mesh.uvs.all { it in 0f..1f })
    }

    @Test fun rawObservationTakesPriorityOverInterpolatedDepth() {
        val mesh = RgbdSurfaceMesher.build(frame(rawAt = 4 to 4))
        assertEquals(1, mesh.measuredVertices)
        val depths = mesh.positions.filterIndexed { i, _ -> i % 3 == 2 }
        assertTrue(depths.any { abs(it + .9f) < .001f })
    }

    @Test fun noTrianglesBridgeARealDepthDiscontinuity() {
        val mesh = RgbdSurfaceMesher.build(frame(value = { x, _ ->
            if (x < 4) 1000 else 2000
        }))
        assertTrue(mesh.triangleCount > 0)
        for (i in mesh.triangles.indices step 3) {
            val a = mesh.triangles[i].toInt() and 0xffff
            val b = mesh.triangles[i + 1].toInt() and 0xffff
            val c = mesh.triangles[i + 2].toInt() and 0xffff
            val depths = listOf(mesh.positions[a * 3 + 2],
                mesh.positions[b * 3 + 2], mesh.positions[c * 3 + 2])
            assertTrue(depths.maxOrNull()!! - depths.minOrNull()!! < .10f)
        }
    }

    @Test fun missingDepthNeverCreatesSurfaces() {
        val mesh = RgbdSurfaceMesher.build(frame(value = { _, _ -> 0 }))
        assertEquals(0, mesh.triangleCount)
        assertEquals(0, mesh.vertexCount)
    }
}
