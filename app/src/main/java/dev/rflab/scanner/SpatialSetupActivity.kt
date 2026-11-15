package dev.rflab.scanner

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Beginner workflow: 3D scan -> rooms -> sensors -> devices -> automations.
 * Never mixes independent ARCore origins or silently overwrites v0.4 home data.
 */
class SpatialSetupActivity : Activity() {
    private val dark = Color.rgb(11, 22, 35)
    private val surface = Color.rgb(24, 42, 61)
    private val surface2 = Color.rgb(35, 59, 77)
    private val ink = Color.rgb(232, 243, 250)
    private val muted = Color.rgb(158, 186, 209)
    private val accent = Color.rgb(104, 224, 230)
    private lateinit var home: SpatialHomeStore
    private lateinit var scan: RoomScanStore
    private lateinit var body: LinearLayout
    private lateinit var footer: LinearLayout
    private lateinit var stepBar: LinearLayout
    private var stage = 0
    private val titles = listOf("3D-SCAN", "RÄUME", "MESSEN", "GERÄTE", "AUTOMATION")
    private var firstCorner: Pair<Float, Float>? = null
    private val scanRequest = 611
    private val backupExportRequest = 612
    private val backupImportRequest = 613
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun label(value: String, size: Float = 13f, color: Int = ink,
                      bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1.06f)
        }
    private fun bg(c: Int, r: Int = 14) = GradientDrawable().apply {
        setColor(c); cornerRadius = dp(r).toFloat()
    }
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0,
                    height: Int = -2) {
        parent.addView(child, LinearLayout.LayoutParams(-1, height).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        })
    }
    private fun card(title: String, hint: String = ""): LinearLayout = col().apply {
        background = bg(surface)
        setPadding(dp(15), dp(14), dp(15), dp(15))
        add(this, label(title, 14f, ink, true))
        if (hint.isNotBlank()) add(this, label(hint, 11.9f, muted), 7)
    }
    private fun button(title: String, primary: Boolean = false, clicked: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 12.8f
            setTextColor(if (primary) dark else ink)
            background = bg(if (primary) accent else surface2, 10)
            minHeight = dp(46)
            setOnClickListener { clicked() }
        }
    private fun row(vararg children: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        children.forEach { child ->
            addView(child, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(5)
            })
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        home = SpatialHomeStore(this)
        scan = RoomScanStore(this)
        stage = getPreferences(MODE_PRIVATE).getInt("setup_stage", 0).coerceIn(0, 4)
        val root = col().apply {
            setBackgroundColor(dark)
            setPadding(dp(12), dp(12), dp(12), dp(7))
        }
        setContentView(root)
        SystemBarsSafeArea.install(this, root)
        add(root, label("RF LAB  /  SPATIAL HOME", 20f, ink, true), bottom = 3)
        // Compact header: spatial map and scan get the screen, not explanations.
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        stepBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroller.addView(stepBar)
        add(root, scroller, bottom = 9)
        val sc = ScrollView(this).apply { isFillViewport = true }
        body = col().apply { setPadding(dp(2), dp(3), dp(2), dp(13)) }
        sc.addView(body)
        root.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        add(root, footer, top = 6)
        render()
    }
    override fun onResume() {
        super.onResume()
        if (::body.isInitialized) {
            home = SpatialHomeStore(this)
            scan = RoomScanStore(this)
            render()
        }
    }
    private fun navigate(next: Int) {
        stage = next.coerceIn(0, 4)
        firstCorner = null
        getPreferences(MODE_PRIVATE).edit().putInt("setup_stage", stage).apply()
        render()
    }
    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        footer.removeAllViews()
        stepBar.removeAllViews()
        titles.forEachIndexed { i, name ->
            stepBar.addView(button((i + 1).toString() + " " + name, stage == i) {
                navigate(i)
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(5) })
        }
        add(body, label((stage + 1).toString() + " / 5", 11.6f, accent, true), bottom = 4)
        when (stage) {
            0 -> scanStep()
            1 -> roomsStep()
            2 -> sensingStep()
            3 -> devicesStep()
            else -> automationStep()
        }
        footer.addView(button("Zurück") {
            if (stage > 0) navigate(stage - 1) else openHome(0)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        footer.addView(button(if (stage == 4) "Dashboard" else "Weiter", true) {
            when {
                stage == 4 -> openHome(0)
                stage == 0 && !scan.hasScan() && home.rooms.isEmpty() ->
                    toast("Zuerst einen 3D-Scan erstellen oder vorhandene Räume nutzen.")
                stage == 1 && home.rooms.isEmpty() ->
                    toast("Bitte mindestens einen Raum im Scan markieren.")
                else -> navigate(stage + 1)
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun scanView(top: Boolean, height: Int,
                         onCorner: ((Float, Float) -> Unit)? = null) {
        val view = ScanCloudView(this).apply {
            store = scan
            topDown = top
            rooms = home.rooms.toList()
            selectCorners = onCorner != null
            corner1 = firstCorner
            onRoomCorner = onCorner
        }
        add(body, view, 9, 10, dp(height))
    }

    private fun scanStep() {
        add(body, label("Deine Wohnung in 3D", 22f, ink, true), bottom = 8)
        add(body, button("●  Fotos aufnehmen · Camera2", true) {
            startActivity(Intent(this, PhotoCaptureActivity::class.java))
        }, bottom = 8)
        add(body, row(
            button("Foto-Galerie / KI-Tiefe") {
                startActivity(Intent(this, PhotoGalleryActivity::class.java))
            },
            button("ARCore-Scan (alt)") {
                startActivityForResult(Intent(this, SpatialScanActivity::class.java),
                    scanRequest)
            }
        ), bottom = 8)
        val frames = RgbdKeyframeArchive(this).let {
            val count = it.files().size
            it.shutdown()
            count
        }
        if (scan.pointCount() > 0) {
            val previewDp = (resources.displayMetrics.heightPixels /
                resources.displayMetrics.density * .48f).toInt().coerceIn(300, 650)
            scanView(false, previewDp)
            add(body, label(scan.pointCount().toString() + " Punkte · " +
                scan.poseCount().toString() + " Kamerapositionen",
                12f, muted), bottom = 10)
        } else if (frames == 0) {
            add(body, label("Bewege die Kamera langsam durch den Raum.",
                13f, muted), bottom = 12)
        }
        if (frames > 0) {
            add(body, button("5 Tiefenverfahren vergleichen  ›", true) {
                startActivity(Intent(this, DepthComparisonActivity::class.java))
            }, bottom = 10)
            if (scan.pointCount() > 0) {
                add(body, button("Kameratexturiertes 3D-Mesh  ›") {
                    startActivity(Intent(this, RgbdSurfaceViewerActivity::class.java))
                }, bottom = 9)
            } else {
                add(body, label(frames.toString() +
                    " Fotos gespeichert · KI- und Foto-Stereo-Vergleich verfügbar",
                    12f, muted), bottom = 10)
            }
        }
        add(body, row(
            button("Backup sichern") { exportBackup() },
            button("Backup laden") { importBackupDialog() }
        ), bottom = 11)
        if (home.rooms.isNotEmpty()) {
            add(body, button("Vorhandene Räume verwenden") {
                navigate(2)
            }, bottom = 7)
        }
    }

    private fun roomsStep() {
        add(body, label("Räume im Scan definieren", 22f, ink, true), bottom = 6)
        add(body, label("Tippe in der Draufsicht zuerst eine Raumecke und dann die " +
            "gegenüberliegende Ecke an. Benenne den Raum. Wiederhole für weitere Räume.",
            12.8f, muted), bottom = 9)
        if (scan.hasScan()) {
            val select = card("RAUMBEREICH WÄHLEN",
                if (firstCorner == null) "Ecke 1/2 – erste Ecke antippen"
                else "Ecke 2/2 – gegenüberliegende Ecke antippen")
            add(select, button("Auswahl zurücksetzen") {
                firstCorner = null; render()
            }, 8)
            add(body, select, bottom = 8)
            scanView(true, 395) { x, z -> onCorner(x, z) }
        } else {
            val error = card("3D-SCAN FEHLT",
                "Du kannst auch mit der vorhandenen manuellen Wohnungskarte fortfahren.")
            add(error, button("Zum 3D-Scan") { navigate(0) }, 9)
            add(body, error, bottom = 8)
        }
        val list = card("RÄUME", home.rooms.size.toString() + " Räume definiert")
        home.rooms.forEach { r ->
            add(list, label(r.name + " · " +
                String.format(Locale.GERMAN, "%.1f × %.1f m", r.width, r.depth),
                12.5f), 9)
            add(list, button("Raum löschen") {
                AlertDialog.Builder(this).setTitle(r.name + " löschen?")
                    .setMessage("Alle zugeordneten Messpunkte, Geräte und Regeln " +
                        "werden ebenfalls entfernt.")
                    .setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Entfernen") { _, _ ->
                        home.removeRoom(r.id); render()
                    }.show()
            }, 4)
        }
        add(list, button("Manuellen Grundriss-Editor öffnen") { openHome(1) }, 11)
        add(body, list, bottom = 10)
        add(body, label("Aktuell rechteckige Raumgrenzen; freie Polygone und " +
            "automatische Wandsegmentierung sind noch nicht enthalten.",
            11.5f, muted), 6)
    }

    private fun onCorner(x: Float, z: Float) {
        val prev = firstCorner
        if (prev == null) { firstCorner = x to z; render(); return }
        firstCorner = null
        val left = min(prev.first, x)
        val top = min(prev.second, z)
        val w = abs(prev.first - x)
        val d = abs(prev.second - z)
        if (w < 1f || d < 1f || w > 30f || d > 30f ||
            left < 0f || top < 0f || left + w > 100f || top + d > 100f) {
            toast("Raumfläche muss zwischen 1 und 30 m je Achse liegen.")
            render()
            return
        }
        val name = EditText(this).apply {
            setText("Raum " + (home.rooms.size + 1))
            setSingleLine(true)
        }
        AlertDialog.Builder(this).setTitle("Raum benennen")
            .setMessage(String.format(Locale.GERMAN,
                "Markierte Fläche: %.1f × %.1f Meter", w, d))
            .setView(name).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                if (!home.addRoom(name.text.toString(), left, top, w, d))
                    toast("Ungültige oder mit einem anderen Raum überlappende Fläche.")
                render()
            }.show()
    }

    private fun sensingStep() {
        add(body, label("Physikalische Merkmale einmessen", 21f, ink, true), bottom = 7)
        add(body, label("Der Grundriss ist die gemeinsame Referenz. " +
            "WLAN wird für die spätere Kamera-freie Ortung gebraucht. " +
            "Magnetfeld und Akustik liefern zusätzliche Forschungsdaten.",
            12.8f, muted), bottom = 12)
        val wifi = card("1   WLAN / RF",
            "Mehrere WLAN-Fingerprints pro Raum sammeln. " +
            "Das erlaubt später Raumlokalisierung ohne Kamera.")
        add(wifi, label(home.anchors.count { it.sweeps.isNotEmpty() }
            .toString() + " Referenzpunkte mit WLAN-Daten", 12.3f), 8)
        add(wifi, button("WLAN EINMESSEN", true) { openHome(2) }, 10)
        add(body, wifi, bottom = 11)
        val mag = card("2   MAGNETFELD",
            "Echte X/Y/Z-Werte aufnehmen und als CSV dokumentieren.")
        add(mag, label("Gespeicherte Raumprofile: " +
            SpatialSensorsStore(this).magnetic.size, 11.4f, muted), 7)
        add(mag, button("MAGNETFELD MESSEN") {
            openSensorForRoom(MagneticLabActivity::class.java)
        }, 9)
        add(body, mag, bottom = 11)
        val audio = card("3   AKUSTIK / ECHO",
            "Aktives Akustikexperiment mit kurzen hörbaren Chirps und Mikrofonaufnahme.")
        add(audio, label("Gespeicherte Raumprofile: " +
            SpatialSensorsStore(this).acoustic.size, 11.4f, muted), 7)
        add(audio, button("AKUSTIK EINMESSEN") {
            openSensorForRoom(AcousticLabActivity::class.java)
        }, 9)
        add(body, audio, bottom = 11)
        add(body, label("Für die erste Alexa-Simulation reicht die WLAN-Kalibrierung. " +
            "Audio und Magnetfeld sind optionale Vergleichsverfahren.", 11.5f, muted), 4)
    }

    private fun devicesStep() {
        add(body, label("Geräte auf der Karte markieren", 22f, ink, true), bottom = 7)
        add(body, label("Ordne Router/Access Points, Lampen und Steckdosen " +
            "deinen Räumen zu. Die Positionen lassen sich danach im Grundriss korrigieren.",
            12.8f, muted), bottom = 11)
        val c = card("GERÄTEÜBERSICHT", home.devices.size.toString() + " Geräte eingetragen")
        home.devices.take(18).forEach { device ->
            add(c, label(device.type + ": " + device.name + " · " +
                (home.room(device.roomId)?.name ?: "Unbekannt"), 12.3f), 7)
        }
        add(c, button("GERÄTE POSITIONIEREN", true) { openHome(1) }, 11)
        add(body, c, bottom = 10)
        add(body, label("Bei Zigbee- oder Matter-Geräten erfolgt die Zuordnung " +
            "zunächst manuell. Ein WLAN-Scan verrät nicht ihre physische Position.",
            11.5f, muted), 6)
    }

    private fun automationStep() {
        add(body, label("Automatisierungen planen", 22f, ink, true), bottom = 7)
        add(body, label("Die App erkennt deinen Raum anhand der eingemessenen WLAN-Fingerprints. " +
            "Erst nach einer stabilen Zuordnung werden Lichtszenen vorgeschlagen.",
            12.8f, muted), bottom = 12)
        val c = card("SICHERER TESTMODUS",
            "Echte Geräte werden noch nicht geschaltet. Alexa-Routinen benötigen " +
                "später eine sichere Kontoverknüpfung.")
        add(c, label(home.rules.count { it.enabled }.toString() +
            " aktive Testregeln · " + home.events.size + " protokollierte Ereignisse",
            12.5f), 9)
        add(c, button("REGELN EINRICHTEN", true) { openHome(3) }, 11)
        add(body, c, bottom = 11)
        add(body, button("Zum Spatial-Home-Dashboard") { openHome(0) }, bottom = 9)
        add(body, row(
            button("Komplettes Backup") { exportBackup() },
            button("Backup wiederherstellen") { importBackupDialog() }
        ))
    }

    private fun openSensorForRoom(activity: Class<out Activity>) {
        if (home.rooms.isEmpty()) {
            toast("Bitte zuerst mindestens einen Raum im Scan definieren.")
            return
        }
        AlertDialog.Builder(this).setTitle("Welchen Raum einmessen?")
            .setItems(home.rooms.map { it.name }.toTypedArray()) { _, index ->
                startActivity(Intent(this, activity).apply {
                    putExtra("room_id", home.rooms[index].id)
                })
            }.show()
    }

    private fun exportBackup() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "rflab_full_home_scan_backup.json")
        }, backupExportRequest)
    }

    private fun importBackupDialog() {
        AlertDialog.Builder(this).setTitle("Gesamtes Spatial-Home-Backup einspielen?")
            .setMessage("Die vorhandene Wohnungskarte, der 3D-Scan und alle " +
                "zugeordneten Magnet- und Akustikprofile werden ersetzt. " +
                "Bitte vorher ein Backup exportieren.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Backup auswählen") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }, backupImportRequest)
            }.show()
    }

    private fun importBackup(uri: android.net.Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: error("Datei nicht lesbar")
            val bytes = input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16384)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    if (out.size() + read > 18_000_000) error("Backup größer als 18 MB")
                    out.write(chunk, 0, read)
                }
                out.toByteArray()
            }
            if (SpatialFullBackup.import(this, String(bytes, Charsets.UTF_8))) {
                home = SpatialHomeStore(this)
                scan = RoomScanStore(this)
                firstCorner = null
                toast("Wohnung, 3D-Scan und Raumprofile wiederhergestellt.")
                render()
            } else toast("Ungültiges Backup – bestehende Daten wurden beibehalten.")
        } catch (e: Exception) {
            toast("Backup-Import: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun openHome(tab: Int) {
        startActivity(Intent(this, SpatialHomeActivity::class.java).apply {
            putExtra("open_tab", tab)
        })
    }
    @Deprecated("Simple scan result integration")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        when (request) {
            scanRequest -> {
                scan = RoomScanStore(this)
                navigate(1)
            }
            backupImportRequest -> data?.data?.let { importBackup(it) }
            backupExportRequest -> {
                try {
                    val uri = data?.data ?: return
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(SpatialFullBackup.export(this))
                    } ?: error("Datei nicht schreibbar")
                    toast("Gesamtes Spatial-Home-Backup gespeichert.")
                } catch (e: Exception) {
                    toast("Backup-Export: " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }
}
