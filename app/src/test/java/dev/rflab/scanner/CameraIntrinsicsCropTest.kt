package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class CameraIntrinsicsCropTest {
    @Test fun scalesPreCorrectionSensorTo640x480Image() {
        val k=CameraIntrinsicsCrop.approximate(
            doubleArrayOf(3000.0,3000.0,2000.0,1500.0),
            doubleArrayOf(0.0,0.0,4000.0,3000.0),640,480)!!
        assertEquals(480.0,k.fx,0.01)
        assertEquals(480.0,k.fy,0.01)
        assertEquals(320.0,k.cx,0.01)
        assertEquals(240.0,k.cy,0.01)
    }

    @Test fun widescreenCropRemovesTopAndBottomFromSensor() {
        val k=CameraIntrinsicsCrop.approximate(
            doubleArrayOf(3000.0,3000.0,2000.0,1500.0),
            doubleArrayOf(0.0,0.0,4000.0,3000.0),1280,720)!!
        assertEquals(960.0,k.fx,0.01)
        assertEquals(960.0,k.fy,0.01)
        assertEquals(640.0,k.cx,0.01)
        assertEquals(360.0,k.cy,0.01)
    }

    @Test fun invalidCalibrationIsNeverFakedAsMeasured() {
        assertNull(CameraIntrinsicsCrop.approximate(
            doubleArrayOf(-1.0,0.0,0.0,0.0),
            doubleArrayOf(0.0,0.0,1000.0,800.0),640,480))
    }
}
