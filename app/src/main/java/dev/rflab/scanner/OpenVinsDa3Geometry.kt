package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Geometry bridge: native OpenVINS Camera2 RGB frame -> the exact portrait
 * rotate/center-crop used by DA3 -> native camera coordinates -> OpenVINS
 * world -> RF Lab Y-up display coordinates.
 *
 * OpenVINS q_cam is the JPL q_GtoC in native-lib.cpp. JPL quat_2_Rot(q)
 * maps global to camera, so its inverse (camera to global) is the
 * Hamilton-style rotation using the SAME numerical [w,x,y,z] values.
 *
 * This is NOT metric calibration. Pose extrinsics/intrinsics are from the
 * session's unvalidated estimator config; DA3 predicts relative inverse depth.
 */
internal object OpenVinsDa3Geometry {
    data class Calibration(
        val fx: Double, val fy: Double, val cx: Double, val cy: Double,
        val width: Int, val height: Int,
        val k1: Double, val k2: Double, val p1: Double, val p2: Double
    ) {
        fun compatibleWith(sourceWidth: Int, sourceHeight: Int): Boolean =
            sourceWidth > 0 && sourceHeight > 0 &&
                abs(sourceWidth.toDouble() / sourceHeight -
                    width.toDouble() / height) < .025
    }

    /** Parse only the pinhole/radtan model used by the native estimator. */
    fun calibration(yaml: String): Calibration? {
        fun vector(key: String, count: Int): DoubleArray? {
            val match = Regex("(?m)^\\s*" + Regex.escape(key) +
                ":\\s*\\[([^]\\r\\n]+)]").find(yaml) ?: return null
            val parts = match.groupValues[1].split(',').map {
                it.trim().toDoubleOrNull() ?: return null
            }
            if (parts.size < count || parts.any { !it.isFinite() }) return null
            return parts.toDoubleArray()
        }
        if (!Regex("(?m)^\\s*camera_model:\\s*pinhole\\s*$").containsMatchIn(yaml) ||
            !Regex("(?m)^\\s*distortion_model:\\s*radtan\\s*$").containsMatchIn(yaml))
            return null
        val k = vector("intrinsics", 4) ?: return null
        val d = vector("distortion_coeffs", 4) ?: return null
        val size = vector("resolution", 2) ?: return null
        val w = size[0].toInt()
        val h = size[1].toInt()
        if (k[0] <= 1.0 || k[1] <= 1.0 || w !in 160..8192 ||
            h !in 120..8192 || abs(size[0] - w) > 1e-6 ||
            abs(size[1] - h) > 1e-6) return null
        return Calibration(k[0], k[1], k[2], k[3], w, h,
            d[0], d[1], d[2], d[3])
    }

    /**
     * Pixel-center inverse of Da3DepthEngine.predictJpeg():
     * landscape JPEG -> clockwise 90-degree bitmap rotation, then
     * aspect-fill scaling with a centered crop to 504 x 896.
     */
    fun sourcePixel(modelX: Double, modelY: Double,
                    sourceWidth: Int, sourceHeight: Int,
                    modelWidth: Int, modelHeight: Int): DoubleArray? {
        if (sourceWidth <= 0 || sourceHeight <= 0 ||
            modelWidth <= 0 || modelHeight <= 0 ||
            !modelX.isFinite() || !modelY.isFinite()) return null
        val rotated = sourceWidth > sourceHeight
        val rw = if (rotated) sourceHeight else sourceWidth
        val rh = if (rotated) sourceWidth else sourceHeight
        val scale = max(modelWidth.toDouble() / rw, modelHeight.toDouble() / rh)
        val left = (modelWidth - rw * scale) / 2.0
        val top = (modelHeight - rh * scale) / 2.0
        val rx = (modelX + .5 - left) / scale - .5
        val ry = (modelY + .5 - top) / scale - .5
        val x = if (rotated) ry else rx
        val y = if (rotated) sourceHeight - 1.0 - rx else ry
        if (x < 0.0 || y < 0.0 ||
            x >= sourceWidth || y >= sourceHeight) return null
        return doubleArrayOf(x, y)
    }

    /** Un-distort the source pixel via iterative inverse Brown-Conrady. */
    private fun opticalRay(x: Double, y: Double, k: Calibration,
                           sourceWidth: Int, sourceHeight: Int): DoubleArray? {
        if (!k.compatibleWith(sourceWidth, sourceHeight)) return null
        val sx = sourceWidth.toDouble() / k.width
        val sy = sourceHeight.toDouble() / k.height
        val xd = (x - k.cx * sx) / (k.fx * sx)
        val yd = (y - k.cy * sy) / (k.fy * sy)
        var ux = xd
        var uy = yd
        repeat(6) {
            val r2 = ux * ux + uy * uy
            val radial = 1.0 + k.k1 * r2 + k.k2 * r2 * r2
            if (radial <= .15 || !radial.isFinite()) return null
            val dx = 2 * k.p1 * ux * uy + k.p2 * (r2 + 2 * ux * ux)
            val dy = k.p1 * (r2 + 2 * uy * uy) + 2 * k.p2 * ux * uy
            ux = (xd - dx) / radial
            uy = (yd - dy) / radial
        }
        if (!ux.isFinite() || !uy.isFinite() ||
            abs(ux) > 3.0 || abs(uy) > 3.0) return null
        return doubleArrayOf(ux, uy, 1.0)
    }

    /** Project real native pose and one positive relative depth to map axes. */
    fun mapPoint(sample: ExternalVioTrajectory.Sample,
                 calibration: Calibration, sourceX: Double, sourceY: Double,
                 sourceWidth: Int, sourceHeight: Int,
                 depthVioUnits: Double): FloatArray? {
        if (!depthVioUnits.isFinite() || depthVioUnits !in .1..30.0) return null
        val ray = opticalRay(sourceX, sourceY, calibration,
            sourceWidth, sourceHeight) ?: return null
        val qw = sample.qw
        val qx = sample.qx
        val qy = sample.qy
        val qz = sample.qz
        val norm = sqrt(qw * qw + qx * qx + qy * qy + qz * qz)
        if (!norm.isFinite() || norm !in .9..1.1) return null
        val w = qw / norm
        val x = qx / norm
        val y = qy / norm
        val z = qz / norm
        val px = ray[0] * depthVioUnits
        val py = ray[1] * depthVioUnits
        val pz = depthVioUnits
        // Camera->world inverse of OpenVINS's JPL world->camera rotation.
        val tx = 2.0 * (y * pz - z * py)
        val ty = 2.0 * (z * px - x * pz)
        val tz = 2.0 * (x * py - y * px)
        val gx = sample.x + px + w * tx + (y * tz - z * ty)
        val gy = sample.y + py + w * ty + (z * tx - x * tz)
        val gz = sample.z + pz + w * tz + (x * ty - y * tx)
        val result = floatArrayOf(gx.toFloat(), gz.toFloat(), (-gy).toFloat())
        return result.takeIf { p -> p.all { it.isFinite() } }
    }
}
