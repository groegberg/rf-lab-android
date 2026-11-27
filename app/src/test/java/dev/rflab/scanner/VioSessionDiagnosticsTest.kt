package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class VioSessionDiagnosticsTest {
    private fun poses(times: List<Long>, xs: List<Double> = times.indices.map { it.toDouble() }) =
        times.mapIndexed { i, t ->
            ExternalVioTrajectory.Sample(t, xs[i], 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        }

    @Test fun detectsTimestampMismatchWithoutInventingCameraPoses() {
        val trajectory = poses((0..19).map { 1_000_000_000L + it * 50_000_000L })
        val report = VioSessionDiagnostics.inspect(trajectory,
            listOf(8_000_000_000L, 8_100_000_000L))
        assertEquals(0, report.matchedPhotos)
        assertTrue(report.warnings.any { it.contains("Zeitstempel") })
        assertEquals(20.0, report.poseRateHz, 1e-6)
        assertEquals(50.0, report.medianPoseGapMs, 1e-6)
    }

    @Test fun measuresPhotoCoverageAndLargePoseGaps() {
        val trajectory = poses(listOf(
            1_000_000_000L, 1_050_000_000L, 1_100_000_000L,
            2_000_000_000L, 2_050_000_000L
        ))
        val report = VioSessionDiagnostics.inspect(trajectory,
            listOf(1_010_000_000L, 1_800_000_000L))
        assertEquals(1, report.matchedPhotos)
        assertEquals(0.5, report.matchedFraction, 1e-9)
        assertEquals(900.0, report.maxPoseGapMs, 1e-9)
        assertTrue(report.warnings.any { it.contains("650 ms") })
    }

    @Test fun returnOffsetIsNotPresentedAsVerifiedLoopClosure() {
        val t = (0..3).map { 1_000_000_000L + it * 50_000_000L }
        val report = VioSessionDiagnostics.inspect(poses(t,
            listOf(0.0, 1.0, 1.0, 0.0)), t)
        assertEquals(2.0, report.pathLengthUnvalidated, 1e-9)
        assertEquals(0.0, report.returnOffsetUnvalidated, 1e-9)
        assertEquals(0.0, report.returnOffsetToPathLength!!, 1e-9)
    }

    @Test fun emptyDatasetGivesExplicitWarning() {
        val report = VioSessionDiagnostics.inspect(emptyList(), listOf(123L))
        assertEquals(0.0, report.poseRateHz, 0.0)
        assertEquals(0, report.matchedPhotos)
        assertNull(report.returnOffsetToPathLength)
        assertTrue(report.warnings.isNotEmpty())
    }
}
