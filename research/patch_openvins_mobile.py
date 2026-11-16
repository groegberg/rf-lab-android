#!/usr/bin/env python3
"""
Reproduce RF Lab's distinct GPLv3 OpenVINS Android research build.
Invoked inside GitHub Actions after cloning a PINNED upstream revision.
Corresponding source = upstream source + this patch + VioBootstrap.kt.
"""
from pathlib import Path
root = Path("openvins-mobile")
pkg = root/"app/src/main/java/com/openvins/android"
assert (pkg/"MainActivity.kt").is_file(), "Missing upstream Android source"
assets = root/"app/src/main/assets/rflab-config"
assets.mkdir(parents=True, exist_ok=True)
for n in ("estimator_config.yaml", "kalibr_imu_chain.yaml", "kalibr_imucam_chain.yaml"):
    (assets/n).write_bytes((root/"app_device/config"/n).read_bytes())
bootstrap = Path("research/openvins/VioBootstrap.kt")
assert bootstrap.is_file(), "Missing RF Lab corresponding source patch"
(pkg/"VioBootstrap.kt").write_bytes(bootstrap.read_bytes())

activity = pkg/"MainActivity.kt"
s = activity.read_text()
def change(old,new):
    global s
    assert old in s, "Upstream changed: " + old[:90]
    s = s.replace(old,new,1)

change("    private var isRunningOV: Boolean = false",
"""    private var isRunningOV: Boolean = false
    private var vioReady = false
    private var lastAccelSample: FloatArray? = null
    private var lastAccelTimestampNs: Long = 0L
    private var lastImuTimestampNs: Long = 0L""")
change("""                Manifest.permission.CAMERA,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE""",
"""                Manifest.permission.CAMERA""")
change("""        val appRecordFolder =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                .toString() + "/openvins/" """.rstrip(),
"""        val appRecordFolder =
            File(getExternalFilesDir(null) ?: filesDir, "research_recordings")
                .absolutePath + "/" """.rstrip())
change("""        File(privateConfigDir).mkdirs()

        // Config files should be in private directory (pushed by sync script)""",
"""        File(privateConfigDir).mkdirs()
        val boot = VioBootstrap.install(this, File(privateConfigDir))
        vioReady = boot.ready
        Toast.makeText(this,
            if (vioReady) "OpenVINS: vorläufige Android-Sensorkalibrierung"
            else "VIO gesperrt: " + boot.reason,
            Toast.LENGTH_LONG).show()

        // Config files bundled on-device; no ADB or computer needed.""")
change("""        reset.setOnClickListener {
            isRunningOV = if (isRunningOV) {""",
"""        reset.setOnClickListener {
            if (!vioReady) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("VIO-Kalibrierung unvollständig")
                    .setMessage("OpenVINS verwendet keine fremden Kamera-/IMU-Werte. " +
                        "Die Kamera meldet eine fehlende oder unpassende Kalibrierung. " +
                        "Details: " + File(getExternalFilesDir(null),
                            "config/rflab_device_calibration.txt").absolutePath)
                    .setPositiveButton("OK", null).show()
                return@setOnClickListener
            }
            isRunningOV = if (isRunningOV) {""")
start = s.index("    override fun onSensorChanged(event: SensorEvent?) {")
end = s.index("    override fun onAccuracyChanged(",start)
s = s[:start] + """    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.size < 3) return
        val ts = event.timestamp
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Android reuses SensorEvent buffers, never store the event.
                lastAccelSample = event.values.copyOf(3)
                lastAccelTimestampNs = ts
            }
            Sensor.TYPE_GYROSCOPE -> {
                val a = lastAccelSample ?: return
                // Push at gyro rate with most recent accel reading, rather
                // than dropping half the events by pairing both callbacks.
                if (ts <= lastImuTimestampNs ||
                    kotlin.math.abs(ts - lastAccelTimestampNs) > 25_000_000L) return
                lastImuTimestampNs = ts
                processInertialJNI(a[0],a[1],a[2],
                    event.values[0], event.values[1], event.values[2],
                    ts * 1e-9)
            }
        }
    }

""" + s[end:]
activity.write_text(s)
manifest=root/"app/src/main/AndroidManifest.xml"
m=manifest.read_text()
m=m.replace("""    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"/>
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"/>
""","")
m=m.replace('android:label="@string/app_name"',
            'android:label="OpenVINS RF Lab Research"')
manifest.write_text(m)
print("Applied: first-run on-device Camera2 calibration, private storage, no ADB or legacy storage permissions, non-stale IMU samples.")
