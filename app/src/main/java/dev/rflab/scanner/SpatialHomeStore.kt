package dev.rflab.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One persistent home coordinate system; never silently equate it to an ARCore run. */
data class HomeRoom(
    val id: String, var name: String, var x: Float, var z: Float,
    var width: Float, var depth: Float
) {
    fun contains(px: Float, pz: Float) = px >= x && px <= x + width &&
        pz >= z && pz <= z + depth
}
data class RfSweep(
    val atMs: Long, val readings: Map<String, Int>, val ssids: Map<String, String>
)
data class HomeAnchor(
    val id: String, val roomId: String, var label: String, val x: Float, val z: Float,
    val sweeps: MutableList<RfSweep> = mutableListOf()
) {
    fun median(): Map<String, Int> {
        val grouped = mutableMapOf<String, MutableList<Int>>()
        sweeps.forEach { sweep ->
            sweep.readings.forEach { (bssid, rssi) ->
                grouped.getOrPut(bssid) { mutableListOf() }.add(rssi)
            }
        }
        return grouped.mapValues { (_, values) ->
            val sorted = values.sorted()
            sorted[sorted.size / 2]
        }
    }
}
data class HomeDevice(
    val id: String, var name: String, var roomId: String,
    var x: Float, var z: Float, var type: String, var actionName: String = ""
)
data class HomeRule(
    val id: String, val roomId: String, var action: String,
    var enabled: Boolean = false, var enterSeconds: Int = 8,
    var minConfidence: Double = .8
)
data class HomeEvent(val atMs: Long, val room: String, val detail: String)

/**
 * Local-only spatial data. No cloud sync, tokens, or Alexa account data.
 * All writes use temporary files. Room coordinates are user-defined meters.
 */
class SpatialHomeStore(private val context: Context) {
    val rooms = mutableListOf<HomeRoom>()
    val anchors = mutableListOf<HomeAnchor>()
    val devices = mutableListOf<HomeDevice>()
    val rules = mutableListOf<HomeRule>()
    val events = mutableListOf<HomeEvent>()
    var simulationEnabled = true
        private set
    private val target = File(context.filesDir, "spatial_home_v1.json")

    init { load() }

    fun room(id: String?): HomeRoom? = rooms.firstOrNull { it.id == id }

    fun addRoom(name: String, x: Float, z: Float, width: Float, depth: Float): Boolean {
        if (name.isBlank() || name.length > 50 || !x.isFinite() || !z.isFinite() ||
            !width.isFinite() || !depth.isFinite() ||
            x !in 0f..95f || z !in 0f..95f ||
            width !in 1f..30f || depth !in 1f..30f ||
            x + width > 100f || z + depth > 100f) return false
        // Overlap makes room classification and tap-to-place ambiguous.
        if (rooms.any {
                x < it.x + it.width - .01f && x + width > it.x + .01f &&
                z < it.z + it.depth - .01f && z + depth > it.z + .01f
            }) return false
        rooms.add(HomeRoom(UUID.randomUUID().toString(), name.trim(), x, z, width, depth))
        save()
        return true
    }

    fun removeRoom(id: String) {
        rooms.removeAll { it.id == id }
        anchors.removeAll { it.roomId == id }
        devices.removeAll { it.roomId == id }
        rules.removeAll { it.roomId == id }
        save()
    }

    fun addAnchor(roomId: String, x: Float, z: Float): HomeAnchor? {
        val r = room(roomId) ?: return null
        if (!r.contains(x, z)) return null
        val name = "P" + (anchors.count { it.roomId == roomId } + 1)
        val a = HomeAnchor(UUID.randomUUID().toString(), roomId, name, x, z)
        anchors.add(a); save()
        return a
    }

    fun removeAnchor(id: String) {
        anchors.removeAll { it.id == id }
        save()
    }

    fun addSweep(id: String, sweep: RfSweep): Boolean {
        val a = anchors.find { it.id == id } ?: return false
        if (sweep.readings.size < 3) return false
        // A hard cap prevents an accidentally long field session growing unbounded.
        a.sweeps.add(sweep)
        if (a.sweeps.size > 35) a.sweeps.removeAt(0)
        save()
        return true
    }

    fun addDevice(name: String, roomId: String, type: String): HomeDevice? {
        val room = room(roomId) ?: return null
        if (name.isBlank()) return null
        val d = HomeDevice(UUID.randomUUID().toString(), name.trim().take(55), roomId,
            room.x + room.width / 2f, room.z + room.depth / 2f, type)
        devices.add(d); save()
        return d
    }

    fun moveDevice(id: String, x: Float, z: Float): Boolean {
        val d = devices.find { it.id == id } ?: return false
        val r = room(d.roomId) ?: return false
        if (!r.contains(x, z)) return false
        d.x = x; d.z = z; save()
        return true
    }

    fun removeDevice(id: String) { devices.removeAll { it.id == id }; save() }

    fun ruleFor(roomId: String) = rules.firstOrNull { it.roomId == roomId }

    fun saveRule(roomId: String, text: String, enabled: Boolean, dwell: Int): HomeRule? {
        room(roomId) ?: return null
        val rule = ruleFor(roomId) ?: HomeRule(UUID.randomUUID().toString(), roomId, "")
            .also { rules.add(it) }
        rule.action = text.trim().take(100)
        rule.enabled = enabled
        rule.enterSeconds = dwell.coerceIn(3, 120)
        save()
        return rule
    }

    fun setSimulation(enabled: Boolean) {
        // There is intentionally no remote command transport in this build.
        simulationEnabled = enabled
        save()
    }

    fun log(roomName: String, detail: String) {
        events.add(HomeEvent(System.currentTimeMillis(), roomName, detail))
        while (events.size > 75) events.removeAt(0)
        save()
    }

    fun exportJson(): String = serialize().toString(2)


    /**
     * Transactional user-selected JSON restore. Keep a backup of the previous
     * map and roll it back if malformed content fails parse validation.
     */
    @Synchronized fun restoreJson(content: String): Boolean {
        if (content.length > 6_000_000) return false
        val incoming = try { JSONObject(content) } catch (_: Exception) { return false }
        if (incoming.optString("schema") != "rflab-spatial-home-v1") return false
        val rs = incoming.optJSONArray("rooms") ?: return false
        val aa = incoming.optJSONArray("anchors") ?: return false
        val dd = incoming.optJSONArray("devices") ?: return false
        val rr = incoming.optJSONArray("rules") ?: return false
        if (rs.length() > 200 || aa.length() > 4000 ||
            dd.length() > 1000 || rr.length() > 200) return false
        val original = if (target.exists()) target.readText(Charsets.UTF_8) else null
        return try {
            val tmp = File(target.parentFile, target.name + ".restore.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
            rooms.clear(); anchors.clear(); devices.clear(); rules.clear(); events.clear()
            load()
            if (rooms.size != rs.length() || anchors.size != aa.length() ||
                devices.size != dd.length() || rules.size != rr.length()) {
                error("Imported content is inconsistent")
            }
            save()
            true
        } catch (_: Exception) {
            if (original == null) target.delete()
            else target.writeText(original, Charsets.UTF_8)
            rooms.clear(); anchors.clear(); devices.clear(); rules.clear(); events.clear()
            if (original != null) load()
            false
        }
    }

    @Synchronized fun save() {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(serialize().toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun serialize(): JSONObject {
        val root = JSONObject()
            .put("schema", "rflab-spatial-home-v1")
            .put("simulation_enabled", simulationEnabled)
        val rs = JSONArray()
        rooms.forEach { r -> rs.put(JSONObject()
            .put("id", r.id).put("name", r.name)
            .put("x", r.x.toDouble()).put("z", r.z.toDouble())
            .put("w", r.width.toDouble()).put("d", r.depth.toDouble())) }
        root.put("rooms", rs)
        val aa = JSONArray()
        anchors.forEach { a ->
            val sweeps = JSONArray()
            a.sweeps.forEach { s ->
                val readings = JSONObject()
                s.readings.forEach { (k, v) -> readings.put(k, v) }
                val ssids = JSONObject()
                s.ssids.forEach { (k, v) -> ssids.put(k, v) }
                sweeps.put(JSONObject().put("at_ms", s.atMs)
                    .put("readings", readings).put("ssids", ssids))
            }
            aa.put(JSONObject().put("id", a.id).put("room_id", a.roomId)
                .put("label", a.label).put("x", a.x.toDouble())
                .put("z", a.z.toDouble()).put("sweeps", sweeps))
        }
        root.put("anchors", aa)
        val dd = JSONArray()
        devices.forEach { d -> dd.put(JSONObject()
            .put("id", d.id).put("name", d.name).put("room_id", d.roomId)
            .put("x", d.x.toDouble()).put("z", d.z.toDouble())
            .put("type", d.type).put("action", d.actionName)) }
        root.put("devices", dd)
        val rr = JSONArray()
        rules.forEach { r -> rr.put(JSONObject()
            .put("id", r.id).put("room_id", r.roomId).put("action", r.action)
            .put("enabled", r.enabled).put("seconds", r.enterSeconds)
            .put("min_confidence", r.minConfidence)) }
        root.put("rules", rr)
        val ee = JSONArray()
        events.forEach { e -> ee.put(JSONObject()
            .put("at_ms", e.atMs).put("room", e.room).put("detail", e.detail)) }
        root.put("events", ee)
        return root
    }

    private fun load() {
        if (!target.exists()) return
        try {
            val root = JSONObject(target.readText(Charsets.UTF_8))
            if (root.optString("schema") != "rflab-spatial-home-v1") return
            simulationEnabled = root.optBoolean("simulation_enabled", true)
            val r = root.optJSONArray("rooms") ?: JSONArray()
            for (i in 0 until r.length()) {
                val j = r.optJSONObject(i) ?: continue
                rooms.add(HomeRoom(j.getString("id"), j.getString("name"),
                    j.getDouble("x").toFloat(), j.getDouble("z").toFloat(),
                    j.getDouble("w").toFloat(), j.getDouble("d").toFloat()))
            }
            val a = root.optJSONArray("anchors") ?: JSONArray()
            for (i in 0 until a.length()) {
                val j = a.optJSONObject(i) ?: continue
                val anchor = HomeAnchor(j.getString("id"), j.getString("room_id"),
                    j.getString("label"), j.getDouble("x").toFloat(),
                    j.getDouble("z").toFloat())
                val scans = j.optJSONArray("sweeps") ?: JSONArray()
                for (k in 0 until scans.length()) {
                    val scan = scans.optJSONObject(k) ?: continue
                    val values = linkedMapOf<String, Int>()
                    val ssids = linkedMapOf<String, String>()
                    val v = scan.optJSONObject("readings") ?: JSONObject()
                    for (key in v.keys()) values[key] = v.optInt(key)
                    val s = scan.optJSONObject("ssids") ?: JSONObject()
                    for (key in s.keys()) ssids[key] = s.optString(key)
                    anchor.sweeps.add(RfSweep(scan.optLong("at_ms"), values, ssids))
                }
                if (rooms.any { it.id == anchor.roomId }) anchors.add(anchor)
            }
            val ds = root.optJSONArray("devices") ?: JSONArray()
            for (i in 0 until ds.length()) {
                val j = ds.optJSONObject(i) ?: continue
                devices.add(HomeDevice(j.getString("id"), j.getString("name"),
                    j.getString("room_id"), j.getDouble("x").toFloat(),
                    j.getDouble("z").toFloat(), j.optString("type"),
                    j.optString("action")))
            }
            val ruleList = root.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until ruleList.length()) {
                val j = ruleList.optJSONObject(i) ?: continue
                rules.add(HomeRule(j.getString("id"), j.getString("room_id"),
                    j.getString("action"), j.optBoolean("enabled"),
                    j.optInt("seconds", 8), j.optDouble("min_confidence", .8)))
            }
            val eventsList = root.optJSONArray("events") ?: JSONArray()
            for (i in 0 until eventsList.length()) {
                val j = eventsList.optJSONObject(i) ?: continue
                events.add(HomeEvent(j.optLong("at_ms"), j.optString("room"),
                    j.optString("detail")))
            }
        } catch (_: Exception) {
            // Leave the input intact for diagnostics or manual recovery.
            rooms.clear(); anchors.clear(); devices.clear(); rules.clear(); events.clear()
        }
    }
}
