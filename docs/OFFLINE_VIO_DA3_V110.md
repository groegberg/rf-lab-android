# RF Lab v1.1.0-dev — offline visual/gyro odometry + Depth Anything 3

**Experimental! Device-native processing, no PC, cloud, root or server.**

RF Lab now records additional camera frames alongside the manually selected
full-resolution Camera2 photos. A user-triggered offline pipeline estimates
relative camera motion with genuine feature-based epipolar geometry, reads the
independent gyro stream, and runs the already bundled DA3-Small model on the
high-resolution photographs. Outputs are saved to the same local session.

## Architecture and reality check

1. **Acquisition:** Camera2 640x480 YUV -> motion frames at roughly 10 Hz
   downsampled to 320x240 grayscale and compressed in motion_*.rfvo files.
   Strict limits: finite background queue, 3600 frames, 200 MB per recording
   segment. This runs in parallel to the existing manual/auto JPEG capture.
2. **Synchronized metadata:** Original image sensor timestamps recorded for
   both motion frames and JPEG stills (same camera device). The full-rate
   calibrated/uncalibrated IMU CSVs and Camera2 lens intrinsics/distortion,
   lens-to-device quaternion, lens pose reference and clock source are stored
   locally. If Camera2 does not report REALTIME, we do **not** fuse its
   timestamps with the device IMU.
3. **Optical VO:** OpenCV 4.14 Android ORB keypoints and descriptors,
   nearest-neighbour ratio matching, essential-matrix RANSAC, recoverPose
   cheirality test and minimum-inlier/parallax rejection. Each accepted pair
   estimates a relative orientation and a **unit-length translation direction**.
   Poses are composed into an *unscaled* trajectory; reject low-feature and
   geometrically degenerate steps. Discover potential loop matches, but
   do **not** apply an invented global loop closure.
4. **Visual-inertial rotation prior (conditional):** When and ONLY when
   the Camera2 timebase is REALTIME, the Android lens pose quaternion/reference
   is valid and optical rotation agrees with integrated gyro rotation
   within 9 degrees, the gyro rotation is accepted as the relative rotation
   prior. Optical translation is still only a direction. When the
   calibration is missing or inconsistent, remain optical-only. No inertial
   translation or accelerometer double-integration is claimed.
5. **DA3-Small offline batch:** The real bundled LiteRT model processes up
   to 24 selected high-resolution photos on-device, sequentially (GPU then CPU
   fallback). Each output stores an image preview plus dense
   504x896 **relative** depth map quantized to uint16 with local min/max
   metadata. These depth estimates do NOT become metres by averaging them.
6. **Timestamp association:** Each JPEG is matched to the closest offline
   optical pose within 170 ms. Missing pairs are represented as missing,
   not guessed. The run writes relative_trajectory.json, manifest.json,
   depth_*.u16 and depth_*.jpg into offline_*/ inside the capture session.
7. **In-app UI:** Photo gallery -> "Offline: Optische Posen + Depth Anything 3";
   a local relative camera path and last AI depth map are shown, together with
   matched poses, valid/rejected steps and inertial rotations used.
   The user-triggered session ZIP export includes all camera frames,
   camera calibration, complete IMU CSV and offline results.

## Explicit limitations — why this is NOT yet metric VIO/SLAM

- The essential matrix provides relative translation **direction but no
  distance**. Each step is represented with unit length, so the path is in
  arbitrary relative units; it is unsuitable for measuring a room.
- Gyro rotation integration is a conditional prior, *not* a calibrated
  full VIO estimator with accelerometer bias, preintegration, velocity and
  gravity initialization. Real calibration still needs testing.
- No landmark bundle adjustment, keyframe graph optimization, loop-closure
  correction, pose graph relocalization or global metric scale.
- Current Android camera intrinsics are rescaled from sensor metadata to the
  downsampled YUV stream; field verification of Camera2 crop/distortion and
  orientation is still essential.
- Depth Anything is the **single-image** DA3 Small LiteRT conversion and
  produces relative depth. The generated output is a **pose-associated
  depth dataset**, not a global TSDF/surfel mesh.
- Plain walls, insufficient parallax, tracking loss and rotation-only
  recordings may cause blank or drifting trajectories. This is expected and
  documented, rather than claiming success.
- New OpenCV Android dependency is Apache 2.0; no ORB-SLAM3 GPL code is
  included or re-licensed.

## Test on Pixel 10 Pro XL

1. Back up existing RF/Smart Home data and any previous RGB-D ZIP if a
   differently signed debug APK requires reinstalling.
2. Open 3D-SCAN -> Camera2 -> manually record 15-25 photos while walking
   sideways around a high-texture object. Keep capture running for at
   least 15 seconds. Avoid pure rotation; use genuine parallax.
3. Open Fotos -> Offline: Optische Posen + Depth Anything 3.
4. Inspect the line trajectory; check accepted/rejected ratios and actual
   depth preview. A wide, unstructured room may fail until the data quality
   is adequate — never invent camera poses.
5. Check the IMU accepted count. No IMU rotation priors when the camera
   metadata is UNKNOWN or lens pose is missing.
6. Export the capture session ZIP and ensure motion_*.rfvo, imu_*.csv,
   camera.json, original JPEGs and offline_*/ outputs are present.
7. Compare repeated passes around an object; look for drift and any
   uncorrected loop closing. **Do not use the arbitrary path as metres.**

## Subsequent research milestone

- Camera/IMU extrinsic and time offset validation with field datasets.
- A native full-fledged VIO/SLAM optimizer: keyframe/landmark graph,
  IMU bias and velocity states, preintegration, bundle adjustment,
  relocalization and global loop-closure optimization.
- Recover a trustworthy metric scale (physical calibration/known distance,
  stereo baseline or well-calibrated inertial fusion).
- Convert DA3 depth to the same calibrated metric scale and verify
  geometric consistency across registered frames.
- Fuse into a true mesh (TSDF/surfels) and optionally use Gaussian
  Splatting for appearance, not metric accuracy.

References:
- https://docs.opencv.org/4.13.0/javadoc/org/opencv/calib3d/Calib3d.html
- https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP
- https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LENS_POSE_ROTATION
- https://huggingface.co/litert-community/Depth-Anything-3-Small-LiteRT
