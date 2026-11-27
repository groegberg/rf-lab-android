package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class StereoGeometryTest {
    private fun frame(x: Float): StoredRgbdKeyframe {
        val pose = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            x, 0f, 0f, 1f)
        return StoredRgbdKeyframe(1L + (x * 1000).toLong(), pose,
            640, 480, 320f, 320f, 320f, 240f,
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            byteArrayOf(), byteArrayOf(), byteArrayOf(), byteArrayOf())
    }

    @Test fun projectedDepthReprojectsInOriginalFrame() {
        val f = frame(0f)
        val point = StereoGeometry.point(f, .62f, .41f, 2.0f)
        val uv = StereoGeometry.project(f, point[0], point[1], point[2])
        assertNotNull(uv)
        assertEquals(.62f, uv!![0], .0001f)
        assertEquals(.41f, uv[1], .0001f)
    }

    @Test fun cameraBaselineCreatesStereoParallax() {
        val origin = frame(0f)
        val shifted = frame(.2f)
        val near = StereoGeometry.point(origin, .5f, .5f, 1f)
        val far = StereoGeometry.point(origin, .5f, .5f, 4f)
        val nearUv = StereoGeometry.project(shifted, near[0], near[1], near[2])!!
        val farUv = StereoGeometry.project(shifted, far[0], far[1], far[2])!!
        assertTrue(nearUv[0] < farUv[0])
        assertEquals(.5f, nearUv[1], .0001f)
    }

    @Test fun imageCropMappingHandlesRotation() {
        val corners = floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        val center = StereoGeometry.depthToImage(corners, .5f, .5f)
        assertEquals(.5f, center[0], .0001f)
        assertEquals(.5f, center[1], .0001f)
        val corner = StereoGeometry.depthToImage(corners, 0f, 0f)
        assertEquals(0f, corner[0], .0001f)
        assertEquals(1f, corner[1], .0001f)
    }

    @Test fun pointsBehindCameraAreRejected() {
        assertNull(StereoGeometry.project(frame(0f), 0f, 0f, 1f))
    }
}
