package dev.rflab.scanner

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Experimental geometric hint, NOT a calibrated metric position fix. */
data class SpatialHint(val x: Float, val z: Float, val uncertaintyM: Float, val anchors: Int)
data class DeviceProximity(
    val device: HomeDevice, val nominalM: Float,
    val lowerM: Float, val upperM: Float, val kind: String
)

/**
 * Uses ONLY same-room, user-measured RSSI references. Requires at least three
 * spatially separated reference positions. Conservative uncertainty prevents
 * distances being confused with centimetre-accurate localization.
 */
class SpatialProximity {
    fun hint(home: SpatialHomeStore, estimate: RoomEstimate?, scan: RfSweep?): SpatialHint? {
        if (estimate?.quality != "OK" || scan == null) return null
        val roomId = estimate.roomId ?: return null
        data class Weighted(val point: HomeAnchor, val error: Double)
        val anchors = home.anchors.filter { it.roomId == roomId && it.sweeps.isNotEmpty() }
        if (anchors.size < 3) return null
        val spreadX = anchors.maxOf { it.x } - anchors.minOf { it.x }
        val spreadZ = anchors.maxOf { it.z } - anchors.minOf { it.z }
        if (spreadX < .75f || spreadZ < .75f) return null
        val estimates = anchors.mapNotNull { point ->
            val medians = point.median()
            val overlap = medians.keys.intersect(scan.readings.keys)
            if (overlap.size < 3) return@mapNotNull null
            val rms = sqrt(overlap.sumOf { bssid ->
                (medians.getValue(bssid) - scan.readings.getValue(bssid))
                    .toDouble().pow(2)
            } / overlap.size)
            Weighted(point, rms)
        }.sortedBy { it.error }.take(4)
        if (estimates.size < 3) return null
        val baseline = estimates.first().error
        val weights = estimates.map { exp(-(it.error - baseline) / 4.5) }
        val sum = weights.sum().takeIf { it > 0 } ?: return null
        val x = estimates.indices.sumOf { weights[it] * estimates[it].point.x } / sum
        val z = estimates.indices.sumOf { weights[it] * estimates[it].point.z } / sum
        val variance = estimates.indices.sumOf {
            val dx = estimates[it].point.x - x
            val dz = estimates[it].point.z - z
            weights[it] * (dx * dx + dz * dz)
        } / sum
        val radius = max(1.5, sqrt(variance) * 1.8)
        return SpatialHint(x.toFloat(), z.toFloat(), radius.toFloat(), estimates.size)
    }

    fun nearby(home: SpatialHomeStore, estimate: RoomEstimate?, hint: SpatialHint?,
               limitM: Float = 2f): List<DeviceProximity> {
        val roomId = estimate?.roomId ?: return emptyList()
        if (hint == null || estimate.quality != "OK") return emptyList()
        return home.devices.filter { it.roomId == roomId }.map { device ->
            val dx = device.x - hint.x
            val dz = device.z - hint.z
            val nominal = sqrt(dx * dx + dz * dz)
            val min = max(0f, nominal - hint.uncertaintyM)
            val max = nominal + hint.uncertaintyM
            val status = when {
                max < limitM -> "NAHE (unter Modellannahmen)"
                min > limitM -> "WEITER ENTFERNT"
                else -> "UNSICHER"
            }
            DeviceProximity(device, nominal, min, max, status)
        }.sortedBy { it.nominalM }
    }
}
