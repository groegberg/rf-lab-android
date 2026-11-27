package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class VioSensorHealthTest {
    @Test fun tracks30HzCameraAnd200HzGyroWithoutMixingClocks() {
        val health=VioSensorHealth()
        for(i in 0..60)health.cameraFrame(1_000_000_000L+i*33_333_333L)
        for(i in 0..399)health.gyroscope(5_000_000_000L+i*5_000_000L)
        val report=health.report()
        assertEquals(30f,report.cameraHz,0.2f)
        assertEquals(200f,report.gyroHz,0.2f)
        assertEquals(0,report.frameGaps)
    }

    @Test fun detectsMissingFramesWithoutInventingTrackingPose() {
        val health=VioSensorHealth()
        for(ts in listOf(1_000_000_000L,1_033_000_000L,
            1_066_000_000L,1_186_000_000L,1_220_000_000L))
            health.cameraFrame(ts)
        assertEquals(1,health.report().frameGaps)
        assertEquals(0f,health.report().gyroHz,0f)
    }

    @Test fun repeatedAndOutOfOrderSamplesAreIgnored() {
        val health=VioSensorHealth()
        health.cameraFrame(100_000_000L)
        health.cameraFrame(100_000_000L)
        health.cameraFrame(90_000_000L)
        health.cameraFrame(134_000_000L)
        assertEquals(2,health.report().cameraSamples)
        health.gyroscope(0L)
        assertEquals(0,health.report().gyroSamples)
    }
}
