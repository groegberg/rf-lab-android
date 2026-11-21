package dev.rflab.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VioInitializationHealthTest {
    private fun status(
        cam: Int = 100, queued: Int = 80, processed: Int = 79,
        expired: Int = 0, imu: Int = 700, fed: Int = 695,
        waitImu: Int = 0, config: Int = 0, initialized: Int = 0,
        camClock: Double = 340.0, imuClock: Double = 340.005,
        processedClock: Double = 339.97, nowClock: Double = 340.04
    ): VioInitializationHealth = VioInitializationHealth.read(
        intArrayOf(cam,queued,processed,expired,imu,fed,waitImu,config,initialized),
        doubleArrayOf(camClock,imuClock,processedClock,nowClock))

    @Test fun waitingForStaticInitRequiresARealNativeInitializedFlag() {
        val s = status()
        assertTrue(s.issue.contains("Ruhe"))
        assertTrue(s.compact().contains("VIO 79"))
        assertEquals(5.0, s.imuCameraDeltaMs!!, .001)
    }

    @Test fun realNativeInitializationWinsOnlyWithActualFlag() {
        assertTrue(status(initialized=1).issue.contains("initialisiert"))
    }

    @Test fun configurationAndMissingImuAreDiagnosedNotPretendedToBeTracking() {
        assertTrue(status(config=1).issue.contains("Konfigurationsfehler"))
        assertTrue(status(imu=0,fed=0).issue.contains("Gyroskop"))
        assertTrue(status(imu=500,fed=0).issue.contains("erreicht"))
        assertTrue(status(cam=0).issue.contains("Kamerastream"))
    }

    @Test fun clockMismatchAndSlowNativeProcessingAreDistinct() {
        assertTrue(status(camClock=350.0,imuClock=330.0)
            .issue.contains("Zeitbasis"))
        assertTrue(status(expired=90,processed=4)
            .issue.contains("zu langsam"))
    }

    @Test fun latchedNativeInitializationDoesNotHideEightySecondVioStall(){
        val stalled=status(initialized=1,camClock=1020046.0,
            imuClock=1020046.196,processedClock=1019962.674,
            nowClock=1020046.199)
        assertTrue(stalled.issue.contains("steht"))
        assertTrue(stalled.processedAgeMs!!>83_000)
        assertTrue(stalled.report().contains("last_processed_age_ms="))
    }

    @Test fun rawNativeReportExposesAllCountersForDiagnosis() {
        val s=status(expired=6,waitImu=12,initialized=0)
        val report=s.report()
        assertTrue(report.contains("camera_expired_500ms=6"))
        assertTrue(report.contains("camera_waiting_for_imu_checks=12"))
        assertTrue(report.contains("Native initialized: false"))
        assertTrue(report.contains("NOT validated"))
    }
}
