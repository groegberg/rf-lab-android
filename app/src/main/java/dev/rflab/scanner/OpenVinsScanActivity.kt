package dev.rflab.scanner

import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import java.io.File
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
    private val bgColor = Color.rgb(9, 18, 30)
    private val accent = Color.rgb(95, 222, 209)
    private lateinit var scan: RoomScanStore
    private lateinit var sessionEvents: SessionStore
    private lateinit var cloud: ScanCloudView
    private lateinit var stateLabel: TextView
    private lateinit var detailLabel: TextView
    private lateinit var diagnosticLabel: TextView
    private lateinit var startButton: Button
    private lateinit var processButton: Button
    private val worker = Executors.newSingleThreadExecutor()
    private var recording = false
    private var nativePoseSeen = false
    private var lastUiNs = 0L
    private var lastSavedNs = 0L
    private var lastDiagNs = 0L
    private var currentHealth: VioInitializationHealth? = null
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
            setTextColor(if (primary) bgColor else Color.WHITE)
            background = shape(if (primary) accent else Color.rgb(27, 54, 73))
            setOnClickListener { action() }
        }

    override fun renderLegacyTrajectoryOverlay() = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bgColor
        window.navigationBarColor = bgColor
        scan = RoomScanStore(this, "rflab_openvins_scan_v1.json")
        sessionEvents = SessionStore(this)

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
            setBackgroundColor(bgColor)
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
        diagnosticLabel = label("Native Diagnose: noch keine Messdaten", 10.7f).apply {
            setTextColor(Color.rgb(171, 205, 221))
            setPadding(dp(7), 0, dp(7), dp(6))
            maxLines = 3
        }
        root.addView(stateLabel)
        root.addView(detailLabel)
        root.addView(diagnosticLabel)
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
        root.addView(button("Diagnose anzeigen / kopieren") { showDiagnostics() },
            LinearLayout.LayoutParams(-1, dp(39)).apply {
                topMargin = dp(5)
            })

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
        lastDiagNs = 0L
        currentHealth = null
        diagnosticLabel.text = "IMU-Ruhephase erfassen · 3–5 Sekunden ruhig halten"
        try {
            if (!startEmbeddedScan()) {
                stateLabel.text = "OpenVINS nicht startbereit – Kamera/Ordner prüfen"
                return
            }
            recording = true
            sessionEvents.start()
            sessionEvents.write("spatial_optical_scan", JSONObject()
                .put("format", "OpenVINS native MSCKF RGB+IMU+poses")
                .put("backend", "OpenVINS")
                .put("coordinate_frame", "one separate native Z-up session")
                .put("metric_calibration_validated", false)
                .put("arcore_frame_registered", false))
            startButton.text = "■ Scan stoppen"
            stateLabel.text = "○ Initialisierung · erst 3–5 Sekunden ruhig halten"
            detailLabel.text = "Das Smartphone möglichst ruhig halten, dann langsam " +
                "seitwärts bewegen. Kamera/IMU werden nativ erfasst."
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
        refreshNativeDiagnostics(now)
        if (now - lastUiNs > 250_000_000L) {
            lastUiNs = now
            sessionEvents.write("vio_camera_pose", JSONObject()
                .put("state", "TRACKING")
                .put("position_y_up_unvalidated", JSONArray()
                    .put(mapped[0].toDouble())
                    .put(mapped[1].toDouble())
                    .put(mapped[2].toDouble()))
                .put("quaternion_native_wxyz", JSONArray()
                    .put(quaternion[0]).put(quaternion[1])
                    .put(quaternion[2]).put(quaternion[3]))
                .put("timestamp_type", "RF Lab callback elapsedRealtimeNanos"))
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
        refreshNativeDiagnostics(SystemClock.elapsedRealtimeNanos())
    }

    private fun refreshNativeDiagnostics(nowNs: Long) {
        if (!recording || !::diagnosticLabel.isInitialized) return
        if (nowNs - lastDiagNs < 600_000_000L) return
        lastDiagNs = nowNs
        try {
            val (counters, clocks) = readNativeDiagnostics()
            val health = VioInitializationHealth.read(counters, clocks)
            currentHealth = health
            diagnosticLabel.text = health.compact()
            if (!health.initialized) {
                stateLabel.text = "○ " + health.issue
                if (health.rawCamera >= 10 && health.fedImu >= 100 &&
                    health.processedCamera >= 10 &&
                    health.expiredCamera <= health.processedCamera) {
                    detailLabel.text = "VIO hat Eingabedaten: " +
                        "Handy zunächst 3–5 s ruhig halten; " +
                        "danach langsam bewegen. Keine Pose wird simuliert."
                }
            }
        } catch (error: Throwable) {
            diagnosticLabel.text = "Native Diagnose nicht lesbar: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }

    private fun cameraDiagnostics(): String = try {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it)
                .get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        val props = cameraId?.let { manager.getCameraCharacteristics(it) }
        val clock = props?.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        val clockText = when (clock) {
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                "Camera2 boot-time/realtime"
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN ->
                "Camera2 timestamp source UNKNOWN"
            else -> "Camera2 timestamp source unavailable"
        }
        val intrinsic = props?.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val calibration = File(getExternalFilesDir(null),
            "config/PIXEL_CALIBRATION_WARNING.txt")
        "Android " + Build.VERSION.RELEASE + " / API " +
            Build.VERSION.SDK_INT + " / " + Build.MODEL + "\n" +
            "Camera2 id=" + (cameraId ?: "unknown") + " · " + clockText +
            " · intrinsic_available=" + (intrinsic != null) + "\n" +
            "Camera2 calibration seed file=" + calibration.exists() + "\n" +
            (if(calibration.isFile)calibration.readText().take(750) else
                "No Camera2 calibration seed found: old/unvalidated config may be in use")
    } catch (e: Exception) {
        "Camera2 metadata unavailable: " + (e.message ?: "unknown")
    }

    private fun diagnosticReport(): String {
        val current = try {
            val (counts, times) = readNativeDiagnostics()
            VioInitializationHealth.read(counts, times)
        } catch (_: Throwable) { currentHealth }
        return (current?.report() ?: "Native diagnostics unavailable") +
            "\nRF Lab live VIO poses=" + scan.poseCount() +
            "\nRF Lab nativePoseSeen=" + nativePoseSeen +
            "\n" + cameraDiagnostics()
    }

    private fun showDiagnostics() {
        val body = diagnosticReport()
        AlertDialog.Builder(this)
            .setTitle("OpenVINS · echte Initialisierungsdiagnose")
            .setMessage(body)
            .setNegativeButton("Schließen", null)
            .setPositiveButton("Text kopieren") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("RF Lab OpenVINS diagnostics", body))
                Toast.makeText(this, "Diagnose kopiert", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun stopCapture() {
        if (!recording) return
        // Close IMU/RGB/pose CSV writers BEFORE shutting down MSCKF.
        recording = false
        try {
            stopEmbeddedScan()
            scan.save()
            // Retain the actual native queue/IMU counters even when no pose
            // was initialized. No Logcat/ADB access is needed to investigate.
            val report = diagnosticReport()
            File(filesDir, "rflab_openvins_last_diagnostics.txt")
                .writeText(report)
            sessionEvents.write("vio_diagnostic", JSONObject()
                .put("text", report))
            sessionEvents.write("spatial_optical_scan_end", JSONObject()
                .put("backend", "OpenVINS")
                .put("native_camera_poses", scan.poseCount())
                .put("native_pose_initialized", nativePoseSeen))
            sessionEvents.stop()
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
