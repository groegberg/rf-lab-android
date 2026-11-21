package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsSparsePreviewGateTest {
    private val stable=OpenVinsOnlineCalibrationHealth.Report(
        true,462,.5,.8,.016,1.1,21.2,emptyList())
    private val metrics=mapOf(
        "lens_pose_reference" to "PRIMARY_CAMERA",
        "timestamp_source" to "REALTIME",
        "af_mode" to "OFF",
        "physical_camera_changed" to "false")
    private val warnings=listOf(
        "Kamera-IMU-Translation nicht durch Gyroskop-Referenz gedeckt (Ist: PRIMARY_CAMERA)",
        "Camera2-YAML-Seed stammt nicht nachweisbar von derselben gyroskopreferenzierten Pixel-Kamera")
    private val evid=OpenVinsCameraEvidence.Verdict(
        false,warnings,"0",930,metrics)
    private val seed="id=0 K=447.5,447.5,319,241\n"+
        "seed=Android lens rotation, zero translation seed"

    @Test fun permitsClearlyMarkedPrimaryCameraSparseResearchPreview(){
        assertTrue(OpenVinsSparsePreviewGate.allowed(evid,stable,seed))
    }
    @Test fun rejectsSwitchingLensOrNonRealtimeCameraClock(){
        assertFalse(OpenVinsSparsePreviewGate.allowed(
            evid.copy(problems=warnings+"Physisches Kameraobjektiv gewechselt"),
            stable,seed))
        assertFalse(OpenVinsSparsePreviewGate.allowed(
            evid.copy(problems=warnings+"Kamera-Zeitstempel nicht synchron"),
            stable,seed))
    }
    @Test fun rejectsUnstableOnlineCalibrationAndOtherPhoneSeed(){
        assertFalse(OpenVinsSparsePreviewGate.allowed(
            evid,stable.copy(acceptedForExperimentalFusion=false),seed))
        assertFalse(OpenVinsSparsePreviewGate.allowed(
            evid,stable,seed.replace("id=0 K=","id=1 K=")))
    }
    @Test fun neverTreatsSparsePreviewAsCertifiedDA3RoomMap(){
        assertFalse(OpenVinsSparsePreviewGate.allowed(
            evid.copy(allowedForExperimentalFusion=true),stable,seed))
    }
}
