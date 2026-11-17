package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsCameraEvidenceTest {
    private val k=OpenVinsDa3Geometry.Calibration(
        478.0,478.0,320.0,240.0,640,480,0.0,0.0,0.0,0.0)
    private val good="""
        schema=rf-lab-camera-capture-evidence-v1
        camera_id=0
        timestamp_source=REALTIME
        lens_pose_reference=GYROSCOPE
        sensor_orientation_degrees=90
        image_width=640
        image_height=480
        intrinsics_available=true
        logical_multi_camera=true
        active_physical_id=0
        physical_camera_changed=false
        capture_result_count=200
        af_mode=OFF
        ois_mode=OFF
        eis_mode=OFF
        distortion_correction_mode=OFF
        zoom_ratio=1.0
        max_focal_relative_drift=0.001
        max_focus_diopter_drift=0.0
        calibration_source=approximate_camera2_seed_not_kalibr
        online_openvins_calibration_exported=false
        metric_calibration_verified=false
    """.trimIndent()

    @Test fun stableMetadataAllowsOnlyExperimentalNotMetricFusion() {
        val verdict=OpenVinsCameraEvidence.inspect(good,k)
        assertTrue(verdict.problems.toString(),verdict.allowedForExperimentalFusion)
        assertEquals(200,verdict.testedFrames)
        assertEquals("false",verdict.metrics["metric_calibration_verified"])
    }

    @Test fun missingOldSessionCannotSilentlyFuse() {
        assertFalse(OpenVinsCameraEvidence.inspect(null,k)
            .allowedForExperimentalFusion)
    }

    @Test fun rejectsUnreferencedImuAndUncomparableClock() {
        val bad=good.replace("GYROSCOPE","PRIMARY_CAMERA")
            .replace("timestamp_source=REALTIME","timestamp_source=UNKNOWN")
        val verdict=OpenVinsCameraEvidence.inspect(bad,k)
        assertFalse(verdict.allowedForExperimentalFusion)
        assertTrue(verdict.problems.any{it.contains("Zeitstempel")})
        assertTrue(verdict.problems.any{it.contains("Gyroskop")})
    }

    @Test fun detectsCameraSwitchAndOisOrAutofocusChange() {
        val bad=good.replace("physical_camera_changed=false",
                "physical_camera_changed=true")
            .replace("af_mode=OFF","af_mode=ACTIVE(4)")
            .replace("ois_mode=OFF","ois_mode=ACTIVE(1)")
        val verdict=OpenVinsCameraEvidence.inspect(bad,k)
        assertFalse(verdict.allowedForExperimentalFusion)
        assertTrue(verdict.problems.size>=3)
    }

    @Test fun detectsActualImageIntrinsicsAndZoomDrift() {
        val bad=good.replace("image_width=640","image_width=480")
            .replace("zoom_ratio=1.0","zoom_ratio=1.1")
            .replace("max_focal_relative_drift=0.001",
                "max_focal_relative_drift=0.03")
        val verdict=OpenVinsCameraEvidence.inspect(bad,k)
        assertFalse(verdict.allowedForExperimentalFusion)
        assertTrue(verdict.problems.size>=3)
    }

    @Test fun evidenceWithoutCapturedFramesIsNotProof() {
        val bad=good.replace("capture_result_count=200",
            "capture_result_count=0")
        assertFalse(OpenVinsCameraEvidence.inspect(bad,k)
            .allowedForExperimentalFusion)
    }
}
