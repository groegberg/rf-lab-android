package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class VioMotionQualityTest {
    private fun samples(n:Int,dtSeconds:Double,velocity:Double):
        List<ExternalVioTrajectory.Sample> =
        (0 until n).map { i ->
            ExternalVioTrajectory.Sample(
                1_000_000_000L+(i*dtSeconds*1e9).toLong(),
                i*dtSeconds*velocity,0.0,0.0,
                1.0,0.0,0.0,0.0)
        }

    @Test fun ordinarySlowIndoorTrajectoryIsNotDeclaredDiverged(){
        val report=VioMotionQuality.inspect(samples(240,1.0/30,0.8))
        assertTrue(report.reasons.toString(),
            report.acceptableForExperimentalGeometry)
        assertEquals(0.8,report.medianSpeedUnitsPerSecond,0.001)
    }

    @Test fun uploadedPixelStyleTwentySecondSeventyMetreDriftIsRejected(){
        val report=VioMotionQuality.inspect(samples(303,1.0/15,3.8))
        assertFalse(report.acceptableForExperimentalGeometry)
        assertTrue(report.reasons.toString(),
            report.reasons.any{it.contains("VIO-Einheiten verschoben")})
        assertTrue(report.displacementUnvalidated>70.0)
    }

    @Test fun instantaneousLargePoseJumpIsNotSilentlyAccepted(){
        val poses=samples(90,1.0/30,0.1).toMutableList()
        for(i in 48 until poses.size){
            poses[i]=poses[i].copy(x=poses[i].x+4.0)
        }
        val report=VioMotionQuality.inspect(poses)
        assertFalse(report.acceptableForExperimentalGeometry)
        assertTrue(report.maxSpeedUnitsPerSecond>100.0)
    }

    @Test fun shortOrMissingTrackingIsNotAccepted(){
        assertFalse(VioMotionQuality.inspect(emptyList())
            .acceptableForExperimentalGeometry)
        assertFalse(VioMotionQuality.inspect(samples(3,.05,.4))
            .acceptableForExperimentalGeometry)
    }
}
