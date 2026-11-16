# RF Lab v1.2.1 — Live OpenVINS + offline Depth Anything 3

The experimental architecture uses **two explicitly separate Android apps**:
1. **OpenVINS Mobile GPLv3 research app** for near-real-time Camera2 plus Android IMU, running an actual native monocular MSCKF visual-inertial estimator.
2. **RF Lab** for private ZIP import, native VIO pose time association, offline DA3 Small depth inference and preview.

Both run entirely on Pixel 10 Pro XL without root, PC or cloud processing. Camera/IMU calibration is still NOT validated on this device and no physically accurate room measurements are claimed.

## Live OpenVINS research app

The native engine is built from pinned upstream source at
https://github.com/goldbattle/open_vins_mobile commit
f0c465695c67c0eac786f4edf83a68109a6a58a9 with licensed research patches.

- A live HUD differentiates stopped, initializing and native VIO pose available; all positions are explicitly shown as uncalibrated.
- Starting Record stores a ~10 Hz grayscale Camera2 stream in cam0/, ~1 Hz color RGB JPEGs in rgb/, timestamped calibrated IMU stream in imu0.csv, and native initialized camera poses in vio_camera_trajectory.csv.
- Exact camera/IMU YAML configuration and explicit Pixel accuracy warning are copied to the recording folder.
- The folder button exports the latest stopped session through Android's normal Save ZIP document picker, from private scoped storage. No file sharing without explicit action.

## RF Lab offline processing

- From 3D-SCAN, start the separate installed OpenVINS app through Live VIO testen · OpenVINS.
- From Foto-Galerie, select OpenVINS-Aufnahme importieren (ZIP).
- Imported photos, real native VIO poses, source timestamps, IMU CSV and YAML calibration are stored in the RF Lab private photo session.
- Choose Offline: Optische Posen + Depth Anything 3. The genuine bundled LiteRT Depth Anything 3 Small runs sequentially on up to 24 selected imported color photographs. Each photo is matched only to a genuinely recorded native VIO camera pose within 180 ms.
- The app shows the externally estimated path, number of matched photos and relative DA3 depth images. All untrusted/out-of-range pose associations remain absent, never fabricated.
- Standard RF Lab Camera2 sessions keep the previous optical-only OpenCV fallback.

## Pixel acceptance procedure

1. Back up RF Lab data before updating APKs if Android requires an uninstall.
2. Install the latest RF Lab APK and the standalone OpenVINS GPLv3 research APK.
3. Open RF Lab 3D-SCAN → Live VIO testen · OpenVINS.
4. Tap Play to start OpenVINS. Walk slowly *sideways* around an object with visible texture until the HUD reports initialized VIO.
5. Tap Record. Walk for 20–60 seconds around the space and return near the start.
6. Stop Record, then use the folder icon to save a VIO ZIP using Android's file picker.
7. Open RF Lab → Foto-Galerie → OpenVINS-Aufnahme importieren (ZIP); verify both RGB images and native poses are reported.
8. Start the offline DA3 analysis and check how closely the trajectory returns to the starting area.

## Essential accuracy and license boundaries

- OpenVINS is a genuine visual-inertial MSCKF, not a globally loop-closed SLAM engine. Drift still accumulates without place recognition and global optimization.
- Android camera intrinsics, distortion and reported lens orientation are used as **calibration seeds**, not validated ground truth for this Pixel. IMU biases, gravity, temporal offset and camera coordinate transforms require field tests.
- Native VIO positions are physically metric only when the camera/IMU calibration and inertial initialization are correct. We mark imported coordinates **unvalidated**, not measured metres.
- Depth Anything 3 monocular values are still relative; no global metric mesh or TSDF fusion is claimed.
- The native research APK is subject to GPLv3. It is distributed **separately** from RF Lab, with upstream code reference and modification scripts. No OpenVINS GPL C++ code is linked into RF Lab.
- The two apps cannot own the same physical camera simultaneously. RF Lab imports data after the independent live capture. No Pixel 10 Pro XL hardware result is yet verified.
- Apartment imagery and motion traces are private and should not be publicly uploaded.

Next: camera/IMU calibration, timed dynamic motion validation, cross-run drift/loop metrics, independent loop-closing map optimization, verified scale alignment between DA3 and VIO, then volumetric texture-mesh fusion.

## Native VIO recording diagnostics

RF Lab now reports camera/pose timestamp matches, average native VIO pose
rate, the largest gap between successive native states and explicit warnings
for a clock mismatch, sparse poses or missing photo associations. Each
offline run containing native OpenVINS data writes
`openvins_capture_diagnostics.json` into its local results directory.

The start-to-end offset divided by traversed path length is included solely
as an **unvalidated recording diagnostic**, NOT a drift or loop-closure
accuracy claim: it is only meaningful when the user actually returns to
the starting physical position. No diagnostic replaces a calibrated Pixel
camera-to-IMU transform or a physical ground-truth measurement.
