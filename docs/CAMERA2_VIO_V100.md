# RF Lab 1.0.0-dev — independent Camera2 capture and VIO-ready data

This is a **new research acquisition foundation** for the Pixel 10 Pro XL.
The experimental old ARCore scanner and v0.9 five-method comparison remain
available, but they no longer own the main recording workflow.

**Implemented; all processing on the Android phone (no root/PC/cloud):**

- Direct rear-camera Camera2 preview independent of ARCore Depth / ARCore
  session, native JPEG still capture (capped at 2304x2304).
- User-controlled photo shutter ("FOTO") and opt-in "Auto: AN" mode.
  Automatic photos are gated by optical texture/corner count,
  patch-descriptor overlap, pixel displacement, blur/sharpness proxy and
  gyroscope angular speed. You can always force a manual shot.
- An independent *image-only* Shi-Tomasi-style feature tracker and 64-bit
  binary descriptors on 160x120 YUV preview data (no ARCore depth).
  Live feedback: corner count, matched visual features, blur/movement and
  overlap. **2D tracking only; no 6DoF SLAM pose or loop closure claimed.**
- Each saved JPEG has a sidecar JSON with exact Camera2 SENSOR_TIMESTAMP,
  its REALTIME/UNKNOWN timebase status, sensor orientation, JPEG rotation,
  exposure, ISO, focal length, focus distance, AF/AE states, camera id,
  intrinsics/distortion when available, sensor active array, optical quality
  and a small gyroscope/accelerometer window.
- Full continuous timestamped Android gyro/accelerometer streams
  (calibrated and uncalibrated when supported) in separate imu_*.csv files.
  Raw event timestamps are retained. Never assume Camera2 and Android IMU
  clocks agree when the camera declares UNKNOWN timebase.
- Capture sets stored in filesDir/rflab_photo_sessions_v1/session_*/
  with JPEG+JSON per frame, local gallery, manual delete of poor images,
  explicit session switch and privacy-sensitive ZIP export via SAF.
- On-device photo review using the **genuine bundled DA3-Small LiteRT
  model**, GPU then CPU fallback; the SAME crop is shown for original and
  relative depth, which is explicitly *not metric*.
- Existing independent ARCore Depth methods and RF/Smart Home components
  are not migrated or discarded. No fake identity camera poses are added.

## Test on Pixel 10 Pro XL

1. Back up RF Home state and old RGB-D ZIP first; installing a differently
   signed debug APK could force a data-deleting uninstall.
2. Open RF Lab -> 3D-SCAN -> **Fotos aufnehmen · Camera2**.
3. Photograph the room manually; keep a little overlap between frames
   and move laterally around structured furniture. Take 10-25 images.
4. Enable "Auto: AN" and move sideways. Watch feature count and matches.
   It must not capture repeated images while standing perfectly still or
   rotating too quickly. Disable Auto if it selects poor photographs.
5. Open "Fotos"; inspect thumbnails, delete blurred/duplicate photos.
   Tap a photo to compare its RGB crop with its REAL DA3 relative depth.
6. Export the current series as ZIP. Confirm each image has a JSON, with
   camera timestamps and calibration, plus continuous imu_*.csv segments.
7. Verify timestamp_source is REALTIME or UNKNOWN. UNKNOWN means no
   trustworthy camera/IMU temporal alignment is asserted; it must be
   calibrated before using inertial VIO.
8. Test app resume, gallery return, manual override, 100+ images,
   thermal behaviour and available storage.
9. Revisit old ARCore scanner using the smaller "ARCore-Scan (alt)"
   button; do not silently merge different coordinate systems.

## What is not implemented

- **No independent visual-inertial SLAM backend yet.** The image feature
  tracker produces match count/2D motion as a *quality diagnostic only*.
  This phase captures trustworthy input data for a future native tracker.
- No independent 6-DoF camera trajectories, map points, bundle adjustment,
  loop closure or place relocalization yet.
- The independent Camera2 images currently support DA3 **relative depth**
  review. They do not support ARCore Raw/Full comparison because they are
  acquired by a different camera-session pipeline with no registered poses.
- No global metric depth scale, TSDF fusion, textured room mesh or Gaussian
  Splatting yet. Camera2 calibration fields can be unavailable or require
  undistortion/crop verification.
- The camera's REALTIME timestamp source still needs field verification of
  actual exposure time, camera2 image/result pairing and IMU lag.
- An ORB-SLAM3 port and licensing decision (GPLv3 obligations) have not
  been incorporated into the closed RF Lab app. That is a separate workstream.

## Next engineering milestone

1. Record and inspect a representative 10-25 photo+IMU dataset on Pixel.
2. Calibrate IMU-to-camera rotation, intrinsics/distortion, shutter delay and
   the synchronized timestamp source; measure actual hardware noise/bias.
3. Add a native feature-based visual-inertial tracker with global bundle
   adjustment and loop closure, evaluate independently from ARCore.
4. Run DA3 on multiple registered keyframes, estimate metric scale using
   independent anchors, then build a globally consistent volumetric mesh.
5. Add Gaussian Splatting viewer only after metric geometry passes tape
   / laser reference measurements.

Source for CAMERA/IMU timestamp contract:
https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP
https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#SENSOR_INFO_TIMESTAMP_SOURCE
