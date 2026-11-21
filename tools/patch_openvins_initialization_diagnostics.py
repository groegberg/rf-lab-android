#!/usr/bin/env python3
"""Pinned GPLv3 OpenVINS Mobile: static-init/ZUPT fix and native init counters.

Use *real* Camera2/IMU timestamps and the actual native worker; do not synthesize
poses, fabricate timestamps or silently declare a successful initialization.
Applied after both research patches and the RF Lab scan subclass bridge.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
cpp=root/"app/src/main/cpp/native-lib.cpp"
s=cpp.read_text(encoding="utf-8")
def replace(old,new):
    global s
    if s.count(old)!=1:
        raise RuntimeError("OpenVINS native source changed: "+old[:105])
    s=s.replace(old,new,1)

# Original worker ignored camera notifications until a hard 100 ms timeout:
# wait_for(predicate) had a predicate that was false for normal frames.
replace(
    'processing_cv.wait_for(proc_lck, std::chrono::milliseconds(100), [&] { return !thread_should_run; });',
    'processing_cv.wait_for(proc_lck, std::chrono::milliseconds(20));'
)

anchor='const double MAX_CAMERA_AGE_SECONDS = 0.5; // Skip measurements older than 500ms'
replace(anchor,anchor+"""

// Atomic counters support the RF Lab on-device diagnostics HUD and SAF export.
// Every value comes from the actual Camera2 callback, native queue or MSCKF
// worker; a nonempty feature overlay alone does NOT prove initialization.
std::atomic<int> diag_camera_received{0};
std::atomic<int> diag_camera_enqueued{0};
std::atomic<int> diag_camera_processed{0};
std::atomic<int> diag_camera_expired{0};
std::atomic<int> diag_imu_received{0};
std::atomic<int> diag_imu_fed{0};
std::atomic<int> diag_camera_wait_imu{0};
std::atomic<int> diag_config_failures{0};
std::atomic<int> diag_initialized{0};
std::atomic<long long> diag_cam_latest_ns{0};
std::atomic<long long> diag_imu_latest_ns{0};
std::atomic<long long> diag_processed_latest_ns{0};
""")

replace('  // get Mat from raw address\n  clock_t begin = clock();',
        '  diag_camera_received.fetch_add(1);\n'
        '  diag_cam_latest_ns.store((long long)time_in_ns);\n'
        '  // get Mat from raw address\n  clock_t begin = clock();')
replace('        camera_queue.push_back(message);',
        '        camera_queue.push_back(message);\n'
        '        diag_camera_enqueued.fetch_add(1);')
replace('          camera_queue.pop_front();\n          continue;',
        '          camera_queue.pop_front();\n'
        '          diag_camera_expired.fetch_add(1);\n          continue;')
replace('        if (camera_queue.at(0).timestamp >= timestamp_imu_inC) {',
        '        if (camera_queue.at(0).timestamp >= timestamp_imu_inC) {\n'
        '          diag_camera_wait_imu.fetch_add(1);')
replace('      sys->feed_measurement_camera(cam_msg);',
        '      sys->feed_measurement_camera(cam_msg);\n'
        '      diag_camera_processed.fetch_add(1);\n'
        '      diag_processed_latest_ns.store((long long)(cam_msg.timestamp * 1e9));')
replace('      // Display things if we have initialized\n      if (sys->initialized()) {',
        '      // Display things if we have initialized\n'
        '      if (sys->initialized()) {\n'
        '        diag_initialized.store(1);')
replace('    if (!parser->successful()) {',
        '    if (!parser->successful()) {\n'
        '      diag_config_failures.fetch_add(1);')
replace('  // Cast to our native type\n  double n_ax = static_cast<double>(ax);',
        '  diag_imu_received.fetch_add(1);\n'
        '  diag_imu_latest_ns.store((long long)time_in_ns);\n'
        '  // Cast to our native type\n  double n_ax = static_cast<double>(ax);')
replace('    sys->feed_measurement_imu(message_imu);',
        '    sys->feed_measurement_imu(message_imu);\n'
        '    diag_imu_fed.fetch_add(1);')
replace('    // Starting the system: ensure clean state\n',
        """    // Starting the system: ensure clean state
    diag_camera_received.store(0);
    diag_camera_enqueued.store(0);
    diag_camera_processed.store(0);
    diag_camera_expired.store(0);
    diag_imu_received.store(0);
    diag_imu_fed.store(0);
    diag_camera_wait_imu.store(0);
    diag_config_failures.store(0);
    diag_initialized.store(0);
    diag_cam_latest_ns.store(0);
    diag_imu_latest_ns.store(0);
    diag_processed_latest_ns.store(0);
""")
s+="""

// Diagnostics are independent of sys access: all fields are atomic and valid
// even while native VIO initialization is pending or after it was stopped.
// counters: camera callbacks, queued, processed, too-old, gyro samples, IMU
// fed, camera waiting for newer IMU, config failures, initialized (0/1).
// clock seconds: last raw camera, last raw gyro, last processed camera, now
// on CLOCK_BOOTTIME. Distinguishes source clock mismatch from missing IMU.
extern "C" JNIEXPORT void JNICALL
Java_com_openvins_android_MainActivity_getNativeDiagnosticsJNI(
    JNIEnv* env, jobject instance, jintArray counters, jdoubleArray clocks) {
  if (counters == nullptr || clocks == nullptr ||
      env->GetArrayLength(counters) < 9 || env->GetArrayLength(clocks) < 4)
    return;
  jint data[9] = {
      diag_camera_received.load(), diag_camera_enqueued.load(),
      diag_camera_processed.load(), diag_camera_expired.load(),
      diag_imu_received.load(), diag_imu_fed.load(),
      diag_camera_wait_imu.load(), diag_config_failures.load(),
      diag_initialized.load()
  };
  struct timespec ts;
  clock_gettime(CLOCK_BOOTTIME, &ts);
  jdouble sec[4] = {
      diag_cam_latest_ns.load() * 1e-9,
      diag_imu_latest_ns.load() * 1e-9,
      diag_processed_latest_ns.load() * 1e-9,
      ts.tv_sec + ts.tv_nsec * 1e-9
  };
  env->SetIntArrayRegion(counters, 0, 9, data);
  env->SetDoubleArrayRegion(clocks, 0, 4, sec);
}
"""
cpp.write_text(s,encoding="utf-8")

# The copied yaml from the old Android test already exists on device, so
# updating only the bundled asset is insufficient. Apply this idempotent
# option to the PRIVATE estimator YAML at app start on every installed build.
# Enabling ZUPT at initialization makes static init work after a stationary
# window without needing an acceleration jerk. It does not fake any IMU pose.
main=root/"app/src/main/java/com/openvins/android/MainActivity.kt"
k=main.read_text(encoding="utf-8")
marker='    /** Host scanner receives native MSCKF camera pose, not 2-D feature tracks.'
if k.count(marker)!=1: raise RuntimeError("Kotlin host bridge missing")
k=k.replace(marker,"""    private external fun getNativeDiagnosticsJNI(
        counters: IntArray, clocks: DoubleArray)

    /** Native counters and boot-time clocks; safe even before initialization. */
    protected fun readNativeDiagnostics(): Pair<IntArray, DoubleArray> {
        val counters=IntArray(9)
        val clocks=DoubleArray(4)
        getNativeDiagnosticsJNI(counters,clocks)
        return counters to clocks
    }

"""+marker,1)
# The upstream floating HUD previously instructed continuous movement even
# when the *static* initializer needs an initial stationary period.
old_status='OpenVINS · INITIALISIERT NOCH · langsam seitwärts gehen'
new_status='OpenVINS · INIT · zunächst 3–5 s ruhig halten'
if k.count(old_status)!=1: raise RuntimeError("Old init HUD text missing")
k=k.replace(old_status,new_status,1)

needle='        // Calibration templates are unvalidated defaults.'
if k.count(needle)!=1:raise RuntimeError("Runtime config patch anchor missing")
k=k.replace(needle,needle+"""
        // Old RF Lab installs retain an earlier estimator YAML; migrate only
        // the single initializer option while retaining personalized camera
        // intrinsics/extrinsics and the independent calibration warning.
        val estimatorFile=File(privateConfigDir,"estimator_config.yaml")
        if(estimatorFile.isFile){
            val original=estimatorFile.readText()
            val migrated=original.replace(
                Regex("(?m)^try_zupt: *(?:true|false)"),
                "try_zupt: true")
            if(migrated != original)estimatorFile.writeText(migrated)
        }
""",1)
main.write_text(k,encoding="utf-8")

yaml=root/"app/src/main/assets/vio_config/estimator_config.yaml"
cfg=yaml.read_text(encoding="utf-8")
if cfg.count("try_zupt: false")!=1:
    raise RuntimeError("Unexpected original estimator config")
yaml.write_text(cfg.replace("try_zupt: false","try_zupt: true",1),encoding="utf-8")
print("Applied 16-bit-safe native init diagnostics + stationary ZUPT initializer")
