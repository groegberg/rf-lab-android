package dev.rflab.scanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenVinsPoseTransformTest {
    @Test fun worldZUpBecomesRightHandedMapYUp() {
        assertArrayEquals(floatArrayOf(1f, 3f, -2f),
            OpenVinsPoseTransform.toMap(doubleArrayOf(1.0, 2.0, 3.0))!!,
            .00001f)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f),
            OpenVinsPoseTransform.toMap(doubleArrayOf(0.0, 0.0, 1.0))!!,
            .00001f)
        assertArrayEquals(floatArrayOf(0f, 0f, -1f),
            OpenVinsPoseTransform.toMap(doubleArrayOf(0.0, 1.0, 0.0))!!,
            .00001f)
    }

    @Test fun invalidPoseIsRejectedNeverSimulated() {
        assertNull(OpenVinsPoseTransform.toMap(doubleArrayOf(1.0, 2.0)))
        assertNull(OpenVinsPoseTransform.toMap(doubleArrayOf(Double.NaN, 0.0, 1.0)))
        assertNull(OpenVinsPoseTransform.toMap(doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 1.0)))
        assertNull(OpenVinsPoseTransform.toMap(doubleArrayOf(1e300, 0.0, 0.0)))
        assertFalse(OpenVinsPoseTransform.validQuaternion(doubleArrayOf(0.0, 0.0, 0.0, 0.0)))
        assertFalse(OpenVinsPoseTransform.validQuaternion(doubleArrayOf(Double.NaN, 0.0, 0.0, 1.0)))
        assertTrue(OpenVinsPoseTransform.validQuaternion(doubleArrayOf(1.0, 0.0, 0.0, 0.0)))
    }
}
