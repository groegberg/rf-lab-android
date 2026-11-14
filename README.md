# RF Lab — Pixel 10 Pro XL

Native Android research application for public Android Wi-Fi, Wi-Fi RTT, UWB capability, IMU and **optional ARCore 6DoF optical pose** capture. No root or vendor firmware modifications.

## Build

Every commit to `main` runs [Build RF Lab APK](.github/workflows/build-apk.yml) on GitHub Actions. Open the latest successful run and download artifact `RF-Lab-Pixel10-Debug-APK`. Unzip and install `app-debug.apk` on Pixel.

## What is and isn't measured

- **Wi-Fi scan:** nearby BSSIDs, frequencies, received signal strength, 802.11 capabilities; scan requests are limited by Android.
- **Wi-Fi connected link:** 1 Hz RSSI and connected BSSID/SSID *when publicly available*. Logs diagnostic entries if the phone is not actually connected to Wi-Fi. Android may redact identifiers.
- **Wi-Fi RTT:** *peer ranging to compatible access points only*. The presence of a Wi-Fi RTT feature does not mean a nearby AP supports ranging.
- **UWB:** hardware capability detection only; no raw channel impulse response and no peer-ranging session.
- **IMU:** raw accelerometer, gyroscope, magnetometer and device fused rotation sensors.
- **ARCore:** camera-based (visual-inertial) position and orientation, opt-in with camera permission and installed Google Play Services for AR; stores camera **poses only, not camera images or optical geometry**.

**No coherent Wi-Fi CSI/IQ, RF radar reflection maps, UWB CIR, material estimates, or RF-derived room geometry are collected in this version.** AR poses are optical tracking used solely to locate the sensor during Wi-Fi measurements.

## Experimental capture

1. Enable precise location and Wi-Fi; connect the Pixel to your home network (not merely turn Wi-Fi scanning on).
2. Grant Nearby Wi-Fi and precise location permissions, run **Hardware prüfen** and **WLAN Scan**.
3. Start **Messfahrt (IMU + WLAN)**; automatic Wi-Fi scan requests occur at roughly 35-second intervals; connected-link RSSI is sampled once per second when available.
4. Select **ARCore 6DoF starten**, grant camera permission, and aim the camera at textured walls / furniture. The camera preview is for navigation and is never saved.
5. Move slowly around the room for at least 60 seconds; keep the application in the foreground. ARCore coordinates are defined relative to the current tracking session and may reset on reinitialization.
6. Stop ARCore, stop the IMU session, export JSONL.

For RF field mapping, align each `wifi_link` and `wifi_scan` sample with nearby `ar_pose` records by the logging monotonic `t_ns`; also preserve native camera and sensor timestamps. The ARCore `frame_t_ns` timebase is not guaranteed to be directly comparable to `elapsedRealtimeNanos`.

## Limitations and privacy

This is still an experimental data collection application, **not a LiDAR replacement or 3D RF scanner**. RSSI cannot yield dense centimetric geometry or dielectric material spectra without independent calibrated RF measurements. Wi-Fi scan entries with `rssi_valid=false` should be ignored for plots; Android occasionally reports implausible `0 dBm` readings.

Everything is stored locally and only exported on user action. Export contains nearby networks' SSIDs/BSSIDs, which can be location-sensitive. The app does not request Internet permission. Test only networks you own or are authorized to measure.
