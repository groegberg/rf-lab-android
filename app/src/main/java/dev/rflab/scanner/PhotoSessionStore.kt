package dev.rflab.scanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Immutable calibrated Camera2 photo datasets. Photo sessions are independent
 * of the ARCore reconstruction coordinate frame; no artificial poses are
 * assigned. Original JPEGs and metadata remain fully local/private.
 */
internal class PhotoSessionStore(context: Context) {
    private val root = File(context.filesDir, "rflab_photo_sessions_v1")
    private val serial = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    init { root.mkdirs() }

    data class Frame(val jpeg: File, val metadata: File, val json: JSONObject)
    fun sessions(): List<File> = root.listFiles()
        ?.filter { it.isDirectory && it.name.startsWith("session_") }
        ?.sortedByDescending { it.name } ?: emptyList()

    fun newSession(): File {
        val dir = File(root, "session_" + serial.format(Date()))
        check(dir.mkdirs()) { "Unable to create photo session" }
        JSONObject().put("schema", "rflab-camera2-session-v1")
            .put("created_unix_ms", System.currentTimeMillis())
            .put("source", "Camera2 rear camera + Android IMU")
            .put("pose_source", "NONE; independent SLAM is pending")
            .put("geometry_accuracy", "Unvalidated, no metric tracking yet")
            .let { File(dir,"session.json").writeText(it.toString(2)) }
        return dir
    }

    fun frames(session: File): List<Frame> {
        if (!allowed(session)) return emptyList()
        return session.listFiles()
            ?.filter { it.name.startsWith("img_") && it.name.endsWith(".json") }
            ?.sortedBy { it.name }?.mapNotNull { jsonFile ->
                try {
                    val photo = File(session,jsonFile.name.removeSuffix(".json")+".jpg")
                    if (!photo.isFile) null else
                        Frame(photo,jsonFile,JSONObject(jsonFile.readText()))
                } catch (_: Exception) { null }
            } ?: emptyList()
    }

    private fun allowed(session: File): Boolean =
        session.parentFile?.canonicalFile == root.canonicalFile &&
            session.isDirectory && session.name.startsWith("session_")

    /** Called from a dedicated writer executor (not from Camera2 callbacks). */
    fun save(session: File, jpeg: ByteArray, metadata: JSONObject): Frame {
        require(allowed(session))
        require(jpeg.size in 20_000..15_000_000) { "Bad JPEG length" }
        val existing = frames(session)
        require(existing.size < 240) { "Session photo limit reached" }
        val count = (existing.mapNotNull {
            it.jpeg.name.removePrefix("img_").removeSuffix(".jpg").toIntOrNull()
        }.maxOrNull() ?: -1) + 1
        require(count < 10_000) { "Session photo index limit" }
        val total = session.listFiles()?.sumOf { it.length() } ?: 0L
        require(total + jpeg.size <= 550L * 1024L * 1024L) { "Session storage limit" }
        val name = "img_%04d".format(Locale.US,count)
        val photo = File(session,"$name.jpg")
        val jsonFile = File(session,"$name.json")
        require(!photo.exists() && !jsonFile.exists()) { "Photo index already exists" }
        val tempPhoto = File(session,"$name.jpg.part")
        val tempJson = File(session,"$name.json.part")
        try {
            metadata.put("schema","rflab-camera2-frame-v1")
            metadata.put("file","$name.jpg")
            metadata.put("photo_index",count)
            metadata.put("pose_source","NONE")
            metadata.put("image_source","Camera2 native JPEG")
            tempPhoto.writeBytes(jpeg)
            tempJson.writeText(metadata.toString(2))
            check(tempPhoto.renameTo(photo)) { "Could not save JPEG" }
            check(tempJson.renameTo(jsonFile)) { "Could not save capture metadata" }
            return Frame(photo,jsonFile,metadata)
        } finally {
            tempPhoto.delete()
            tempJson.delete()
        }
    }

    fun delete(frame: Frame): Boolean {
        if (!allowed(frame.jpeg.parentFile ?: return false) ||
            frame.jpeg.parentFile != frame.metadata.parentFile) return false
        return frame.jpeg.delete() && frame.metadata.delete()
    }

    /** User-initiated SAF ZIP; no automatic uploads. */
    fun export(session: File, output: OutputStream): Int {
        require(allowed(session))
        val selected = frames(session)
        ZipOutputStream(output.buffered()).use { zip ->
            fun entry(file: File) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().buffered().use { it.copyTo(zip,64*1024) }
                zip.closeEntry()
            }
            entry(File(session,"session.json"))
            selected.forEach { frame ->
                entry(frame.jpeg)
                entry(frame.metadata)
            }
        }
        return selected.size
    }

    companion object {
        fun floats(array: FloatArray?): JSONArray? =
            array?.let { JSONArray().apply { it.forEach { n -> put(n.toDouble()) } } }
        fun doubles(array: DoubleArray?): JSONArray? =
            array?.let { JSONArray().apply { it.forEach { n -> put(n) } } }
    }
}
