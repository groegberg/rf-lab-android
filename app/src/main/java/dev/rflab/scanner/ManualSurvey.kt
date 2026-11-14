package dev.rflab.scanner

import android.content.Context
import android.net.wifi.ScanResult
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class ManualPoint(val id: Int, val x: Float, val z: Float, val label: String)
data class ManualReading(val pointId: Int, val tNs: Long, val bssid: String, val ssid: String,
                         val freq: Int, val rssi: Int, val source: String)
data class ManualSummary(val pointId: Int, val bssid: String, val ssid: String, val freq: Int,
                         val median: Int, val count: Int, val min: Int, val max: Int)

/** Explicit user-placed meter coordinates. Never represented as ARCore localization. */
class ManualSurvey(private val context: Context) {
    var widthM = 6f
        private set
    var heightM = 5f
        private set
    var label = "Manuelle WLAN-Karte"
        private set
    var selectedId: Int? = null
        private set
    var collecting = false
        private set
    private val points = arrayListOf<ManualPoint>()
    private val readings = arrayListOf<ManualReading>()
    private val lastScanT = mutableMapOf<Pair<Int, String>, Long>()
    private var file: File? = null
    private var captureSinceNs = 0L

    @Synchronized fun start(title: String) {
        label = title
        points.clear(); readings.clear(); lastScanT.clear()
        selectedId = null
        collecting = false
        captureSinceNs = 0L
        file = File(context.filesDir, "manual_survey_" + System.currentTimeMillis() + ".json")
        persist()
    }

    @Synchronized fun setSize(width: Float, height: Float): Boolean {
        if (!width.isFinite() || !height.isFinite() || width !in 1f..100f || height !in 1f..100f) return false
        widthM = width; heightM = height; persist()
        return true
    }

    @Synchronized fun addPoint(x: Float, z: Float): ManualPoint? {
        if (x !in 0f..widthM || z !in 0f..heightM) return null
        val p = ManualPoint((points.maxOfOrNull { it.id } ?: 0) + 1, x, z,
            "P" + ((points.maxOfOrNull { it.id } ?: 0) + 1))
        points.add(p)
        selectedId = p.id
        collecting = true
        captureSinceNs = SystemClock.elapsedRealtimeNanos()
        persist()
        return p
    }

    @Synchronized fun select(id: Int) {
        if (points.none { it.id == id }) return
        selectedId = id
        collecting = false // no measurements while physically moving to next point
        captureSinceNs = 0L
        persist()
    }

    @Synchronized fun record(value: Boolean): Boolean {
        if (value && selectedId == null) return false
        collecting = value
        captureSinceNs = if (value) SystemClock.elapsedRealtimeNanos() else 0L
        persist()
        return true
    }

    @Synchronized fun finish() { collecting = false; persist() }
    @Synchronized fun allPoints(): List<ManualPoint> = points.toList()
    @Synchronized fun allReadings(): List<ManualReading> = readings.toList()
    @Synchronized fun count() = readings.size

    @Synchronized fun addLink(time: Long, bssid: String, ssid: String, freq: Int,
                              rssi: Int): ManualReading? {
        return add(time, bssid, ssid, freq, rssi, "connected")
    }

    @Synchronized fun addScan(ap: ScanResult, nowNs: Long, fresh: Boolean): ManualReading? {
        if (!fresh) return null
        val timestamp = ap.timestamp * 1000L
        if (timestamp <= 0 || timestamp < captureSinceNs ||
            abs(nowNs - timestamp) > 2_500_000_000L) return null
        val id = selectedId ?: return null
        val key = Pair(id, ap.BSSID ?: "")
        if (timestamp <= (lastScanT[key] ?: 0L)) return null
        val entry = add(timestamp, ap.BSSID ?: "", ap.SSID ?: "",
            ap.frequency, ap.level, "scan")
        if (entry != null) lastScanT[key] = timestamp
        return entry
    }

    private fun add(time: Long, bssid: String, ssid: String, freq: Int,
                    rssi: Int, source: String): ManualReading? {
        val id = selectedId
        if (!collecting || id == null || time < captureSinceNs || rssi !in -110..-1 || freq <= 0 ||
            bssid.isBlank() || bssid == "02:00:00:00:00:00") return null
        val row = ManualReading(id, time, bssid, ssid, freq, rssi, source)
        readings.add(row)
        if (readings.size % 10 == 0) persist()
        return row
    }

    @Synchronized fun summary(): List<ManualSummary> {
        return readings.groupBy { Pair(it.pointId, it.bssid) }.map { (key, all) ->
            val values = all.map { it.rssi }.sorted()
            val median = if (values.size % 2 == 0)
                (values[values.size / 2 - 1] + values[values.size / 2]) / 2
            else values[values.size / 2]
            ManualSummary(key.first, key.second, all.last().ssid, all.last().freq,
                median, values.size, values.first(), values.last())
        }
    }

    @Synchronized fun accessPoints(): List<Pair<String, String>> {
        return readings.groupBy { it.bssid }.map { (id, list) ->
            Pair(id, list.lastOrNull { it.ssid.isNotBlank() }?.ssid ?: id)
        }.sortedBy { it.second }
    }

    @Synchronized fun selectedDescription(): String {
        val selected = points.firstOrNull { it.id == selectedId }
            ?: return "Noch kein Messpunkt gesetzt"
        val mine = readings.filter { it.pointId == selected.id }
        return String.format(Locale.GERMAN,
            "%s · %.1f / %.1f m · %d Messwerte · %d BSSIDs · %s",
            selected.label, selected.x, selected.z, mine.size,
            mine.map { it.bssid }.distinct().size,
            if (collecting) "AUFNAHME" else "PAUSIERT")
    }

    @Synchronized fun exportCsv(): String {
        val out = StringBuilder("point;x_m;z_m;bssid;ssid;freq_mhz;median_dbm;samples;min_dbm;max_dbm\n")
        val indexed = points.associateBy { it.id }
        fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        for (row in summary().sortedWith(compareBy({ it.pointId }, { it.bssid }))) {
            val p = indexed[row.pointId] ?: continue
            out.append(csv(p.label)).append(';')
                .append(String.format(Locale.ROOT, "%.3f", p.x)).append(';')
                .append(String.format(Locale.ROOT, "%.3f", p.z)).append(';')
                .append(csv(row.bssid)).append(';').append(csv(row.ssid)).append(';')
                .append(row.freq).append(';').append(row.median).append(';')
                .append(row.count).append(';').append(row.min).append(';')
                .append(row.max).append('\n')
        }
        return out.toString()
    }

    @Synchronized fun persist() {
        val destination = file ?: return
        val json = JSONObject().put("schema", "manual-survey-v1").put("label", label)
            .put("width_m", widthM.toDouble()).put("height_m", heightM.toDouble())
            .put("selected_id", selectedId ?: JSONObject.NULL)
        val positions = JSONArray()
        for (p in points) positions.put(JSONObject().put("id", p.id)
            .put("x_m", p.x.toDouble()).put("z_m", p.z.toDouble()).put("label", p.label))
        val samples = JSONArray()
        for (r in readings) samples.put(JSONObject().put("point_id", r.pointId)
            .put("t_ns", r.tNs).put("bssid", r.bssid).put("ssid", r.ssid)
            .put("freq", r.freq).put("rssi", r.rssi).put("source", r.source))
        json.put("points", positions).put("readings", samples)
        val tmp = File(destination.parentFile, destination.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(destination)) {
            destination.writeText(json.toString())
            tmp.delete()
        }
    }

    @Synchronized fun loadLatest(): Boolean {
        val newest = context.filesDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("manual_survey_") &&
                it.name.endsWith(".json") }?.maxByOrNull { it.lastModified() } ?: return false
        return try {
            val json = JSONObject(newest.readText())
            if (json.optString("schema") != "manual-survey-v1") return false
            points.clear(); readings.clear(); lastScanT.clear()
            label = json.optString("label", newest.name)
            widthM = json.optDouble("width_m", 6.0).toFloat().coerceIn(1f, 100f)
            heightM = json.optDouble("height_m", 5.0).toFloat().coerceIn(1f, 100f)
            val ps = json.optJSONArray("points") ?: JSONArray()
            for (i in 0 until ps.length()) {
                val p = ps.optJSONObject(i) ?: continue
                points.add(ManualPoint(p.optInt("id"), p.optDouble("x_m").toFloat(),
                    p.optDouble("z_m").toFloat(), p.optString("label")))
            }
            val rs = json.optJSONArray("readings") ?: JSONArray()
            for (i in 0 until rs.length()) {
                val r = rs.optJSONObject(i) ?: continue
                readings.add(ManualReading(r.optInt("point_id"), r.optLong("t_ns"),
                    r.optString("bssid"), r.optString("ssid"), r.optInt("freq"),
                    r.optInt("rssi"), r.optString("source")))
            }
            val selected = json.optInt("selected_id", -1)
            selectedId = if (points.any { it.id == selected }) selected else null
            collecting = false
            file = newest
            true
        } catch (_: Exception) { false }
    }
}
