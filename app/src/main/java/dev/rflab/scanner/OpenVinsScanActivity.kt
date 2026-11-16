package dev.rflab.scanner

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * RF Lab scan shell around the *one* original native OpenVINS Camera2/IMU
 * activity. The native MSCKF callback is the actual pose source. No ARCore
 * session, second camera, simulated geometry or fake tracking is introduced.
 *
 * Native RGB frames + IMU + timestamped VIO poses are written to sessions/
 * and imported straight into the existing on-phone DA3 offline workflow.
 * A live VIO camera path is stored separately from ARCore's spatial map,
 * since these independent origins have not been registered or calibrated.
 */
class OpenVinsScanActivity : com.openvins.android.MainActivity() {
    private val background = Color.rgb(9, 18, 30)
    private val accent = Color.rgb(95, 222, 209)
    private lateinit var scan: RoomScanStore
    private lateinit var cloud: ScanCloudView
    private lateinit var stateLabel: TextView
    private lateinit var detailLabel: TextView
    private lateinit var startButton: Button
    private lateinit var processButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var recording = false
    private var nativePoseSeen = false
    private var lastUiNs = 0L
    private var lastSavedNs = 0L
    private var latestPosition: FloatArray? = null
    private var latestQuaternion: DoubleArray? = null
    private val oldId = com.openvins.android.R.id.trajectory_view

    private fun dp(px: Int) = (px * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(11).toFloat()
    }
    private fun label(s: String, sp: Float = 12f): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(Color.rgb(205, 229, 235))
            gravity = Gravity.CENTER_VERTICAL
        }
    private fun button(s: String, primary: Boolean = false,
                       action: () -> Unit): Button =
        Button(this).apply {
            text = s
            isAllCaps = false
            textSize = 12f
            setTextColor(if (primary) background else Color.WHITE)
            background = shape(if (primary) accent else Color.rgb(27, 54, 73))
            setOnClickListener { action() }
        }

    override fun renderLegacyTrajectoryOverlay() = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        scan = RoomScanStore(this, "rflab_openvins_scan_v1.json")

        // Preserve OpenVINS's live Camera2ResView and JNI camera frame stream.
        // Reparent its EXISTING view: never open a second camera or IMU source.
        val container = findViewById<ViewGroup>(android.R.id.content)
        val nativeRoot = container.getChildAt(0)
            ?: error("Native OpenVINS camera view missing")
        container.removeView(nativeRoot)
        nativeRoot.findViewById<View>(oldId)?.visibility = View.GONE
        for (id in listOf(com.openvins.android.R.id.toggle_reset,
                com.openvins.android.R.id.toggle_record,
                com.openvins.android.R.id.open_folder)) {
            nativeRoot.findViewById<View>(id)?.visibility = View.GONE
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
            setPadding(dp(8), dp(5), dp(8), dp(7))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(label("RF LAB  /  OPENVINS  /  LIVE", 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(button("Fertig") { stopAndExit() },
            LinearLayout.LayoutParams(dp(88), dp(46)))
        root.addView(toolbar)

        val horizontal = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val viewport = LinearLayout(this).apply {
            orientation = if (horizontal) LinearLayout.HORIZONTAL
                else LinearLayout.VERTICAL
        }
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        val videoParams = if (horizontal)
            LinearLayout.LayoutParams(0, -1, .56f) else
            LinearLayout.LayoutParams(-1, 0, .56f)
        viewport.addView(nativeRoot, videoParams)

        val mapPane = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(13, 28, 43))
        }
        val mapParams = if (horizontal)
            LinearLayout.LayoutParams(0, -1, .44f) else
            LinearLayout.LayoutParams(-1, 0, .44f)
        viewport.addView(mapPane, mapParams)
        cloud = ScanCloudView(this).apply { store = scan }
        mapPane.addView(cloud, FrameLayout.LayoutParams(-1, -1))
        mapPane.addView(label("3D · OPENVINS-KAMERAPFAD", 11f).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = shape(Color.argb(229, 14, 34, 48))
        }, FrameLayout.LayoutParams(-2, dp(34),
            Gravity.TOP or Gravity.START).apply {
            topMargin = dp(9)
            leftMargin = dp(9)
        })

        stateLabel = label("○ Bereit – Tracking noch nicht gestartet", 13f).apply {
            setTextColor(accent)
            setPadding(dp(7), dp(5), dp(7), 0)
        }
        detailLabel = label(
            "Native VIO-Positionen · Kamerabilder und IMU werden lokal aufgenommen. " +
            "Keine ARCore-Registrierung.", 11f).apply {
            maxLines = 2
            setPadding(dp(7), 0, dp(7), dp(5))
        }
        root.addView(stateLabel)
        root.addView(detailLabel)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startButton = button("● Scan starten", true) {
            if (recording) stopCapture() else requestStart()
        }
        controls.addView(startButton,
            LinearLayout.LayoutParams(0, dp(50), 1.0f).apply {
                rightMargin = dp(6)
            })
        processButton = button("KI-Tiefe / Auswertung") {
            importAndAnalyze()
        }
        controls.addView(processButton,
            LinearLayout.LayoutParams(0, dp(50), 1.25f))
        root.addView(controls)

        setContentView(root)
        SystemBarsSafeArea.install(this, root)
        if (scan.poseCount() > 0) {
            stateLabel.text = "Vorheriger VIO-Kamerapfad · " +
                scan.poseCount() + " Positionen"
            detailLabel.text = "Getrennte OpenVINS-Karte · Kein ARCore-Mischscan. " +
                "Für einen neuen Scan werden nur diese VIO-Positionen ersetzt."
        }
    }

    private fun requestStart() {
        if (recording) return
        if (scan.poseCount() > 0) {
            AlertDialog.Builder(this)
                .setTitle("Neuen OpenVINS-Scan aufnehmen?")
                .setMessage("Die gespeicherte OpenVINS-Trajektorie wird ersetzt. " +
                    "Native Aufnahmeserien und deine ARCore-Raumkarte bleiben erhalten. " +
                    "Unterschiedliche VIO-Ursprünge dürfen nicht vermischt werden.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Neuen Scan starten") { _, _ -> startCapture() }
                .show()
        } else startCapture()
    }

    private fun startCapture() {
        if (recording) return
        val name = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date())
        scan.start("OpenVINS MSCKF " + name)
        scan.poseSource = "OpenVINS native MSCKF; own Z-up VIO frame; " +
            "mapped to Y-up for preview; NOT calibrated/registered to ARCore"
        nativePoseSeen = false
        latestPosition = null
        latestQuaternion = null
        lastUiNs = 0L
        lastSavedNs = 0L
        try {
            if (!startEmbeddedScan()) {
                stateLabel.text = "OpenVINS nicht startbereit – Kamera/Ordner prüfen"
                return
            }
            recording = true
            startButton.text = "■ Scan stoppen"
            stateLabel.text = "○ Initialisierung · langsam seitwärts bewegen"
            detailLabel.text = "Kamera + IMU laufen · RGB-Frames und native " +
                "Trajektorie werden synchron aufgezeichnet"
            cloud.invalidate()
        } catch (t: Throwable) {
            stateLabel.text = "OpenVINS-Start fehlgeschlagen: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    override fun onNativePoseReady(position: DoubleArray, quaternion: DoubleArray) {
        if (!recording) return
        val mapped = OpenVinsPoseTransform.toMap(position) ?: return
        if (!OpenVinsPoseTransform.validQuaternion(quaternion)) return
        nativePoseSeen = true
        latestPosition = mapped
        latestQuaternion = quaternion.copyOf()
        scan.addPose(JSONObject().put("state", "TRACKING")
            .put("position_m", JSONArray()
                .put(mapped[0].toDouble())
                .put(mapped[1].toDouble())
                .put(mapped[2].toDouble())))
        cloud.invalidate()
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastUiNs > 250_000_000L) {
            lastUiNs = now
            stateLabel.text = "● Native VIO-Pose · " +
                scan.poseCount() + " Kamerapunkte"
            detailLabel.text = String.format(Locale.GERMANY,
                "X %.2f  Y %.2f  Z %.2f (unkalibriert) · q(wxyz) %.2f / %.2f / %.2f / %.2f",
                mapped[0], mapped[1], mapped[2],
                quaternion[0], quaternion[1], quaternion[2], quaternion[3])
        }
        // Persist periodically so an interrupted scan does not lose all poses.
        if (now - lastSavedNs > 5_000_000_000L) {
            lastSavedNs = now
            scan.save()
        }
    }

    override fun onNativePoseUnavailable() {
        if (!recording) return
        stateLabel.text = if (nativePoseSeen)
            "○ Native Pose nicht verfügbar · Tracking prüfen"
        else "○ VIO initialisiert · Struktur und seitliche Bewegung nötig"
    }

    private fun stopCapture() {
        if (!recording) return
        // Close IMU/RGB/pose CSV writers BEFORE shutting down MSCKF.
        recording = false
        try {
            stopEmbeddedScan()
            scan.save()
            startButton.text = "● Neuer Scan"
            stateLabel.text = if (nativePoseSeen)
                "✓ Aufnahme gesichert · " + scan.poseCount() + " VIO-Positionen"
            else "⚠ Aufnahme beendet · Keine initialisierte OpenVINS-Pose"
            detailLabel.text = "Native RGB-/IMU-/Pose-Daten liegen in RF Lab. " +
                "Mit KI-Tiefe / Auswertung direkt an DA3 übergeben."
        } catch (t: Throwable) {
            stateLabel.text = "Stoppen fehlgeschlagen: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun importAndAnalyze() {
        if (recording) stopCapture()
        processButton.isEnabled = false
        stateLabel.text = "OpenVINS-Aufnahme wird in die RF-Lab-Galerie übernommen …"
        worker.execute {
            try {
                val data = OpenVinsSessionImport(applicationContext)
                    .importLocalLatest()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    processButton.isEnabled = true
                    stateLabel.text = "✓ " + data.photos + " RGB-Fotos · " +
                        data.poses + " VIO-Posen"
                    startActivity(Intent(this, OfflineReconstructionActivity::class.java)
                        .putExtra("photo_session", data.session.name))
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    processButton.isEnabled = true
                    stateLabel.text = "Import fehlgeschlagen: " +
                        (t.message ?: t.javaClass.simpleName)
                    Toast.makeText(this, stateLabel.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun stopAndExit() {
        if (recording) stopCapture()
        setResult(RESULT_OK)
        finish()
    }

    override fun onPause() {
        if (recording) stopCapture()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
