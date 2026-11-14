# RF Lab — Pixel 10 Pro XL

Android research app to probe *public Android APIs* for 2.4/5/6 GHz Wi-Fi, Wi-Fi RTT, UWB capability and timestamped IMU streams. No root required.

## Build

GitHub Actions builds a debug APK on pushes to main, via **Build RF Lab APK**. Open **Actions**, select the newest run and download artifact **RF-Lab-Pixel10-Debug-APK**. On Pixel: allow installing apps from the browser/file manager, then install app-debug.apk.

## Scope and limitations

This is a capability probe, **not** a 3D radar scanner. Standard Android APIs do not expose coherent Wi-Fi CSI/IQ or UWB channel impulse responses. Wi-Fi RTT measures range to compatible cooperating APs (if present); UWB peer ranging is not yet implemented.

Measurements remain local, and user-triggered JSONL export may contain sensitive SSID/BSSID data. The app does not request INTERNET permission. Use only on Wi-Fi networks you own or are authorized to test.

## First experiment

Grant precise location and Nearby Wi-Fi permission; enable system location. Tap hardware check, Wi-Fi scan, optionally Wi-Fi RTT, record a 20–60 second walking session, stop, and export the JSONL file.
