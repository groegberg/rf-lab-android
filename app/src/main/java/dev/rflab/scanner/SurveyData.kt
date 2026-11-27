package dev.rflab.scanner

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Local ARCore coordinate frame: x right, y up, z toward the camera. */
data class SurveyPose(
    val timeNs: Long, val run: Int, val x: Float, val y: Float, val z: Float,
    val qx: Float, val qy: Float, val qz: Float, val qw: Float
)

data class SurveyRadio(
    val timeNs: Long, val ssid: String, val bssid: String, val frequency: Int,
    val rssi: Int, val origin: String, val pose: SurveyPose?
)

data class SurveyDepth(val x: Float, val y: Float, val z: Float, val confidence: Float,
                       val run: Int = 0, val argb: Int = 0, val rawDepth: Boolean = true)

data class ApStats(
    val bssid: String, var ssid: String, var frequency: Int,
    var count: Int = 0, var totalRssi: Long = 0, var minRssi: Int = 0, var maxRssi: Int = -120
) {
    val meanRssi: Double get() = if (count == 0) -120.0 else totalRssi.toDouble() / count
    val channel: Int get() = when {
        frequency in 2412..2472 -> (frequency - 2407) / 5
        frequency == 2484 -> 14
        frequency in 5000..5895 -> (frequency - 5000) / 5
        frequency in 5955..7115 -> (frequency - 5950) / 5
        else -> -1
    }
    val band: String get() = when {
        frequency < 3000 -> "2,4 GHz"
        frequency < 5900 -> "5 GHz"
        else -> "6 GHz"
    }
    fun add(signal: Int, apSsid: String, freq: Int) {
        if (count == 0) { minRssi = signal; maxRssi = signal }
        count++
        totalRssi += signal
        minRssi = min(minRssi, signal)
        maxRssi = max(maxRssi, signal)
        if (apSsid.isNotBlank() && apSsid != "<unknown ssid>") ssid = apSsid
        if (freq > 0) frequency = freq
    }
}

data class MatchResult(
    val name: String, val score: Double, val overlap: Int, val total: Int, val sameDevice: Boolean
)

class SurveyData {
    val poses = mutableListOf<SurveyPose>()
    val radios = mutableListOf<SurveyRadio>()
    val depth = mutableListOf<SurveyDepth>()
    val aps = linkedMapOf<String, ApStats>()
    val roam = mutableListOf<String>()
    val latestFingerprint = linkedMapOf<String, Int>()
    var arRun = 0
    var freshScans = 0
    var staleScans = 0
    var scanRequests = 0
    var acceptedScanRequests = 0
    var depthSupported: Boolean? = null
    var lastRssi = -127
    var lastConnectedBssid = ""
    var lastConnectedSsid = ""
    var title = "Neue Messfahrt"
    var createdAtMs = System.currentTimeMillis()

    @Synchronized fun reset(name: String) {
        poses.clear(); radios.clear(); depth.clear(); aps.clear(); roam.clear()
        latestFingerprint.clear()
        arRun = 0; freshScans = 0; staleScans = 0
        scanRequests = 0; acceptedScanRequests = 0
        depthSupported = null; lastRssi = -127
        lastConnectedBssid = ""; lastConnectedSsid = ""
        title = name; createdAtMs = System.currentTimeMillis()
    }

    @Synchronized fun newArRun() { arRun++ }

    @Synchronized fun addPose(payload: JSONObject, nowNs: Long) {
        if (payload.optString("state") != "TRACKING") return
        val p = payload.optJSONArray("position_m") ?: return
        val q = payload.optJSONArray("quaternion_xyzw") ?: return
        if (p.length() < 3 || q.length() < 4) return
        val pose = SurveyPose(nowNs, arRun,
            p.optDouble(0).toFloat(), p.optDouble(1).toFloat(), p.optDouble(2).toFloat(),
            q.optDouble(0).toFloat(), q.optDouble(1).toFloat(),
            q.optDouble(2).toFloat(), q.optDouble(3).toFloat())
        if (pose.x.isFinite() && pose.y.isFinite() && pose.z.isFinite()) poses.add(pose)
    }

    @Synchronized fun addDepth(points: List<SurveyDepth>) {
        if (points.isEmpty()) return
        if (depth.size >= 24000) depth.subList(0, min(depth.size, 2000)).clear()
        depth.addAll(points.take(260).map { it.copy(run = arRun) })
    }

    @Synchronized fun nearestPose(atNs: Long, maxGapNs: Long = 600_000_000L): SurveyPose? {
        if (poses.isEmpty()) return null
        // Poses arrive in increasing timestamp order; recent updates are near the end.
        var closest: SurveyPose? = null
        var distance = maxGapNs + 1
        for (i in poses.indices.reversed()) {
            val p = poses[i]
            val d = abs(p.timeNs - atNs)
            if (d < distance) { closest = p; distance = d }
            if (p.timeNs < atNs - maxGapNs) break
        }
        return if (distance <= maxGapNs) closest else null
    }

    private fun appendRadio(nowNs: Long, ssid: String, bssid: String, freq: Int,
                            signal: Int, origin: String, pose: SurveyPose? = null) {
        if (bssid.isBlank() || bssid == "02:00:00:00:00:00" || signal !in -110..-1) return
        val positioned = pose ?: nearestPose(nowNs)
        radios.add(SurveyRadio(nowNs, ssid, bssid, freq, signal, origin, positioned))
        aps.getOrPut(bssid) { ApStats(bssid, ssid, freq) }.add(signal, ssid, freq)
    }

    @Synchronized fun addScan(payload: JSONObject, timeNs: Long) {
        val fresh = payload.optBoolean("updated", false)
        if (fresh) freshScans++ else staleScans++
        val observations = payload.optJSONArray("aps") ?: return
        if (!fresh) return // Do not map cached scans as if they were new RF samples
        latestFingerprint.clear()
        for (i in 0 until observations.length()) {
            val ap = observations.optJSONObject(i) ?: continue
            val signal = ap.optInt("rssi_dbm", 0)
            val bssid = ap.optString("bssid")
            if (signal !in -110..-1 || bssid.isBlank()) continue
            val freq = ap.optInt("frequency_mhz")
            val seenNs = ap.optLong("seen_t_ns", 0L)
            val isFresh = seenNs > 0 && abs(timeNs - seenNs) < 2_500_000_000L
            // Android SDK's timestamp is elapsedRealtime microseconds, converted to nanos.
            if (!isFresh) continue
            val ssid = ap.optString("ssid")
            latestFingerprint[bssid] = signal
            appendRadio(seenNs, ssid, bssid, freq, signal, "scan")
        }
    }

    @Synchronized fun addLink(payload: JSONObject, timeNs: Long) {
        val bssid = payload.optString("bssid")
        val ssid = payload.optString("ssid")
        val rssi = payload.optInt("rssi_dbm", 0)
        if (rssi !in -110..-1) return
        if (lastConnectedBssid.isNotBlank() && lastConnectedBssid != bssid) {
            val previous = lastConnectedBssid
            roam.add("Wechsel: " + previous + " → " + bssid +
                if (lastConnectedSsid.isNotBlank()) " (" + lastConnectedSsid + ")" else "")
        }
        lastConnectedBssid = bssid
        lastConnectedSsid = ssid
        lastRssi = rssi
        appendRadio(timeNs, ssid, bssid, payload.optInt("frequency_mhz"),
            rssi, "connected")
    }

    @Synchronized fun snapshotPoses() = poses.toList()
    @Synchronized fun snapshotRadios() = radios.toList()
    @Synchronized fun snapshotDepth() = depth.toList()
    @Synchronized fun snapshotAps() = aps.values.map { it.copy() }
    @Synchronized fun snapshotFingerprint(): Map<String, Int> = latestFingerprint.toMap()
    @Synchronized fun snapshotRoam() = roam.toList()
    @Synchronized fun positionedCount() = radios.count { it.pose != null }
    @Synchronized fun geometryCount() = depth.size

    companion object {
        /**
         * Load existing RFLab sessions, including old 0.2.x files, without changing
         * their coordinate frames or inventing missing pose information.
         */
        fun fromJsonl(file: File): SurveyData {
            val data = SurveyData()
            data.title = file.name.removeSuffix(".jsonl")
            data.createdAtMs = file.lastModified()
            val delayed = mutableListOf<Triple<String, JSONObject, Long>>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    try {
                        val row = JSONObject(line)
                        val type = row.optString("type")
                        val t = row.optLong("t_ns")
                        val p = row.optJSONObject("payload") ?: JSONObject()
                        when (type) {
                            "ar_session_start" -> data.newArRun()
                            "ar_pose" -> data.addPose(p, t)
                            "ar_depth_points" -> {
                                val array = p.optJSONArray("points") ?: JSONArray()
                                val points = ArrayList<SurveyDepth>(array.length())
                                for (i in 0 until array.length()) {
                                    val a = array.optJSONArray(i) ?: continue
                                    if (a.length() >= 4) points.add(
                                        SurveyDepth(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(),
                                            a.optDouble(2).toFloat(), a.optDouble(3).toFloat()))
                                }
                                data.addDepth(points)
                            }
                            "ar_depth_capability" -> data.depthSupported = p.optBoolean("supported")
                            "wifi_scan", "wifi_link" -> delayed.add(Triple(type, p, t))
                            "wifi_scan_request" -> {
                                data.scanRequests++
                                if (p.optBoolean("accepted")) data.acceptedScanRequests++
                            }
                        }
                    } catch (_: Exception) { /* Incomplete last line in an interrupted session */ }
                }
            }
            for ((type, p, t) in delayed) {
                if (type == "wifi_scan") data.addScan(p, t) else data.addLink(p, t)
            }
            return data
        }
    }
}

/** Persistent Wi-Fi fingerprints, stored only in the app's private files. */
class FingerprintLibrary(private val context: Context) {
    data class Anchor(
        val name: String, val createdMs: Long, val model: String,
        val readings: Map<String, Int>
    )
    private val file = File(context.filesDir, "wifi_fingerprint_anchors.json")
    private val anchors = mutableListOf<Anchor>()

    init { load() }

    fun all(): List<Anchor> = anchors.toList()

    fun save(name: String, readings: Map<String, Int>): Boolean {
        if (name.isBlank() || readings.size < 3) return false
        val clean = name.trim().take(55)
        val model = Build.MANUFACTURER + " " + Build.MODEL
        anchors.removeAll { it.name.equals(clean, ignoreCase = true) }
        anchors.add(Anchor(clean, System.currentTimeMillis(), model, readings.toMap()))
        persist()
        return true
    }

    fun delete(name: String) {
        anchors.removeAll { it.name == name }
        persist()
    }

    fun predict(current: Map<String, Int>): List<MatchResult> {
        if (current.size < 3) return emptyList()
        val currentModel = Build.MANUFACTURER + " " + Build.MODEL
        return anchors.mapNotNull { anchor ->
            val overlap = anchor.readings.keys.intersect(current.keys)
            if (overlap.size < 3) return@mapNotNull null
            // Root mean square RSSI difference, with a penalty for absent APs.
            val msd = overlap.sumOf { bssid ->
                (anchor.readings.getValue(bssid) - current.getValue(bssid))
                    .toDouble().pow(2.0)
            } / overlap.size
            val absent = (anchor.readings.size + current.size - 2 * overlap.size)
            val penalty = 2.5 * absent / max(3, anchor.readings.size)
            val rms = sqrt(msd) + penalty
            MatchResult(anchor.name, rms, overlap.size,
                anchor.readings.size, anchor.model == currentModel)
        }.sortedBy { it.score }.take(3)
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val list = JSONArray(file.readText())
            for (i in 0 until list.length()) {
                val a = list.optJSONObject(i) ?: continue
                val r = a.optJSONObject("readings") ?: continue
                val samples = linkedMapOf<String, Int>()
                for (key in r.keys()) samples[key] = r.optInt(key)
                anchors.add(Anchor(a.optString("name"), a.optLong("created_ms"),
                    a.optString("device"), samples))
            }
        } catch (_: Exception) { anchors.clear() }
    }

    private fun persist() {
        val array = JSONArray()
        for (anchor in anchors) {
            val samples = JSONObject()
            anchor.readings.forEach { (k, v) -> samples.put(k, v) }
            array.put(JSONObject().put("name", anchor.name)
                .put("created_ms", anchor.createdMs).put("device", anchor.model)
                .put("readings", samples))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(array.toString()); tmp.delete()
        }
    }
}
