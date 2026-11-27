package dev.rflab.scanner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class MagneticSample(val tNs: Long, val x: Float, val y: Float, val z: Float,
                          val biasX: Float?, val biasY: Float?, val biasZ: Float?)

class MagneticLabActivity : Activity(), SensorEventListener {
    private lateinit var sensors: SensorManager
    private var selected: Sensor? = null
    private var recording = false
    private lateinit var home: SpatialHomeStore
    private lateinit var profiles: SpatialSensorsStore
    private var selectedRoomId: String? = null
    private lateinit var roomLabel: TextView
    private val samples = mutableListOf<MagneticSample>()
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var graph: MagneticGraph
    private var lastCaptureNs = 0L
    private val exportRequest = 307
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(13, 24, 37)
        window.navigationBarColor = Color.rgb(13, 24, 37)
        home = SpatialHomeStore(this)
        profiles = SpatialSensorsStore(this)
        selectedRoomId = intent.getStringExtra("room_id") ?: home.rooms.firstOrNull()?.id
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        selected = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(24), dp(17), dp(12))
            setBackgroundColor(Color.rgb(13, 24, 37))
        }
        setContentView(root)
        SystemBarsSafeArea.install(this, root)
        fun text(value: String, size: Float, color: Int = Color.WHITE): TextView =
            TextView(this).apply { this.text = value; textSize = size; setTextColor(color) }
        root.addView(text("RF LAB / MAGNETIC LAB", 20f))
        roomLabel = text("Raum: " + (home.room(selectedRoomId)?.name ?: "nicht ausgewählt"), 12.4f)
        root.addView(roomLabel)
        root.addView(Button(this).apply {
            text = "Raum auswählen"
            isAllCaps = false
            setOnClickListener { selectRoom() }
        })
        root.addView(text("3-Achsen-Magnetfeld in Mikrotesla · echte Sensorwerte",
            12f, Color.rgb(151, 190, 211)))
        status = text("Initialisierung …", 13f)
        root.addView(status, LinearLayout.LayoutParams(-1, dp(115)))
        graph = MagneticGraph(this)
        root.addView(graph, LinearLayout.LayoutParams(-1, 0, 1f))
        button = Button(this).apply {
            text = "Aufnahme starten"
            isAllCaps = false
            setOnClickListener {
                recording = !recording
                if (recording) {
                    samples.clear()
                    lastCaptureNs = 0L
                }
                text = if (recording) "Aufnahme beenden" else "Neue Aufnahme starten"
                updateStatus(null)
            }
        }
        root.addView(button)
        root.addView(Button(this).apply {
            text = "Magnetfeldprofil im Raum speichern"
            isAllCaps = false
            setOnClickListener { saveRoomProfile() }
        })
        root.addView(Button(this).apply {
            text = "CSV exportieren"
            isAllCaps = false
            setOnClickListener { exportCsv() }
        })
        root.addView(text("Bei Bewegung ändern sich die Komponenten auch durch die " +
            "Geräteorientierung. Dies ist kein automatischer Metalldetektor und noch keine 3D-Karte. " +
            "Im Freien und fern von Magneten zuerst eine Referenzmessung aufnehmen.",
            11.5f, Color.rgb(151, 190, 211)))
    }
    override fun onResume() {
        super.onResume()
        selected?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: updateStatus(null)
    }
    override fun onPause() {
        sensors.unregisterListener(this)
        recording = false
        if (::button.isInitialized) button.text = "Aufnahme starten"
        super.onPause()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor != selected || event.values.size < 3) return
        val v = event.values
        val row = MagneticSample(SystemClock.elapsedRealtimeNanos(),
            v[0], v[1], v[2],
            if (v.size >= 6) v[3] else null,
            if (v.size >= 6) v[4] else null,
            if (v.size >= 6) v[5] else null)
        if (recording && row.tNs - lastCaptureNs > 80_000_000L) {
            samples.add(row)
            lastCaptureNs = row.tNs
            if (samples.size > 10000) {
                recording = false
                button.text = "Aufnahme starten"
            }
        }
        graph.push(sqrt(row.x*row.x + row.y*row.y + row.z*row.z))
        updateStatus(row)
    }
    private fun updateStatus(s: MagneticSample?) {
        if (!::status.isInitialized) return
        if (selected == null) {
            status.text = "Kein Magnetometer vorhanden."
            return
        }
        if (s == null) {
            status.text = "Sensor: " + selected!!.name + "\n" +
                samples.size + " gespeicherte Werte."
            return
        }
        val magnitude = sqrt(s.x*s.x + s.y*s.y + s.z*s.z)
        status.text = String.format(Locale.GERMAN,
            "X = %.2f µT  ·  Y = %.2f µT  ·  Z = %.2f µT\n" +
            "Betrag |B| = %.2f µT\n%s · %d Werte%s",
            s.x,s.y,s.z,magnitude,
            if (recording) "AUFNAHME" else "Live-Vorschau",samples.size,
            if (s.biasX != null) " · Rohsensor + Bias" else " · kalibriert")
    }

    private fun selectRoom() {
        if (home.rooms.isEmpty()) {
            android.widget.Toast.makeText(this, "Bitte zuerst Räume im Scan definieren.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("Magnetfeld zu welchem Raum?")
            .setItems(home.rooms.map { it.name }.toTypedArray()) { _, index ->
                selectedRoomId = home.rooms[index].id
                roomLabel.text = "Raum: " + home.rooms[index].name
            }.show()
    }

    private fun saveRoomProfile() {
        val id = selectedRoomId
        if (id == null || home.room(id) == null || samples.size < 15) {
            android.widget.Toast.makeText(this,
                "Zuerst Raum wählen und mindestens 15 Messwerte aufnehmen.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        recording = false
        button.text = "Aufnahme starten"
        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
        val x = median(samples.map { it.x.toDouble() })
        val y = median(samples.map { it.y.toDouble() })
        val z = median(samples.map { it.z.toDouble() })
        val magnitudes = samples.map {
            sqrt((it.x * it.x + it.y * it.y + it.z * it.z).toDouble())
        }
        val avg = magnitudes.average()
        val variance = magnitudes.sumOf { (it - avg) * (it - avg) } / magnitudes.size
        profiles.addMag(MagneticRoomProfile(id, System.currentTimeMillis(),
            x, y, z, kotlin.math.sqrt(variance), samples.size,
            if (selected?.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
                "Android uncalibrated XYZ in device frame"
            else "Android calibrated XYZ in device frame"))
        android.widget.Toast.makeText(this,
            "Magnetfeldprofil für " + home.room(id)?.name + " gespeichert.",
            android.widget.Toast.LENGTH_LONG).show()
    }

    private fun exportCsv() {
        if (samples.isEmpty()) {
            android.widget.Toast.makeText(this, "Noch keine Messwerte aufgenommen.",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        recording = false
        button.text = "Aufnahme starten"
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "rflab_magnetometer.csv")
        }, exportRequest)
    }
    @Deprecated("Activity result API")
    override fun onActivityResult(request: Int, code: Int, data: Intent?) {
        super.onActivityResult(request, code, data)
        if (request != exportRequest || code != RESULT_OK) return
        try {
            val uri = data?.data ?: return
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { dst ->
                dst.write("elapsed_t_ns;x_uT;y_uT;z_uT;magnitude_uT;bias_x_uT;bias_y_uT;bias_z_uT\n")
                for (s in samples) {
                    val b = sqrt(s.x*s.x + s.y*s.y + s.z*s.z)
                    dst.write(String.format(Locale.ROOT,
                        "%d;%.5f;%.5f;%.5f;%.5f;%s;%s;%s\n",
                        s.tNs, s.x, s.y, s.z, b,
                        s.biasX?.toString() ?: "", s.biasY?.toString() ?: "",
                        s.biasZ?.toString() ?: ""))
                }
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "CSV: " + e.message,
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
class MagneticGraph(context: Context) : View(context) {
    private val values = ArrayDeque<Float>()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(110, 226, 185); strokeWidth = 2.5f
    }
    fun push(v: Float) {
        values.addLast(v)
        while (values.size > 200) values.removeFirst()
        invalidate()
    }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(22, 39, 56))
        if (values.size < 2) return
        val vals = values.toList()
        val minV = vals.minOrNull() ?: 0f
        val maxV = vals.maxOrNull() ?: 1f
        val range = max(1f, maxV - minV)
        val left = 12f
        val right = width - 12f
        val top = 25f
        val bottom = height - 28f
        pen.style = Paint.Style.STROKE
        for (i in 1 until vals.size) {
            val x1 = left + (i-1)* (right-left)/(vals.size-1)
            val x2 = left + i * (right-left)/(vals.size-1)
            val y1 = bottom-(vals[i-1]-minV)/range*(bottom-top)
            val y2 = bottom-(vals[i]-minV)/range*(bottom-top)
            c.drawLine(x1,y1,x2,y2,pen)
        }
        pen.style = Paint.Style.FILL
        pen.textSize = resources.displayMetrics.density * 12f
        pen.color = Color.rgb(185, 216, 237)
        c.drawText(String.format(Locale.GERMAN, "|B| %.1f–%.1f µT", minV, maxV),
            left, 17f, pen)
        pen.color = Color.rgb(110, 226, 185)
    }
}
