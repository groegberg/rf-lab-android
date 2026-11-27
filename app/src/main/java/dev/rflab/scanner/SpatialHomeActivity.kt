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
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Spatial Home v0.4.0
 * User-defined rooms and Wi-Fi-only localization. Optional camera Lab is separate.
 * All automation outcomes are simulated: no real Alexa commands are sent.
 */
class SpatialHomeActivity : Activity() {
    private val background = Color.rgb(11, 20, 33)
    private val cardColor = Color.rgb(24, 39, 57)
    private val detailColor = Color.rgb(38, 58, 78)
    private val white = Color.rgb(229, 240, 250)
    private val quiet = Color.rgb(164, 188, 208)
    private val cyan = Color.rgb(101, 210, 237)
    private val green = Color.rgb(116, 232, 169)
    private val orange = Color.rgb(251, 181, 102)
    private lateinit var home: SpatialHomeStore
    private lateinit var wifi: WifiManager
    private val locator = SpatialLocator()
    private lateinit var automation: SpatialAutomation
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var nav: LinearLayout
    private lateinit var header: TextView
    private var tab = 0
    private val tabs = listOf("HOME", "WOHNUNG", "EINMESSEN", "AUTOMATION", "LAB", "EXPERTEN")
    private var selectedRoomId: String? = null
    private var selectedAnchorId: String? = null
    private var placingDeviceId: String? = null
    private var collecting = false
    private var tracking = false
    private var lastSweep: RfSweep? = null
    private var lastEstimate: RoomEstimate? = null
    private var lastScanSeenNs = 0L
    private var lastFreshMs = 0L
    private var freshCount = 0
    private var cachedCount = 0
    private var acceptedRequests = 0
    private var rejectedRequests = 0
    private var notice = "Räume einrichten und WLAN-Funkfeld einmessen."
    private var registered = false
    private val permissionRequest = 218
    private val homeExportRequest = 219
    private val homeImportRequest = 220
    private val csvImportRequest = 221
    private val progressTask = object : Runnable {
        override fun run() {
            refreshHeader()
            handler.postDelayed(this, 2000)
        }
    }
    private val scanTask = object : Runnable {
        override fun run() {
            if (!tracking && !collecting) return
            requestScan()
            handler.postDelayed(this, 35_000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                consumeScan(updated)
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = background
        window.navigationBarColor = background
        home = SpatialHomeStore(this)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        automation = SpatialAutomation(home)
        selectedRoomId = home.rooms.firstOrNull()?.id
        tab = intent.getIntExtra("open_tab", 0).coerceIn(0, 5)
        layout()
        render()
    }

    override fun onResume() {
        super.onResume()
        try {
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, filter)
            registered = true
        } catch (e: Exception) { notice = "Scan-Receiver: " + e.javaClass.simpleName }
        handler.removeCallbacks(progressTask)
        handler.post(progressTask)
        scheduleScans()
    }

    override fun onPause() {
        // Never attribute measurements collected after returning from another
        // app to the last position: the phone may have moved while paused.
        if (collecting) {
            collecting = false
            notice = "Messpunktaufnahme nach App-Wechsel automatisch pausiert."
        }
        handler.removeCallbacks(scanTask)
        handler.removeCallbacks(progressTask)
        if (registered) {
            unregisterReceiver(receiver)
            registered = false
        }
        // Camera-free localization has no background scan bypass in this release.
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(scanTask)
        handler.removeCallbacks(progressTask)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun panel(color: Int, radius: Int = 13): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun text(value: String, size: Float = 13f, color: Int = white,
                     bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1.05f)
        }
    private fun stack() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun add(parent: LinearLayout, view: View, top: Int = 0, bottom: Int = 0,
                    width: Int = -1, height: Int = -2) {
        val p = LinearLayout.LayoutParams(width, height)
        p.topMargin = dp(top)
        p.bottomMargin = dp(bottom)
        parent.addView(view, p)
    }
    private fun card(title: String, subtitle: String = ""): LinearLayout =
        stack().apply {
            background = panel(cardColor)
            setPadding(dp(15), dp(14), dp(15), dp(15))
            add(this, text(title, 13f, cyan, true))
            if (subtitle.isNotBlank()) add(this, text(subtitle, 11.5f, quiet), 7)
        }
    private fun action(name: String, primary: Boolean = false,
                       onClick: () -> Unit): Button =
        Button(this).apply {
            text = name
            isAllCaps = false
            textSize = 12f
            setTextColor(if (primary) this@SpatialHomeActivity.background else white)
            background = panel(if (primary) cyan else detailColor, 10)
            minHeight = dp(45)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
        }
    private fun actions(vararg items: Button): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        items.forEach { b ->
            val params = LinearLayout.LayoutParams(0, dp(48), 1f)
            params.setMargins(dp(3), 0, dp(3), 0)
            bar.addView(b, params)
        }
        return bar
    }
    private fun note(msg: String) { add(content, text(msg, 11.6f, quiet), 5, 11) }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun layout() {
        root = stack()
        root.setBackgroundColor(background)
        root.setPadding(dp(12), dp(9), dp(12), dp(8))
        setContentView(root)
        SystemBarsSafeArea.install(this, root)
        add(root, text("RF LAB   /   SPATIAL HOME", 21f, white, true))
        add(root, text("Funkfeld · Räume · Geräte · Automationen", 11.5f, quiet), 3)
        header = text("", 11.6f, quiet)
        add(root, header, 10, 8)
        val scrollNav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scrollNav.addView(nav)
        add(root, scrollNav, 0, 8)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = stack()
        content.setPadding(dp(2), dp(2), dp(2), dp(20))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    }
    private fun refreshHeader() {
        val est = lastEstimate
        val age = if (lastFreshMs > 0L) (System.currentTimeMillis() - lastFreshMs) / 1000L else -1
        val room = if (est?.usable(System.currentTimeMillis()) == true)
            est.roomName + " · " + (est.confidence * 100).toInt() + "%"
        else "Position unbekannt"
        header.text = room + "   |   " +
            if (age >= 0) "WLAN vor " + age + " s" else "Kein aktueller WLAN-Scan"
    }
    private fun selectTab(index: Int) {
        tab = index
        render()
    }
    private fun render() {
        content.removeAllViews()
        nav.removeAllViews()
        tabs.forEachIndexed { i, item ->
            val chip = action(item, tab == i) { selectTab(i) }
            val lp = LinearLayout.LayoutParams(-2, dp(41))
            lp.rightMargin = dp(7)
            nav.addView(chip, lp)
        }
        refreshHeader()
        when (tab) {
            0 -> homePage()
            1 -> roomsPage()
            2 -> measurePage()
            3 -> automationPage()
            4 -> labPage()
            else -> diagnosticsPage()
        }
    }

    private fun map(height: Int = 310, tap: ((Float, Float) -> Unit)? = null) {
        val view = SpatialFloorplanView(this).apply {
            home = this@SpatialHomeActivity.home
            activeRoomId = selectedRoomId
            activeAnchorId = selectedAnchorId
            estimate = lastEstimate
            locationHint = SpatialProximity().hint(
                this@SpatialHomeActivity.home, lastEstimate, lastSweep)
            onTap = tap
        }
        add(content, view, 5, 10, height = dp(height))
    }

    private fun currentRoom(): HomeRoom? =
        home.room(selectedRoomId) ?: home.rooms.firstOrNull()

    private fun homePage() {
        val est = lastEstimate
        val age = if (lastFreshMs > 0) (System.currentTimeMillis() - lastFreshMs) / 1000L else -1L
        val quick = card("EINFACH EINRICHTEN",
            "3D-Scan → Räume → RF/Magnet/Akustik → Geräte → Automationen")
        add(quick, action("Geführte Einrichtung öffnen", true) {
            startActivity(Intent(this, SpatialSetupActivity::class.java))
        }, 8)
        add(content, quick, bottom = 12)
        val summary = card("DEIN STANDORT", if (est?.usable(System.currentTimeMillis()) == true)
            "WLAN-Fingerprint stimmt mit einer bekannten Raumposition überein."
            else "Noch keine sichere Raumzuordnung. Unbekannte Positionen lösen keine Aktion aus.")
        add(summary, text(if (est?.usable(System.currentTimeMillis()) == true)
            est.roomName else "Position unbekannt", 24f, white, true), 12)
        add(summary, text("Qualität: " + (est?.quality ?: "NICHT_GEMESSEN") +
            " · letzter frischer Scan: " +
            if (age < 0) "keiner" else age.toString() + " s", 11.7f, quiet), 6)
        add(summary, actions(
            action(if (tracking) "Ortung stoppen" else "Ortung starten", true) {
                if (tracking) {
                    tracking = false; collecting = false
                    scheduleScans(); notice = "Ortung beendet."
                    automation.reset(); render()
                } else requireWifi {
                    tracking = true; scheduleScans(); notice = "Ortung im Vordergrund gestartet."
                    render()
                }
            },
            action("WLAN-Scan") { requireWifi { requestScan() } }
        ), 12)
        add(content, summary, bottom = 12)
        val status = card("MESSSTATUS")
        add(status, text("Frische Scans: " + freshCount + " · gecacht: " + cachedCount +
            " · Scananfragen: " + acceptedRequests + " akzeptiert / " +
            rejectedRequests + " abgewiesen", 12.2f))
        add(status, text("Aktuelle BSSIDs: " + (lastSweep?.readings?.size ?: 0) +
            " · Kartenpunkte: " + home.anchors.size +
            " · Geräte: " + home.devices.size, 12.2f), 8)
        add(status, text(notice, 11.4f, orange), 9)
        add(content, status, bottom = 12)
        map(280) { x, z ->
            val r = home.rooms.firstOrNull { it.contains(x, z) }
            if (r != null) {
                selectedRoomId = r.id
                toast("Raum " + r.name + " ausgewählt.")
            }
        }
        val probs = card("RAUMWAHRSCHEINLICHKEITEN")
        val candidates = est?.candidates.orEmpty()
        if (candidates.isEmpty()) add(probs, text("Noch keine auswertbaren Fingerprints.", 12f))
        else candidates.take(5).forEach {
            add(probs, text(it.name + " · " +
                (it.probability * 100).toInt() + "% · Rohscore " +
                String.format(Locale.GERMAN, "%.1f", it.scoreDbm) +
                " dB · " + it.overlap + " gemeinsame BSSIDs", 12f), 7)
        }
        add(content, probs, bottom = 12)
        val proximity = card("NÄCHSTE GERÄTE · EXPERIMENTELL",
            "RSSI-basierte Näherung. Kein exakter Abstand, keine Gerätesteuerung.")
        val h = SpatialProximity().hint(home, lastEstimate, lastSweep)
        if (h == null) {
            add(proximity, text("Mindestens drei räumlich verteilte Messpunkte " +
                "im sicher erkannten Raum erforderlich.", 12f, quiet), 7)
        } else {
            add(proximity, text(String.format(Locale.GERMAN,
                "Position ca. %.1f / %.1f m · Unsicherheitsradius mindestens %.1f m",
                h.x, h.z, h.uncertaintyM), 12f), 7)
            val nearby = SpatialProximity().nearby(home, lastEstimate, h)
            if (nearby.isEmpty()) add(proximity, text("Keine Geräte in diesem Raum verortet."))
            nearby.take(8).forEach { d ->
                add(proximity, text(d.device.name + " · " +
                    String.format(Locale.GERMAN, "%.1f–%.1f m", d.lowerM, d.upperM) +
                    " · " + d.kind, 12f,
                    if (d.kind.startsWith("NAHE")) green else quiet), 8)
            }
        }
        add(content, proximity, bottom = 12)
        note("Die Prozentwerte sind vorläufige, nicht statistisch kalibrierte Modellgewichte. " +
            "Die Markierung zeigt den ähnlichsten Messpunkt, keine genaue Distanz. " +
            "Ortung funktioniert derzeit nur, während die App sichtbar ist.")
    }

    private fun roomsPage() {
        val summary = card("WOHNUNG & GERÄTE",
            "Räume als schematische Rechtecke in Metern anlegen. Die Karte ist kein automatischer 3D-Scan.")
        add(summary, text(home.rooms.size.toString() + " Räume · " +
            home.devices.size + " Geräte · " + home.anchors.size + " Messpunkte", 12.5f), 7)
        add(summary, actions(
            action("Raum hinzufügen", true) { roomDialog() },
            action("Gerät hinzufügen") { addDeviceDialog() }
        ), 12)
        add(content, summary, bottom = 12)
        map(335) { x, z ->
            val device = placingDeviceId
            if (device != null) {
                if (home.moveDevice(device, x, z)) {
                    notice = "Geräteposition gespeichert."
                    placingDeviceId = null
                } else toast("Bitte innerhalb des zugeordneten Raums tippen.")
            } else {
                val r = home.rooms.firstOrNull { it.contains(x, z) }
                if (r != null) selectedRoomId = r.id
            }
            render()
        }
        if (placingDeviceId != null) note("Positionieren: Tippe im Grundriss auf die gewünschte Geräteposition.")
        for (r in home.rooms) {
            val item = card(r.name.uppercase(Locale.GERMAN),
                String.format(Locale.GERMAN, "%.1f × %.1f m · %d Messpunkte",
                    r.width, r.depth, home.anchors.count { it.roomId == r.id }))
            add(item, actions(
                action("Auswählen") { selectedRoomId = r.id; render() },
                action("Löschen") {
                    AlertDialog.Builder(this).setTitle(r.name + " löschen?")
                        .setMessage("Messpunkte, Gerätepositionen und Regeln dieses Raums gehen verloren.")
                        .setNegativeButton("Abbrechen", null)
                        .setPositiveButton("Löschen") { _, _ ->
                            home.removeRoom(r.id)
                            selectedRoomId = home.rooms.firstOrNull()?.id
                            render()
                        }.show()
                }
            ), 8)
            home.devices.filter { it.roomId == r.id }.forEach { device ->
                add(item, text(device.type.uppercase(Locale.GERMAN) +
                    "  ·  " + device.name +
                    if (device.bssid.isNotBlank()) " · " + device.bssid.takeLast(8) else "",
                    12.6f, white, true), 12)
                if (device.type == "Access Point") add(item,
                    action("WLAN-BSSID zuordnen") { chooseAccessPointBssid(device) }, 7)
                add(item, actions(
                    action("Auf Karte setzen") {
                        placingDeviceId = device.id
                        selectedRoomId = device.roomId
                        render()
                    },
                    action("Entfernen") {
                        home.removeDevice(device.id); render()
                    }
                ), 5)
            }
            add(content, item, bottom = 12)
        }
        if (home.rooms.isEmpty()) note("Beginne mit mindestens zwei Räumen. Ohne mehrere Referenzräume " +
            "können wir nicht beurteilen, ob ein Funkmuster eindeutig ist.")
    }

    private fun suggestedCoordinates(): Pair<Float, Float> {
        if (home.rooms.isEmpty()) return Pair(0f, 0f)
        val right = home.rooms.maxOf { it.x + it.width }
        return if (right < 24f) Pair(right + .5f, 0f)
            else Pair(0f, home.rooms.maxOf { it.z + it.depth } + .5f)
    }
    private fun roomDialog() {
        val (sx, sz) = suggestedCoordinates()
        val fields = stack().apply { setPadding(dp(20), dp(4), dp(20), 0) }
        fun field(caption: String, value: String): EditText {
            add(fields, text(caption, 11.5f, quiet), 8)
            return EditText(this).apply {
                setText(value)
                setTextColor(white)
                setHintTextColor(quiet)
                setSingleLine(true)
                add(fields, this)
            }
        }
        val name = field("Raumname", "Wohnzimmer")
        val x = field("Start X (m)", sx.toString())
        val z = field("Start Z (m)", sz.toString())
        val w = field("Breite (m)", "4")
        val d = field("Tiefe (m)", "4")
        AlertDialog.Builder(this).setTitle("Raum im Grundriss")
            .setView(fields).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                fun number(t: EditText) = t.text.toString().replace(',', '.').toFloatOrNull() ?: -1f
                val ok = home.addRoom(name.text.toString(),
                    number(x), number(z), number(w), number(d))
                if (!ok) toast("Ungültige Maße oder überlappende Räume (max. 100 m).")
                else selectedRoomId = home.rooms.last().id
                render()
            }.show()
    }

    private fun addDeviceDialog() {
        val room = currentRoom()
        if (room == null) { toast("Zuerst einen Raum anlegen."); return }
        AlertDialog.Builder(this).setTitle("Gerätetyp")
            .setItems(arrayOf("Lampe", "Steckdose", "Access Point / Router")) { _, which ->
                val name = EditText(this).apply {
                    hint = "z. B. Stehlampe Sofa"
                    setSingleLine(true)
                }
                AlertDialog.Builder(this).setTitle("Gerät zu " + room.name)
                    .setMessage("Das Gerät wird zunächst in der Raummitte platziert. " +
                        "Anschließend kannst du seine echte Position antippen.")
                    .setView(name).setNegativeButton("Abbrechen", null)
                    .setPositiveButton("Anlegen") { _, _ ->
                        val type = when (which) {
                            0 -> "Lampe"
                            1 -> "Steckdose"
                            else -> "Access Point"
                        }
                        val d = home.addDevice(name.text.toString(), room.id, type)
                        if (d == null) toast("Bitte Gerätenamen eingeben.")
                        else {
                            placingDeviceId = d.id
                            selectedRoomId = room.id
                            if (type == "Access Point") chooseAccessPointBssid(d)
                        }
                        render()
                    }.show()
            }.show()
    }


    private fun chooseAccessPointBssid(device: HomeDevice) {
        val known = home.anchors.flatMap { anchor ->
            anchor.sweeps.flatMap { sweep ->
                sweep.readings.keys.map { bssid ->
                    bssid to (sweep.ssids[bssid] ?: "")
                }
            }
        }.groupBy { it.first }.map { (bssid, all) ->
            bssid to (all.lastOrNull { it.second.isNotBlank() }?.second ?: "")
        }.sortedWith(compareBy({ it.second }, { it.first }))
        val labels = listOf("Später zuordnen") + known.map { (bssid, ssid) ->
            (if (ssid.isBlank()) "Unbekannt" else ssid.take(22)) +
                " · " + bssid.takeLast(8)
        }
        AlertDialog.Builder(this).setTitle("AP mit gemessener BSSID verknüpfen")
            .setMessage("WLAN-BSSIDs sind Funkinterfaces, nicht zwingend " +
                "eindeutige physische Router. Ein Mesh-Knoten kann mehrere BSSIDs haben.")
            .setItems(labels.toTypedArray()) { _, index ->
                device.bssid = if (index == 0) "" else known[index - 1].first
                home.save()
                render()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun measurePage() {
        val summary = card("RF-FUNKFELD EINMESSEN",
            "Stelle dich an einen bekannten Punkt, tippe dessen Position auf den Plan und " +
                "warte auf frische WLAN-Scans. Für einen anderen Punkt vorher pausieren.")
        val room = currentRoom()
        add(summary, text("Aktueller Raum: " + (room?.name ?: "Noch kein Raum"), 12.5f, white, true), 8)
        add(summary, action("Raum auswählen") {
            chooseRoom { selectedRoomId = it; selectedAnchorId = null; collecting = false; render() }
        }, 8)
        val anchor = home.anchors.firstOrNull { it.id == selectedAnchorId }
        add(summary, text("Messpunkt: " + (anchor?.label ?: "Auf Karte setzen") +
            " · " + (anchor?.sweeps?.size ?: 0) + " frische Messserien", 11.5f, quiet), 8)
        add(summary, actions(
            action(if (collecting) "Pausieren" else "Am Punkt messen", true) {
                if (collecting) {
                    collecting = false; notice = "Aufnahme pausiert. Position wechseln."
                    scheduleScans(); render()
                } else if (anchor == null) toast("Zuerst Messpunkt im Grundriss setzen.")
                else requireWifi {
                    collecting = true; tracking = true
                    notice = "Aufnahme aktiv: für 2–4 frische Scans möglichst stillhalten."
                    scheduleScans(); render()
                }
            },
            action("Scan anfordern") { requireWifi { requestScan() } }
        ), 9)
        add(content, summary, bottom = 8)
        map(340) { x, z ->
            val r = home.rooms.firstOrNull { it.contains(x, z) }
            if (r == null) {
                toast("Tippe innerhalb eines eingezeichneten Raums.")
            } else {
                selectedRoomId = r.id
                // Touching an existing point selects it but does NOT collect while moving.
                val nearest = home.anchors.filter { it.roomId == r.id }
                    .minByOrNull { (it.x - x) * (it.x - x) + (it.z - z) * (it.z - z) }
                val distance2 = if (nearest != null)
                    (nearest.x - x) * (nearest.x - x) + (nearest.z - z) * (nearest.z - z)
                else Float.MAX_VALUE
                if (distance2 < .22f * .22f) {
                    selectedAnchorId = nearest?.id
                    collecting = false
                    notice = "Vorhandenen Punkt gewählt. Zum Aufnehmen 'Am Punkt messen' drücken."
                } else {
                    val newPoint = home.addAnchor(r.id, x, z)
                    selectedAnchorId = newPoint?.id
                    collecting = false
                    notice = "Punkt " + (newPoint?.label ?: "") + " angelegt; " +
                        "am Ort bleiben und 'Am Punkt messen' drücken."
                }
                scheduleScans(); render()
            }
        }
        val points = card("MESSPUNKTE", "Eine Funkkarte benötigt mehrere Referenzen pro Raum.")
        for (a in home.anchors.filter { it.roomId == currentRoom()?.id }) {
            add(points, text(a.label + " · " + a.sweeps.size +
                " frische Scans · " + a.median().size + " BSSIDs" +
                if (a.id == selectedAnchorId) " · GEWÄHLT" else "", 12.1f), 8)
            add(points, actions(
                action("Wählen") {
                    selectedAnchorId = a.id; collecting = false
                    scheduleScans(); render()
                },
                action("Löschen") {
                    home.removeAnchor(a.id)
                    if (selectedAnchorId == a.id) selectedAnchorId = null
                    collecting = false; scheduleScans(); render()
                }
            ), 4)
        }
        add(content, points, bottom = 12)
        val quality = card("FUNKFELD-QUALITÄT")
        val trainedRooms = home.rooms.count { r ->
            home.anchors.any { it.roomId == r.id && it.sweeps.isNotEmpty() }
        }
        add(quality, text("Kalibrierte Räume: " + trainedRooms + "/" + home.rooms.size +
            " · eingemessene Punkte: " + home.anchors.count { it.sweeps.isNotEmpty() }, 12f))
        add(quality, text("Frische Scans: " + freshCount +
            " · letzte Aufnahme: " + (lastSweep?.readings?.size ?: 0) + " BSSIDs", 12f), 7)
        val validation = SpatialValidator().validate(home.rooms, home.anchors)
        val vtext = if (validation.tested == 0)
            "Kreuzvalidierung noch nicht möglich: mindestens zwei Messpunkte pro Raum " +
                "in mindestens zwei Räumen erforderlich."
        else "Messpunktprüfung: " + validation.correct + "/" + validation.tested +
            " richtig · " + validation.wrong + " falsch · " + validation.unknown +
            " unbekannt. Stichprobe klein, keine Genauigkeitsgarantie."
        add(quality, text(vtext, 11.5f, if (validation.wrong > 0) orange else quiet), 10)
        add(quality, action("Vorhandene Nachtmessung übernehmen") { importManual() }, 10)
        add(quality, action("Alte Nachtmessungs-CSV importieren") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, csvImportRequest)
        }, 8)
        add(content, quality, bottom = 12)
        note("Android limitiert aktive WLAN-Scans. Wir verwenden nur frische AP-Daten " +
            "mit plausiblen Hardwarezeitstempeln. 10–20 Sekunden können für mehrere Scans zu kurz sein: " +
            "Warte auf die tatsächlich angezeigte Messserienzahl.")
    }

    private fun importManual() {
        val manual = ManualSurvey(this)
        if (!manual.loadLatest() || manual.allPoints().isEmpty()) {
            toast("Keine gespeicherte RF-Lab-v0.3.1-Nachtmessung gefunden."); return
        }
        val (x, z) = suggestedCoordinates()
        if (!home.addRoom("Importierte Nachtmessung", x, z,
                manual.widthM, manual.heightM)) {
            toast("Kein freier Platz für importierten Raum. Raumkoordinaten prüfen."); return
        }
        val room = home.rooms.last()
        val summary = manual.summary().groupBy { it.pointId }
        for (point in manual.allPoints()) {
            val anchor = home.addAnchor(room.id, x + point.x, z + point.z) ?: continue
            val values = summary[point.id].orEmpty().associate { it.bssid to it.median }
            val ssids = summary[point.id].orEmpty().associate { it.bssid to it.ssid }
            if (values.size >= 3) home.addSweep(anchor.id,
                RfSweep(System.currentTimeMillis(), values, ssids))
        }
        selectedRoomId = room.id
        notice = "Nachtmessung übernommen. Mediane sind nur eine zusammengefasste Referenz."
        render()
    }

    private fun automationPage() {
        val overview = card("AUTOMATION · SICHERER TESTMODUS",
            "Raumwechsel erzeugen ausschließlich lokale Simulationseinträge. " +
                "Echte Alexa-Schaltbefehle sind in dieser APK deaktiviert.")
        add(overview, text("Simulation: " +
            if (home.simulationEnabled) "AKTIV" else "PAUSIERT", 13f,
            if (home.simulationEnabled) green else orange, true), 9)
        add(overview, action(if (home.simulationEnabled)
            "Simulation pausieren" else "Simulation starten", true) {
            home.setSimulation(!home.simulationEnabled)
            automation.reset()
            render()
        }, 9)
        add(content, overview, bottom = 12)
        val rules = card("RAUMREGELN")
        if (home.rooms.isEmpty()) add(rules, text("Zuerst Räume anlegen."))
        home.rooms.forEach { room ->
            val rule = home.ruleFor(room.id)
            add(rules, text(room.name + " · " +
                if (rule?.enabled == true) "Testregel aktiv" else "Keine aktive Regel",
                12.6f, white, true), 11)
            add(rules, text(rule?.action?.ifBlank { "Noch keine Aktion" } ?: "Noch keine Aktion",
                11.7f, quiet), 3)
            add(rules, action("Regel bearbeiten") { ruleDialog(room) }, 6)
        }
        add(content, rules, bottom = 12)
        val journal = card("EREIGNISPROTOKOLL")
        if (home.events.isEmpty()) add(journal, text("Noch keine simulierten Aktionen.", 12f))
        else home.events.asReversed().take(14).forEach {
            val t = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMAN).format(Date(it.atMs))
            add(journal, text(t + " · " + it.room + "\n" + it.detail, 11.8f), 8)
        }
        add(content, journal, bottom = 12)
        val alexa = card("ALEXA · INTEGRATIONSSTATUS",
            "Alexa-Smart-Home-Skill benötigt ein Developer-Konto, OAuth-Account-Linking " +
                "und ein sicher betriebenes Backend. Derzeit NICHT verbunden.")
        add(alexa, text("Der spätere Alexa-Adapter stellt virtuelle Raumsensoren bereit. " +
            "Die tatsächliche Freischaltung für de-DE und Routine-Auslöser muss im " +
            "Alexa-Developer-Konto getestet werden.", 11.7f, quiet), 7)
        add(alexa, text("Vorerst werden keine Lampen, Steckdosen oder Cloud-Dienste angesprochen.",
            11.7f, orange), 8)
        add(content, alexa, bottom = 12)
        note("Nur frische, mehrdeutigkeitssichere Raumzuordnungen dürfen Aktionen vorschlagen. " +
            "Kein Auto-Off bei fehlenden oder veralteten Messwerten.")
    }

    private fun ruleDialog(room: HomeRoom) {
        val old = home.ruleFor(room.id)
        val outer = stack().apply { setPadding(dp(19), dp(8), dp(19), dp(4)) }
        add(outer, text("Simulierter Aktionsname", 12f))
        val name = EditText(this).apply {
            setText(old?.action ?: "Licht " + room.name + " EIN")
            setTextColor(white)
            setSingleLine(true)
        }
        add(outer, name, 6)
        add(outer, text("Mindestaufenthalt in Sekunden (3–120)", 11.5f), 12)
        val delay = EditText(this).apply {
            setText((old?.enterSeconds ?: 8).toString())
            setTextColor(white)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        add(outer, delay, 6)
        AlertDialog.Builder(this).setTitle("Regel für " + room.name)
            .setView(outer).setNegativeButton("Abbrechen", null)
            .setNeutralButton("Deaktivieren") { _, _ ->
                home.saveRule(room.id, name.text.toString(), false,
                    delay.text.toString().toIntOrNull() ?: 8)
                automation.reset(); render()
            }
            .setPositiveButton("Testregel aktivieren") { _, _ ->
                home.saveRule(room.id, name.text.toString(), true,
                    delay.text.toString().toIntOrNull() ?: 8)
                automation.reset(); render()
            }.show()
    }

    private fun chooseRoom(action: (String) -> Unit) {
        if (home.rooms.isEmpty()) { toast("Zuerst Räume einrichten."); return }
        AlertDialog.Builder(this).setTitle("Raum auswählen")
            .setItems(home.rooms.map { it.name }.toTypedArray()) { _, index ->
                action(home.rooms[index].id)
            }.show()
    }

    private fun labPage() {
        val box = card("RF LAB · EXPERIMENTE",
            "Die Forschungsfunktionen bleiben bewusst vom Produktivsystem getrennt.")
        add(box, text("Wi-Fi-Funkfeldkarte · 3D-ARCore-Trajektorie · optisches Depth · " +
            "Mesh-Roaming · WLAN-RTT · UWB-Diagnose · Indoor-Fingerprints", 12.5f))
        add(box, action("Field Studio v0.3.1 öffnen", true) {
            collecting = false; tracking = false
            scheduleScans()
            startActivity(Intent(this, MainActivity::class.java))
        }, 12)
        add(content, box, bottom = 12)
        val future = card("PHYSICS LAB",
            "Magnetfeld und aktive Akustikmessung sind als Experimente verfügbar. " +
                "Adaptive Messplanung und Sensorfusion folgen.")
        add(future, action("Magnetic Lab · Live-3D-Magnetometer", true) {
            startActivity(Intent(this, MagneticLabActivity::class.java))
        }, 9)
        add(future, action("Acoustic Lab · Echo-Chirp", false) {
            startActivity(Intent(this, AcousticLabActivity::class.java))
        }, 8)
        add(future, text("Magnetik liefert echte X/Y/Z-Werte; Akustik liefert ein " +
            "qualitatives Echo-Profil. Eine metrisch kalibrierte 3D-Rekonstruktion " +
            "dieser beiden Sensoren ist noch nicht enthalten.", 12.3f, quiet), 9)
        add(content, future)
    }

    private fun diagnosticsPage() {
        val box = card("EXPERTEN & BESTEHENDE FUNKTIONEN",
            "Die alte Diagnoseansicht bleibt vollständig erhalten.")
        add(box, action("Klassische Diagnose öffnen", true) {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }, 9)
        add(box, action("Field Studio öffnen") {
            startActivity(Intent(this, MainActivity::class.java))
        }, 8)
        add(content, box, bottom = 12)
        val exports = card("DATEN & SICHERUNG")
        add(exports, text("Wohnungsmodell, Geräte, RSSI-Referenzen und lokale Testprotokolle " +
            "werden im privaten App-Speicher abgelegt.", 12.1f))
        add(exports, actions(
            action("Backup exportieren", true) { exportHome() },
            action("Backup importieren") { promptHomeImport() }
        ), 11)
        add(exports, text("Wichtig: Vor der Deinstallation eines Debug-Builds exportieren. " +
            "Das Entfernen der App löscht lokale Karten und Gerätezuordnungen.", 11.3f, orange), 10)
        add(content, exports, bottom = 12)
        note("Die App hat keine Alexa-Zugriffsdaten und sendet keine Präsenzinformationen " +
            "oder Gerätepositionen an externe Server.")
    }

    private fun promptHomeImport() {
        AlertDialog.Builder(this).setTitle("Wohnungsdaten wiederherstellen?")
            .setMessage("Ein importiertes Backup ersetzt die aktuelle Wohnungskarte, " +
                "alle Messpunkte, Geräte und Automationsregeln. " +
                "Bitte vorher die aktuelle Karte exportieren.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("JSON auswählen") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }, homeImportRequest)
            }.show()
    }

    private fun importCsv(uri: android.net.Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: error("CSV nicht lesbar")
            val bytes = stream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > 4_000_000) error("CSV größer als 4 MB")
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
            val result = SpatialManualCsvImporter().import(home,
                String(bytes, Charsets.UTF_8))
            selectedRoomId = home.rooms.lastOrNull()?.id
            selectedAnchorId = null
            collecting = false
            lastEstimate = null
            automation.reset()
            notice = result.points.toString() + " Punkte und " +
                result.bssidReadings + " BSSID-Mediane importiert."
            render()
            toast(notice)
        } catch (e: Exception) {
            toast("CSV-Import: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun importHome(uri: android.net.Uri) {
        try {
            val stream = contentResolver.openInputStream(uri) ?: error("Datei nicht lesbar")
            val bytes = stream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16_384)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buffer.size() + n > 6_000_000) error("Backup größer als 6 MB")
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            val ok = home.restoreJson(String(bytes, Charsets.UTF_8))
            if (ok) {
                selectedRoomId = home.rooms.firstOrNull()?.id
                selectedAnchorId = null
                collecting = false
                lastEstimate = null
                automation.reset()
                scheduleScans()
                toast("Wohnungskarte erfolgreich wiederhergestellt.")
                render()
            } else toast("Ungültiges Backup. Vorhandene Daten wurden beibehalten.")
        } catch (e: Exception) {
            toast("Import fehlgeschlagen: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun exportHome() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "rf_lab_spatial_home.json")
        }, homeExportRequest)
    }

    @Deprecated("Activity result API retained for compatibility with existing app")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK) return
        if (code == homeImportRequest) {
            data?.data?.let { importHome(it) }
            return
        }
        if (code == csvImportRequest) {
            data?.data?.let { importCsv(it) }
            return
        }
        if (code != homeExportRequest) return
        try {
            val uri = data?.data ?: return
            contentResolver.openOutputStream(uri)?.use {
                it.write(home.exportJson().toByteArray(Charsets.UTF_8))
            } ?: error("Kein Schreibzugriff")
            toast("Spatial-Home-Modell exportiert.")
        } catch (e: Exception) { toast("Exportfehler: " + e.javaClass.simpleName) }
    }

    private fun hasWifiPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requireWifi(ready: () -> Unit) {
        if (!hasWifiPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                permissionRequest)
            notice = "Für WLAN-Scans präzisen Standort erlauben."
            refreshHeader()
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!lm.isLocationEnabled) {
            toast("Bitte Standortdienste in Android einschalten.")
            notice = "Standortdienste ausgeschaltet."
            return
        }
        ready()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>,
                                            results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == permissionRequest) {
            if (hasWifiPermission()) {
                notice = "Standort erlaubt. Nun WLAN-Scan starten."
                render()
            } else toast("Ohne Standortberechtigung sind WLAN-Fingerprints nicht verfügbar.")
        }
    }

    private fun scheduleScans() {
        handler.removeCallbacks(scanTask)
        if (tracking || collecting) handler.post(scanTask)
    }

    private fun requestScan() {
        if (!hasWifiPermission()) { notice = "Standortberechtigung fehlt."; return }
        try {
            val ok = wifi.startScan()
            if (ok) acceptedRequests++ else rejectedRequests++
            notice = if (ok) "WLAN-Scan angefordert; Ergebnisse können verzögert sein."
                else "Scan abgewiesen bzw. gedrosselt. Vorhandene Scans werden nicht neu gemessen."
            refreshHeader()
        } catch (e: Exception) {
            notice = "WLAN-Scan nicht verfügbar: " + e.javaClass.simpleName
            refreshHeader()
        }
    }

    private fun consumeScan(fresh: Boolean) {
        if (!hasWifiPermission()) return
        if (!fresh) {
            cachedCount++
            notice = "WLAN meldet gecachte Ergebnisse; nicht für Lokalisierung verwendet."
            refreshHeader()
            return
        }
        try {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val hits = linkedMapOf<String, Int>()
            val ssids = linkedMapOf<String, String>()
            var mostRecentNs = 0L
            for (ap in wifi.scanResults) {
                val bssid = ap.BSSID ?: continue
                val seen = ap.timestamp * 1000L
                if (seen <= 0L || abs(nowNs - seen) > 2_500_000_000L ||
                    ap.level !in -110..-1 || bssid.isBlank() ||
                    bssid == "02:00:00:00:00:00") continue
                hits[bssid] = ap.level
                ssids[bssid] = ap.SSID ?: ""
                if (seen > mostRecentNs) mostRecentNs = seen
            }
            if (mostRecentNs <= lastScanSeenNs || hits.isEmpty()) {
                cachedCount++
                notice = "Nur alte AP-Zeitstempel; Messung verworfen."
                refreshHeader()
                return
            }
            lastScanSeenNs = mostRecentNs
            freshCount++
            lastFreshMs = System.currentTimeMillis()
            val sweep = RfSweep(lastFreshMs, hits, ssids)
            lastSweep = sweep
            val anchorId = selectedAnchorId
            if (collecting && anchorId != null) {
                if (home.addSweep(anchorId, sweep)) {
                    notice = hits.size.toString() + " BSSIDs an Messpunkt gespeichert."
                } else notice = "Zu wenige APs; mindestens drei BSSIDs erforderlich."
            } else notice = hits.size.toString() + " frische BSSIDs für Raumlokalisierung."
            lastEstimate = locator.estimate(home, sweep)
            automation.onFreshEstimate(lastEstimate!!)?.let { notice = it }
            render()
        } catch (e: Exception) {
            notice = "WLAN-Ergebnisse: " + e.javaClass.simpleName
            refreshHeader()
        }
    }
}
