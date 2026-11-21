# RF Lab v1.3 — Embedded OpenVINS (research preview)

## What has changed
RF Lab offers a tracking selector with three modes: OpenVINS (native MSCKF
camera + IMU), ARCore (legacy depth-backed room scanner), and Camera2
(photo acquisition with offline processing). The original, pinned
goldbattle/open_vins_mobile engine is now compiled as an Android library
within the **same RF Lab APK**, under RF Lab's compileSdk 36 / targetSdk 35.
No other app has to be installed for new native VIO recordings.

OpenVINS retains its own native Camera2 acquisition and UI while active; it
does not run simultaneously with ARCore. The native recording stays in RF
Lab's private external files sessions directory. Gallery -> "Interne
OpenVINS-Aufnahme übernehmen" imports the most recent finished native
recording without using the Android document picker. Legacy ZIP import,
offline Depth Anything 3 and native VIO diagnostics remain available.

CRITICAL: OpenVINS currently produces a genuine camera path, but DOES NOT
feed the legacy ARCore Depth RoomScanStore online. A colorized spatial home
map / 3D surface from OpenVINS + DA3 has not yet been completed. The VIO
trajectory does not constitute a calibrated metric room survey.

## Development and build
On a fresh checkout, first run:

  python3 tools/bootstrap_embedded_openvins.py

Then build with Java 17, Android SDK 36, NDK 28.2.13676358,
CMake 3.18.1, Gradle 8.13:

  gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug

GitHub CI runs the same bootstrap and builds the debug APK. OpenVINS is
pinned to upstream commit f0c465695c67c0eac786f4edf83a68109a6a58a9,
including its native core submodule, and includes Pixel-scoped recording,
camera/IMU seed and live initialized-pose patches. It is NOT a dummy
tracker or a rebranded ARCore wrapper.

The native OpenVINS demo is converted to an Android library and its
separate launcher/storage permissions are stripped. The RF Lab manifest
registers com.openvins.android.MainActivity internally. Its original JNI
package path is preserved. To keep the OpenCV native Mat ABI coherent,
the experiment replaces the host's Maven OpenCV 4.14.0 with upstream
OpenCV 4.5.1 shared by both modules. Build and regression tests must pass
before any user install.

## GPLv3 license and publication
OpenVINS Mobile is GPLv3. When directly linked into a single RF Lab APK,
the combined distributed work must satisfy GPLv3, including providing
its complete corresponding source code, reproducible build modifications
and notices to recipients. The private RF Lab repository cannot be
assumed to fulfill that requirement for outsiders. Do not publish/share
this combined APK externally before deciding to release RF Lab's
corresponding source under a compliant license and checking dependencies.
Separate-package prior research remains an alternative if that is not
acceptable.

## Tracking quality and limitations
Android Camera2 camera intrinsics, camera/IMU extrinsics and timing are
metadata-based calibration SEEDS only, never physically calibrated on
Pixel 10 Pro XL. OpenVINS does not do global loop-closure optimization.
Depth Anything 3 Small predicts relative depth and currently does not
make an automatically scaled/fused metric room mesh.

### Field verification
1. Install the new integrated RF Lab APK (no standalone OpenVINS APK).
2. Select Tracking -> OpenVINS in the RF Lab 3D scan setup.
3. Start the native engine; move with parallax until it initializes.
4. Record a measured 30-60s circuit; stop the recording, then leave VIO.
5. Gallery -> Interne OpenVINS-Aufnahme übernehmen.
6. Check pose/photos time association and gaps; run offline DA3.
7. Compare with a taped-distance room route and legacy ARCore.

## Native runtime ABI and 16 KiB verification

The pinned Android NDK r28c (28.2.13676358) builds both embedded OpenVINS
and the required rebuilt Boost 1.72 libraries. OpenVINS CMake opts into
`ANDROID_STL=c++_shared` so all native modules use a single dynamically
packaged libc++ runtime. NDK r28c provides 16 KiB-aligned libc++ shared
objects; Android Gradle Plugin packages the runtime. CI checks every ARM64
PT_LOAD alignment, ZIP alignment and the *actual* ELF DT_NEEDED names.
The app still requires a physical Pixel test for initialized VIO and camera
clock accuracy; build success cannot prove visual-inertial localization.
