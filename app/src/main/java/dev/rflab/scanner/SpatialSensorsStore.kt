package dev.rflab.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MagneticRoomProfile(
    val roomId: String, val atMs: Long, val xUt: Double, val yUt: Double,
    val zUt: Double, val stdMagnitude: Double, val samples: Int,
    val source: String
)
data class AcousticRoomProfile(
    val roomId: String, val atMs: Long, val sampleRate: Int,
    val source: String, val directDelayMs: Double, val peakEchoDelayMs: Double,
    val peakEchoStrength: Double, val envelope: List<Double>
)

/**
 * Room-level physical fingerprints. Acoustic values are qualitative echoes,
 * NOT accurate metric depth. Sensor coordinate frames are documented.
 */
class SpatialSensorsStore(private val context: Context) {
    private val file = File(context.filesDir, "spatial_sensors_v1.json")
    val magnetic = mutableListOf<MagneticRoomProfile>()
    val acoustic = mutableListOf<AcousticRoomProfile>()
    init { load() }

    @Synchronized fun addMag(profile: MagneticRoomProfile) {
        magnetic.add(profile)
        while (magnetic.size > 300) magnetic.removeAt(0)
        save()
    }
    @Synchronized fun addAudio(profile: AcousticRoomProfile) {
        acoustic.add(profile)
        while (acoustic.size > 300) acoustic.removeAt(0)
        save()
    }
    @Synchronized fun magCount(roomId: String) = magnetic.count { it.roomId == roomId }
    @Synchronized fun audioCount(roomId: String) = acoustic.count { it.roomId == roomId }

    @Synchronized fun save() {
        val root = JSONObject().put("schema", "rflab-room-sensor-profiles-v1")
        val magRows = JSONArray()
        for (p in magnetic) magRows.put(JSONObject()
            .put("room_id", p.roomId).put("at_ms", p.atMs)
            .put("x_ut", p.xUt).put("y_ut", p.yUt).put("z_ut", p.zUt)
            .put("std_b_ut", p.stdMagnitude).put("samples", p.samples)
            .put("source", p.source))
        val audioRows = JSONArray()
        for (p in acoustic) {
            val values = JSONArray()
            p.envelope.forEach { values.put(it) }
            audioRows.put(JSONObject()
                .put("room_id", p.roomId).put("at_ms", p.atMs)
                .put("sample_rate", p.sampleRate).put("source", p.source)
                .put("direct_delay_ms", p.directDelayMs)
                .put("echo_delay_ms", p.peakEchoDelayMs)
                .put("echo_strength", p.peakEchoStrength)
                .put("envelope", values))
        }
        root.put("magnetic", magRows).put("acoustic", audioRows)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }
    @Synchronized fun load() {
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optString("schema") != "rflab-room-sensor-profiles-v1") return
            magnetic.clear()
            val a = json.optJSONArray("magnetic") ?: JSONArray()
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                magnetic.add(MagneticRoomProfile(
                    r.optString("room_id"), r.optLong("at_ms"),
                    r.optDouble("x_ut"), r.optDouble("y_ut"), r.optDouble("z_ut"),
                    r.optDouble("std_b_ut"), r.optInt("samples"), r.optString("source")))
            }
            acoustic.clear()
            val b = json.optJSONArray("acoustic") ?: JSONArray()
            for (i in 0 until b.length()) {
                val r = b.optJSONObject(i) ?: continue
                val arr = r.optJSONArray("envelope") ?: JSONArray()
                val envelope = (0 until arr.length()).map { arr.optDouble(it) }
                acoustic.add(AcousticRoomProfile(
                    r.optString("room_id"), r.optLong("at_ms"), r.optInt("sample_rate"),
                    r.optString("source"), r.optDouble("direct_delay_ms"),
                    r.optDouble("echo_delay_ms"), r.optDouble("echo_strength"), envelope))
            }
        } catch (_: Exception) {
            magnetic.clear(); acoustic.clear()
        }
    }
}
