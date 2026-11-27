package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class StructuralPlaneFitterTest {
    private fun wall(): List<ScanPoint> = buildList {
        for (y in 0 until 24) for (z in 0 until 24) {
            add(ScanPoint(2f + ((y + z) % 3 - 1) * .003f,
                y * .095f, z * .095f, .9f, raw = true))
        }
    }

    private fun floor(): List<ScanPoint> = buildList {
        for (x in 0 until 25) for (z in 0 until 25) {
            add(ScanPoint(x * .085f, .015f + ((x + z) % 3 - 1) * .002f,
                z * .085f, .95f, raw = true))
        }
    }

    @Test fun findsAnArbitrarilyOrientedVerticalPlane() {
        val found = StructuralPlaneFitter.detect(wall(), maxPlanes = 4)
        val plane = found.firstOrNull { it.kind == StructuralPlane.Kind.VERTICAL }
        assertNotNull(plane)
        assertTrue(plane!!.support >= 300)
        assertTrue(abs(plane.distance(2f, 1f, 1f)) < .035f)
    }

    @Test fun findsFlatHorizontalSurfaceFromRawOnly() {
        val points = floor() + List(500) {
            ScanPoint(it / 100f, 2f, .2f, .26f, raw = false)
        }
        val found = StructuralPlaneFitter.detect(points, maxPlanes = 4)
        val plane = found.firstOrNull { it.kind == StructuralPlane.Kind.HORIZONTAL }
        assertNotNull(plane)
        assertTrue(plane!!.support >= 400)
        assertEquals(.015f, -plane.d, .03f)
    }

    @Test fun onlyNearbySupportedInterpolatedPointsAreEligibleToSnap() {
        val planes = StructuralPlaneFitter.detect(wall(), maxPlanes = 4)
        val snapped = StructuralPlaneFitter.snap(2.03f, 1f, 1f, planes)
        assertNotNull(snapped)
        assertEquals(2f, snapped!![0], .015f)
        assertNull(StructuralPlaneFitter.snap(2.4f, 1f, 1f, planes))
        assertNull(StructuralPlaneFitter.snap(2.03f, 5f, 1f, planes))
    }
}
