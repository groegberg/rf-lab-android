package dev.rflab.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.HashSet
import kotlin.math.floor
import kotlin.math.max

data class ScanPoint(val x: Float, val y: Float, val z: Float, val confidence: Float)
data class ScanTrack(val x: Float, val y: Float, val z: Float)
data class ScanBounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float,
                      val minZ: Float, val maxZ: Float) {
    val width get() = maxX - minX
    val depth get() = maxZ - minZ
    val height get() = maxY - minY
}

/**
 * One optical ARCore coordinate frame only. Start a NEW scan instead of
 * concatenating different ARCore origins.
 *
 * Visual data: metric, sparse ARCore Raw Depth point cloud (not RGB texture or
 * LiDAR). Downsampled and de-duplicated at 12 cm, capped to control memory.
 */
class RoomScanStore(private val context: Context) {
    private val path = File(context.filesDir, "rflab_room_scan_v1.json")
    private val cells = HashSet<String>()
    private val cloud = mutableListOf<ScanPoint>()
    private val track = mutableListOf<ScanTrack>()
    var createdAtMs: Long = 0L
        private set
    var originX = 0f
        private set
    var originZ = 0f
        private set
    var complete = false
        private set
    var label = "Raumscan"
        private set
    var sampleFrames = 0
        private set

    init { load() }

    @Synchronized fun start(name: String = "Raumscan") {
        cloud.clear(); track.clear(); cells.clear()
        createdAtMs = System.currentTimeMillis()
        originX = 0f; originZ = 0f
        sampleFrames = 0
        complete = false
        label = name
    }

    @Synchronized fun addDepth(points: List<SurveyDepth>) {
        if (complete || cloud.size >= 16_000) return
        sampleFrames++
        for (point in points) {
            if (point.confidence < .45f || !point.x.isFinite() ||
                !point.y.isFinite() || !point.z.isFinite() ||
                max(max(kotlin.math.abs(point.x), kotlin.math.abs(point.y)),
                    kotlin.math.abs(point.z)) > 50f) continue
            val cell = listOf(point.x, point.y, point.z)
                .joinToString(":") { floor(it / .12f).toInt().toString() }
            if (!cells.add(cell)) continue
            cloud.add(ScanPoint(point.x, point.y, point.z, point.confidence))
            if (cloud.size >= 16_000) break
        }
    }

    @Synchronized fun addPose(pose: org.json.JSONObject) {
        if (complete || pose.optString("state") != "TRACKING") return
        val p = pose.optJSONArray("position_m") ?: return
        if (p.length() < 3) return
        val x = p.optDouble(0).toFloat()
        val y = p.optDouble(1).toFloat()
        val z = p.optDouble(2).toFloat()
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val previous = track.lastOrNull()
        if (previous == null || (x - previous.x) * (x - previous.x) +
            (y - previous.y) * (y - previous.y) +
            (z - previous.z) * (z - previous.z) >= .025f * .025f) {
            track.add(ScanTrack(x, y, z))
            if (track.size > 1700) track.removeAt(0)
        }
    }

    @Synchronized fun finish(): Boolean {
        complete = true
        val b = bounds() ?: return false
        // A fixed world->floorplan translation remains unchanged once defined.
        originX = .6f - b.minX
        originZ = .6f - b.minZ
        save()
        return true
    }

    @Synchronized fun clear() {
        cloud.clear(); track.clear(); cells.clear()
        createdAtMs = 0L; complete = false; sampleFrames = 0
        originX = 0f; originZ = 0f
        path.delete()
    }

    @Synchronized fun points() = cloud.toList()
    @Synchronized fun trajectory() = track.toList()
    @Synchronized fun pointCount() = cloud.size
    @Synchronized fun poseCount() = track.size
    @Synchronized fun hasScan() = complete && cloud.size >= 50

    @Synchronized fun bounds(): ScanBounds? {
        if (cloud.isEmpty()) return null
        return ScanBounds(cloud.minOf { it.x }, cloud.maxOf { it.x },
            cloud.minOf { it.y }, cloud.maxOf { it.y },
            cloud.minOf { it.z }, cloud.maxOf { it.z })
    }

    @Synchronized fun save() {
        if (createdAtMs <= 0L) return
        val root = JSONObject()
            .put("schema", "rflab-optical-depth-v1")
            .put("label", label).put("created_at_ms", createdAtMs)
            .put("origin_x", originX.toDouble())
            .put("origin_z", originZ.toDouble())
            .put("completed", complete)
            .put("sample_frames", sampleFrames)
            .put("source", "ARCore raw optical depth + visual-inertial poses")
            .put("rgb_textured", false)
        val samples = JSONArray()
        cloud.forEach { p -> samples.put(JSONArray()
            .put(p.x.toDouble()).put(p.y.toDouble())
            .put(p.z.toDouble()).put(p.confidence.toDouble())) }
        val poses = JSONArray()
        track.forEach { p -> poses.put(JSONArray().put(p.x.toDouble())
            .put(p.y.toDouble()).put(p.z.toDouble())) }
        root.put("points", samples).put("poses", poses)
        val temp = File(path.parentFile, path.name + ".tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (!temp.renameTo(path)) {
            path.writeText(root.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    @Synchronized fun load(): Boolean {
        if (!path.exists()) return false
        return try {
            val root = JSONObject(path.readText(Charsets.UTF_8))
            if (root.optString("schema") != "rflab-optical-depth-v1") return false
            cloud.clear(); track.clear(); cells.clear()
            label = root.optString("label", "Raumscan")
            createdAtMs = root.optLong("created_at_ms")
            originX = root.optDouble("origin_x").toFloat()
            originZ = root.optDouble("origin_z").toFloat()
            complete = root.optBoolean("completed", false)
            sampleFrames = root.optInt("sample_frames")
            val points = root.optJSONArray("points") ?: JSONArray()
            for (i in 0 until minOf(points.length(), 16000)) {
                val row = points.optJSONArray(i) ?: continue
                if (row.length() < 4) continue
                cloud.add(ScanPoint(row.optDouble(0).toFloat(),
                    row.optDouble(1).toFloat(), row.optDouble(2).toFloat(),
                    row.optDouble(3).toFloat()))
            }
            val poses = root.optJSONArray("poses") ?: JSONArray()
            for (i in 0 until minOf(poses.length(), 1700)) {
                val row = poses.optJSONArray(i) ?: continue
                if (row.length() < 3) continue
                track.add(ScanTrack(row.optDouble(0).toFloat(),
                    row.optDouble(1).toFloat(), row.optDouble(2).toFloat()))
            }
            complete && cloud.size >= 50
        } catch (_: Exception) {
            cloud.clear(); track.clear(); cells.clear(); complete = false
            false
        }
    }
}
