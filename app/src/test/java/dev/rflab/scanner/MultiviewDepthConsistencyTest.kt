package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class MultiviewDepthConsistencyTest {
    private fun image(w: Int, h: Int, mm: Int): ByteArray =
        ByteArray(w * h * 2).also { bytes ->
            for (i in 0 until w * h) {
                bytes[i * 2] = (mm and 255).toByte()
                bytes[i * 2 + 1] = ((mm ushr 8) and 255).toByte()
            }
        }

    private fun frame(x: Float, depth: Int = 1000,
                      raw: Int = 0, confidence: Int = 0): StoredRgbdKeyframe {
        val pose = floatArrayOf(1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f,
            x, 0f, 0f, 1f)
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        return StoredRgbdKeyframe((x * 1000).toLong() + 1,
            pose, 16, 16, 10f, 10f, 8f, 8f, corners, byteArrayOf(),
            image(16, 16, depth), image(16, 16, raw),
            ByteArray(16 * 16) { confidence.toByte() })
    }

    @Test fun matchingOtherViewConfirmsGeometry() {
        val source = frame(0f)
        val other = frame(.2f)
        val evidence = MultiviewDepthConsistency.check(0f, 0f, -1f,
            source, listOf(other))
        assertEquals(MultiviewDepthConsistency.Result.CONFIRMED, evidence.verdict)
        assertEquals(1, evidence.matching)
        assertEquals(0, evidence.conflicting)
    }

    @Test fun inconsistentFullDepthRejectsAClaimedSurface() {
        val evidence = MultiviewDepthConsistency.check(0f, 0f, -1f,
            frame(0f), listOf(frame(.2f, depth = 2000)))
        assertEquals(MultiviewDepthConsistency.Result.CONTRADICTED, evidence.verdict)
    }

    @Test fun aRawRawConflictIsDistinguishedFromInterpolatedDepth() {
        val evidence = MultiviewDepthConsistency.check(0f, 0f, -1f,
            frame(0f), listOf(frame(.2f, depth = 1000, raw = 2000,
                confidence = 255)))
        assertEquals(1, evidence.rawConflicts)
        assertEquals(MultiviewDepthConsistency.Result.CONTRADICTED, evidence.verdict)
    }

    @Test fun noParallaxOrNoOverlapIsNotAConfirmation() {
        val same = MultiviewDepthConsistency.check(0f, 0f, -1f,
            frame(0f), listOf(frame(0f)))
        assertEquals(MultiviewDepthConsistency.Result.UNOBSERVED, same.verdict)
        assertEquals(0, same.tested)
        val outside = MultiviewDepthConsistency.check(0f, 0f, -1f,
            frame(0f), listOf(frame(2.9f)))
        assertEquals(MultiviewDepthConsistency.Result.UNOBSERVED, outside.verdict)
    }
}
