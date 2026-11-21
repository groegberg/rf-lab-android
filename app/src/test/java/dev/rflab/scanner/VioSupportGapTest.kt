package dev.rflab.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class VioSupportGapTest {
    private fun checkFusion(extraHeader: String, counters: (Int) -> String,
                            step: Double, accepted: Boolean) {
        val header = "timestamp_ns,x,y,z,qw,qx,qy,qz" + extraHeader
        val rows = (0..60).asSequence().map { i ->
            "${1_000_000_000L + i * 50_000_000L},${i * step},0,0,1,0,0,0" + counters(i)
        }
        val poses = ExternalVioTrajectory.parse(sequenceOf(header) + rows)
        assertEquals(61, poses.size)
        assertEquals(accepted, VioMotionQuality.inspect(poses).acceptableForExperimentalGeometry)
    }

    @Test fun movingWithoutVisualUpdatesIsRejected() {
        checkFusion(",slam_landmarks_in_state,msckf_tracks_kept", { ",0,0" }, .01, false)
    }

    @Test fun supportBreaksTheRunAndStillnessIsAllowed() {
        val fields = ",msckf_tracks_kept,slam_landmarks_in_state"
        checkFusion(fields, { if (it == 30) ",0,1" else ",0,0" }, .01, true)
        checkFusion(fields, { if (it == 30) ",1,0" else ",0,0" }, .01, true)
        checkFusion(fields, { ",0,0" }, 0.0, true)
    }

    @Test fun olderExportsHaveUnknownSupport() {
        checkFusion("", { "" }, .01, true)
    }
}
