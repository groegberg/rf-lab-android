package com.openvins.android

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.graphics.ImageFormat
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.math.sqrt

/**
 * RF Lab research bootstrap for isolated GPLv3 OpenVINS Mobile.
 * Replaces foreign phone's hardcoded camera calibration with current
 * device metadata. Only enables VIO when the camera explicitly reports
 * gyro-relative lens pose, calibrated intrinsics and REALTIME clock.
 *
 * Android camera calibration is a provisional seed, not verified Kalibr.
 */
internal object VioBootstrap {
    data class Result(val ready: Boolean, val reason: String,
                      val cameraId: String?, val width: Int, val height: Int)
    fun install(context: Context, folder: File): Result {
        folder.mkdirs()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(
                CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        } ?: return Result(false, "Keine Rückkamera", null, 0, 0)
        val info = manager.getCameraCharacteristics(id)
        val timestampSource = info.get(
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val realTime = timestampSource ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val intr = info.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val active = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val quat = info.get(CameraCharacteristics.LENS_POSE_ROTATION)
        val poseReference = info.get(CameraCharacteristics.LENS_POSE_REFERENCE)
        val options = info.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)
        // The upstream camera uses an up-to-640x480 YUV stream.
        val selected = options?.filter {
            it.width <= 640 && it.height <= 480 &&
                it.width >= 320 && it.height >= 240
        }?.maxByOrNull { it.width * it.height }
        val w = selected?.width ?: 640
        val h = selected?.height ?: 480
        val warnings = mutableListOf<String>()
        if (!realTime) warnings += "Camera timestamp is not REALTIME"
        if (intr == null || intr.size < 4 || active == null)
            warnings += "Camera intrinsics missing"
        if (quat == null || quat.size < 4)
            warnings += "Camera-to-gyro rotation missing"
        if (poseReference != CameraMetadata.LENS_POSE_REFERENCE_GYROSCOPE)
            warnings += "Lens pose is not referenced to GYROSCOPE"
        if (selected == null) warnings += "No matching YUV preview format"
        val calibration = File(folder, "rflab_device_calibration.txt")
        calibration.writeText(
            "Research metadata: NOT checkerboard/Kalibr verified.\n" +
            "cameraId=" + id + "\n" +
            "timestampSource=" + timestampSource + "\n" +
            "preview=" + w + "x" + h + "\n" +
            "lensPoseReference=" + poseReference + "\n" +
            "sensorOrientation=" +
            info.get(CameraCharacteristics.SENSOR_ORIENTATION) + "\n" +
            "diagnostics=" + warnings.joinToString("; ") + "\n")
        for (name in listOf("estimator_config.yaml",
                "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml")) {
            val dst = File(folder, name)
            if (!dst.exists()) context.assets.open("rflab-config/" + name).use {
                src -> dst.outputStream().use { dest -> src.copyTo(dest) }
            }
        }
        if (warnings.isNotEmpty()) {
            return Result(false, warnings.joinToString("; "), id, w, h)
        }
        val sX = w.toDouble() / active!!.width()
        val sY = h.toDouble() / active.height()
        val fx = intr!![0] * sX
        val fy = intr[1] * sY
        val cx = (intr[2] - active.left) * sX
        val cy = (intr[3] - active.top) * sY
        if (!fx.isFinite() || !fy.isFinite() || fx <= 0 || fy <= 0)
            return Result(false, "Camera2 intrinsics invalid", id, w, h)
        val q = quat!!
        val magnitude = sqrt(q.sumOf { it.toDouble() * it.toDouble() })
        if (magnitude !in 0.95..1.05)
            return Result(false, "Camera quaternion invalid", id, w, h)
        val x = q[0] / magnitude
        val y = q[1] / magnitude
        val z = q[2] / magnitude
        val qw = q[3] / magnitude
        val matrix = doubleArrayOf(
            1 - 2 * (y*y + z*z), 2*(x*y - z*qw), 2*(x*z + y*qw),
            2*(x*y + z*qw), 1 - 2*(x*x + z*z), 2*(y*z - x*qw),
            2*(x*z - y*qw), 2*(y*z + x*qw), 1 - 2*(x*x + y*y)
        )
        // Android camera frame -> gyro frame; inverse for Kalibr T_cam_imu.
        val inverse = DoubleArray(9) { i ->
            matrix[(i % 3) * 3 + i / 3]
        }
        fun d(value: Double) = String.format(Locale.US, "%.10f", value)
        val transform = (0..2).joinToString("\n") { row ->
            "    - [" + d(inverse[3*row]) + ", " +
                d(inverse[3*row+1]) + ", " +
                d(inverse[3*row+2]) + ", 0.0]"
        }
        val yaml = "%YAML:1.0\n\ncam0:\n" +
            "  T_cam_imu:\n" + transform + "\n" +
            "    - [0.0, 0.0, 0.0, 1.0]\n" +
            "  cam_overlaps: []\n" +
            "  camera_model: pinhole\n" +
            "  distortion_coeffs: [0.0, 0.0, 0.0, 0.0]\n" +
            "  distortion_model: radtan\n" +
            "  intrinsics: [" + d(fx) + ", " + d(fy) +
                ", " + d(cx) + ", " + d(cy) + "]\n" +
            "  resolution: [" + w + ", " + h + "]\n" +
            "  rostopic: /cam0/image_raw\n" +
            "  timeshift_cam_imu: 0.0\n"
        // Keep a user-calibrated config unchanged. New install is provisional.
        if (!File(folder, "rflab_user_calibrated.marker").exists()) {
            File(folder, "kalibr_imucam_chain.yaml").writeText(yaml)
            File(folder, "rflab_generated_camera.marker").writeText(
                "Camera2 provisional; verify optical/inertial calibration.\n")
        }
        Log.i("RFOpenVINS", "VIO research config: " + id + " " + w + "x" + h)
        return Result(true, "Camera2 provisional metadata only", id, w, h)
    }
}
