# RF Lab RGB-D Scanner v0.6.0-dev (development branch)

**First fully on-device development milestone:** denser ARCore Raw + Full
Depth, corrected CPU-camera RGB mapping, confidence-aware 5-cm voxel fusion,
and a live camera-colour / height-colour toggle.

This is still a coloured point cloud, **not yet a textured metric mesh,
AI reconstruction or Gaussian Splatting**. The v0.5.0 Smart Home features
remain available.

[Read implementation status, Pixel field test and known limitations](docs/MOBILE_RGBD_V060.md)

---

# RF Lab Guided Spatial Home v0.5.0

**New beginner-first setup:** Camera + ARCore **live 3D room scan** → mark rooms on the scanned top-down cloud → spatial Wi-Fi, magnetic and acoustic measurements → map APs, smart lamps and plugs → plan safe simulation-only automations.

This version stores a real **sparse ARCore optical Depth point cloud** (height-colored, not phototextured) with tracking path and a stable frame for user-drawn room rectangles. It supports room-level magnetometer and chirp echo fingerprints, optional measured AP BSSID association, and comprehensive JSON home/scan/profile backup. Alexa is still a nonconnected contract prototype and no physical device is switched.

**Start here:** [v0.5.0 Guided Scan & Testing](docs/GUIDED_SCAN_V050.md).

---

# RF Lab Spatial Home v0.4.0

**New default screen: HOME / WOHNUNG / EINMESSEN / AUTOMATION / LAB / EXPERTEN.**

Camera-free room fingerprinting with real Android Wi-Fi RSSI scans, persistent apartment plan, mapped smart-home device aliases, conservative proximity preview, local automation simulator, JSON backup/restore, old v0.3.1 manual-survey CSV migration and experimental Magnetic Lab.

**Alexa is NOT connected**: the offline Smart Home MotionSensor message contract is under `integrations/alexa/`. OAuth account linking, event gateway tokens, a deployed backend and actual Alexa routine support must be verified before any real device can be controlled.

**Read first:** [Spatial Home test and implementation guide](docs/SPATIAL_HOME_V040.md). Previous Field Studio and classic expert diagnostics remain available in LAB and EXPERTEN.

---

# RF Lab Field Studio — Android

RF Lab is a **root-free, offline Android Wi-Fi survey and spatial-measurement app**. It uses public Android APIs and optionally ARCore visual-inertial tracking and Depth. The new user-facing interface groups features into **Karte, Mesh, Raum, Indoor, Experten**; the original diagnostic tools are preserved in a separate screen.

## Build / install

Every push to `main` runs [Build RF Lab APK](.github/workflows/build-apk.yml) and provides a debug APK under **Artifacts → RF-Lab-Pixel10-Debug-APK**. Download the artifact, unzip and install `app-debug.apk` on Android. The APK is a development build, not Play Store production-signed. Debug-key differences between CI runs can require uninstalling an earlier APK; **export measurements before uninstalling because uninstalling removes app data**.

## Features

### 1. Funkfeldkarte — 2D/3D Wi-Fi spatial map

- Start a **Messfahrt**, accept precise-location and camera permissions, and keep the app foregrounded with the camera directed at a feature-rich scene.
- Public Wi-Fi scans and once-per-second connected AP signal measurements are matched to the closest ARCore pose, accepting up to 600 ms difference.
- Choose a specific **BSSID** in the dropdown to show its measured RSSI samples. The 2D map uses distance-limited inverse-distance interpolation **only for a selected BSSID**, only where enough spatially distributed points exist, with blank regions elsewhere.
- The 3D button displays a **rotatable isometric projection of real 3D measurements**, not an RF-derived 3D environment. Pinch to zoom and drag to pan.
- No phase/CSI, IQ or radio reflections are measured.

### 2. Mesh & WLAN analysis

- BSSID and band inventory, RSSI min/mean/max, channel occupancy **as counts of observed APs rather than measured channel utilization**, received scan counts, scan-request acceptance.
- Roaming event listing when the connected BSSID changes.
- **Vorher/Nachher**: load a previous measurement from the on-device local JSONL archive. Compare only the same BSSID across runs; results are not normalized for device orientation or environment.
- Tap an AP to show it on the 2D/3D map.

### 3. Raumscan — ARCore Depth optical geometry

- On ARCore Depth-compatible devices, the survey enables `Config.DepthMode.AUTOMATIC`.
- Approximately once per second it samples the **raw 16-bit depth image and confidence image**, filters invalid or low-confidence points, converts them to 3D world coordinates using the ARCore camera pose and intrinsics, and stores a bounded sparse point cloud.
- `ar_depth_points` JSONL records hold points as `[world_x, world_y, world_z, confidence]` in meters.
- Blue dots in the room view are **optical geometry only**. This is currently a sparse point-cloud preview, not a watertight 3D mesh or room-object classifier.
- On unsupported devices the feature gracefully falls back to AR trajectory-only or WLAN-only measurement.

### 4. Indoor — offline Wi-Fi fingerprint recognition

- Stand at a known location, trigger a fresh WLAN scan, name the place and save the BSSID-to-RSSI fingerprint locally.
- With ≥3 overlapping BSSIDs, the app compares the new scan to all stored anchors using mean-square RSSI differences with missing-AP penalties.
- Shows the best-matching **named known place**, overlap count, and alternatives; it **does not claim metric positioning accuracy**.
- Anchor names and fingerprints persist in private Android app storage across restarts. Uninstalling the app removes these entries. They are not sent to any server.
- Profiles can degrade after router changes, moving between bands or changing phone model.

### 5. Experten / Diagnose

The previous RF Lab UI remains available via **Erweiterte Diagnoseansicht öffnen**, including hardware capabilities, Wi-Fi 7 metadata, MLO, RTT probing, 10-second scan diagnostics, raw IMU and the 9-config ARCore camera sweep. It is intentionally separate from the main measurement workflow.

## Compatibility and privacy

- Minimum Android SDK: 31 (Android 12); ARCore and Depth **are optional**.
- Needs WLAN plus precise location and system location enabled for Wi-Fi scans. ARCore camera permission is only needed for the 3D features.
- No root; no vendor-private APIs; no Internet permission.
- Local raw JSONL exports contain SSIDs/BSSIDs and 3D trajectories. Treat them as location-sensitive.
- Wi-Fi scan throttling and cached results remain Android-dependent; `wifi_scan.updated=false` results are excluded from spatial sampling.
- Each ARCore restart creates a new local coordinate frame: paths from different tracking sessions are **not silently fused**. A future registration step is required to align separate sessions reliably.

## Recording / basic test plan

1. Open RF Lab, start a new measurement, grant permissions. Camera will be shown if ARCore starts; phone can also collect Wi-Fi-only results.
2. Hold the device with enough light and move slowly through at least several meters. Let the first Wi-Fi scan finish. Leave the app in foreground.
3. Stop measurement and inspect the **Karte** tab; select the AP whose signal you want to interpolate. Toggle 2D or 3D.
4. Open **Raum** to inspect the optical point cloud if Depth is supported.
5. Open **Mesh** to review APs and roam events; optionally load a previous measurement for comparison.
6. In **Indoor**, collect named reference fingerprints and retake a fresh scan to identify the nearest known reference.
7. Use **Experten** for low-level diagnostics, or export the JSONL file for external scientific processing.

The project is deliberately honest about limitations: it does **not** identify hidden objects, reconstruct RF reflection fields, or infer material properties from RSSI alone. The optical geometry and radio measurements are separate sources synchronized to a shared *local* pose frame.
