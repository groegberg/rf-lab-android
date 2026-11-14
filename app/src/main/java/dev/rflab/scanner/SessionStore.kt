package dev.rflab.scanner

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SessionStore(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var count = 0
    var currentFile: File? = null
        private set
    val isRecording: Boolean get() = writer != null

    @Synchronized
    fun start(): File {
        if (writer != null) return currentFile!!
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "rflab_${formatter.format(Date())}.jsonl")
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
        currentFile = file
        count = 0
        write("session_start", JSONObject().put("schema", "rflab-jsonl-v1")
            .put("utc_ms", System.currentTimeMillis())
            .put("clock", "elapsedRealtimeNanos")
            .put("note", "WiFi/RTT/IMU metadata only; no CSI/IQ"))
        return file
    }

    @Synchronized
    fun write(type: String, payload: JSONObject) {
        if (writer == null) return
        writer!!.write(JSONObject()
            .put("type", type)
            .put("t_ns", SystemClock.elapsedRealtimeNanos())
            .put("payload", payload).toString())
        writer!!.newLine()
        count += 1
        if (count % 64 == 0) writer!!.flush()
    }

    @Synchronized
    fun flush() { writer?.flush() }

    @Synchronized
    fun stop() {
        if (writer == null) return
        write("session_stop", JSONObject().put("events_so_far", count))
        writer?.flush()
        writer?.close()
        writer = null
    }

    companion object {
        fun floatArray(values: FloatArray): JSONArray {
            val result = JSONArray()
            values.forEach { result.put(if (it.isFinite()) it.toDouble() else JSONObject.NULL) }
            return result
        }
    }
}
