#!/usr/bin/env bash
# Alternative to billed GitHub-hosted runners. Linux/WSL2/Android NDK only.
# Never uploads APKs, models, photos, diagnostics, source or credentials.
# GPLv3/OpenVINS: keep the generated debug APK with corresponding source.
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${ANDROID_HOME:-}" == "" && "${ANDROID_SDK_ROOT:-}" != "" ]]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to installed Android SDK first}"
if ! command -v java >/dev/null 2>&1; then
  echo "JDK17 is required" >&2
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 required" >&2
  exit 2
fi
gradle_command="${GRADLE_COMMAND:-gradle}"
source_revision="$(git rev-parse HEAD)"
export ORG_GRADLE_PROJECT_rfLabTrackingTest=true
export ORG_GRADLE_PROJECT_rfLabSourceRevision="$source_revision"
if [[ ! -x "$(command -v "$gradle_command" 2>/dev/null || true)" ]]; then
  echo "Gradle 8.13 must be installed and in PATH; export GRADLE_COMMAND if needed." >&2
  exit 2
fi

sdkmanager="$(find "$ANDROID_HOME/cmdline-tools" -type f -name sdkmanager 2>/dev/null |
  sort -V | tail -n 1)"
if [[ -z "$sdkmanager" ]]; then
  echo "Install Android commandline-tools in ANDROID_HOME" >&2
  exit 2
fi
"$sdkmanager" --install "platforms;android-36" "build-tools;35.0.0" \
  "platform-tools" "ndk;28.2.13676358" "cmake;3.22.1"

echo "[1/5] Preparing pinned native OpenVINS (GPLv3)"
python3 tools/bootstrap_embedded_openvins.py
test -f third_party/openvins_mobile/LICENSE
echo "[2/5] 16 KiB ARM64 Boost"
bash tools/rebuild_boost_android_16k.sh
echo "[3/5] Native library + app tests"
"$gradle_command" --stacktrace --no-daemon :app:testDebugUnitTest
if command -v node >/dev/null 2>&1; then
  node integrations/alexa/messageFactory.test.mjs
fi
echo "[4/5] Assemble Android debug APK"
"$gradle_command" --stacktrace --no-daemon :app:assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk
test -s "$apk"
unzip -l "$apk" | grep -F 'lib/arm64-v8a/libnative-lib.so'
unzip -l "$apk" | grep -F 'lib/arm64-v8a/libopencv_java4.so'
unzip -l "$apk" | grep -F 'assets/vio_config/estimator_config.yaml'
python3 tools/check_android_native_dependencies.py "$apk"
python3 tools/check_elf_16k.py "$apk" --strict
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 "$apk"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify "$apk"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk" |
  grep -F "package: name='dev.rflab.scanner.trackingtest' versionCode='26' versionName='1.3.10-qa.1+${ORG_GRADLE_PROJECT_rfLabSourceRevision:0:8}'"

echo "[5/5] Copy verified PRIVATE Pixel APK"
out_dir=private-build
mkdir -p "$out_dir"
out="$out_dir/RF-Lab-v1.3.10-Tracking-Pixel-LOCAL-PRIVATE.apk"
cp "$apk" "$out"
sha256sum "$out" | tee "$out.sha256"
git rev-parse HEAD | tee "$out.source-commit.txt"
echo "VERIFIED LOCAL BUILD: $out"
echo "This file remains private on this machine. No GitHub release is published."
