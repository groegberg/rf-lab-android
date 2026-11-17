#!/usr/bin/env python3
"""Reproducible GPLv3 OpenVINS Mobile -> embedded RF Lab Android library.

For development only. Distribution of the combined APK requires compliance
with the GPLv3, including the complete corresponding RF Lab+OpenVINS source.
Never copy proprietary source into an upstream public checkout/repository.
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys

PROJECT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = PROJECT / "third_party" / "openvins_mobile"
PIN = "f0c465695c67c0eac786f4edf83a68109a6a58a9"

def run(*args: str, cwd: pathlib.Path = PROJECT) -> None:
    print("+", " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)

def write(path: pathlib.Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")

def main() -> None:
    marker = SOURCE / ".rf_lab_embedded_v1"
    if marker.exists():
        if marker.read_text().strip() != PIN:
            raise RuntimeError("Unexpected OpenVINS source pin")
        print("Pinned embedded OpenVINS source is already prepared")
        return
    if SOURCE.exists():
        raise RuntimeError(
            "Unprepared OpenVINS checkout found. Move it aside before re-running."
        )
    SOURCE.parent.mkdir(parents=True, exist_ok=True)
    run("git", "clone", "--branch", "restructure", "--recurse-submodules",
        "https://github.com/goldbattle/open_vins_mobile.git", str(SOURCE))
    actual = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=SOURCE, text=True
    ).strip()
    if actual != PIN:
        raise RuntimeError("Unpinned upstream revision: " + actual)
    run("git", "submodule", "update", "--init", "--recursive", cwd=SOURCE)
    for name in ("estimator_config.yaml", "kalibr_imu_chain.yaml",
                 "kalibr_imucam_chain.yaml"):
        source_file = SOURCE / "app_device" / "config" / name
        if not source_file.is_file():
            raise FileNotFoundError(source_file)
        destination = SOURCE / "app" / "src" / "main" / "assets" / "vio_config" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, destination)

    # Uses exactly the same native tracker and Pixel safety modifications as
    # the validated standalone GPLv3 research build, never a mock VIO engine.
    run(sys.executable, str(PROJECT / "research" / "openvins_pixel_test_patch.py"),
        str(SOURCE))
    # Before recording and embedding the GPLv3 activity, pin Camera2 lens
    # state (focus, distortion, zoom) and audit ACTUAL capture metadata.
    # Display orientation never alters unrotated ImageReader sensor pixels.
    run(sys.executable, str(PROJECT / "tools" /
        "patch_openvins_camera_calibration_audit.py"), str(SOURCE))
    run(sys.executable, str(PROJECT / "research" / "openvins_record_export_patch.py"),
        str(SOURCE))
    # Export actual online camera intrinsics/extrinsics/time offset with
    # each native pose. Re-using the original YAML for DA3 is incorrect after
    # OpenVINS online calibration has started changing its filter state.
    run(sys.executable, str(PROJECT / "tools" /
        "patch_openvins_online_calibration_export.py"), str(SOURCE))
    # RF Lab's scanner subclasses the real native VIO activity, receiving
    # initialized MSCKF poses via callbacks without a second camera pipeline.
    run(sys.executable, str(PROJECT / "tools" / "patch_embedded_openvins_scan.py"),
        str(SOURCE))
    # Static VIO needs an initial stationary IMU window; expose *actual*
    # native camera/IMU/worker counters and enable stationary ZUPT startup.
    run(sys.executable, str(PROJECT / "tools" /
        "patch_openvins_initialization_diagnostics.py"), str(SOURCE))

    cmake = SOURCE / "app" / "src" / "main" / "cpp" / "CMakeLists.txt"
    c = cmake.read_text(encoding="utf-8")
    begin = c.index("if(ANDROID_ABI STREQUAL armeabi-v7a)")
    end = c.index("# Include OpenCV project", begin)
    c = c[:begin] + "# arm64 NEON is intrinsic; never use -mfpu.\n\n" + c[end:]
    # Native VIO .so must be loadable on both 4KiB and 16KiB Android.
    c += """
# Android NDK r26: explicitly opt in to 16 KiB ELF PT_LOAD alignment.
target_link_options(native-lib PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
"""
    write(cmake, c)

    # The host application controls targetSdk and its camera permission.
    # No standalone launcher, obsolete storage permission or targetSdk 30.
    write(SOURCE / "app" / "src" / "main" / "AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.CAMERA"/>
    <application />
</manifest>
""")

    # Keep upstream Java/Kotlin package com.openvins.android unchanged: its
    # JNI exports are Java_com_openvins_android_* and would break if renamed.
    write(SOURCE / "app" / "build.gradle", """plugins {
    id 'com.android.library'
    id 'org.jetbrains.kotlin.android'
}
android {
    namespace 'com.openvins.android'
    compileSdk 36
    ndkVersion '28.2.13676358'
    defaultConfig {
        minSdk 31
        ndk { abiFilters 'arm64-v8a' }
        externalNativeBuild {
            cmake {
                cppFlags '-std=c++17 -frtti -fexceptions'
                abiFilters 'arm64-v8a'
                arguments '-DOpenCV_DIR=' + rootProject.file('third_party/openvins_mobile/thirdparty/opencv/native').absolutePath,
                    '-DBOOST_DIR=' + rootProject.file('third_party/openvins_mobile/thirdparty/boost').absolutePath,
                    '-DEIGEN3_DIR=' + rootProject.file('third_party/openvins_mobile/thirdparty/eigen3').absolutePath,
                    '-DANDROID_ARM_NEON=ON',
                    '-DANDROID_STL=c++_shared'
            }
        }
    }
    externalNativeBuild {
        cmake { path 'src/main/cpp/CMakeLists.txt' }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
}
dependencies {
    api project(':openvins_opencv')
    implementation project(':openvins_boost')
    implementation 'androidx.core:core-ktx:1.15.0'
    implementation 'androidx.appcompat:appcompat:1.7.0'
    implementation 'com.google.android.material:material:1.12.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.2.0'
}
""")

    # A single OpenCV ABI/version in the APK is required: native VIO Mat
    # pointers and Java Mat objects must use the SAME OpenCV binary.
    # Replaces the host's newer Maven OpenCV for this experiment.
    write(SOURCE / "thirdparty" / "opencv" / "build.gradle", """plugins {
    id 'com.android.library'
}
android {
    namespace 'org.opencv'
    compileSdk 36
    defaultConfig { minSdk 31; ndk { abiFilters 'arm64-v8a' } }
    buildFeatures { buildConfig true; aidl true }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    sourceSets {
        main {
            jniLibs.srcDirs = ['native/libs']
            java.srcDirs = ['java/src']
            aidl.srcDirs = ['java/src']
            res.srcDirs = ['java/res']
            manifest.srcFile 'java/AndroidManifest.xml'
        }
    }
}
""")
    write(SOURCE / "thirdparty" / "boost" / "build.gradle", """plugins {
    id 'com.android.library'
}
android {
    namespace 'com.thirdparty.boost'
    compileSdk 36
    defaultConfig { minSdk 31; ndk { abiFilters 'arm64-v8a' } }
    sourceSets { main { jniLibs.srcDirs = ['libs'] } }
}
""")
    # These prebuilt Boost .so files are dependencies of the native OpenVINS
    # engine; they must be in the same APK, not just on the linker search path.
    for filename in ("libboost_chrono.so", "libboost_system.so",
                     "libboost_filesystem.so", "libboost_thread.so",
                     "libboost_date_time.so", "libboost_atomic.so"):
        if not (SOURCE / "thirdparty" / "boost" / "libs" /
                "arm64-v8a" / filename).is_file():
            raise FileNotFoundError(filename)
    lib = SOURCE / "app" / "src" / "main" / "cpp" / "native-lib.cpp"
    if "Java_com_openvins_android_MainActivity" not in lib.read_text(encoding="utf-8"):
        raise RuntimeError("Native VIO JNI exports not found")
    if "vio_camera_trajectory.csv" not in lib.read_text(encoding="utf-8"):
        raise RuntimeError("Expected native recording patch is missing")
    if not (SOURCE / "LICENSE").is_file():
        raise RuntimeError("GPLv3 license is missing")
    # Include license in the APK. Keeping RF Lab private is fine for private
    # local development, NOT for distribution of the combined GPL program.
    license_path = PROJECT / "app" / "src" / "main" / "assets" / "licenses" / "openvins-gplv3.txt"
    license_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(SOURCE / "LICENSE", license_path)
    write(marker, PIN + "\n")
    print("OpenVINS embedded Android module prepared. Not Pixel-calibrated.")

if __name__ == "__main__":
    main()
