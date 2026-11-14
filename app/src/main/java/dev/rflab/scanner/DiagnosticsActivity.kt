package dev.rflab.scanner

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.MloLink
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Base64
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream

class DiagnosticsActivity : Activity(), SensorEventListener {
    private lateinit var wifi: WifiManager
    private var rtt: WifiRttManager? = null
    private lateinit var sensors: SensorManager
    private lateinit var store: SessionStore
    private lateinit var logView: TextView
    private lateinit var status: TextView
    private lateinit var arPose: ArPoseTracker
    private val history = ArrayDeque<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var latestScan: List<ScanResult> = emptyList()
    private var recording = false
    private var receiverActive = false
    private var fastWifiScans = false
    private val autoScanIntervalMs: Long get() = if (fastWifiScans) 10_000L else 35_000L
    private val permissionCode = 110
    private val exportCode = 120
    private val cameraPermCode = 121
    private var pendingCameraSweep = false
    private var wifiLinkMisses = 0

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.RSSI_CHANGED_ACTION) {
                if (recording) {
                    val updatedRssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -127)
                    store.write("wifi_rssi_event", JSONObject()
                        .put("rssi_dbm", updatedRssi)
                        .put("valid", updatedRssi in -110..-1))
                    logConnectedWifi()
                }
                return
            }
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                showScan(fresh)
            }
        }
    }

    private val linkSampler = object : Runnable {
        override fun run() {
            if (!recording) return
            logConnectedWifi()
            handler.postDelayed(this, 1000L)
        }
    }

    private val autoWifiScanner = object : Runnable {
        override fun run() {
            if (!recording) return
            if (permitted()) startWifiScan()
            else log("Automatischer WLAN-Scan: Berechtigung fehlt")
            handler.postDelayed(this, autoScanIntervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        rtt = getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        store = SessionStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 32, 22, 40)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = "RF LAB · Pixel 10 Pro XL"
            textSize = 24f
        })
        status = TextView(this).apply { text = "Bereit"; textSize = 17f }
        root.addView(status)
        fun button(name: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = name; setOnClickListener { action() } })
        }
        button("1. Berechtigungen freigeben") { requestPerms() }
        button("2. Hardware prüfen") { capabilities() }
        button("3. WLAN Scan (2,4/5/6 GHz)") { ensureSession(); startWifiScan() }
        button("4. WiFi RTT messen") { ensureSession(); measureRtt() }
        button("5. Messfahrt starten (IMU + WLAN)") { startMotion() }
        button("6. Messfahrt stoppen") { stopMotion() }
        button("7. Messpunkt markieren") {
            ensureSession()
            store.write("marker", JSONObject().put("label", "manual marker"))
            log("Messpunkt markiert")
        }
        button("8. JSONL exportieren") { exportJsonl() }
        button("9. ARCore 6DoF starten") { startArTracking() }
        button("10. ARCore stoppen") { if (::arPose.isInitialized) arPose.stop() }
        button("11. Alle ARCore-Kamera-Configs testen (~63s)") { startArConfigTest() }
        button("12. WLAN-Kanalübersicht abfragen") { ensureSession(); requestChannelSurvey() }
        root.addView(Button(this).apply {
            text = "13. WLAN-Schnellscan: AUS (35 s)"
            setOnClickListener {
                fastWifiScans = !fastWifiScans
                text = if (fastWifiScans) "13. WLAN-Schnellscan: AN (10 s)"
                    else "13. WLAN-Schnellscan: AUS (35 s)"
                ensureSession()
                store.write("wifi_scan_mode", JSONObject()
                    .put("requested_interval_ms", autoScanIntervalMs)
                    .put("note", "Android may throttle; each attempt and freshness are separately logged"))
                handler.removeCallbacks(autoWifiScanner)
                if (recording) handler.post(autoWifiScanner)
                log("WLAN-Scanintervall: ${autoScanIntervalMs / 1000} s; Schnellmodus für deaktivierte Android-Scan-Drosselung")
            }
        })
        arPose = ArPoseTracker(this, this, store, report = { message -> runOnUiThread { log(message) } })
        root.addView(arPose, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (260 * resources.displayMetrics.density).toInt()))
        root.addView(TextView(this).apply {
            text = "ARCore zeigt nur das Kamerabild zur Orientierung. Im Export werden ausschließlich die Kamera-Posen protokolliert, keine Bilder."
            textSize = 12f
        })
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION).apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wifiReceiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(wifiReceiver, filter)
            receiverActive = true
        } catch (e: Exception) { log("WLAN Receiver: ${e.message}") }
        log("Nur öffentliche Android-APIs; keine CSI/IQ-Rohdaten und kein Internet-Zugriff.")
        log("Für WLAN-Scan: präzisen Standort erlauben und Standortdienste aktivieren.")
        capabilities()
    }

    private fun log(message: String) {
        history.addLast(message)
        if (history.size > 70) history.removeFirst()
        if (::logView.isInitialized) logView.text = history.joinToString("\n")
    }

    private fun permitted(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED)

    private fun startArTracking() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermCode)
            return
        }
        ensureSession()
        arPose.start()
    }

    private fun startArConfigTest() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingCameraSweep = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), cameraPermCode)
            return
        }
        pendingCameraSweep = false
        ensureSession()
        arPose.startCameraSweep()
    }

    private fun requestPerms() {
        val requests = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requests.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        requestPermissions(requests.toTypedArray(), permissionCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionCode) {
            log(if (permitted()) "Berechtigungen OK" else "Berechtigungen fehlen")
            capabilities()
        } else if (requestCode == cameraPermCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingCameraSweep) startArConfigTest() else startArTracking()
            } else {
                pendingCameraSweep = false
                log("ARCore benötigt Kamera-Berechtigung; WLAN/IMU funktionieren weiterhin.")
            }
        }
    }

    private fun ensureSession() {
        if (!store.isRecording) {
            store.start()
            store.write("capabilities", capabilityPayload())
        }
        status.text = "Messdatei: ${store.currentFile?.name}"
    }

    private fun capabilityPayload(): JSONObject {
        val pm = packageManager
        val record = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("permissions_granted", permitted())
            .put("wifi_24ghz", wifi.is24GHzBandSupported)
            .put("wifi_5ghz", wifi.is5GHzBandSupported)
.put("wifi_6ghz", wifi.is6GHzBandSupported)
            .put("wifi_7_11be", wifi.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE))
            .put("wifi_aware", (getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager)?.isAvailable == true)
            .put("wifi_rtt_available", rtt?.isAvailable == true)
            .put("uwb_feature", pm.hasSystemFeature("android.hardware.uwb"))
            .put("public_csi_or_iq_api", false)
        val inventory = JSONArray()
        for (id in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GAME_ROTATION_VECTOR)) {
            val s = sensors.getDefaultSensor(id)
            inventory.put(JSONObject().put("id", id).put("present", s != null)
                .put("name", s?.name ?: ""))
        }
        record.put("sensors", inventory)
        return record
    }

    private fun capabilities() {
        ensureSession()
        val record = capabilityPayload()
        store.write("capabilities", record)
        log("Hardware: ${Build.MODEL}, Android API ${Build.VERSION.SDK_INT}")
        log("WLAN 2,4=${wifi.is24GHzBandSupported}, 5=${wifi.is5GHzBandSupported}, 6=${wifi.is6GHzBandSupported}")
        log("RTT=${rtt?.isAvailable == true} · UWB-Chip=${record.getBoolean("uwb_feature")}")
        log("Sensoren: ${record.getJSONArray("sensors").length()} geprüft")
    }

    private fun startWifiScan() {
        if (!permitted()) { log("Zuerst Berechtigungen erteilen"); return }
        try {
            val ok = wifi.startScan()
            store.write("wifi_scan_request", JSONObject().put("accepted", ok)
                .put("note", "Acceptance does not guarantee fresh scan results"))
            log(if (ok) "WLAN Scan gestartet" else "Scan gedrosselt/nicht gestartet – Cache wird gelesen")
            if (!ok) showScan(false)
        } catch (e: Exception) { log("WLAN Fehler: ${e.message}") }
    }

    private fun mloLinksToJson(links: List<MloLink>): JSONArray {
        val out = JSONArray()
        for (link in links) {
            out.put(JSONObject()
                .put("id", link.linkId)
                .put("band_code", link.band)
                .put("channel", link.channel)
                .put("state_code", link.state)
                .put("rssi_dbm", if (link.rssi in -110..-1) link.rssi else JSONObject.NULL)
                .put("ap_mac", link.apMacAddress?.toString() ?: ""))
        }
        return out
    }

    private fun beaconElementsToJson(ap: ScanResult): JSONArray {
        val out = JSONArray()
        for (element in ap.informationElements) {
            val buffer = element.bytes.asReadOnlyBuffer()
            val bytes = ByteArray(minOf(buffer.remaining(), 2048))
            buffer.get(bytes)
            out.put(JSONObject()
                .put("id", element.id)
                .put("id_ext", element.idExt)
                .put("bytes_b64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
        }
        return out
    }

    private fun isRtt(ap: ScanResult): Boolean =
        ap.is80211mcResponder() ||
            (Build.VERSION.SDK_INT >= 35 && ap.is80211azNtbResponder())

    private fun showScan(fresh: Boolean) {
        if (!permitted()) return
        try {
            latestScan = wifi.scanResults.sortedBy { it.frequency }
            val aps = JSONArray()
            for (ap in latestScan) {
                aps.put(JSONObject()
                    .put("ssid", ap.SSID)
                    .put("bssid", ap.BSSID)
                    .put("frequency_mhz", ap.frequency)
                    .put("rssi_dbm", ap.level)
                    .put("rssi_valid", ap.level in -110..-1)
                    .put("scan_age_ms", (android.os.SystemClock.elapsedRealtimeNanos() - ap.timestamp * 1000L) / 1_000_000.0)
                    .put("security_types", JSONArray().apply { ap.securityTypes.forEach { put(it) } })
                    .put("channel_width_code", ap.channelWidth)
                    .put("wifi_standard_code", ap.wifiStandard)
                    .put("seen_t_ns", ap.timestamp * 1000L)
                    .put("rtt", isRtt(ap))
                    .put("rtt_mc", ap.is80211mcResponder())
                    .put("rtt_az_ntb", Build.VERSION.SDK_INT >= 35 && ap.is80211azNtbResponder())
                    .put("center_frequency0_mhz", ap.centerFreq0)
                    .put("center_frequency1_mhz", ap.centerFreq1)
                    .put("mlo_ap_mac", ap.apMldMacAddress?.toString() ?: "")
                    .put("mlo_link_id", ap.apMloLinkId)
                    .put("mlo_affiliated_links", mloLinksToJson(ap.affiliatedMloLinks))
                    .put("beacon_ies", beaconElementsToJson(ap)))
            }
            store.write("wifi_scan", JSONObject().put("updated", fresh).put("aps", aps))
            requestChannelSurvey()
            log("WLAN: ${latestScan.size} APs, ${latestScan.count { isRtt(it) }} RTT-fähig")
            for (ap in latestScan.take(10)) log("${ap.SSID} · ${ap.frequency} MHz · ${ap.level} dBm")
        } catch (e: Exception) { log("ScanResult Fehler: ${e.message}") }
    }

    private fun requestChannelSurvey() {
        if (Build.VERSION.SDK_INT < 34 || !permitted()) return
        try {
            wifi.getChannelData(mainExecutor) { channels ->
                val rows = JSONArray()
                for (bundle in channels) {
                    rows.put(JSONObject()
                        .put("frequency_mhz", bundle.getInt(WifiManager.CHANNEL_DATA_KEY_FREQUENCY_MHZ))
                        .put("ap_count", bundle.getInt(WifiManager.CHANNEL_DATA_KEY_NUM_AP)))
                }
                store.write("wifi_channel_ap_counts", JSONObject()
                    .put("channels", rows)
                    .put("source", "most_recent_scan")
                    .put("not_channel_busy_fraction", true))
                log("WLAN-Kanalübersicht: ${rows.length()} Frequenzen")
            }
        } catch (e: Exception) {
            store.write("wifi_channel_survey_error", JSONObject()
                .put("type", e.javaClass.simpleName).put("message", e.message ?: ""))
            log("Kanalübersicht nicht verfügbar: ${e.javaClass.simpleName}")
        }
    }

    private fun measureRtt() {
        if (!permitted()) { log("Berechtigungen fehlen"); return }
        val manager = rtt
        if (manager?.isAvailable != true) { log("RTT derzeit nicht verfügbar"); return }
        val peers = latestScan.filter { isRtt(it) }.take(RangingRequest.getMaxPeers())
        if (peers.isEmpty()) { log("Keine RTT-fähigen APs gefunden"); return }
        try {
            val request = RangingRequest.Builder().addAccessPoints(peers).build()
            manager.startRanging(request, mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    store.write("rtt_failure", JSONObject().put("code", code))
                    log("RTT Fehlercode: $code")
                }
                override fun onRangingResults(results: List<RangingResult>) {
                    val rows = JSONArray()
                    for (item in results) {
                        val row = JSONObject()
                            .put("bssid", item.macAddress?.toString() ?: "")
                            .put("status", item.status)
                        if (item.status == RangingResult.STATUS_SUCCESS) {
                            row.put("distance_mm", item.distanceMm)
                                .put("distance_std_mm", item.distanceStdDevMm)
                                .put("rssi_dbm", item.rssi)
                                .put("ranging_t_ms", item.rangingTimestampMillis)
                                .put("attempted_measurements", item.numAttemptedMeasurements)
                                .put("successful_measurements", item.numSuccessfulMeasurements)
                            log("RTT ${item.macAddress}: ${item.distanceMm / 1000.0} m")
                        } else log("RTT ${item.macAddress}: Status ${item.status}")
                        rows.put(row)
                    }
                    store.write("rtt_results", JSONObject().put("results", rows))
                }
            })
        } catch (e: Exception) { log("RTT-Startfehler: ${e.message}") }
    }

    private fun startMotion() {
        if (recording) return
        ensureSession()
        recording = true
        var count = 0
        for (id in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GAME_ROTATION_VECTOR)) {
            val sensor = sensors.getDefaultSensor(id)
            if (sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) count++
        }
        store.write("motion_start", JSONObject()
            .put("sensor_count", count)
            .put("auto_wifi_scan_interval_ms", autoScanIntervalMs))
        handler.post(linkSampler)
        handler.post(autoWifiScanner)
        status.text = "Messfahrt läuft: IMU + WLAN"
        log("Messfahrt gestartet: $count Sensoren; WLAN-Scan alle ${autoScanIntervalMs / 1000}s (Android-Throttling möglich)")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording) return
        store.write("imu", JSONObject()
            .put("sensor_type", event.sensor.type)
            .put("sensor_t_ns", event.timestamp)
            .put("accuracy", event.accuracy)
            .put("values", SessionStore.floatArray(event.values)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (recording && sensor != null)
            store.write("sensor_accuracy", JSONObject().put("sensor_type", sensor.type).put("accuracy", accuracy))
    }

    private fun logConnectedWifi() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val defaultOnWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            var info: WifiInfo? = if (defaultOnWifi) caps?.transportInfo as? WifiInfo else null
            var source = "active_network"
            // Public but deprecated fallback: useful for diagnosing Android 16/17 WiFiInfo redaction.
            if (info == null || info.frequency <= 0) {
                @Suppress("DEPRECATION")
                val legacy = wifi.connectionInfo
                if (legacy != null && legacy.networkId >= 0 && legacy.frequency > 0) {
                    info = legacy
                    source = "legacy_wifi_manager"
                }
            }
            if (info != null && info.frequency > 0 && info.rssi in -110..-1) {
                wifiLinkMisses = 0
                store.write("wifi_link", JSONObject()
                    .put("source", source)
                    .put("rssi_dbm", info.rssi)
                    .put("bssid", info.bssid ?: "")
                    .put("ssid", info.ssid ?: "")
                    .put("frequency_mhz", info.frequency)
                    .put("tx_speed_mbps", info.txLinkSpeedMbps)
                    .put("rx_speed_mbps", info.rxLinkSpeedMbps)
                    .put("wifi_standard", info.wifiStandard)
                    .put("security_type", info.currentSecurityType)
                    .put("max_supported_tx_mbps", info.maxSupportedTxLinkSpeedMbps)
                    .put("max_supported_rx_mbps", info.maxSupportedRxLinkSpeedMbps)
                    .put("supplicant_state", info.supplicantState.name)
                    .put("ap_mld_mac", info.apMldMacAddress?.toString() ?: "")
                    .put("mlo_link_id", info.apMloLinkId)
                    .put("mlo_associated_links", mloLinksToJson(info.associatedMloLinks))
                    .put("mlo_affiliated_links", mloLinksToJson(info.affiliatedMloLinks)))
            } else {
                wifiLinkMisses++
                if (wifiLinkMisses == 1 || wifiLinkMisses % 10 == 0) {
                    store.write("wifi_link_diagnostic", JSONObject()
                        .put("wifi_enabled", wifi.isWifiEnabled)
                        .put("default_network_wifi", defaultOnWifi)
                        .put("status", "no_connected_wifi_link_or_redacted_info")
                        .put("source", source))
                    if (wifiLinkMisses == 1) log("Kein WLAN-Link-Sample: Bitte tatsächlich mit einem WLAN verbinden.")
                }
            }
        } catch (e: Exception) {
            store.write("wifi_link_error", JSONObject().put("message", e.message ?: ""))
            log("Link-Fehler: ${e.message}")
        }
    }

    private fun stopMotion() {
        if (!recording) return
        recording = false
        sensors.unregisterListener(this)
        handler.removeCallbacks(linkSampler)
        handler.removeCallbacks(autoWifiScanner)
        store.write("motion_stop", JSONObject())
        store.flush()
        status.text = "IMU gestoppt – Session bleibt für Export geöffnet"
        log("IMU gestoppt. WLAN/RTT und Marker können weiterhin in dieselbe Session geschrieben werden.")
    }

    private fun exportJsonl() {
        if (store.currentFile == null) { log("Noch keine Daten"); return }
        store.flush()
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/x-ndjson"
            putExtra(Intent.EXTRA_TITLE, store.currentFile!!.name)
        }
        startActivityForResult(intent, exportCode)
    }

    @Deprecated("Uses Android platform activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val file = store.currentFile ?: return
        try {
            store.flush()
            val output = contentResolver.openOutputStream(uri) ?: error("Export stream unavailable")
            output.use { destination -> FileInputStream(file).use { it.copyTo(destination) } }
            log("Export OK: ${file.name}")
        } catch (e: Exception) { log("Export fehlgeschlagen: ${e.message}") }
    }

    override fun onPause() {
        if (::arPose.isInitialized) arPose.stop()
        super.onPause()
    }

    override fun onDestroy() {
        stopMotion()
        store.stop()
        if (receiverActive) unregisterReceiver(wifiReceiver)
        super.onDestroy()
    }
}
