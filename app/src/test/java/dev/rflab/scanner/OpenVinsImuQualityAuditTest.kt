package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsImuQualityAuditTest {
    private fun measurements(rotating:Boolean):
            List<OpenVinsImageOrientation.Imu> =
        (0 until 1600).map{i->
            OpenVinsImageOrientation.Imu(
                1_000_000_000L+i*2_500_000L,
                .02*(i%7),0.0,9.81,
                if(rotating)1.2 else .008,
                if(rotating).75 else -.006,
                .003)
        }

    @Test fun stableHighRateImuProvidesStationaryBiasEstimate(){
        val result=OpenVinsImuQualityAudit.inspect(measurements(false))
        assertTrue(result.medianHz in 390.0..410.0)
        assertTrue(result.stationaryWindows>=3)
        assertEquals(.008,result.stationaryGyroBiasRadS[0],1e-5)
        assertEquals(-.006,result.stationaryGyroBiasRadS[1],1e-5)
        assertTrue(result.stationaryAccelerationMagnitude!! in 9.7..10.0)
    }

    @Test fun rotatingHandsetIsNotFalselyUsedAsImuBias(){
        val result=OpenVinsImuQualityAudit.inspect(measurements(true))
        assertEquals(0,result.stationaryWindows)
        assertTrue(result.stationaryGyroBiasRadS.isEmpty())
        assertTrue(result.warnings.any{it.contains("ruhige")})
    }
}
