package dev.rflab.scanner

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * RF Lab Field Studio
 *
 * Normal Android app; feature-degrades to WiFi-only on devices without ARCore
 * and AR trajectory-only if ARCore Depth is unavailable. No root, Internet,
 * vendor-private APIs or external radio hardware are required.
 */
class MainActivity : Activity(), SensorEventListener {
    private val background = Color.rgb(9, 17, 29)
    private val surface = Color.rgb(21, 34, 51)
    private val surfaceBright = Color.rgb(29, 46, 66)
    private val light = Color.rgb(237, 244, 251)
    private val muted = Color.rgb(153, 174, 196)
    private val accent = Color.rgb(70, 201, 220)
    private val success = Color.rgb(94, 218, 162)
    private val orange = Color.rgb(251, 192, 111)
    private val gap = 12
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wifi: WifiManager
    private lateinit var sensorManager: SensorManager
    private lateinit var store: SessionStore
    private lateinit var ar: ArPoseTracker
    private lateinit var fingerprints: FingerprintLibrary
    private var data = SurveyData()
    private var baseline: SurveyData? = null
    private var recording = false
    private var fastScans = false
    private var receiverRegistered = false
    private var selectedBssid = ""
    private var currentTab = 0
    private var map: SurveyMapView? = null
    private var infoLog = "Messung bereit"
    private var lastFile: File? = null
    private var uiCount = 0
    private var lastScanTimeNs = 0L
    private var pendingSurvey = false
    private var pendingOneShotScan = false

    private lateinit var headerStatus: TextView
    private lateinit var topButton: Button
    private lateinit var page: LinearLayout
    private lateinit var previewShell: LinearLayout
    private lateinit var navigation: LinearLayout

    private val tabs = listOf("KARTE", "MESH", "RAUM", "INDOOR", "EXPERTEN")
    private val permissionCode = 170
    private val cameraCode = 171
    private val exportCode = 172

    private val scanTask = object : Runnable {
        override fun run() {
            if (!recording) return
            requestScan()
            handler.postDelayed(this, if (fastScans) 10_000 else 35_000)
        }
    }
    private val linkTask = object : Runnable {
        override fun run() {
            if (!recording) return
            sampleConnectedWifi()
            handler.postDelayed(this, 1000)
        }
    }
    private val uiTask = object : Runnable {
        override fun run() {
            updateStatus()
            if (currentTab == 0 || currentTab == 2) map?.update(data, selectedBssid)
            uiCount++
            handler.postDelayed(this, 1100)
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val newResults = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                readScan(newResults)
            } else if (intent?.action == WifiManager.RSSI_CHANGED_ACTION && recording) {
                val value = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -127)
                store.write("wifi_rssi_event", JSONObject()
                    .put("rssi_dbm", value).put("valid", value in -110..-1))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        fingerprints = FingerprintLibrary(this)
        store = SessionStore(this)
        ar = ArPoseTracker(this, this, store,
            { message -> runOnUiThread {
                infoLog = message
                updateStatus()
            } },
            { pose, t -> data.addPose(pose, t) },
            { cloud, _ -> data.addDepth(cloud) }
        )
        buildShell()
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(scanReceiver, filter)
            receiverRegistered = true
        } catch (e: Exception) { infoLog = "Broadcast: " + e.javaClass.simpleName }
        renderTab()
        handler.post(uiTask)
        val previous = sessions().firstOrNull()
        if (previous != null) loadSession(previous)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int = 16, stroke: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }
    }

    private fun stack(): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(value: String, size: Float = 14f, color: Int = light,
                     bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }

    private fun addVertical(container: LinearLayout, view: View, marginTop: Int = 0,
                            marginBottom: Int = 0, height: Int = -2) {
        container.addView(view, LinearLayout.LayoutParams(-1, height).apply {
            topMargin = dp(marginTop)
            bottomMargin = dp(marginBottom)
        })
    }

    private fun card(label: String? = null): LinearLayout {
        val holder = stack()
        holder.setPadding(dp(15), dp(15), dp(15), dp(15))
        holder.background = rounded(surface, 18, Color.rgb(36, 56, 77))
        if (label != null) addVertical(holder, text(label, 15f, light, true), marginBottom = 9)
        return holder
    }

    private fun action(label: String, onClick: () -> Unit, prominent: Boolean = false): Button =
        Button(this).apply {
            text = label
            textSize = 12.5f
            isAllCaps = false
            setTextColor(if (prominent) this@MainActivity.background else light)
            background = rounded(if (prominent) accent else surfaceBright, 12)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            minHeight = dp(43)
            setOnClickListener { onClick() }
        }

    private fun section(title: String, sub: String) {
        addVertical(page, text(title, 22f, light, true), marginTop = 12)
        addVertical(page, text(sub, 12.8f, muted), marginTop = 5, marginBottom = 13)
    }

    private fun rowOf(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEachIndexed { idx, button ->
                addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                    if (idx > 0) leftMargin = dp(7)
                })
            }
        }

    private fun messageLine(content: String) {
        addVertical(page, text(content, 12f, muted), marginTop = 10)
    }

    private fun buildShell() {
        val root = stack()
        root.setBackgroundColor(background)
        root.setPadding(dp(13), dp(7), dp(13), dp(8))
        setContentView(root)

        val heading = stack()
        heading.setPadding(dp(5), dp(5), dp(5), dp(6))
        addVertical(heading, text("RF LAB  /  FIELD STUDIO", 21f, light, true))
        addVertical(heading, text("3D-Funkfeld · Mesh · Raum · Indoor", 11.5f, muted), 2)
        root.addView(heading)

        val capture = card()
        headerStatus = text("Bereit", 12.5f, muted)
        topButton = action("Neue Messfahrt starten", { if (recording) stopSurvey() else startSurvey() }, true)
        capture.addView(topButton, LinearLayout.LayoutParams(-1, dp(45)))
        addVertical(capture, headerStatus, marginTop = 8)
        addVertical(root, capture, marginBottom = 8)

        previewShell = stack()
        previewShell.visibility = View.GONE
        previewShell.setPadding(dp(5), dp(4), dp(5), dp(4))
        previewShell.background = rounded(surface, 12)
        previewShell.addView(ar, LinearLayout.LayoutParams(-1, dp(195)))
        addVertical(root, previewShell, marginBottom = 7)

        val barScroll = HorizontalScrollView(this)
        barScroll.isHorizontalScrollBarEnabled = false
        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        barScroll.addView(navigation)
        addVertical(root, barScroll, marginBottom = 8)

        val contentScroll = ScrollView(this)
        contentScroll.isFillViewport = false
        page = stack()
        page.setPadding(dp(2), dp(4), dp(2), dp(18))
        contentScroll.addView(page)
        root.addView(contentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun updateStatus() {
        if (!::headerStatus.isInitialized) return
        topButton.text = if (recording) "Messfahrt beenden und speichern" else "Neue Messfahrt starten"
        topButton.background = rounded(if (recording) orange else accent, 12)
        val poseCount = data.snapshotPoses().size
        val radioCount = data.positionedCount()
        val scanCount = data.freshScans
        headerStatus.text = if (recording)
            "AUFNAHME  ·  " + poseCount + " Posen  ·  " + radioCount +
                " räumliche RF-Werte  ·  " + scanCount + " Scans\n" + infoLog
        else data.title + "  ·  " + poseCount + " Posen  ·  " +
            radioCount + " räumliche RF-Werte\n" + infoLog
        previewShell.visibility = if (recording && ar.isTrackingActive) View.VISIBLE else View.GONE
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        renderTab()
    }

    private fun drawNav() {
        navigation.removeAllViews()
        tabs.forEachIndexed { idx, item ->
            val active = currentTab == idx
            val chip = text(item, 12f, if (active) background else muted, active)
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(14), dp(11), dp(14), dp(11))
            chip.background = rounded(if (active) accent else surface, 10)
            chip.setOnClickListener { switchTab(idx) }
            navigation.addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply {
                rightMargin = dp(6)
            })
        }
    }

    private fun renderTab() {
        if (!::page.isInitialized) return
        page.removeAllViews()
        map = null
        drawNav()
        when (currentTab) {
            0 -> renderMapPage()
            1 -> renderMeshPage()
            2 -> renderRoomPage()
            3 -> renderIndoorPage()
            else -> renderExpertPage()
        }
    }

    private fun selectableAPs(): List<ApStats> =
        data.snapshotAps().sortedByDescending { it.meanRssi }.take(80)

    private fun addMapSelector() {
        val choices = selectableAPs()
        val labels = listOf("Alle Sender · gemessene Punkte") +
            choices.map { ap ->
                (ap.ssid.ifBlank { "Unbekannt" }).take(20) +
                    " · " + ap.band + " · " + "%.0f".format(Locale.US, ap.meanRssi) +
                    " dBm · " + ap.bssid.takeLast(5)
            }
        val spinner = Spinner(this)
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return text(getItem(position) ?: "", 13f, light)
                    .apply { setPadding(dp(11), dp(13), dp(10), dp(13)) }
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return text(getItem(position) ?: "", 13f, light)
                    .apply {
                        background = rounded(surfaceBright, 4)
                        setPadding(dp(10), dp(12), dp(10), dp(12))
                    }
            }
        }
        spinner.adapter = adapter
        spinner.setBackgroundColor(surfaceBright)
        val index = choices.indexOfFirst { it.bssid == selectedBssid }
        spinner.setSelection(if (index < 0) 0 else index + 1)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedBssid = if (position == 0) "" else choices[position - 1].bssid
                map?.selectedBssid = selectedBssid
                map?.invalidate()
            }
        }
        addVertical(page, spinner, marginBottom = 9)
    }

    private fun addMap(threeD: Boolean, geometry: Boolean) {
        val plot = SurveyMapView(this).apply {
            is3d = threeD
            showDepth = geometry
            heatmap = !threeD
        }
        plot.background = rounded(surface)
        plot.update(data, selectedBssid)
        map = plot
        addVertical(page, plot, height = dp(335), marginBottom = 10)
        addVertical(page, text("Farben = gemessener RSSI · Cyan = ARCore-Trajektorie" +
            if (geometry) " · blaue Punkte = optische Tiefe" else "", 11.3f, muted))
    }

    private fun renderMapPage() {
        section("Funkfeldkarte", "Live-Messpunkte, lokale Interpolation und 3D-Ansicht pro Access Point.")
        val stats = card("MESSÜBERSICHT")
        val poses = data.snapshotPoses()
        val count = data.positionedCount()
        addVertical(stats, text(poses.size.toString() + " Posen  ·  " + count +
            " verortete WLAN-Werte  ·  " + data.freshScans + " frische Scans", 13f, light))
        addVertical(stats, text(if (count < 6) "Für eine Heatmap mehrere Meter bewegen; sonst nur echte Punkte sichtbar."
            else "Heatmap nur nahe gemessenen Punkten; ungemessene Bereiche bleiben frei.", 11.5f, muted), 6)
        addVertical(page, stats, marginBottom = 12)
        addMapSelector()
        addMap(false, false)
        addVertical(page, rowOf(
            action("2D", { map?.is3d = false; map?.invalidate() }),
            action("3D", { map?.is3d = true; map?.invalidate() }),
            action("Zentrieren", { map?.resetView() })
        ), marginTop = 11)
        addVertical(page, rowOf(
            action("Archiv öffnen", { showArchive(false) }),
            action("JSONL exportieren", { exportJsonl() }, true)
        ), marginTop = 8)
        if (recording && !ar.isTrackingActive) addVertical(page,
            action("ARCore-Tracking erneut starten", { startAr() }), marginTop = 8)
        messageLine("3D ist eine isometrische Darstellung echter Messpositionen. Die Signalinterpolation " +
            "ist eine geschätzte 2D-Oberfläche, kein Radar oder volumetrisches RF-Imaging.")
    }

    private fun renderMeshPage() {
        section("Mesh & WLAN-Analyse", "Access Points, Funkkanäle, Roaming und Messfahrtvergleich.")
        addVertical(page, action("Analyse aktualisieren", { renderTab() }), marginBottom = 9)
        val aps = selectableAPs()
        val top = card("NETZWERKSTATUS")
        val byBand = aps.groupBy { it.band }
        addVertical(top, text(aps.size.toString() + " BSSIDs  ·  " +
            (byBand["2,4 GHz"]?.size ?: 0) + " × 2,4 GHz  ·  " +
            (byBand["5 GHz"]?.size ?: 0) + " × 5 GHz  ·  " +
            (byBand["6 GHz"]?.size ?: 0) + " × 6 GHz", 13f, light))
        addVertical(top, text(data.freshScans.toString() + " frische / " +
            data.staleScans + " gespeicherte Scans  ·  " +
            data.acceptedScanRequests + "/" + data.scanRequests + " Scan-Anforderungen akzeptiert",
            11.8f, muted), 7)
        addVertical(page, top, marginBottom = 12)

        val overlaps = aps.groupBy { it.frequency }.filter { it.key > 0 && it.value.size > 1 }
            .entries.sortedByDescending { it.value.size }
        val channels = card("KANALÜBERLAGERUNG (BSSID-ANZAHL)")
        if (overlaps.isEmpty()) addVertical(channels, text("Keine mehrfach belegten Kanäle erkannt.", 12.5f))
        for ((mhz, group) in overlaps.take(8)) {
            addVertical(channels, text(mhz.toString() + " MHz · " + group.size +
                " sichtbare BSSIDs", 13f), marginTop = 6)
        }
        addVertical(channels, text("Anzahl erkannter APs ≠ gemessene Kanalbelegung oder Störung.", 11f, muted), 8)
        addVertical(page, channels, marginBottom = 12)

        val roaming = card("VERBINDUNG / ROAMING")
        addVertical(roaming, text("Verbunden: " +
            (data.lastConnectedSsid.ifBlank { "nicht erfasst" }) +
            "  ·  " + data.lastRssi.toString() + " dBm", 12.5f))
        val transitions = data.snapshotRoam()
        addVertical(roaming, text(if (transitions.isEmpty())
            "Kein AP-Wechsel in dieser Messfahrt protokolliert."
            else transitions.takeLast(6).joinToString("\n"), 11.5f, muted), 8)
        addVertical(page, roaming, marginBottom = 12)

        val list = card("SICHTBARE ACCESS POINTS · ANTIPPEN FÜR KARTE")
        if (aps.isEmpty()) addVertical(list, text("Noch keine WLAN-Daten. Messfahrt starten.", 12f, muted))
        for (ap in aps.take(24)) {
            val label = (ap.ssid.ifBlank { "<versteckte SSID>" }) + " · " +
                ap.band + " · Kanal " + ap.channel
            val b = action(label + "  /  " +
                "%.1f".format(Locale.US, ap.meanRssi) + " dBm", {
                selectedBssid = ap.bssid
                switchTab(0)
            })
            addVertical(list, b, marginTop = 6)
            addVertical(list, text("BSSID " + ap.bssid + " · " + ap.count +
                " Messwerte · " + ap.minRssi + " bis " + ap.maxRssi + " dBm", 10.5f, muted), 3)
        }
        addVertical(page, list, marginBottom = 12)

        val compare = card("VORHER / NACHHER")
        val ref = baseline
        if (ref == null) addVertical(compare, text("Vergleiche die mittlere Signalstärke gleicher BSSIDs zweier Messfahrten.", 12f, muted))
        else {
            addVertical(compare, text("Referenz: " + ref.title, 12f, accent), marginBottom = 8)
            var differences = 0
            val refAps = ref.snapshotAps().associateBy { it.bssid }
            for (ap in aps) {
                val older = refAps[ap.bssid] ?: continue
                val delta = ap.meanRssi - older.meanRssi
                if (differences++ >= 15) break
                addVertical(compare, text(ap.ssid.take(16) + " · " + ap.bssid.takeLast(5) +
                    ": " + (if (delta >= 0) "+" else "") +
                    "%.1f".format(Locale.US, delta) + " dB", 12.5f,
                    if (delta > 2) success else if (delta < -2) orange else light), marginTop = 5)
            }
            if (differences == 0) addVertical(compare, text("Keine gemeinsamen BSSIDs vorhanden.", 11.5f, muted))
        }
        addVertical(compare, action("Referenzmessfahrt auswählen", { showArchive(true) }),
            marginTop = 12)
        addVertical(page, compare)
    }

    private fun renderRoomPage() {
        section("3D-Raumscan", "Optisch erfasste Geometrie mit ARCore Depth und WLAN-Überlagerung.")
        val room = card("OPTISCHE RAUMERFASSUNG")
        val supported = data.depthSupported
        addVertical(room, text(
            when (supported) {
                true -> "ARCore Depth aktiv · " + data.geometryCount() + " 3D-Tiefenpunkte"
                false -> "Depth nicht verfügbar – Trajektorie bleibt nutzbar"
                null -> "Depth-Fähigkeit wird beim Start von ARCore geprüft"
            }, 13f, if (supported == true) success else light))
        addVertical(room, text("Raumgeometrie stammt aus der Kamera/Tiefenschätzung. " +
            "Blaue Punkte zeigen gemessene optische 3D-Punkte – kein geschätztes Material oder RF-Reflexionsbild.",
            11.7f, muted), 7)
        addVertical(page, room, marginBottom = 12)
        addMapSelector()
        addMap(true, true)
        addVertical(page, rowOf(
            action("3D", { map?.is3d = true; map?.invalidate() }),
            action("Draufsicht", { map?.is3d = false; map?.invalidate() }),
            action("Zentrieren", { map?.resetView() })
        ), marginTop = 12)
        if (recording && !ar.isTrackingActive) addVertical(page,
            action("ARCore-Tracking erneut starten", { startAr() }), marginTop = 8)
        messageLine("Die ARCore-Koordinaten sind pro Session lokal. Es gibt noch keine " +
            "automatische Registrierung verschiedener Raummessfahrten oder geschlossene Wand-Meshes.")
    }

    private fun renderIndoorPage() {
        section("Indoor-Lokalisierung", "WLAN-Fingerprints lernen und bekannte Positionen wiedererkennen.")
        val reference = card("REFERENZPUNKTE")
        addVertical(reference, text(fingerprints.all().size.toString() +
            " gespeicherte Orte · " + data.snapshotFingerprint().size +
            " BSSIDs in der letzten aktuellen WLAN-Messung", 12.5f))
        addVertical(reference, text("Gehe an einen bekannten Punkt, mache einen neuen WLAN-Scan " +
            "und speichere einen Namen (z. B. Flur, Couch, Küche).", 11.5f, muted), 7)
        addVertical(reference, rowOf(
            action("Jetzt WLAN scannen", { requestScan() }),
            action("Ort speichern", { saveAnchorPrompt() }, true)
        ), marginTop = 12)
        addVertical(page, reference, marginBottom = 12)

        val estimate = card("POSITIONSSCHÄTZUNG")
        val current = data.snapshotFingerprint()
        val matches = fingerprints.predict(current)
        if (matches.isEmpty()) {
            addVertical(estimate, text("Noch keine belastbare Zuordnung.", 14f, light, true))
            addVertical(estimate, text("Mindestens 3 gemeinsame BSSIDs und gespeicherte Referenzpunkte nötig.",
                11.5f, muted), 6)
        } else {
            val top = matches.first()
            addVertical(estimate, text("Nächstes bekanntes Profil: " + top.name, 17f, success, true))
            addVertical(estimate, text(top.overlap.toString() + " gemeinsame BSSIDs · " +
                "%.1f".format(Locale.US, top.score) + " dB Profilabweichung · " +
                (if (top.sameDevice) "gleiches Gerät" else "anderes Gerät"), 11.6f, muted), 6)
            for (candidate in matches.drop(1)) {
                addVertical(estimate, text(candidate.name + ": " +
                    "%.1f".format(Locale.US, candidate.score) + " dB", 12f), 6)
            }
        }
        addVertical(estimate, text("Das ist WLAN-Fingerprint-Matching (Ortserkennung), " +
            "keine metrische GPS- oder Zentimeterposition. Routeränderungen verschieben die Profile.",
            11f, muted), 9)
        addVertical(page, estimate, marginBottom = 12)

        val saved = card("GESPEICHERTE ORTE")
        val anchors = fingerprints.all()
        if (anchors.isEmpty()) addVertical(saved, text("Noch keine Referenzorte gespeichert.", 12.5f, muted))
        for (a in anchors) {
            val line = rowOf(
                action(a.name + " (" + a.readings.size + " APs)", {
                    AlertDialog.Builder(this).setTitle(a.name)
                        .setMessage("Gerät: " + a.model + "\nReferenz-BSSIDs: " + a.readings.size)
                        .setPositiveButton("OK", null).show()
                }),
                action("Löschen", {
                    AlertDialog.Builder(this).setTitle("Referenzort löschen?")
                        .setMessage(a.name)
                        .setPositiveButton("Löschen") { _, _ ->
                            fingerprints.delete(a.name); renderTab()
                        }.setNegativeButton("Abbrechen", null).show()
                })
            )
            addVertical(saved, line, marginTop = 8)
        }
        addVertical(page, saved)
        messageLine("Daten bleiben lokal in der App. Für reproduzierbare Ergebnisse mehrere " +
            "Referenzmessungen pro Raum und gleiche Smartphone-Ausrichtung verwenden.")
    }

    private fun renderExpertPage() {
        section("Experten & Diagnose", "Alle bisherigen Messfunktionen und erweiterte Rohprotokolle.")
        val diag = card("KLASSISCHE RF-LAB-DIAGNOSE")
        addVertical(diag, text("Hardwarecheck, Wi-Fi RTT, UWB-Erkennung, Kamera-Config-Test, " +
            "IMU-Aufzeichnung, 802.11-Information-Elements und Diagnoselog bleiben erhalten.",
            12.4f, muted), marginBottom = 10)
        addVertical(diag, action("Erweiterte Diagnoseansicht öffnen", {
            if (recording) {
                AlertDialog.Builder(this)
                    .setTitle("Messfahrt beenden?")
                    .setMessage("Vor dem Wechsel in die Expertenansicht muss die aktuelle Messfahrt gespeichert werden.")
                    .setPositiveButton("Speichern & öffnen") { _, _ ->
                        stopSurvey()
                        startActivity(Intent(this, DiagnosticsActivity::class.java))
                    }.setNegativeButton("Abbrechen", null).show()
            } else startActivity(Intent(this, DiagnosticsActivity::class.java))
        }, true))
        addVertical(page, diag, marginBottom = 12)

        val settings = card("MESS-EINSTELLUNGEN")
        addVertical(settings, text("Automatische WLAN-Scans: " +
            (if (fastScans) "10 Sekunden (experimentell)" else "35 Sekunden (Standard)"), 12.5f))
        addVertical(settings, action(if (fastScans) "10-s-Schnellscan ausschalten" else
            "10-s-Schnellscan einschalten", {
            fastScans = !fastScans
            if (recording) {
                handler.removeCallbacks(scanTask)
                handler.post(scanTask)
            }
            if (store.isRecording) store.write("wifi_scan_mode", JSONObject()
                .put("interval_ms", if (fastScans) 10000 else 35000))
            renderTab()
        }), marginTop = 9)
        addVertical(settings, text("Android kann Scans trotz kürzerer Anforderung drosseln; " +
            "nur aktualisierte Ergebnisse werden für Karten verwendet.", 11.2f, muted), 8)
        addVertical(settings, rowOf(
            action("Berechtigungen", { requestWifiPermissions() }),
            action("Neuer WLAN-Scan", { requestScan() })
        ), marginTop = 10)
        addVertical(settings, rowOf(
            action("JSONL exportieren", { exportJsonl() }),
            action("Archiv öffnen", { showArchive(false) })
        ), marginTop = 8)
        addVertical(page, settings, marginBottom = 12)

        val info = card("DATEN & DATENSCHUTZ")
        addVertical(info, text("Kein Root · Keine Internetberechtigung · Keine CSI-/I/Q-Rohdaten.",
            12.3f, light))
        addVertical(info, text("Die App speichert lokale WLAN-Kennungen, ARCore-Positionen " +
            "und optional optische Tiefenpunkte. Export nur auf Benutzeraktion. " +
            "Verschiedene ARCore-Sessions erhalten unabhängige lokale Koordinaten.",
            11.5f, muted), 7)
        addVertical(page, info)
    }

    private fun saveAnchorPrompt() {
        val readings = data.snapshotFingerprint()
        if (readings.size < 3) {
            Toast.makeText(this, "Zuerst einen frischen WLAN-Scan mit mindestens 3 APs durchführen.",
                Toast.LENGTH_LONG).show()
            return
        }
        val name = EditText(this).apply {
            hint = "z. B. Küche / Schreibtisch"
            isSingleLine = true
            setTextColor(Color.BLACK)
        }
        AlertDialog.Builder(this).setTitle("Indoor-Referenzort speichern")
            .setMessage(readings.size.toString() + " WLAN-Sender werden als Fingerprint gespeichert.")
            .setView(name).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                val label = name.text?.toString() ?: ""
                if (fingerprints.save(label, readings)) {
                    if (store.isRecording) store.write("fingerprint_reference", JSONObject()
                        .put("name", label).put("bssid_count", readings.size))
                    renderTab()
                } else Toast.makeText(this, "Name eingeben und mindestens 3 APs erfassen.",
                    Toast.LENGTH_LONG).show()
            }.show()
    }

    private fun wifiAllowed() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED)

    private fun requestWifiPermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        requestPermissions(list.toTypedArray(), permissionCode)
    }

    private fun startSurvey() {
        if (recording) return
        if (!wifiAllowed()) {
            pendingSurvey = true
            requestWifiPermissions()
            return
        }
        pendingSurvey = false
        val identifier = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN).format(Date())
        data = SurveyData().apply { reset("Messfahrt " + identifier) }
        selectedBssid = ""
        baseline = null
        store.start()
        lastFile = store.currentFile
        store.write("survey_start", JSONObject()
            .put("mode", "field_studio")
            .put("version", "0.3.0")
            .put("local_frame", "ARCore per-session local")
            .put("requested_wifi_interval_ms", if (fastScans) 10000 else 35000))
        recording = true
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        handler.post(scanTask)
        handler.post(linkTask)
        infoLog = "Messfahrt läuft. WLAN-Daten werden erfasst."
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startAr()
        } else {
            infoLog = "Kamera erlauben, damit 3D-Messpunkte entstehen."
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraCode)
        }
        renderTab()
        updateStatus()
    }

    private fun startAr() {
        if (!recording || ar.isTrackingActive) return
        data.newArRun()
        if (!ar.start()) infoLog = "ARCore nicht gestartet; WLAN-only-Messung aktiv."
        updateStatus()
    }

    private fun stopSurvey() {
        if (!recording) return
        recording = false
        handler.removeCallbacks(scanTask)
        handler.removeCallbacks(linkTask)
        sensorManager.unregisterListener(this)
        ar.stop()
        store.write("survey_stop", JSONObject()
            .put("poses", data.snapshotPoses().size)
            .put("positioned_radios", data.positionedCount())
            .put("depth_points", data.geometryCount()))
        store.stop()
        infoLog = "Messfahrt lokal gespeichert. Export oder Archiv öffnen."
        renderTab()
        updateStatus()
    }

    private fun requestScan() {
        if (!wifiAllowed()) {
            pendingOneShotScan = true
            requestWifiPermissions()
            return
        }
        pendingOneShotScan = false
        try {
            val allowed = wifi.startScan()
            if (recording && store.isRecording) {
                store.write("wifi_scan_request", JSONObject()
                    .put("accepted", allowed)
                    .put("requested_interval_ms", if (fastScans) 10000 else 35000))
            }
            data.scanRequests++
            if (allowed) data.acceptedScanRequests++
            infoLog = if (allowed) "WLAN-Scan angefordert." else "Scan abgelehnt/gedrosselt."
            if (!allowed) readScan(false)
        } catch (e: Exception) {
            infoLog = "Scan: " + e.javaClass.simpleName
        }
    }

    private fun readScan(fresh: Boolean) {
        if (!wifiAllowed()) return
        try {
            val results = wifi.scanResults
            val t = SystemClock.elapsedRealtimeNanos()
            val aps = JSONArray()
            for (ap in results) {
                aps.put(JSONObject()
                    .put("ssid", ap.SSID ?: "")
                    .put("bssid", ap.BSSID ?: "")
                    .put("frequency_mhz", ap.frequency)
                    .put("rssi_dbm", ap.level)
                    .put("rssi_valid", ap.level in -110..-1)
                    .put("seen_t_ns", ap.timestamp * 1000L)
                    .put("channel_width_code", ap.channelWidth)
                    .put("wifi_standard_code", ap.wifiStandard))
            }
            val payload = JSONObject().put("updated", fresh).put("aps", aps)
            data.addScan(payload, t)
            if (recording && store.isRecording) store.write("wifi_scan", payload)
            lastScanTimeNs = t
            infoLog = results.size.toString() + " APs, " +
                (if (fresh) "neuer Scan" else "zwischengespeicherte Daten")
            if (!recording) renderTab()
        } catch (e: Exception) { infoLog = "Scan lesen: " + e.javaClass.simpleName }
    }

    private fun sampleConnectedWifi() {
        if (!wifiAllowed()) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
            val onWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            var wifiInfo = if (onWifi) capabilities?.transportInfo as? WifiInfo else null
            if (wifiInfo == null || wifiInfo.frequency <= 0) {
                @Suppress("DEPRECATION")
                val legacy = wifi.connectionInfo
                if (legacy != null && legacy.frequency > 0) wifiInfo = legacy
            }
            val link = wifiInfo ?: return
            val payload = JSONObject()
                .put("bssid", link.bssid ?: "")
                .put("ssid", link.ssid ?: "")
                .put("rssi_dbm", link.rssi)
                .put("frequency_mhz", link.frequency)
                .put("tx_speed_mbps", link.txLinkSpeedMbps)
                .put("rx_speed_mbps", link.rxLinkSpeedMbps)
                .put("wifi_standard", link.wifiStandard)
            val time = SystemClock.elapsedRealtimeNanos()
            if (recording && store.isRecording) store.write("wifi_link", payload)
            data.addLink(payload, time)
        } catch (e: Exception) { infoLog = "WLAN-Link: " + e.javaClass.simpleName }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (recording && store.isRecording) store.write("imu", JSONObject()
            .put("sensor_type", event.sensor.type)
            .put("sensor_t_ns", event.timestamp)
            .put("values", SessionStore.floatArray(event.values)))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == permissionCode) {
            if (wifiAllowed()) {
                val shouldSurvey = pendingSurvey
                val shouldScan = pendingOneShotScan
                pendingSurvey = false
                pendingOneShotScan = false
                if (shouldSurvey) startSurvey()
                else if (shouldScan) requestScan()
            } else {
                pendingSurvey = false
                pendingOneShotScan = false
                infoLog = "WLAN-Zugriff benötigt Standort- und WLAN-Berechtigungen."
            }
        } else if (requestCode == cameraCode) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED)
                startAr()
            else infoLog = "WLAN-only-Modus: Kamera wurde nicht erlaubt."
        }
        updateStatus()
    }

    private fun sessions(): List<File> {
        val dir = getExternalFilesDir(null) ?: filesDir
        return dir.listFiles()?.filter { it.isFile && it.name.startsWith("rflab_") &&
            it.name.endsWith(".jsonl") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun showArchive(forComparison: Boolean) {
        if (recording) {
            Toast.makeText(this, "Erst Messfahrt beenden.", Toast.LENGTH_SHORT).show()
            return
        }
        val choices = sessions().filter { !forComparison || it.absolutePath != lastFile?.absolutePath }
        if (choices.isEmpty()) {
            Toast.makeText(this, "Noch keine gespeicherten Messfahrten.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (forComparison) "Referenzmessfahrt auswählen" else "Messfahrt öffnen")
            .setItems(choices.take(40).map { it.name }.toTypedArray()) { _, index ->
                if (forComparison) loadBaseline(choices[index]) else loadSession(choices[index])
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun loadSession(file: File) {
        if (recording) return
        infoLog = "Lade " + file.name
        updateStatus()
        Thread {
            try {
                val loaded = SurveyData.fromJsonl(file)
                runOnUiThread {
                    if (!recording) {
                        data = loaded
                        lastFile = file
                        selectedBssid = ""
                        infoLog = "Messfahrt aus lokalem Archiv geladen."
                        renderTab()
                        updateStatus()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { infoLog = "Ladefehler: " + e.javaClass.simpleName; updateStatus() }
            }
        }.start()
    }

    private fun loadBaseline(file: File) {
        infoLog = "Vergleichsmessung wird geladen."
        Thread {
            try {
                val loaded = SurveyData.fromJsonl(file)
                runOnUiThread { baseline = loaded; infoLog = "Referenz geladen."; renderTab() }
            } catch (e: Exception) {
                runOnUiThread { infoLog = "Vergleich fehlgeschlagen: " + e.javaClass.simpleName }
            }
        }.start()
    }

    private fun exportJsonl() {
        if (recording) {
            Toast.makeText(this, "Bitte Messfahrt vor dem Export beenden.", Toast.LENGTH_SHORT).show()
            return
        }
        val file = lastFile ?: sessions().firstOrNull()
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Keine Messdatei vorhanden.", Toast.LENGTH_SHORT).show()
            return
        }
        lastFile = file
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }, exportCode)
    }

    @Deprecated("Uses platform activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        if (requestCode != exportCode || resultCode != RESULT_OK) return
        val target = result?.data ?: return
        val source = lastFile ?: return
        try {
            contentResolver.openOutputStream(target)?.use { dst ->
                FileInputStream(source).use { it.copyTo(dst) }
            } ?: error("Zieldatei nicht zugänglich")
            infoLog = "Export erfolgreich: " + source.name
        } catch (e: Exception) { infoLog = "Exportfehler: " + e.message }
        updateStatus()
    }

    override fun onPause() {
        if (::ar.isInitialized && ar.isTrackingActive) {
            ar.stop()
            infoLog = "ARCore pausiert; Session kann nach Rückkehr fortgesetzt werden."
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (recording) stopSurvey()
        handler.removeCallbacks(uiTask)
        handler.removeCallbacks(scanTask)
        handler.removeCallbacks(linkTask)
        if (receiverRegistered) unregisterReceiver(scanReceiver)
        super.onDestroy()
    }
}
