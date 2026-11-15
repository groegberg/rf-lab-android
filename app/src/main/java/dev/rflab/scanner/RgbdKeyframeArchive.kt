package dev.rflab.scanner

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * One colour image, paired depth maps, calibration and pose from the SAME ARCore
 * frame. The camera Image is copied before release; JPEG/ZIP work is off the GL
 * thread. This is local-only and intentionally excluded from JSON home backups.
 */
data class RgbdKeyframeData(
    val timestampNs: Long,
    val cameraWorld: FloatArray,
    val depthWidth: Int,
    val depthHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val uvImageCorners: FloatArray,  // p00, p10, p01, p11 in IMAGE_NORMALIZED
    val imageWidth: Int,
    val imageHeight: Int,
    val nv21: ByteArray,
    val fullDepth: ByteArray,
    val rawDepth: ByteArray,
    val rawConfidence: ByteArray
)

data class StoredRgbdKeyframe(
    val timestampNs: Long,
    val cameraWorld: FloatArray,
    val width: Int,
    val height: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val uvImageCorners: FloatArray,
    val imageJpeg: ByteArray,
    val fullDepth: ByteArray,
    val rawDepth: ByteArray,
    val rawConfidence: ByteArray
)

/** 180 bounded local RGB-D keyframes; the viewer can load a subset at a time. */
class RgbdKeyframeArchive(context: Context) {
    private val directory = File(context.filesDir, "rflab_rgbd_keyframes_v1")
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(3), ThreadPoolExecutor.AbortPolicy())
    private val number = AtomicInteger(0)
    @Volatile private var generation = 0
    @Volatile private var accepted = 0
    @Volatile private var rejected = 0
    @Volatile private var storedBytes = 0L

    val savedCount: Int get() = accepted
    val droppedCount: Int get() = rejected
    val pendingCount: Int get() = writer.queue.size + writer.activeCount

    init {
        directory.mkdirs()
        val files = files()
        accepted = files.size
        number.set((files.mapNotNull { it.name.removePrefix("kf_")
            .removeSuffix(".zip").toIntOrNull() }.maxOrNull() ?: -1) + 1)
        storedBytes = files.sumOf { it.length() }
    }

    /** Only call after the user's existing "replace old scan" confirmation. */
    @Synchronized fun clearForNewScan() {
        generation++
        writer.queue.clear()
        files().forEach { it.delete() }
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        accepted = 0
        rejected = 0
        number.set(0)
        storedBytes = 0
    }

    fun files(): List<File> = directory.listFiles()
        ?.filter { it.isFile && it.name.startsWith("kf_") && it.name.endsWith(".zip") }
        ?.sortedBy { it.name } ?: emptyList()

    fun submit(frame: RgbdKeyframeData): Boolean {
        if (frame.depthWidth !in 1..2048 || frame.depthHeight !in 1..2048 ||
            frame.imageWidth !in 1..4096 || frame.imageHeight !in 1..4096 ||
            frame.nv21.size != frame.imageWidth * frame.imageHeight * 3 / 2 ||
            accepted + pendingCount >= MAX_KEYFRAMES ||
            storedBytes >= MAX_ARCHIVE_BYTES) {
            rejected++
            return false
        }
        val generationForFrame = generation
        val index = number.getAndIncrement()
        return try {
            writer.execute {
                if (generationForFrame != generation) return@execute
                val file = File(directory, "kf_%05d.zip".format(index))
                val temp = File(directory, file.name + ".tmp")
                try {
                    ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                        val metadata = JSONObject()
                            .put("schema", SCHEMA)
                            .put("frame_timestamp_ns", frame.timestampNs)
                            .put("depth_width", frame.depthWidth)
                            .put("depth_height", frame.depthHeight)
                            .put("image_width", frame.imageWidth)
                            .put("image_height", frame.imageHeight)
                            .put("fx", frame.fx.toDouble())
                            .put("fy", frame.fy.toDouble())
                            .put("cx", frame.cx.toDouble())
                            .put("cy", frame.cy.toDouble())
                            .put("camera_world", floats(frame.cameraWorld))
                            .put("texture_to_image_corners", floats(frame.uvImageCorners))
                            .put("full_depth_kind", "ARCore interpolated metric depth, millimeters")
                            .put("raw_depth_kind", "ARCore sparse measured depth, millimeters")
                        fun entry(name: String, bytes: ByteArray) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                        entry("meta.json", metadata.toString().toByteArray(Charsets.UTF_8))
                        val jpeg = ByteArrayOutputStream()
                        val ok = YuvImage(frame.nv21, ImageFormat.NV21, frame.imageWidth,
                            frame.imageHeight, null).compressToJpeg(
                            Rect(0, 0, frame.imageWidth, frame.imageHeight), 84, jpeg)
                        if (!ok) error("JPEG conversion failed")
                        entry("color.jpg", jpeg.toByteArray())
                        entry("full_depth16_le.bin", frame.fullDepth)
                        entry("raw_depth16_le.bin", frame.rawDepth)
                        entry("raw_confidence8.bin", frame.rawConfidence)
                    }
                    if (generationForFrame != generation) {
                        temp.delete()
                        return@execute
                    }
                    if (!temp.renameTo(file)) error("Could not finalize RGB-D keyframe")
                    synchronized(this) {
                        accepted++
                        storedBytes += file.length()
                    }
                } catch (_: Exception) {
                    temp.delete()
                    rejected++
                }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            rejected++
            false
        }
    }

    fun shutdown() {
        writer.shutdown()
    }

    companion object {
        const val SCHEMA = "rflab-rgbd-keyframe-v1"
        const val MAX_KEYFRAMES = 180
        const val MAX_ARCHIVE_BYTES = 320L * 1024L * 1024L

        private fun floats(values: FloatArray): JSONArray =
            JSONArray().apply { values.forEach { put(it.toDouble()) } }

        fun read(file: File): StoredRgbdKeyframe? {
            return try {
            if (file.length() > 12_000_000) return null
            ZipFile(file).use { zip ->
                fun bytes(name: String, limit: Int): ByteArray {
                    val e = zip.getEntry(name) ?: error("Missing $name")
                    if (e.size > limit || e.size < 0L) error("Invalid $name")
                    return zip.getInputStream(e).use { input ->
                        val result = input.readBytes()
                        if (result.size > limit) error("Oversized $name")
                        result
                    }
                }
                val m = JSONObject(bytes("meta.json", 8192).toString(Charsets.UTF_8))
                if (m.getString("schema") != SCHEMA) return null
                val w = m.getInt("depth_width")
                val h = m.getInt("depth_height")
                if (w !in 1..2048 || h !in 1..2048) return null
                fun array(name: String, n: Int) = FloatArray(n) {
                    m.getJSONArray(name).getDouble(it).toFloat()
                }
                val full = bytes("full_depth16_le.bin", 8_500_000)
                val raw = bytes("raw_depth16_le.bin", 8_500_000)
                val conf = bytes("raw_confidence8.bin", 4_300_000)
                if (full.size != w * h * 2 || raw.size != w * h * 2 ||
                    conf.size != w * h) return null
                StoredRgbdKeyframe(
                    m.getLong("frame_timestamp_ns"), array("camera_world", 16), w, h,
                    m.getDouble("fx").toFloat(), m.getDouble("fy").toFloat(),
                    m.getDouble("cx").toFloat(), m.getDouble("cy").toFloat(),
                    array("texture_to_image_corners", 8),
                    bytes("color.jpg", 8_500_000), full, raw, conf)
            }
            } catch (_: Exception) { null }
        }
    }
}
