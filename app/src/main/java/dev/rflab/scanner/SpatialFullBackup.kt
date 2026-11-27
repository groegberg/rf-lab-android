package dev.rflab.scanner

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * One portable bundle of the *derived* Spatial Home files.
 * This does not silently expose or bundle raw camera/video/microphone records
 * or the old JSONL experiment archive. Those are exported independently.
 */
object SpatialFullBackup {
    private val names = mapOf(
        "home" to Pair("spatial_home_v1.json", "rflab-spatial-home-v1"),
        "scan" to Pair("rflab_room_scan_v1.json", "rflab-optical-depth-v1"),
        "sensors" to Pair("spatial_sensors_v1.json", "rflab-room-sensor-profiles-v1")
    )
    fun export(context: Context): String {
        SpatialHomeStore(context).save()
        val result = JSONObject()
            .put("schema", "rflab-spatial-full-backup-v1")
            .put("exported_at_ms", System.currentTimeMillis())
            .put("disclaimer", "User-calibrated map; experimental optical RGB-D; derived sensor fingerprints")
        val records = JSONObject()
        for ((key, spec) in names) {
            val file = File(context.filesDir, spec.first)
            if (!file.exists()) continue
            val source = JSONObject(file.readText(Charsets.UTF_8))
            if (source.optString("schema") != spec.second) error("Unexpected " + key + " schema")
            records.put(key, source)
        }
        result.put("records", records)
        return result.toString()
    }

    fun import(context: Context, json: String): Boolean {
        if (json.length > 18_000_000) return false
        val root = try { JSONObject(json) } catch (_: Exception) { return false }
        if (root.optString("schema") != "rflab-spatial-full-backup-v1") return false
        val entries = root.optJSONObject("records") ?: return false
        if (!entries.has("home")) return false
        val newValues = linkedMapOf<String, String?>()
        for ((key, spec) in names) {
            val entry = entries.optJSONObject(key)
            if (entry == null) {
                newValues[spec.first] = null // absent optional profiles clear on restore
                if (key == "home") return false
                continue
            }
            if (entry.optString("schema") != spec.second) return false
            if (key == "home" && (entry.optJSONArray("rooms") == null ||
                    entry.optJSONArray("anchors") == null ||
                    entry.optJSONArray("devices") == null)) return false
            if (key == "scan" && (entry.optJSONArray("points")?.length() ?: 0) > 80_000)
                return false
            if (key == "sensors" && (entry.optJSONArray("acoustic")?.length() ?: 0) > 300)
                return false
            newValues[spec.first] = entry.toString()
        }
        val original = names.values.associate { spec ->
            val file = File(context.filesDir, spec.first)
            spec.first to if (file.exists()) file.readText(Charsets.UTF_8) else null
        }
        return try {
            for ((name, content) in newValues) {
                val file = File(context.filesDir, name)
                if (content == null) file.delete()
                else {
                    val tmp = File(file.parentFile, file.name + ".import-tmp")
                    tmp.writeText(content, Charsets.UTF_8)
                    if (!tmp.renameTo(file)) {
                        file.writeText(content, Charsets.UTF_8)
                        tmp.delete()
                    }
                }
            }
            val home = SpatialHomeStore(context)
            val expectedRooms = entries.getJSONObject("home").getJSONArray("rooms").length()
            val expectedAnchors = entries.getJSONObject("home").getJSONArray("anchors").length()
            if (home.rooms.size != expectedRooms || home.anchors.size != expectedAnchors)
                error("Invalid home model")
            if (entries.has("scan") && !RoomScanStore(context).hasScan())
                error("Invalid optical scan")
            val sensors = SpatialSensorsStore(context)
            entries.optJSONObject("sensors")?.let { raw ->
                if (sensors.magnetic.size != (raw.optJSONArray("magnetic")?.length() ?: 0))
                    error("Invalid magnetometer profiles")
                if (sensors.acoustic.size != (raw.optJSONArray("acoustic")?.length() ?: 0))
                    error("Invalid acoustic profiles")
            }
            true
        } catch (_: Exception) {
            for ((name, content) in original) {
                val file = File(context.filesDir, name)
                if (content == null) file.delete()
                else file.writeText(content, Charsets.UTF_8)
            }
            false
        }
    }
}
