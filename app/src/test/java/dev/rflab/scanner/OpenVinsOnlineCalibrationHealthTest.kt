package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class OpenVinsOnlineCalibrationHealthTest {
    private val seed=OpenVinsDa3Geometry.Calibration(
        500.0,500.0,320.0,240.0,640,480,0.0,0.0,0.0,0.0)

    private fun trajectory(focal:(Int)->Double = {500.0},
                           angle:(Int)->Double={0.0},
                           offset:(Int)->Double={0.003},
                           position:(Int)->Double={0.0}):
            List<ExternalVioTrajectory.Sample> =
        (0..59).map{i->
            val a=angle(i)*PI/180.0
            val data=ExternalVioTrajectory.CameraEstimate(
                doubleArrayOf(focal(i),focal(i),320.0,240.0,
                    0.0,0.0,0.0,0.0),
                doubleArrayOf(0.0,0.0,sin(a/2),cos(a/2)),
                doubleArrayOf(position(i),0.0,0.0),offset(i))
            ExternalVioTrajectory.Sample(
                1000000000L + i*40000000L,
                i*.005,0.0,0.0,1.0,0.0,0.0,0.0,data)
        }

    @Test fun stableVioOnlineEstimatePassesOnlyExperimentGate(){
        val r=OpenVinsOnlineCalibrationHealth.inspect(trajectory(),seed)
        assertTrue(r.reasons.toString(),r.acceptedForExperimentalFusion)
        assertEquals(60,r.samples)
        assertEquals(3.0,r.medianTimeOffsetMs,1e-6)
    }

    @Test fun focalLengthDriftCannotBeHiddenBySmoothPoseTrajectory(){
        val r=OpenVinsOnlineCalibrationHealth.inspect(
            trajectory(focal={500.0+it*1.0}),seed)
        assertFalse(r.acceptedForExperimentalFusion)
        assertTrue(r.reasons.any{it.contains("Brennweite")})
    }

    @Test fun cameraImuExtrinsicRotationMustConverge(){
        val r=OpenVinsOnlineCalibrationHealth.inspect(
            trajectory(angle={it*.32}),seed)
        assertFalse(r.acceptedForExperimentalFusion)
        assertTrue(r.reasons.any{it.contains("Extrinsik")})
    }

    @Test fun changingImuCameraOffsetMustNotProduceColoredMap(){
        val r=OpenVinsOnlineCalibrationHealth.inspect(
            trajectory(offset={it*.0005}),seed)
        assertFalse(r.acceptedForExperimentalFusion)
        assertTrue(r.reasons.any{it.contains("Zeitversatz")})
    }

    @Test fun oldEightColumnVioFileIsNotCalibrated(){
        val withoutOnline=trajectory().map{it.copy(onlineCamera=null)}
        assertFalse(OpenVinsOnlineCalibrationHealth.inspect(
            withoutOnline,seed).acceptedForExperimentalFusion)
    }
}
