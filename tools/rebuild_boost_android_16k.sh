#!/usr/bin/env bash
# Rebuild *only* the linked Boost 1.72 libraries for ARM64/16 KiB Android.
# Old upstream Boost .so files are 4KiB ELF aligned and cannot be shipped on
# true 16KiB page-size devices. Never claim compatibility for those binaries.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
test -n "$SDK" || { echo "ANDROID_HOME is required" >&2; exit 1; }
# Ignore runner-preinstalled ANDROID_NDK_HOME: it may point to NDK r27.
NDK="$SDK/ndk/28.2.13676358"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
test -x "$TOOLCHAIN/aarch64-linux-android31-clang++" || {
    echo "Android NDK 26.3 toolchain missing: $TOOLCHAIN" >&2; exit 1;
}
BUILD_ROOT="$ROOT/third_party/boost-1.72-build"
ARCHIVE="$BUILD_ROOT/boost_1_72_0.tar.bz2"
mkdir -p "$BUILD_ROOT"
curl -fLsS --retry 3 \
  "https://archives.boost.io/release/1.72.0/source/boost_1_72_0.tar.bz2" \
  -o "$ARCHIVE"
echo "59c9b274bc451cf91a9ba1dd2c7fdcaf5d60b1b3aa83f2c9fa143417cc660722  $ARCHIVE" | sha256sum -c -
tar -xjf "$ARCHIVE" -C "$BUILD_ROOT"
SRC="$BUILD_ROOT/boost_1_72_0"
test -f "$SRC/bootstrap.sh"
(
 cd "$SRC"
 ./bootstrap.sh --with-libraries=system,filesystem,thread,chrono,date_time,atomic
 cat > android-user-config.jam <<JAM
using clang : android : "$TOOLCHAIN/aarch64-linux-android31-clang++" ;
JAM
 ./b2 -j3 --user-config=android-user-config.jam \
    toolset=clang-android target-os=android architecture=arm address-model=64 \
    binary-format=elf abi=aapcs threading=multi \
    variant=release link=shared runtime-link=shared \
    --layout=system --stagedir="$BUILD_ROOT/stage" \
    cxxflags="-fPIC -std=c++14 -D__ANDROID_API__=31" \
    linkflags="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
    --with-system --with-filesystem --with-thread --with-chrono --with-date_time --with-atomic stage
)
LIBDIR="$ROOT/third_party/openvins_mobile/thirdparty/boost/libs/arm64-v8a"
test -d "$LIBDIR"
# The OpenVINS native CMake file dynamically imports exactly these five;
# libboost_atomic is copied only if the compiled thread library needs it.
REQUIRED=(system filesystem thread chrono date_time)
for name in "${REQUIRED[@]}"; do
    test -s "$BUILD_ROOT/stage/lib/libboost_$name.so" || {
        echo "Boost ARM64 $name did not build" >&2; exit 1;
    }
done
find "$LIBDIR" -maxdepth 1 -type f -name '*.so' -delete
for name in "${REQUIRED[@]}"; do
  cp "$BUILD_ROOT/stage/lib/libboost_$name.so" "$LIBDIR/"
done
if test -f "$BUILD_ROOT/stage/lib/libboost_atomic.so"; then
  cp "$BUILD_ROOT/stage/lib/libboost_atomic.so" "$LIBDIR/"
fi
# The Android package manager stores only files named *.so. Boost.Build can
# assign versioned DT_SONAME entries like libboost_thread.so.1.72.0; these
# cannot be resolved unless we normalize the dynamic names to the APK names.
# patchelf's page-size option preserves 16 KiB PT_LOAD layout.
if ! command -v patchelf >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq patchelf
fi
for so in "$LIBDIR"/libboost_*.so; do
  name="$(basename "$so")"
  patchelf --page-size 16384 --set-soname "$name" "$so"
  for dep in system filesystem thread chrono date_time atomic; do
    from="libboost_$dep.so.1.72.0"
    to="libboost_$dep.so"
    if readelf -d "$so" | grep -Fq "[$from]"; then
      patchelf --page-size 16384 --replace-needed "$from" "$to" "$so"
    fi
  done
done
# Fail rather than package a library whose dynamic name still points to a
# versioned file absent from the Android APK.
for so in "$LIBDIR"/libboost_*.so; do
  actual="$(patchelf --print-soname "$so")"
  test "$actual" = "$(basename "$so")" || {
    echo "Incorrect Android Boost SONAME in $so: $actual" >&2; exit 1;
  }
  if readelf -d "$so" | grep -E 'NEEDED.*libboost_.*so\.1\.72\.0'; then
    echo "Unresolved versioned Boost dependency in $so" >&2; exit 1
  fi
done
# CMake and APK packaging consume only the binaries from the exact same
# directory; old 4 KiB boost binaries cannot silently leak into the APK.
echo "Rebuilt Boost ARM64 shared objects:"
ls -lh "$LIBDIR"/*.so
