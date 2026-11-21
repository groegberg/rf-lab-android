package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class LiveVioMotionTest {
    private val start = 1_000_000_000L

    @Test fun repeatedSnapshotAndDelayedUiPollDoNotInventSpeed() {
        val guard = LiveVioMotion()
        assertEquals(LiveVioMotion.Result.ACCEPT, guard.observe(start, 0.0, 0.0, 0.0))
        assertEquals(LiveVioMotion.Result.REPEAT, guard.observe(start, 0.0, 0.0, 0.0))
        assertEquals(LiveVioMotion.Result.ACCEPT, guard.observe(start+800_000_000L, .2, .1, .1))
    }

    @Test fun sustainedDriftIsLatchedAndOneShortBurstRecovers() {
        for (sustain in listOf(false, true)) {
            val guard = LiveVioMotion()
            var x = 0.0
            for (i in 0..20) {
                if (i in 1..5 || i > 5 && sustain) x += .6
                guard.observe(start + i*100_000_000L, x, 0.0, 0.0)
            }
            assertEquals(sustain, guard.rejected)
            assertEquals(if (sustain) LiveVioMotion.Result.REJECT else LiveVioMotion.Result.ACCEPT,
                guard.observe(start + 2_100_000_000L, x, 0.0, 0.0))
        }
    }

    @Test fun jumpsTimeRegressionsAndNonfinitePosesAreRejected() {
        for ((stamp, x) in listOf(start+50_000_000L to 1.0, start-1 to 0.0, start+1 to Double.NaN)) {
            val guard = LiveVioMotion()
            guard.observe(start, 0.0, 0.0, 0.0)
            assertEquals(LiveVioMotion.Result.REJECT, guard.observe(stamp, x, 0.0, 0.0))
        }
    }

    @Test fun fullRecordingRetainsBriefDivergenceAfterALongStop() {
        val poses = (0..600).map { i ->
            val x = .3 * i.coerceIn(0, 30)
            ExternalVioTrajectory.Sample(start+i*50_000_000L, x, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        }
        val report = VioMotionQuality.inspect(poses)
        assertEquals(0.0, report.p90SpeedUnitsPerSecond, 0.0)
        assertTrue(report.maxSpeedUnitsPerSecond < LiveVioMotion.MAX_STEP_SPEED)
        assertFalse(report.acceptableForExperimentalGeometry)
    }
}
