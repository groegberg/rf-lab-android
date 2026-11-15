# RF Lab v1.2 — replacing unstable visual odometry with live VI-SLAM

Status: technical architecture and feasibility work. **This document is not an
implementation claim.** Current app v1.1 is not a stable metric SLAM engine.

## Root cause: stop treating v1.1 poses as truth

The current \`OfflineVisualOdometry\` consumes only every third frame of an
approximately 10 Hz, 320x240 camera stream (around 3.3 visual pairs per second).
It computes ORB pair correspondences and a 5-point essential-matrix solution,
then **composes one unit-length translation per accepted pair**. This is not
metric odometry and is highly sensitive to parallax, lost matches, wrong
Camera2 crops and IMU time/extrinsic calibration. Its loop match candidates
do not correct the trajectory. Its gyro rotation check does not estimate
accelerometer bias, gravity, velocity, metric translation or long-term drift.
Accordingly: **do not send those poses to room geometry fusion**.

## Selected algorithm / license

### Preferred open-source prototype: visloc-rs / Basalt Rust port
- Upstream: https://github.com/rsasaki0109/visloc-rs
- Pin for first compatibility probe: \`956694b6b46fe096e715e0c195151897fe8e255b\`.
- MIT OR Apache-2.0 (verify LICENSE and dependency notices before shipping).
- \`bindings/wasm/src/vio.rs\`: real **monocular+IMU** VIO, live phone session,
  KLT frontend and Basalt sliding-window state estimator; exposes
  \`PhoneRig\`, \`Session::push_imu\`, \`Session::ready\`,
  \`Session::process\`, \`FrameUpdate.camera_to_world\`, tracking/low-quality/
  watchdog reset states and connected landmark count.
- The demo works on Android Chrome at
  https://rsasaki0109.github.io/visloc-rs/slam/
  with computation locally on the phone; the page itself is served by the
  external site. It does **not** prove RF Lab's native Android integration.
- Mono+IMU mode is explicitly experimental. Strong stereo+IMU EuRoC benchmarks
  must **not** be taken as Pixel mono-camera performance claims.
- Rust to Android arm64 + JNI is a **porting/verification task**, not just
  adding a Gradle dependency.

### Alternatives
- Spectacular AI Camera2 Android wrapper, commercial license; potentially
  fastest production-quality independent native option if license acquired:
  https://spectacularai.github.io/docs/sdk/wrappers/mobile.html
- Kimera-VIO under BSD-2-Clause: capable but sizeable C++/GTSAM/OpenGV
  dependency stack, Android native build and performance unknown.
- ORB-SLAM3 and OpenVINS under GPLv3: do not copy into RF Lab's private app
  without a distribution/license decision; ORB-SLAM3 commercial licensing
  possible separately.
- ML learned features (SuperPoint/LightGlue) are OPTIONAL for place recognition
  and difficult textures after basic VI estimation works. A depth neural net
  cannot replace correct IMU bias integration or time alignment. CUDA-centric
  learned SLAM stacks are not suitable for straightforward Pixel deployment.

## Required real-time pipeline on Pixel 10 Pro XL

Camera2 YUV 480p @ 20-30 fps -> frame timestamp + calibrated image ->
bounded single-owner native VIO queue (Rust JNI) -> live metric camera pose,
velocity, bias, covariance/quality, reset reason -> camera-dominant UI with
compact 3D path. Gyro+accelerometer independent native timestamps at the
*measured* highest sustainable rate, target 100-200+ Hz, temporally
interpolated to camera exposures. No camera callbacks blocked by inference.

Output must have explicit \`INITIALIZING/TRACKING/DEGRADED/RELOCALIZING/LOST\`
and metric \`pose_valid\` semantics. On loss, freeze geometry; do not keep
adding predicted phantom room coordinates. Do not jump coordinate frames
silently after a VIO watchdog restart. Record the reset and map segment.

Camera calibration per real Camera2 output stream: fx,fy,cx,cy, distortion,
active crop, rotation, rolling shutter/exposure midpoint, camera-to-IMU
extrinsics, gravity alignment and temporal offset. REALTIME metadata is
necessary but not sufficient to claim synchronized exposures/IMU; calibrate
and measure offset and noise on the actual phone.

Online VI estimator: windowed states (pose, velocity, gyro and accelerometer
biases, gravity, 3D landmarks) and IMU preintegration, reprojection
residuals and robust optimization. Keyframe relocalization and an asynchronous
loop-closure/pose graph layer need to correct cumulative drift. The first
shipping milestone can use live windowed VIO without loop closure **only**
with clear drift-state indication, then enable loop closing once validated.

Capture pipeline must be upgraded from v1.1: camera frames at ~3.3 Hz reaching
the current offline VO are far too sparse for good handheld monocular VIO.
Do not base a new VIO on just the JPEG shutter events.

## Offline reconstruction stays independent and opt-in

After recording: retain original camera frames and full IMU stream; refine
keyframe graph, optimize loop closures & camera poses, run DA3 Small on selected
RGB images, calibrate its relative depth with metric VIO/physical scale anchors
and robust multiview consistency, fuse into TSDF/surfels, texture surfaces.
Gaussian Splatting is a view-quality layer after metric validation; not a
geometry metric or a replacement for SLAM.

Do NOT reuse v1.1 arbitrary relative unit-step positions for DA3 metric fusion.

## Porting acceptance gates (no premature APK claims)

1. Native **Android arm64** Basalt/visloc-rs library builds from pinned
   source with a documented permissive dependency license inventory.
2. Test the JNI bridge on prerecorded synthetic/EuRoC grayscale+IMU data
   with timestamp order, timebase mismatch and deterministic replay results.
3. Camera2 preview + IMU live tracking **on Pixel hardware** with calibrated
   intrinsics and sensor axis/extrinsic/time-offset validation. Compare the
   same sequence against ARCore metric tracking, not ARCore depth.
4. Verify 20+ camera fps consumed, original IMU sample rate and end-to-end
   pose latency. Thermal/battery, background use, camera interruption, screen
   rotation, loss/relocalization and loop-closing events are measured.
5. Repeat closed path in a real room; compare start/end drift and physical
   scale against tape/laser references. Establish thresholds from hardware
   measurements; do not advertise arbitrary cm accuracy beforehand.
6. Only then switch default live tracking from the current broken unit-step
   VO to the independently validated VIO backend.
7. After live success, implement offline global pose graph/BA and
   DA3-driven textured TSDF mesh. No cloud processing.

## Pragmatic hardware test *right now*

On an Android phone in Chrome, user may try the upstream browser-only live
VIO demo: https://rsasaki0109.github.io/visloc-rs/slam/ . Processing is
locally in the browser, but fetching the site needs network. It is a
feasibility benchmark, NOT an RF Lab APK or an assurance about the native
Pixel build. Start with the phone at rest, then move laterally and close
a small loop. The results help decide whether to invest in the arm64 port.

## References

- visloc-rs source / license / phone mono VIO:
  https://github.com/rsasaki0109/visloc-rs
- Browser Android proof-of-concept:
  https://rsasaki0109.github.io/visloc-rs/slam/
- Spectacular Android commercial option:
  https://spectacularai.github.io/docs/sdk/wrappers/mobile.html
- OpenVINS calibration fundamentals:
  https://docs.openvins.com/gs-calibration.html
