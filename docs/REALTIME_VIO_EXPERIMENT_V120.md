# RF Lab v1.2.0-dev — high-rate VIO input + separate OpenVINS benchmark

## Why
The v1.1 per-pair optical path used unit-length essential-matrix
translations. That path is not metric and grows distorted with every
pair, so no amount of visual smoothing makes it a stable room trajectory.

We now validate two distinct approaches independently:

1. **RF Lab capture (this app)** — high-rate sensor stream
   and quality telemetry, still paired with DA3 Small offline depth.
2. **OpenVINS Mobile (separate GPLv3 research APK)** — a real native
   multisensor VIO sliding-window estimator. It is NOT linked into RF
   Lab; no OpenVINS GPL code is present in this app.

## RF Lab capture improvements

- Camera2 YUV image reader at 640x480, requesting 30 FPS when available.
- Motion stream now targets every camera frame (~30 FPS rather than 10)
  at native 640x480 luma; finite background compression queue with
  fast deflate and explicit backpressure/drop counting.
- Gyro and accelerometer registration moved to a dedicated sensor thread,
  requesting calibrated sensor intervals of 5ms (200 Hz target) and
  optional uncalibrated intervals of 10ms.
- Camera2 callback never performs compression.
- Exact sensor timestamp, exposure, frame duration, rolling shutter
  skew, ISO, focus and crop saved to camera_timing_*.csv. Export includes it.
- Compact camera HUD shows actual camera and gyro cadence, timestamp gaps,
  compression queue drops. Rates are measured, not guaranteed.
- Existing manually controlled photo capture and DA3 offline inference remain.
- HIGH_SAMPLING_RATE_SENSORS permission added.
- No cloud connections, server, root or alterations to RF data.
- Offline OpenCV optical path remains unscaled; no metric VIO is claimed.

## Separate OpenVINS research build

The experiment is assembled from GPLv3 goldbattle/open_vins_mobile at
commit f0c465695c67c0eac786f4edf83a68109a6a58a9 and the
pinned upstream OpenVINS core submodule.
Source build recipe: .github/workflows/probe-openvins-mobile.yml.
Application modifications: research/openvins_pixel_test_patch.py.

The patch:
- Bundles example YAML configurations in the APK (no ADB/PC needed).
- Uses Android scoped app-private recording storage.
- Uses dedicated sensor callbacks and only pairs gyro with a recent
  accelerometer event, never arbitrarily distant timestamps.
- Enables continuous indoor autofocus; removes forced -2 exposure bias.
- Reads available Camera2 intrinsic calibration and approximately
  rescales to the current frame crop.
- Displays a warning: this is NOT Pixel-calibrated.
- Research app is installed separately and does not share RF Lab data.

### Calibration warning
Android Camera2 can expose focal/principal point and lens pose, but
stable VIO needs validated crop/distortion, IMU-to-camera extrinsic,
time offset and sensor biases. Upstream example transform and temporal
shift do NOT describe this Pixel 10 Pro XL. The standalone test cannot
claim accurate metric tracking until field calibration succeeds.

Camera2 REALTIME image timestamps can be compared against Android IMU
timebase; SOURCE_UNKNOWN cannot be assumed comparable.

## Suggested fair phone test

1. Back up RF Lab measurements first.
2. Capture a 20–30 second well-lit room path with lateral parallax.
   Check camera frame rate, gyro rate and dropped-frame counters.
3. Export the private data: verify camera.json, motion_*.rfvo,
   camera_timing_*.csv, imu_*.csv and JPEG/JSON source photos.
4. Install and launch the **separate** OpenVINS research app.
   Press its start/play button. Wait for initialization; translate
   and turn gently so the IMU experiences actual acceleration.
5. Walk a small approximate loop and return to the initial viewpoint.
   Evaluate drift and whether tracking remained initialized.
   The research app is **not yet Pixel-calibrated**.
6. Assess thermal performance and camera display usability.

## Next engineering milestones

- Pixel-specific camera/IMU calibration (checkerboard or AprilTag;
  time offset, intrinsics, distortion, extrinsics, noise).
- Real-time native sliding-window VIO inside RF Lab: inertial
  preintegration, velocity, bias and gravity states, pose covariance.
- Global loop-closure and bundle adjustment to align multiple rooms.
- Offline metric alignment of DA3 depth across registered images,
  TSDF/surfel/photogrammetric geometry fusion.
- Optional Gaussian Splatting for rendering after metric validation.
