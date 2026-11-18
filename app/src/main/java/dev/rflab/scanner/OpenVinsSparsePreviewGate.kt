package dev.rflab.scanner

/**
 * Separates an explicitly UNCALIBRATED sparse research visualization from
 * the authoritatively stored DA3 colored 3D map.
 *
 * Android's PRIMARY_CAMERA pose reference does not give a camera-to-gyro
 * baseline. It can still provide a camera orientation seed in Android sensor
 * coordinates, but the lever arm and metric geometry remain unverified.
 * Never promote this research preview to the persisted room-scan map.
 */
internal object OpenVinsSparsePreviewGate {
    fun allowed(
        evidence:OpenVinsCameraEvidence.Verdict?,
        online:OpenVinsOnlineCalibrationHealth.Report?,
        calibrationSeed:String?
    ):Boolean {
        if(evidence==null || online?.acceptedForExperimentalFusion!=true ||
            evidence.metrics["lens_pose_reference"]!="PRIMARY_CAMERA" ||
            evidence.testedFrames<6 ||
            evidence.allowedForExperimentalFusion)return false
        // All ordinary camera capture conditions must still pass.
        // Only the two specifically acknowledged device gyro lever-arm
        // limitations may differ from the trusted mapping gate.
        if(evidence.problems.any{
            !it.contains("Gyroskop-Referenz") &&
            !it.contains("Camera2-YAML-Seed")
        })return false
        val id=evidence.cameraId ?:return false
        return calibrationSeed?.contains(
            "seed=Android lens rotation, zero translation seed")==true &&
            calibrationSeed.contains("id="+id+" K=")
    }
}
