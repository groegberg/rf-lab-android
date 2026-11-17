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

    @Test fun exactInverseDa3CropForEveryPhysicalPhoneRoll() {
        // Round trip a sensor pixel through Android Bitmap rotation and the
        // exact aspect-fill transform used by the portrait DA3 model.
        val sourceX=300.5
        val sourceY=220.5
        for(rotation in listOf(0,90,180,270)){
            val rw=if(rotation%180==90)480 else 640
            val rh=if(rotation%180==90)640 else 480
            val rotatedX=when(rotation){
                0->sourceX
                90->480.0-1.0-sourceY
                180->640.0-1.0-sourceX
                else->sourceY
            }
            val rotatedY=when(rotation){
                0->sourceY
                90->sourceX
                180->480.0-1.0-sourceY
                else->640.0-1.0-sourceX
            }
            val scale=maxOf(504.0/rw,896.0/rh)
            val modelX=(rotatedX+.5)*scale +
                (504-rw*scale)/2.0-.5
            val modelY=(rotatedY+.5)*scale +
                (896-rh*scale)/2.0-.5
            val recovered=OpenVinsDa3Geometry.sourcePixel(
                modelX,modelY,640,480,504,896,rotation)!!
            assertEquals("x at "+rotation,sourceX,recovered[0],1e-7)
            assertEquals("y at "+rotation,sourceY,recovered[1],1e-7)
        }
    }

    @Test fun arbitraryPhoneYawPitchAndRollKeepSameWorldObject() {
        val objectX=1.0
        val objectY=0.0
        val objectZ=3.0
        val identity=ExternalVioTrajectory.Sample(
            1,0.0,0.0,0.0,1.0,0.0,0.0,0.0)
        val expected=OpenVinsDa3Geometry.mapPoint(identity,k,
            320+500*objectX/objectZ,240.0,640,480,objectZ)!!
        // Physical phone roll 90 degrees about camera optical axis:
        // identical world point occupies a different sensor image pixel.
        val zq=kotlin.math.sqrt(.5)
        val roll=identity.copy(qw=zq,qz=zq)
        val rollWorld=OpenVinsDa3Geometry.mapPoint(roll,k,
            320.0,240-500*objectX/objectZ,640,480,objectZ)!!
        for(i in 0..2)assertEquals("roll i="+i,expected[i],rollWorld[i],.003f)

        // Physical pitch 15 degrees. Do not merely rotate/flip image:
        // real OpenVINS camera orientation must rotate the entire optical ray.
        val theta=15.0*Math.PI/180.0
        val pitch=identity.copy(qw=kotlin.math.cos(theta/2),
            qx=kotlin.math.sin(theta/2))
        val zCamera=objectZ*kotlin.math.cos(theta)
        val xCamera=objectX
        val yCamera=objectZ*kotlin.math.sin(theta)
        val pitchWorld=OpenVinsDa3Geometry.mapPoint(pitch,k,
            320+500*xCamera/zCamera,240+500*yCamera/zCamera,
            640,480,zCamera)!!
        for(i in 0..2)assertEquals("pitch i="+i,expected[i],pitchWorld[i],.003f)

        val yaw=identity.copy(qw=kotlin.math.cos(theta/2),
            qy=kotlin.math.sin(theta/2))
        val yawX=objectX*kotlin.math.cos(theta)-
            objectZ*kotlin.math.sin(theta)
        val yawZ=objectX*kotlin.math.sin(theta)+
            objectZ*kotlin.math.cos(theta)
        val yawWorld=OpenVinsDa3Geometry.mapPoint(yaw,k,
            320+500*yawX/yawZ,240.0,640,480,yawZ)!!
        for(i in 0..2)assertEquals("yaw i="+i,expected[i],yawWorld[i],.003f)
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
