package dev.rflab.scanner

import kotlin.math.hypot

/**
 * Read-only diagnostics for an imported, native OpenVINS MSCKF trajectory.
 *
 * These measurements check recording continuity and camera/pose timestamp
 * association. They CANNOT validate Pixel camera/IMU calibration, metric
 * accuracy, tracking correctness, or actual loop closure.
 */
internal object VioSessionDiagnostics {
    data class Report(
        val poseCount: Int,
        val photoCount: Int,
        val matchedPhotos: Int,
        val poseRateHz: Double,
        val medianPoseGapMs: Double,
        val maxPoseGapMs: Double,
        val durationSeconds: Double,
        val pathLengthUnvalidated: Double,
        val returnOffsetUnvalidated: Double,
        val returnOffsetToPathLength: Double?,
        val warnings: List<String>
    ) {
        val matchedFraction: Double
            get() = if (photoCount == 0) 0.0
                else matchedPhotos.toDouble() / photoCount
    }

    fun inspect(
        poses: List<ExternalVioTrajectory.Sample>,
        photoTimestamps: List<Long>,
        maxAssociationGapNs: Long = 180_000_000L
    ): Report {
        require(maxAssociationGapNs >= 0L)
        val gaps = poses.zipWithNext { a, b ->
            (b.timestampNs - a.timestampNs).coerceAtLeast(0L)
        }
        val sorted = gaps.sorted()
        val medianNs = when {
            sorted.isEmpty() -> 0.0
            sorted.size % 2 == 1 -> sorted[sorted.size / 2].toDouble()
            else -> (sorted[sorted.size / 2 - 1].toDouble() +
                sorted[sorted.size / 2].toDouble()) / 2.0
        }
        val duration = if (poses.size >= 2)
            (poses.last().timestampNs - poses.first().timestampNs) / 1e9 else 0.0
        val hz = if (duration > 0.0) (poses.size - 1) / duration else 0.0
        val distance = poses.zipWithNext { a, b ->
            hypot(hypot(b.x - a.x, b.y - a.y), b.z - a.z)
        }.sum()
        val offset = if (poses.size > 1) {
            val first = poses.first()
            val last = poses.last()
            hypot(hypot(last.x - first.x, last.y - first.y), last.z - first.z)
        } else 0.0
        val paired = photoTimestamps.count {
            ExternalVioTrajectory.closestIndex(poses, it, maxAssociationGapNs) >= 0
        }
        val warnings = mutableListOf<String>()
        if (poses.isEmpty()) warnings += "Keine initialisierten nativen VIO-Posen"
        else if (poses.size < 15) warnings += "Sehr kurze VIO-Trajektorie"
        if (sorted.isNotEmpty() && medianNs > 200_000_000.0)
            warnings += "VIO liefert im Median weniger als 5 Posen/s"
        if (sorted.isNotEmpty() && sorted.last() > 650_000_000L)
            warnings += "VIO-Pose-Lücke größer als 650 ms"
        if (photoTimestamps.isNotEmpty() && paired == 0)
            warnings += "Keine RGB-Zeitstempel passen zur nativen VIO-Uhr"
        else if (photoTimestamps.size >= 2 &&
            paired.toDouble() / photoTimestamps.size < 0.8)
            warnings += "Weniger als 80 % der RGB-Bilder haben eine nahe VIO-Pose"
        if (!distance.isFinite() || !offset.isFinite())
            warnings += "Trajektorienabstände sind numerisch ungültig"
        return Report(
            poses.size, photoTimestamps.size, paired, hz,
            medianNs / 1e6, (sorted.lastOrNull() ?: 0L) / 1e6,
            duration, distance, offset,
            if (distance.isFinite() && distance > 1e-9 &&
                offset.isFinite()) offset / distance else null,
            warnings
        )
    }
}
