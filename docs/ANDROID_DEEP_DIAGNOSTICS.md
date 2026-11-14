# RF Lab — camera and networking diagnostics

## RF Lab 0.2.2: all ARCore configurations

1. In a well-lit indoor room, start the app, enable camera permission.
2. Tap **11. Alle ARCore-Kamera-Configs testen (~63s)**.
3. Keep the preview aimed at a textured, well-lit area. A moving camera is not required for the camera benchmark.
4. Wait for `Kameratest abgeschlossen`. Test duration depends on runtime setup overhead; each configuration collects ~7 seconds of frames.
5. Export JSONL, and look for:
   - `ar_camera_config`: entire list of available rear configs, including Camera2 metadata and estimated optical full-sensor FoV.
   - `ar_camera_sweep_start`, `ar_camera_sweep_switch`, `ar_camera_config_test`, `ar_camera_sweep_stop`: actual per-config tests.
   - `ar_camera_intrinsics`: active ARCore CPU-image crop/intrinsics. FOV estimated from image width and fx is **effective crop FOV**, not necessarily the lens full field of view.
   - `ar_error`, `ar_camera_sweep_error`: runtime errors.
6. After the sweep, tap **9. ARCore 6DoF starten** to resume an ordinary trajectory session; a configuration change may reset the AR world-coordinate frame.

NOTE: the nine configurations can share one Camera2 ID. Different CPU/GPU sizes and FPS modes do **not** imply different physical lenses. Camera2 physical IDs and focal lengths are logged where the OS permits it; no vendor-private camera switching is attempted.

## What Android developer options change

- **Wi-Fi scan throttling**: switching throttling **off** may permit more frequent active scans for local testing. It does not provide channel-state information, I/Q samples, or phase.
- **Enable Wi-Fi verbose logging**: increases diagnostic logging; most system Wi-Fi logs are not exposed to ordinary applications. Access via Android Debug Bridge (ADB) from a computer, typically no root needed.
- Other settings such as Bluetooth HCI logging, wireless debugging, and detailed networking diagnostics do not grant privileged RF measurement APIs.

**No root or privileged platform permissions are used by RF Lab.** The app requests no Internet permission and cannot read arbitrary system logs.

## Useful ADB checks from a computer

Install the official Android SDK Platform Tools and activate USB debugging (or secure Wireless debugging). Then:

```bash
adb devices
adb shell dumpsys wifi > wifi_dumpsys.txt
adb shell dumpsys connectivity > connectivity_dumpsys.txt
adb logcat -d -v threadtime > logcat.txt
```

A full bugreport may provide more platform diagnostics:

```bash
adb bugreport rf_lab_bugreport.zip
```

The exact `dumpsys` fields and availability are implementation-dependent. They can contain signal status, connection history, channel/roaming events, and radio firmware statistics; **they do not guarantee CSI or IQ**. Some vendor data are withheld or redacted.

**Privacy warning:** dumpsys, logcat, and bugreports can contain nearby Wi-Fi BSSIDs, network names, device IDs, location-related data, and application logs. Review and redact before sharing. Never paste credentials, full bugreports, or other personal data into a public issue.

## Additional public API logs in RF Lab 0.2.2

- `wifi_scan_request`: whether the OS accepted each scan request.
- `wifi_scan`: per-BSSID RSSI, radio frequency, MLO, information elements, security types, scan age.
- `wifi_channel_ap_counts`: number of nearby APs per frequency from most recent scan; **not spectrum occupancy, measured noise floor, or channel busy percentage**.
- `wifi_link`: connected AP signal, link rates, security type, supported link rates, MLO metadata, when permitted.

For deeper RF measurements, use a compatible device with documented CSI or SDR access. No phone developer toggle can grant a privileged measurement API that the firmware does not expose.
