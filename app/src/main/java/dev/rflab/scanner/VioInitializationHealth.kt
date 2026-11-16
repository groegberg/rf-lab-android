package dev.rflab.scanner

import java.util.Locale
import kotlin.math.abs

/**
 * Interprets only measured native OpenVINS worker counters and device-clock
 * readings. Does not infer initialized VIO from camera feature overlays.
 *
 * Counter ABI is defined by patch_openvins_initialization_diagnostics.py.
 */
internal class VioInitializationHealth private constructor(
    val rawCamera: Int,
    val queuedCamera: Int,
    val processedCamera: Int,
    val expiredCamera: Int,
    val rawImu: Int,
    val fedImu: Int,
    val waitImuChecks: Int,
    val configErrors: Int,
    val initialized: Boolean,
    val lastCameraSec: Double,
    val lastImuSec: Double,
    val lastProcessedSec: Double,
    val nowBootSec: Double
) {
    val imuCameraDeltaMs: Double?
        get() = if (lastCameraSec > 0 && lastImuSec > 0)
            (lastImuSec - lastCameraSec) * 1000.0 else null

    val cameraAgeMs: Double?
        get() = if (lastCameraSec > 0 && nowBootSec > 0)
            (nowBootSec - lastCameraSec) * 1000.0 else null

    val issue: String get() = when {
        configErrors > 0 -> "Konfigurationsfehler – YAML/Camera2-Kalibrierung prüfen"
        rawCamera < 4 -> "Kein Kamerastream am VIO-Eingang"
        rawImu < 10 -> "Zu wenige Gyroskopdaten – Android-IMU prüfen"
        fedImu < 10 -> "IMU kommt an, erreicht den VIO-Schätzer aber nicht"
        queuedCamera < 3 -> "Kamerabilder werden nicht an VIO übergeben"
        imuCameraDeltaMs != null && abs(imuCameraDeltaMs!!) > 1500.0 ->
            "Kamera-/IMU-Zeitbasis weicht stark ab"
        expiredCamera > 10 && expiredCamera > processedCamera ->
            "Bildverarbeitung zu langsam oder Kamera-Zeitbasis falsch"
        processedCamera < 3 -> "VIO verarbeitet noch keine Kameraframes"
        !initialized -> "Kamera und IMU laufen · VIO wartet auf Ruhe/Initialisierung"
        else -> "Native OpenVINS-VIO initialisiert"
    }

    fun compact(): String {
        val gap = imuCameraDeltaMs?.let {
            String.format(Locale.ROOT, "%.0f", it) + " ms"
        } ?: "n/a"
        return "Kamera " + rawCamera + " → Queue " + queuedCamera +
            " → VIO " + processedCamera + " · verworfen " + expiredCamera +
            "\nIMU " + rawImu + " → VIO " + fedImu + " · Δ IMU–Cam " + gap
    }

    fun report(): String = buildString {
        appendLine("RF LAB · OpenVINS Initialisierungsdiagnose")
        appendLine("Native initialized: " + initialized)
        appendLine("Issue: " + issue)
        appendLine("camera_received=" + rawCamera)
        appendLine("camera_queued=" + queuedCamera)
        appendLine("camera_processed=" + processedCamera)
        appendLine("camera_expired_500ms=" + expiredCamera)
        appendLine("imu_received=" + rawImu)
        appendLine("imu_fed=" + fedImu)
        appendLine("camera_waiting_for_imu_checks=" + waitImuChecks)
        appendLine("config_parse_failures=" + configErrors)
        appendLine("camera_clock_sec=" + lastCameraSec)
        appendLine("imu_clock_sec=" + lastImuSec)
        appendLine("last_processed_clock_sec=" + lastProcessedSec)
        appendLine("boot_clock_sec=" + nowBootSec)
        appendLine("imu_camera_delta_ms=" + (imuCameraDeltaMs ?: "unknown"))
        appendLine("camera_age_ms=" + (cameraAgeMs ?: "unknown"))
        appendLine("Note: live camera feature tracks do not prove VIO initialization")
        appendLine("Camera/IMU extrinsics and metric scale NOT validated for Pixel")
    }

    companion object {
        fun read(counts: IntArray, times: DoubleArray): VioInitializationHealth {
            require(counts.size >= 9 && times.size >= 4) {
                "Invalid native OpenVINS diagnostic array dimensions"
            }
            return VioInitializationHealth(
                counts[0].coerceAtLeast(0),
                counts[1].coerceAtLeast(0),
                counts[2].coerceAtLeast(0),
                counts[3].coerceAtLeast(0),
                counts[4].coerceAtLeast(0),
                counts[5].coerceAtLeast(0),
                counts[6].coerceAtLeast(0),
                counts[7].coerceAtLeast(0),
                counts[8] > 0,
                times[0], times[1], times[2], times[3])
        }
    }
}
