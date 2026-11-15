package dev.rflab.scanner

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

data class RoomCandidate(val roomId: String, val name: String, val probability: Double,
                         val scoreDbm: Double, val overlap: Int)
data class RoomEstimate(
    val roomId: String?, val roomName: String, val confidence: Double,
    val atMs: Long, val bestAnchorId: String?, val scoreDbm: Double,
    val overlap: Int, val quality: String, val candidates: List<RoomCandidate>
) {
    fun usable(nowMs: Long): Boolean = roomId != null &&
        quality == "OK" && nowMs - atMs in 0..90_000L && confidence >= .8
}

class SpatialLocator {
    fun estimate(home: SpatialHomeStore, scan: RfSweep, nowMs: Long = System.currentTimeMillis()): RoomEstimate =
        estimate(home.rooms, home.anchors, scan, nowMs)

    fun estimate(rooms: List<HomeRoom>, anchors: List<HomeAnchor>, scan: RfSweep,
                 nowMs: Long = System.currentTimeMillis()): RoomEstimate {
        fun unknown(reason: String, all: List<RoomCandidate> = emptyList()) =
            RoomEstimate(null, "Unbekannt", 0.0, scan.atMs, null, 120.0, 0,
                reason, all)
        if (scan.atMs <= 0L || nowMs - scan.atMs !in 0..90_000L)
            return unknown("SCAN_VERALTET")
        if (scan.readings.size < 3) return unknown("ZU_WENIGE_APS")
        data class AnchorScore(val anchor: HomeAnchor, val score: Double, val overlap: Int)
        val evaluated = anchors.mapNotNull { anchor ->
            if (anchor.sweeps.isEmpty()) return@mapNotNull null
            val reference = anchor.median()
            val common = reference.keys.intersect(scan.readings.keys)
            if (common.size < 3) return@mapNotNull null
            val msd = common.sumOf { bssid ->
                (reference.getValue(bssid) - scan.readings.getValue(bssid)).toDouble().pow(2.0)
            } / common.size
            val missing = (reference.size + scan.readings.size - 2 * common.size).toDouble()
            val missingPenalty = 4.5 * missing / max(3, reference.size)
            AnchorScore(anchor, sqrt(msd) + missingPenalty, common.size)
        }
        if (evaluated.isEmpty()) return unknown("KEINE_GEMEINSAMEN_APS")
        // Every room contributes its closest *measured* reference point.
        val byRoom = evaluated.groupBy { it.anchor.roomId }.mapNotNull { (roomId, scores) ->
            val room = rooms.firstOrNull { it.id == roomId } ?: return@mapNotNull null
            Triple(room, scores.minBy { it.score }, scores.size)
        }.sortedBy { it.second.score }
        val best = byRoom.firstOrNull() ?: return unknown("KEINE_REFERENZEN")
        val weights = byRoom.map { exp(-(it.second.score - best.second.score) / 5.0) }
        val total = weights.sum()
        val candidates = byRoom.mapIndexed { index, (room, match, _) ->
            RoomCandidate(room.id, room.name,
                if (total > 0) weights[index] / total else 0.0,
                match.score, match.overlap)
        }
        val distinctRoomsWithTrainedPoints = anchors.filter { it.sweeps.isNotEmpty() }
            .map { it.roomId }.distinct().size
        val score = best.second.score
        val margin = if (byRoom.size >= 2)
            byRoom[1].second.score - score else 0.0
        val reason = when {
            distinctRoomsWithTrainedPoints < 2 -> "NUR_EIN_RAUM_KALIBRIERT"
            score > 16.0 -> "ZU_GROSSE_ABWEICHUNG"
            margin < 4.0 -> "RAEUME_NICHT_TRENNBAR"
            candidates.first().probability < .8 -> "ZU_GERINGE_SICHERHEIT"
            else -> "OK"
        }
        return RoomEstimate(
            if (reason == "OK") best.first.id else null,
            if (reason == "OK") best.first.name else "Unbekannt",
            if (reason == "OK") candidates.first().probability else 0.0,
            scan.atMs, best.second.anchor.id, score, best.second.overlap,
            reason, candidates
        )
    }
}

/** No real device operations. This cannot switch a lamp or reach Alexa. */
class SpatialAutomation(private val home: SpatialHomeStore) {
    private var candidateRoomId: String? = null
    private var candidateSinceMs = 0L
    private var lastTriggeredRoomId: String? = null
    private var lastProcessedScanAt = 0L

    fun onFreshEstimate(value: RoomEstimate, nowMs: Long = System.currentTimeMillis()): String? {
        if (value.atMs <= lastProcessedScanAt) return null
        lastProcessedScanAt = value.atMs
        if (!value.usable(nowMs)) {
            candidateRoomId = null
            candidateSinceMs = 0L
            return null
        }
        if (candidateRoomId != value.roomId) {
            candidateRoomId = value.roomId
            candidateSinceMs = nowMs
            return null
        }
        val rule = home.ruleFor(value.roomId ?: return null) ?: return null
        if (!home.simulationEnabled || !rule.enabled || value.confidence < rule.minConfidence)
            return null
        if (nowMs - candidateSinceMs < rule.enterSeconds * 1000L) return null
        if (lastTriggeredRoomId == value.roomId) return null
        lastTriggeredRoomId = value.roomId
        val room = home.room(value.roomId) ?: return null
        val event = "SIMULATION: " + rule.action +
            " (Raum=" + room.name + ", P=" + (value.confidence * 100).toInt() + "%)"
        home.log(room.name, event)
        return event
    }

    fun reset() {
        candidateRoomId = null
        candidateSinceMs = 0L
        lastTriggeredRoomId = null
        lastProcessedScanAt = 0L
    }
}
