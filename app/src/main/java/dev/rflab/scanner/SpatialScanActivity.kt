package dev.rflab.scanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Focused scan screen. Single ARCore session -> one persistent local frame.
 * Does not claim a watertight mesh or camera-colored texture.
 */
class SpatialScanActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scan: RoomScanStore
    private lateinit var store: SessionStore
    private lateinit var ar: ArPoseTracker
    private lateinit var cloud: ScanCloudView
    private lateinit var stats: TextView
    private lateinit var guidance: TextView
    private lateinit var start: Button
    private lateinit var finish: Button
    private var active = false
    private var lastMessage = "Kamera auf gut beleuchtete Flächen richten."
    private val cameraRequest = 451
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 900)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(color) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(10, 21, 34)
        window.navigationBarColor = Color.rgb(10, 21, 34)
        scan = RoomScanStore(this)
        store = SessionStore(this)
        ar = ArPoseTracker(this, this, store,
            { message -> runOnUiThread { lastMessage = message; refresh() } },
            { pose, _ -> scan.addPose(pose) },
            { points, _ -> scan.addDepth(points) }
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(12))
            setBackgroundColor(Color.rgb(10, 21, 34))
        }
        setContentView(root)
        root.addView(text("01   /   RAUM IN 3D ERFASSEN", 20f), LinearLayout.LayoutParams(-1, dp(36)))
        root.addView(text("Kamera + ARCore Depth · ohne Root · optische Geometrie",
            11.8f, Color.rgb(163, 192, 213)))
        guidance = text("Bewege das Pixel langsam an Wänden und Möbeln entlang. " +
            "Halte sichtbare, strukturierte Flächen im Bild. Bei Dunkelheit ist 3D-Tracking schwierig.",
            12.5f, Color.rgb(211, 231, 244))
        root.addView(guidance, LinearLayout.LayoutParams(-1, dp(66)))
        val content = ScrollView(this).apply { isFillViewport = true }
        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(stack)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val cap = text("LIVE-KAMERA", 12f, Color.rgb(94, 206, 242))
        stack.addView(cap)
        val cameraContainer = LinearLayout(this).apply {
            setBackgroundColor(Color.rgb(17, 39, 58))
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        stack.addView(cameraContainer, LinearLayout.LayoutParams(-1, dp(235)))
        cameraContainer.addView(ar, LinearLayout.LayoutParams(-1, -1))
        val label = text("3D-PUNKTWOLKE · FARBE NACH HÖHE",
            12f, Color.rgb(94, 206, 242))
        val spacer = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(11) }
        stack.addView(label, spacer)
        cloud = ScanCloudView(this).apply { store = scan }
        stack.addView(cloud, LinearLayout.LayoutParams(-1, dp(300)))
        stats = text("", 12f, Color.WHITE)
        stack.addView(stats, LinearLayout.LayoutParams(-1, dp(68)))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        start = Button(this).apply {
            isAllCaps = false
            text = "3D-Scan starten"
            setOnClickListener {
                if (active) stopCapture(false)
                else requestStart()
            }
        }
        finish = Button(this).apply {
            isAllCaps = false
            text = "Scan sichern"
            setOnClickListener { finishAndReturn() }
        }
        controls.addView(start, LinearLayout.LayoutParams(0, dp(55), 1f))
        controls.addView(finish, LinearLayout.LayoutParams(0, dp(55), 1f))
        root.addView(controls)
        root.addView(text("3D-Ansicht ziehen = drehen · Zwei Finger = zoomen. " +
            "Die Farben sind eine Höhenkodierung, keine fotografischen Oberflächenfarben. " +
            "Ein Raum wird erst im nächsten Schritt im Scan markiert.",
            10.5f, Color.rgb(155, 181, 204)))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (active) stopCapture(true)
        super.onPause()
    }

    private fun refresh() {
        if (!::stats.isInitialized) return
        val poses = scan.poseCount()
        val points = scan.pointCount()
        val sampled = scan.sampleFrames
        stats.text = String.format(Locale.GERMAN,
            "ARCore-Posen: %d · 3D-Punkte: %d · Depth-Frames: %d\n%s",
            poses, points, sampled, lastMessage)
        cloud.invalidate()
        start.text = if (active) "Scan pausieren" else "3D-Scan starten"
        finish.isEnabled = points >= 50 && poses >= 3
    }

    private fun requestStart() {
        if (active) return
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraRequest)
            return
        }
        if (scan.hasScan()) {
            val hasRooms = SpatialHomeStore(this).rooms.isNotEmpty()
            if (hasRooms) {
                AlertDialog.Builder(this).setTitle("Alte Raumzuordnung erhalten")
                    .setMessage("Die gespeicherten Räume liegen in einem anderen Koordinatensystem. " +
                        "Ein neuer ARCore-Scan lässt sich ohne Registrierung nicht automatisch " +
                        "auf diese Wohnungskarte übertragen. Der alte Scan bleibt erhalten.")
                    .setPositiveButton("OK", null).show()
                return
            }
            AlertDialog.Builder(this).setTitle("Vorherigen 3D-Scan ersetzen?")
                .setMessage("Der bisherige optische Scan wird ersetzt. " +
                    "Vorher den Scan bei Bedarf in der App betrachten.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Neuen Scan starten") { _, _ -> startCapture() }.show()
        } else startCapture()
    }

    private fun startCapture() {
        if (active) return
        val time = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date())
        scan.start("ARCore-Scan " + time)
        val file = store.start()
        store.write("spatial_optical_scan", JSONObject()
            .put("format", "ARCore raw depth sparse point cloud")
            .put("coordinate_frame", "single ARCore session only")
            .put("color", "height false color, not RGB texture"))
        // Surface is attached and visible. Start after one UI frame to allow GL init.
        ar.visibility = View.VISIBLE
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            if (ar.start()) {
                active = true
                lastMessage = "Scan läuft. Kamera langsam bewegen."
            } else {
                lastMessage = "ARCore konnte nicht starten; Berechtigungen und ARCore prüfen."
                store.stop()
            }
            refresh()
        }, 140)
    }

    private fun stopCapture(saveIfEnough: Boolean) {
        if (!active && !store.isRecording) return
        active = false
        ar.stop()
        store.write("spatial_optical_scan_end", JSONObject()
            .put("depth_points", scan.pointCount())
            .put("camera_poses", scan.poseCount()))
        store.stop()
        if (saveIfEnough && scan.pointCount() >= 50 && scan.poseCount() >= 3) {
            scan.finish()
            lastMessage = "Scan automatisch gesichert (App-Wechsel)."
        } else lastMessage = "Scan pausiert. Zur Sicherung 'Scan sichern' drücken."
        refresh()
    }

    private fun finishAndReturn() {
        if (active) stopCapture(false)
        if (scan.pointCount() < 50 || scan.poseCount() < 3) {
            Toast.makeText(this, "Noch zu wenig 3D-Geometrie. " +
                "Mindestens 50 Tiefenpunkte und 3 Kameraposen benötigt.",
                Toast.LENGTH_LONG).show()
            return
        }
        if (!scan.finish()) {
            Toast.makeText(this, "Keine gültige 3D-Geometrie erfasst.", Toast.LENGTH_LONG).show()
            return
        }
        setResult(RESULT_OK)
        finish()
    }

    override fun onRequestPermissionsResult(code: Int,
        permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == cameraRequest) {
            if (checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED) requestStart()
            else Toast.makeText(this, "Für den 3D-Scan ist Kamerazugriff nötig.",
                Toast.LENGTH_LONG).show()
        }
    }
}
