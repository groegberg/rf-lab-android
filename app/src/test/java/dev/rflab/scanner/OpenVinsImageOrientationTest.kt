package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsImageOrientationTest {
    private val eye=doubleArrayOf(1.0,0.0,0.0,
        0.0,1.0,0.0,0.0,0.0,1.0)

    private fun samples(x:Double,y:Double,z:Double,gyro:Double=.05)=
        (0..30).map{i->
            OpenVinsImageOrientation.Imu(1_000_000_000L+i*10_000_000L,
                x,y,z,gyro,0.0,0.0)
        }

    @Test fun nativeImuGravityNotUiRotationChoosesAllFourModelRotations() {
        val cases=listOf(
            Triple(doubleArrayOf(0.0,-9.81,0.0),0,0.0),
            Triple(doubleArrayOf(-9.81,0.0,0.0),90,0.0),
            Triple(doubleArrayOf(0.0,9.81,0.0),180,0.0),
            Triple(doubleArrayOf(9.81,0.0,0.0),270,0.0)
        )
        for((acc,expected,_) in cases){
            val r=OpenVinsImageOrientation.estimate(
                samples(acc[0],acc[1],acc[2]),1_150_000_000L,eye)!!
            assertEquals(expected,r.degrees)
            assertTrue("rotation="+expected,r.usableForFusion)
            assertEquals(0.0,r.errorDegrees,1e-8)
        }
    }

    @Test fun rejectsGravityParallelToOpticalAxisAndFastRotation(){
        assertNull(OpenVinsImageOrientation.estimate(
            samples(0.0,0.0,9.81),1_150_000_000L,eye))
        val highGyro=OpenVinsImageOrientation.estimate(
            samples(0.0,-9.81,0.0,3.0),1_150_000_000L,eye)!!
        assertFalse(highGyro.usableForFusion)
    }

    @Test fun obliqueRollHasReliableAngleButFailsGeometricGate() {
        val v=9.81/kotlin.math.sqrt(2.0)
        val report=OpenVinsImageOrientation.estimate(
            samples(-v,-v,0.0),1_150_000_000L,eye)!!
        assertEquals(45.0,report.errorDegrees,.0001)
        assertFalse(report.usableForFusion)
    }

    @Test fun usesOpenVinsPositiveImuTimeOffsetConvention(){
        // OpenVINS: t_IMU = t_CAMERA + dt. The opposite sign would
        // misassociate the accelerometer on device roll transitions.
        assertEquals(1_018_000_000L,
            OpenVinsImageOrientation.imuTimestampNs(
                1_000_000_000L,.018))
        assertEquals(982_000_000L,
            OpenVinsImageOrientation.imuTimestampNs(
                1_000_000_000L,-.018))
        assertNull(OpenVinsImageOrientation.imuTimestampNs(
            1_000_000_000L,.25))
    }

    @Test fun parsesCameraFromImuTransformAndRejectsReflections() {
        val yaml="""
            cam0:
              T_cam_imu:
                - [0.0, -1.0, 0.0, 0.0]
                - [1.0, 0.0, 0.0, 0.0]
                - [0.0, 0.0, 1.0, 0.0]
                - [0.0, 0.0, 0.0, 1.0]
        """.trimIndent()
        val r=OpenVinsImageOrientation.camFromImu(yaml)!!
        assertEquals(-1.0,r[1],1e-10)
        assertEquals(1.0,r[3],1e-10)
        assertNull(OpenVinsImageOrientation.camFromImu(
            yaml.replace("- [0.0, 0.0, 1.0, 0.0]",
                "- [0.0, 0.0, -1.0, 0.0]")))
    }

    @Test fun parsesActuallyRecordedGyroAndAccelerometerColumns(){
        val text=listOf("timestamp,omega_x,omega_y,omega_z,alpha_x,alpha_y,alpha_z",
            "1000000000,0.03,0.04,0.05,0,-9.81,0",
            "1010000000,0.07,0.06,0.05,0,-9.81,0")
        val samples=OpenVinsImageOrientation.parseImu(text.asSequence())
        assertEquals(2,samples.size)
        assertEquals(-9.81,samples[1].ay,1e-10)
        assertEquals(.07,samples[1].wx,1e-10)
    }
}
