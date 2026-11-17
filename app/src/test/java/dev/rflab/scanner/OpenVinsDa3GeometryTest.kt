package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class OpenVinsDa3GeometryTest {
    private val yaml = """
        %YAML:1.0
        cam0:
          camera_model: pinhole
          distortion_model: radtan
          intrinsics: [500.0, 500.0, 320.0, 240.0]
          resolution: [640, 480]
          distortion_coeffs: [0, 0, 0, 0]
    """.trimIndent()

    private val k = OpenVinsDa3Geometry.calibration(yaml)!!

    @Test fun readsActualPinholeCalibrationAndRejectsUnsupportedCamera() {
        assertEquals(500.0,k.fx,1e-10)
        assertEquals(640,k.width)
        assertTrue(k.compatibleWith(1280,960))
        assertFalse(k.compatibleWith(480,640))
        assertNull(OpenVinsDa3Geometry.calibration(
            yaml.replace("pinhole","omni")))
        assertNull(OpenVinsDa3Geometry.calibration(
            yaml.replace("radtan","equidistant")))
    }

    @Test fun undoesRealDa3RotateAndAspectFillCrop() {
        val middle=OpenVinsDa3Geometry.sourcePixel(
            251.5,447.5,640,480,504,896)!!
        assertEquals(319.5,middle[0],1e-5)
        assertEquals(239.5,middle[1],1e-5)
        val left=OpenVinsDa3Geometry.sourcePixel(
            24.0,447.5,640,480,504,896)!!
        assertTrue(left[1] > middle[1])
        val portrait=OpenVinsDa3Geometry.sourcePixel(
            251.5,447.5,480,640,504,896)!!
        assertEquals(239.5,portrait[0],1e-5)
        assertEquals(319.5,portrait[1],1e-5)
    }

    @Test fun projectsCameraForwardWithNativeCameraPosition() {
        val pose=ExternalVioTrajectory.Sample(
            1L,1.0,2.0,3.0,1.0,0.0,0.0,0.0)
        val xyz=OpenVinsDa3Geometry.mapPoint(
            pose,k,320.0,240.0,640,480,1.0)!!
        assertEquals(1f,xyz[0],.002f)
        assertEquals(4f,xyz[1],.002f)
        assertEquals(-2f,xyz[2],.002f)
    }

    @Test fun appliesFullJplCameraToWorldOrientationNotOnlyPosition() {
        val q=1.0/sqrt(2.0)
        val pose=ExternalVioTrajectory.Sample(1L,0.0,0.0,0.0,q,q,0.0,0.0)
        val xyz=OpenVinsDa3Geometry.mapPoint(
            pose,k,320.0,240.0,640,480,1.0)!!
        assertEquals(0f,xyz[0],.002f)
        assertEquals(0f,xyz[1],.002f)
        assertEquals(1f,xyz[2],.002f)
    }

    @Test fun invalidDepthOrPoseRejectedRatherThanFabricated() {
        val pose=ExternalVioTrajectory.Sample(
            1L,0.0,0.0,0.0,1.0,0.0,0.0,0.0)
        assertNull(OpenVinsDa3Geometry.mapPoint(
            pose,k,320.0,240.0,640,480,Double.NaN))
        assertNull(OpenVinsDa3Geometry.mapPoint(
            pose,k,320.0,240.0,480,640,1.0))
        assertNull(OpenVinsDa3Geometry.mapPoint(
            pose.copy(qw=Double.NaN),k,320.0,240.0,640,480,1.0))
    }
}
