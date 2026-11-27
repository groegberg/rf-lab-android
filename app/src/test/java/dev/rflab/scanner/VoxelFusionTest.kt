package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class VoxelFusionTest {
    @Test fun closeCoordinatesHaveSameCell() {
        assertEquals(VoxelFusion.key(1.01f, 0.01f, -2.01f),
            VoxelFusion.key(1.02f, 0.02f, -2.02f))
        assertNotEquals(VoxelFusion.key(1.01f, .01f, -2.01f),
            VoxelFusion.key(1.11f, .01f, -2.01f))
        assertNull(VoxelFusion.key(Float.NaN, 0f, 0f))
        assertNull(VoxelFusion.key(60f, 0f, 0f))
    }

    @Test fun rawMeasurementReplacesFullAndCannotBeOverwritten() {
        val full = SurveyDepth(1f, 2f, 3f, .26f,
            argb = 0xff123456.toInt(), rawDepth = false)
        val raw = SurveyDepth(1.02f, 2f, 3f, .9f,
            argb = 0xffabcdef.toInt(), rawDepth = true)
        val measured = VoxelFusion.merge(VoxelFusion.point(full), raw)
        assertTrue(measured.raw)
        assertEquals(raw.x, measured.x)
        assertEquals(measured, VoxelFusion.merge(measured, full))
    }

    @Test fun measuredPointsAreAveragedAndColored() {
        val a = SurveyDepth(1f, 0f, 0f, .8f, argb = 0xffff0000.toInt())
        val b = SurveyDepth(1.02f, 0f, 0f, .8f, argb = 0xff0000ff.toInt())
        val merged = VoxelFusion.merge(VoxelFusion.point(a), b)
        assertEquals(1.01f, merged.x, .001f)
        assertEquals(2, merged.observations)
        assertTrue(merged.raw)
        assertEquals(255, (merged.argb ushr 24) and 255)
        assertNotEquals(a.argb, merged.argb)
        assertNotEquals(b.argb, merged.argb)
    }

    @Test fun missingColourDoesNotErasePreviousCameraRgb() {
        val old = VoxelFusion.point(SurveyDepth(0f, 0f, 1f, .8f,
            argb = 0xff88aa77.toInt()))
        val updated = VoxelFusion.merge(old, SurveyDepth(0f, 0f, 1f, .8f))
        assertEquals(old.argb, updated.argb)
    }
}
