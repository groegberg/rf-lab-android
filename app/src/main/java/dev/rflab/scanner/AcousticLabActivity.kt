package dev.rflab.scanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Proof-of-concept acoustic room signature: audible sweep and matched filter.
 * It does not derive metric 3D geometry or promise calibrated ranging.
 */
class AcousticLabActivity : Activity() {
    private lateinit var home: SpatialHomeStore
    private lateinit var profiles: SpatialSensorsStore
    private lateinit var status: TextView
    private lateinit var plot: AcousticProfileView
    private lateinit var measureButton: Button
    private lateinit var roomLabel: TextView
    private var roomId: String? = null
    private var result: EchoAnalysis? = null
    @Volatile private var measuring = false
    private val permissionCode = 619
    private val exportCode = 620

    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    private fun label(s: String, size: Float, color: Int = Color.WHITE) =
        TextView(this).apply { text = s; textSize = size; setTextColor(color) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(11, 23, 38)
        window.navigationBarColor = Color.rgb(11, 23, 38)
        home = SpatialHomeStore(this)
        profiles = SpatialSensorsStore(this)
        roomId = intent.getStringExtra("room_id")
            ?: home.rooms.firstOrNull()?.id
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(17), dp(15), dp(15))
            setBackgroundColor(Color.rgb(11, 23, 38))
        }
        setContentView(root)
        root.addView(label("RF LAB  /  AKUSTIK", 21f))
        root.addView(label("Aktive Echo-Messung · hörbarer Chirp 1,3–6,5 kHz",
            12f, Color.rgb(168, 199, 220)))
        roomLabel = label("", 13f)
        root.addView(roomLabel, LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(Button(this).apply {
            text = "Raum ändern"
            isAllCaps = false
            setOnClickListener { chooseRoom() }
        })
        status = label("Noch keine Messung. Möglichst ruhigen Raum wählen.",
            13f, Color.rgb(223, 237, 247))
        root.addView(status, LinearLayout.LayoutParams(-1, dp(92)))
        plot = AcousticProfileView(this)
        root.addView(plot, LinearLayout.LayoutParams(-1, 0, 1f))
        measureButton = Button(this).apply {
            text = "Chirp senden & Echo aufnehmen"
            isAllCaps = false
            setOnClickListener { requestMeasurement() }
        }
        root.addView(measureButton)
        root.addView(Button(this).apply {
            text = "Echo-Profil diesem Raum zuordnen"
            isAllCaps = false
            setOnClickListener { saveRoomProfile() }
        })
        root.addView(Button(this).apply {
            text = "Echo-Profil CSV exportieren"
            isAllCaps = false
            setOnClickListener { exportCsv() }
        })
        root.addView(label("Sicherheit: Der kurze Ton ist hörbar. " +
            "Lautstärke gering halten, nicht direkt ans Ohr halten. " +
            "Die Android-Audiolatenz verhindert derzeit eine verlässliche " +
            "absolute Entfernungsschätzung. Gemessen wird ein relatives Raum-Echoprofil.",
            10.7f, Color.rgb(164, 190, 214)))
        refreshRoom()
    }

    override fun onPause() {
        measuring = false
        super.onPause()
    }

    private fun refreshRoom() {
        roomLabel.text = "Raum: " + (home.room(roomId)?.name ?: "nicht ausgewählt")
    }
    private fun chooseRoom() {
        if (home.rooms.isEmpty()) {
            Toast.makeText(this, "Zuerst Räume im 3D-Scan definieren.",
                Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Echo für welchen Raum?")
            .setItems(home.rooms.map { it.name }.toTypedArray()) { _, i ->
                roomId = home.rooms[i].id
                refreshRoom()
            }.show()
    }

    private fun requestMeasurement() {
        if (measuring) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionCode)
        } else startMeasurement()
    }
    override fun onRequestPermissionsResult(code: Int,
        permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == permissionCode && grants.isNotEmpty() &&
            grants[0] == PackageManager.PERMISSION_GRANTED) startMeasurement()
    }

    private fun startMeasurement() {
        if (measuring) return
        measuring = true
        measureButton.isEnabled = false
        status.text = "Messung läuft: Mikrofon und Lautsprecher werden einmalig verwendet."
        Thread {
            try {
                val rate = 48_000
                val sound = AcousticSignal.chirp(rate)
                val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val unprocessed = manager.getProperty(
                    AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
                    else MediaRecorder.AudioSource.MIC
                val minBytes = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBytes <= 0) error("Unsupported audio input format")
                val audio = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder()
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(max(minBytes * 2, rate * 2))
                    .build()
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    audio.release()
                    error("AudioRecord unavailable")
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(sound.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                try {
                    if (track.state != AudioTrack.STATE_INITIALIZED)
                        error("Speaker playback unavailable")
                    val written = track.write(sound, 0, sound.size)
                    if (written != sound.size) error("Could not write complete chirp")
                    val recording = ShortArray((rate * 1.25).toInt())
                    audio.startRecording()
                    val playback = Thread {
                        try {
                            Thread.sleep(150L)
                            if (measuring) track.play()
                        } catch (_: Exception) {}
                    }
                    playback.start()
                    var received = 0
                    while (measuring && received < recording.size) {
                        val n = audio.read(recording, received,
                            min(4096, recording.size - received),
                            AudioRecord.READ_BLOCKING)
                        if (n <= 0) error("Microphone read failed (" + n + ")")
                        received += n
                    }
                    playback.join(700)
                    if (!measuring || received < recording.size / 2)
                        error("Recording interrupted")
                    val captured = recording.copyOf(received)
                    val analysis = AcousticSignal.analyze(captured, sound, rate)
                    runOnUiThread {
                        result = analysis
                        plot.profile = analysis.envelope
                        status.text = String.format(Locale.GERMAN,
                            "Echo-Profil: 64 Messbereiche\n" +
                            "Audiolatenz/erste Kopplung: %.1f ms\n" +
                            "Weitere Reflexion: %.1f ms (relativ), Intensität %.2f",
                            analysis.directArrivalMs,
                            analysis.strongestEchoAfterMs,
                            analysis.relativeEchoStrength)
                    }
                } finally {
                    try { audio.stop() } catch (_: Exception) {}
                    audio.release()
                    try { track.stop() } catch (_: Exception) {}
                    track.release()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Audiomessung fehlgeschlagen: " +
                        (e.message ?: e.javaClass.simpleName) +
                        "\nMikrofonfreigabe und Lautsprecher prüfen."
                }
            } finally {
                measuring = false
                runOnUiThread { measureButton.isEnabled = true }
            }
        }.start()
    }

    private fun saveRoomProfile() {
        val r = result
        val id = roomId
        if (r == null || id == null || home.room(id) == null) {
            Toast.makeText(this, "Erst Raum auswählen und Echo messen.",
                Toast.LENGTH_LONG).show()
            return
        }
        profiles.addAudio(AcousticRoomProfile(id, System.currentTimeMillis(), 48_000,
            "audible chirp 1300-6500Hz; Android microphone matched filter",
            r.directArrivalMs, r.strongestEchoAfterMs,
            r.relativeEchoStrength, r.envelope))
        Toast.makeText(this, "Akustisches Raumprofil gespeichert.",
            Toast.LENGTH_LONG).show()
    }

    private fun exportCsv() {
        if (result == null) {
            Toast.makeText(this, "Erst eine Echo-Messung durchführen.",
                Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "rflab_acoustic_profile.csv")
        }, exportCode)
    }
    @Deprecated("Simple export")
    override fun onActivityResult(request: Int, statusCode: Int, data: Intent?) {
        super.onActivityResult(request, statusCode, data)
        if (request != exportCode || statusCode != RESULT_OK) return
        try {
            val uri = data?.data ?: return
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write("relative_time_ms;relative_matched_filter_peak\n")
                result?.envelope?.forEachIndexed { index, value ->
                    it.write(String.format(Locale.ROOT, "%.4f;%.6f\n",
                        (index + .5) * 320.0 / 64.0, value))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "CSV-Export: " + e.message,
                Toast.LENGTH_LONG).show()
        }
    }
}
class AcousticProfileView(context: Context) : View(context) {
    var profile: List<Double> = emptyList()
        set(value) { field = value; invalidate() }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.rgb(18, 36, 52))
        val data = profile
        pen.color = Color.rgb(168, 197, 216)
        pen.textSize = 12f * resources.displayMetrics.density
        if (data.isEmpty()) {
            c.drawText("Echo-Profil erscheint nach der Messung",
                14f, height / 2f, pen)
            return
        }
        val left = 18f
        val top = 38f
        val dx = (width - 32f) / data.size
        val available = max(1f, height - top - 36f)
        data.forEachIndexed { index, amplitude ->
            val x = left + index * dx
            pen.color = Color.rgb(96, 219, 188)
            pen.strokeWidth = max(2f, dx * .80f)
            c.drawLine(x, height - 24f,
                x, height - 24f - (amplitude * available).toFloat(), pen)
        }
        pen.color = Color.rgb(199, 216, 233)
        c.drawText("Relativer Echoverlauf · 0–320 ms", left, 19f, pen)
    }
}
