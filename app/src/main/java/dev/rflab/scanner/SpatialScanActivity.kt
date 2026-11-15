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
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Focused scan screen. Single ARCore session -> one persistent local frame.
 * Experimental colourised voxel preview, NOT a mesh or photogrammetry.
 */
class SpatialScanActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scan: RoomScanStore
    private lateinit var store: SessionStore
    private lateinit var ar: ArPoseTracker
    private lateinit var cloud: ScanCloudView
    private lateinit var archive: RgbdKeyframeArchive
    private lateinit var surfaceButton: Button
    private lateinit var more: Button
    private lateinit var stats: TextView
    private lateinit var guidance: TextView
    private lateinit var start: Button
    private lateinit var finish: Button
    private lateinit var colors: Button
    private var active = false
    private var lastMessage = "Kamera auf gut beleuchtete Flächen richten."
    private val cameraRequest = 451
    private val rgbdExportRequest = 452
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 900)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun text(value: String, size: Float, color: Int = Color.WHITE): TextView =
        TextView(this).apply { text = value; textSize = size; setTextColor(color) }

    private val bg = Color.rgb(9, 17, 29)
    private val accent = Color.rgb(64, 221, 181)

    private fun chip(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun floatingLabel(value: String): TextView = text(value, 11f).apply {
        background = chip(Color.argb(208, 9, 21, 32))
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        scan = RoomScanStore(this)
        store = SessionStore(this)
        archive = RgbdKeyframeArchive(this)
        ar = ArPoseTracker(this, this, store,
            { message -> runOnUiThread { lastMessage = message; refresh() } },
            { pose, _ -> scan.addPose(pose) },
            { points, _ -> scan.addDepth(points) },
            { frame -> archive.submit(frame) }
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        setContentView(root)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), 0, dp(8), 0)
        }
        header.addView(text("RF LAB  /  SCAN", 17f).apply {
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        more = Button(this).apply {
            isAllCaps = false
            text = "⋮"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showMore() }
            contentDescription = "Weitere Scan-Optionen"
        }
        header.addView(more, LinearLayout.LayoutParams(dp(55), dp(52)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(54)))

        // Two always-visible canvases; no ScrollView consumes camera/map space.
        val landscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val viewport = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))

        val cameraPane = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(15, 25, 37))
        }
        viewport.addView(cameraPane, if (landscape)
            LinearLayout.LayoutParams(0, -1, .58f) else
            LinearLayout.LayoutParams(-1, 0, .58f))
        cameraPane.addView(ar, FrameLayout.LayoutParams(-1, -1))
        cameraPane.addView(floatingLabel("LIVE  ·  KAMERA"),
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(12); leftMargin = dp(12)
            })
        stats = floatingLabel("Tracking wird gestartet")
        cameraPane.addView(stats, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(12); rightMargin = dp(12)
        })
        guidance = floatingLabel("Bewege das Smartphone langsam seitwärts.")
        guidance.setTextColor(Color.rgb(209, 235, 237))
        guidance.maxLines = 2
        cameraPane.addView(guidance, FrameLayout.LayoutParams(
            -1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(12); rightMargin = dp(12); bottomMargin = dp(12)
        })

        val mapPane = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 25, 38))
        }
        viewport.addView(mapPane, if (landscape)
            LinearLayout.LayoutParams(0, -1, .42f) else
            LinearLayout.LayoutParams(-1, 0, .42f))
        cloud = ScanCloudView(this).apply { store = scan }
        mapPane.addView(cloud, FrameLayout.LayoutParams(-1, -1))
        mapPane.addView(floatingLabel("3D  ·  KARTE"),
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(12); topMargin = dp(12)
            })
        colors = Button(this).apply {
            isAllCaps = false
            text = "RGB"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = chip(Color.argb(230, 23, 56, 71))
            setOnClickListener {
                cloud.showCameraColors = !cloud.showCameraColors
                text = if (cloud.showCameraColors) "RGB" else "Höhe"
            }
            contentDescription = "Zwischen Kamera- und Höhenfarben wechseln"
        }
        mapPane.addView(colors, FrameLayout.LayoutParams(dp(65), dp(38),
            Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(12); topMargin = dp(10)
        })
        surfaceButton = Button(this).apply {
            isAllCaps = false
            text = "3D-Mesh  ›"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = chip(Color.argb(235, 24, 55, 74))
            setOnClickListener { openSurface() }
        }
        mapPane.addView(surfaceButton, FrameLayout.LayoutParams(dp(117), dp(42),
            Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(12); bottomMargin = dp(12)
        })

        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        start = Button(this).apply {
            isAllCaps = false
            text = "●  Scannen"
            textSize = 15f
            setTextColor(bg)
            background = chip(accent)
            setOnClickListener {
                if (active) stopCapture(false)
                else requestStart()
            }
        }
        finish = Button(this).apply {
            isAllCaps = false
            text = "✓  Fertig"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = chip(Color.rgb(34, 58, 76))
            setOnClickListener { finishAndReturn() }
        }
        footer.addView(start, LinearLayout.LayoutParams(0, dp(53), 1.2f).apply {
            rightMargin = dp(8)
        })
        footer.addView(finish, LinearLayout.LayoutParams(0, dp(53), 1f))
        root.addView(footer, LinearLayout.LayoutParams(-1, dp(72)))
        refresh()
    }

    private fun openSurface() {
        if (active) stopCapture(true)
        startActivity(Intent(this, RgbdSurfaceViewerActivity::class.java))
    }

    private fun exportKeyframes() {
        if (active) stopCapture(true)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "rflab_rgbd_keyframes.zip")
        }
        startActivityForResult(intent, rgbdExportRequest)
    }

    private fun showMore() {
        val popup = PopupMenu(this, more)
        popup.menu.add(0, 1, 0, "3D-Mesh ansehen")
        popup.menu.add(0, 2, 1, "RGB-D-Rohdaten exportieren")
        popup.menu.add(0, 3, 2, "Scan-Diagnose")
        popup.menu.add(0, 4, 3, "5 Tiefenverfahren vergleichen")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openSurface()
                2 -> exportKeyframes()
                4 -> {
                    if (active) stopCapture(true)
                    startActivity(Intent(this, DepthComparisonActivity::class.java))
                }
                3 -> {
                    val latest = ar.latestRgbdStats
                    AlertDialog.Builder(this)
                        .setTitle("Scan-Diagnose")
                        .setMessage(
                            "Kameraposen: " + scan.poseCount() + "\n" +
                            "3D-Punkte: " + scan.pointCount() + "\n" +
                            "RGB-Punkte: " + scan.coloredPointCount() + "\n" +
                            "Raw-Punkte: " + scan.rawPointCount() + "\n" +
                            "Raw je Frame: " + (latest?.rawCount ?: 0) + "\n" +
                            "Full je Frame: " + (latest?.fullCount ?: 0) + "\n" +
                            "Keyframes: " + archive.savedCount + "\n" +
                            "Ausgelassen: " + archive.droppedCount + "\n\n" +
                            lastMessage)
                        .setPositiveButton("Schließen", null).show()
                }
            }
            true
        }
        popup.show()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        if (active) stopCapture(true)
        else if (!scan.complete && scan.pointCount() >= 50 && scan.poseCount() >= 3)
            scan.finish()
        super.onPause()
    }

    private fun refresh() {
        if (!::stats.isInitialized) return
        val points = scan.pointCount()
        val poses = scan.poseCount()
        val current = ar.latestRgbdStats
        val raw = current?.rawCount ?: 0
        stats.text = when {
            active && raw > 80 -> "●  Tracking gut"
            active && raw > 0 -> "●  Wenig Tiefe"
            active -> "○  Tiefe suchen"
            points > 0 -> "✓  Scan vorhanden"
            else -> "○  Bereit"
        }
        stats.setTextColor(if (active && raw > 80) accent else Color.WHITE)
        guidance.text = when {
            active && raw < 25 ->
                "Langsam seitwärts bewegen · Strukturierte Flächen suchen"
            active -> archive.savedCount.toString() + " Ansichten · " +
                points.toString() + " 3D-Punkte"
            points > 0 -> points.toString() + " 3D-Punkte · " +
                archive.savedCount.toString() + " Ansichten"
            else -> "Bewege das Pixel langsam an Wänden entlang."
        }
        cloud.invalidate()
        start.text = when {
            active -> "■  Stoppen"
            points > 0 -> "●  Neuer Scan"
            else -> "●  Scannen"
        }
        finish.isEnabled = points >= 50 && poses >= 3
        surfaceButton.isEnabled = archive.savedCount > 0
    }

    private fun requestStart() {
        if (active) return
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraRequest)
            return
        }
        if (scan.pointCount() > 0) {
            val hasRooms = SpatialHomeStore(this).rooms.isNotEmpty()
            if (hasRooms) {
                AlertDialog.Builder(this).setTitle("Alte Raumzuordnung erhalten")
                    .setMessage("Die gespeicherten Räume liegen in einem anderen Koordinatensystem. " +
                        "Ein neuer ARCore-Scan lässt sich ohne Registrierung nicht automatisch " +
                        "auf diese Wohnungskarte übertragen. Der alte Scan bleibt erhalten.")
                    .setPositiveButton("OK", null).show()
                return
            }
            AlertDialog.Builder(this).setTitle("3D-Scan wirklich neu beginnen?")
                .setMessage("Der bereits gemessene Scan mit " +
                    scan.pointCount() + " optischen Punkten würde verworfen. " +
                    "Eine neue ARCore-Sitzung hat ein eigenes Koordinatensystem; " +
                    "beide dürfen nicht ohne Registrierung kombiniert werden.")
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Alten ersetzen") { _, _ -> startCapture() }.show()
        } else startCapture()
    }

    private fun startCapture() {
        if (active) return
        val time = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date())
        archive.clearForNewScan()
        scan.start("ARCore-Scan " + time)
        val file = store.start()
        store.write("spatial_optical_scan", JSONObject()
            .put("format", "ARCore RGB-D 5cm fused point cloud v0.6")
            .put("coordinate_frame", "single ARCore session only")
            .put("color", "aligned camera RGB samples + height fallback; not a mesh"))
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
        } else lastMessage = "Scan beendet. 'Scan sichern' oder bewusst neuen Scan starten."
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

    @Deprecated("Uses Android Storage Access Framework result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != rgbdExportRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread({
            try {
                val written = contentResolver.openOutputStream(uri)?.use {
                    archive.exportTo(it)
                } ?: error("Export destination unavailable")
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this,
                        "$written RGB-D-Keyframes exportiert.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) Toast.makeText(this,
                        "RGB-D-Export fehlgeschlagen: " + e.javaClass.simpleName,
                        Toast.LENGTH_LONG).show()
                }
            }
        }, "RgbdExport").start()
    }

    override fun onDestroy() {
        if (::archive.isInitialized) archive.shutdown()
        super.onDestroy()
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
