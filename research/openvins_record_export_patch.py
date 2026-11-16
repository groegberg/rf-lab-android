#!/usr/bin/env python3
"""Additional GPLv3 OpenVINS Mobile research modifications:
- Real-time initialization/pose HUD (does not imply metric validation).
- Synchronized Camera2 RGB stills and native VIO trajectory CSV.
- Scoped, user-triggered SAF ZIP recording export; no external storage grants.
This is applied after openvins_pixel_test_patch.py and is intentionally
separate from RF Lab's own source/binary and licenses.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1] if len(sys.argv) > 1 else "openvins-mobile")
def edit(rel, old, new):
    file = root / rel
    s=file.read_text()
    if old not in s:
        raise RuntimeError(f"Outdated upstream/first-stage patch: {rel}: {old[:85]}")
    file.write_text(s.replace(old,new,1))

cpp="app/src/main/cpp/native-lib.cpp"
edit(cpp,
    "bool is_recording = false;",
    """std::atomic<bool> is_recording{false};
std::atomic<unsigned long long> last_gray_saved_ns{0};
std::atomic<unsigned long long> last_rgb_saved_ns{0};""")
edit(cpp,
    "std::ofstream imu_csv;",
    """std::ofstream imu_csv;
std::ofstream pose_csv;
std::mutex imu_csv_mtx;
std::mutex pose_csv_mtx;""")
edit(cpp,
    '    mkdir((save_folder + "cam0/").c_str(), 0700);',
    """    mkdir((save_folder + "cam0/").c_str(), 0700);
    mkdir((save_folder + "rgb/").c_str(), 0700);
    last_gray_saved_ns.store(0);
    last_rgb_saved_ns.store(0);
    // Snapshot precisely the config used by this capture for later
    // camera/IMU calibration and uncertainty review.
    mkdir((save_folder + "config/").c_str(), 0700);
    for (const char* name : {"estimator_config.yaml",
                             "kalibr_imu_chain.yaml",
                             "kalibr_imucam_chain.yaml",
                             "PIXEL_CALIBRATION_WARNING.txt"}) {
      const std::string from = app_private_folder + "/config/" + name;
      std::ifstream source(from, std::ios::binary);
      if (source.is_open()) {
        std::ofstream target(save_folder + "config/" + name, std::ios::binary);
        target << source.rdbuf();
      }
    }""")
edit(cpp,
    """    // Open our IMU csv file
    imu_csv.open(save_folder + "imu0.csv");
    imu_csv << "timestamp,omega_x,omega_y,omega_z,alpha_x,alpha_y,alpha_z" << std::endl;""",
    """    // IMU and camera trajectories share the original hardware timestamp.
    {
      std::lock_guard<std::mutex> guard(imu_csv_mtx);
      imu_csv.open(save_folder + "imu0.csv");
      imu_csv << "timestamp,omega_x,omega_y,omega_z,alpha_x,alpha_y,alpha_z" << std::endl;
    }
    {
      std::lock_guard<std::mutex> guard(pose_csv_mtx);
      pose_csv.open(save_folder + "vio_camera_trajectory.csv");
      pose_csv << "timestamp_ns,x_m_unvalidated,y_m_unvalidated,z_m_unvalidated,qw,qx,qy,qz" << std::endl;
    }""")
edit(cpp,
    """    // If the file was open, then close it
    if (imu_csv.is_open()) {
      imu_csv.close();
    }""",
    """    // Drain/close while holding the corresponding writer locks.
    {
      std::lock_guard<std::mutex> guard(imu_csv_mtx);
      if (imu_csv.is_open()) imu_csv.close();
    }
    {
      std::lock_guard<std::mutex> guard(pose_csv_mtx);
      if (pose_csv.is_open()) pose_csv.close();
    }""")
edit(cpp,
    """  // If recording save to disk
  if (is_recording) {
    std::string filename = save_folder + "cam0/" + std::to_string(time_in_ns) + ".png";
    cv::imwrite(filename, mat_gray);
    __android_log_print(ANDROID_LOG_INFO, TAG, "saved file: %s\\n", filename.c_str());
  }""",
    """  // Bounded local research recording. The video-quality grayscale
  // images feed offline diagnostics; 1 Hz RGB images feed real DA3 later.
  if (is_recording.load()) {
    const auto last_gray = last_gray_saved_ns.load();
    if (time_in_ns > last_gray + 100000000ULL) {
      last_gray_saved_ns.store(time_in_ns);
      const std::string filename = save_folder + "cam0/" + std::to_string(time_in_ns) + ".png";
      cv::imwrite(filename, mat_gray);
    }
    const auto last_rgb = last_rgb_saved_ns.load();
    if (time_in_ns > last_rgb + 900000000ULL) {
      last_rgb_saved_ns.store(time_in_ns);
      cv::Mat bgr;
      cv::cvtColor(mat, bgr, cv::COLOR_RGBA2BGR);
      const std::string jpeg = save_folder + "rgb/" + std::to_string(time_in_ns) + ".jpg";
      cv::imwrite(jpeg, bgr, {cv::IMWRITE_JPEG_QUALITY, 88});
    }
  }""")
edit(cpp,
    """  if (is_recording && imu_csv.is_open()) {
    imu_csv << time_in_ns << "," << n_gx << "," << n_gy << "," << n_gz << "," << n_ax << "," << n_ay << "," << n_az << std::endl;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%.4f, %.4f, %.4f | %.4f, %.4f, %.4f \\n", n_ax, n_ay, n_az, n_gx, n_gy, n_gz);
  }""",
    """  if (is_recording.load()) {
    std::lock_guard<std::mutex> guard(imu_csv_mtx);
    if (imu_csv.is_open()) {
      imu_csv << time_in_ns << "," << n_gx << "," << n_gy << "," << n_gz << ","
              << n_ax << "," << n_ay << "," << n_az << "\\n";
    }
  }""")
edit(cpp,
    """        trajectory_history.emplace_back(p_cam(0), p_cam(1), p_cam(2), q_cam(3), q_cam(0), q_cam(1), q_cam(2));""",
    """        trajectory_history.emplace_back(p_cam(0), p_cam(1), p_cam(2), q_cam(3), q_cam(0), q_cam(1), q_cam(2));
        // Only initialized native MSCKF poses enter the exported trajectory.
        if (is_recording.load()) {
          std::lock_guard<std::mutex> output_guard(pose_csv_mtx);
          if (pose_csv.is_open()) {
            const auto ts_ns = (unsigned long long)(cam_msg.timestamp * 1e9);
            pose_csv << ts_ns << "," << p_cam(0) << "," << p_cam(1) << ","
                     << p_cam(2) << "," << q_cam(3) << "," << q_cam(0)
                     << "," << q_cam(1) << "," << q_cam(2) << "\\n";
          }
        }""")
# The pose writer is nested under the existing trajectory mutex. There is
# no lock inversion: the record toggle takes only the pose writer mutex.
main="app/src/main/java/com/openvins/android/MainActivity.kt"
edit(main,
    """        val fab_folder = findViewById(R.id.open_folder) as FloatingActionButton
        fab_folder.setOnClickListener {""",
    """        val fab_folder = findViewById(R.id.open_folder) as FloatingActionButton
        fab_folder.setOnClickListener {""")
# Replace the entire old arbitrary-path folder chooser.
file=root/main
s=file.read_text()
start=s.index("        val fab_folder = findViewById(R.id.open_folder)")
end=s.index("        // Our record button",start)
s=s[:start]+"""        // The folder icon is now a privacy-preserving, one-tap ZIP export.
        val fab_folder = findViewById(R.id.open_folder) as FloatingActionButton
        fab_folder.setOnClickListener { exportLastSession() }

"""+s[end:]
# Replace static title with live status (not a claim of calibrated metric data).
# Upstream contains whitespace on its empty line: patch using a stable
# nonempty marker and verify the insertion to avoid unresolved HUD variables.
needle="        if (trajectoryView == null) return"
assert needle in s
s=s.replace(needle,needle+"""
        val hud = findViewById<android.widget.TextView>(R.id.vio_health_status)
        if (!isRunningOV) {
            hud.text = "OpenVINS · STOP · Kalibrierung nicht validiert"
            return
        }""",1)
s=s.replace(
    """        if (!getCurrentPoseJNI(currentPos, currentQuat)) {
            return // System not initialized
        }""",
    """        if (!getCurrentPoseJNI(currentPos, currentQuat)) {
            hud.text = "OpenVINS · INITIALISIERT NOCH · langsam seitwärts gehen"
            return
        }
        hud.text = String.format(java.util.Locale.GERMANY,
            "OpenVINS · VIO läuft · %.2f / %.2f / %.2f (unkalibrierte m)",
            currentPos[0],currentPos[1],currentPos[2])""")
# User explicitly controls file export; only last completed session is included.
marker="    companion object {\n        private const val TAG = \"MainActivity\""
if marker not in s:raise RuntimeError("MainActivity companion anchor moved")
export_code=r'''
    private var pendingExportSession: File? = null
    private fun exportLastSession() {
        if (isRecording) {
            Toast.makeText(this,"Erst Aufnahme stoppen, dann ZIP exportieren",
                Toast.LENGTH_LONG).show()
            return
        }
        val root=File(getExternalFilesDir(null),"sessions")
        val latest=root.listFiles()?.filter{it.isDirectory}
            ?.maxByOrNull{it.lastModified()}
        if(latest==null){
            Toast.makeText(this,"Keine VIO-Aufnahmeserie vorhanden",
                Toast.LENGTH_LONG).show()
            return
        }
        pendingExportSession=latest
        val intent=android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("application/zip")
            .putExtra(android.content.Intent.EXTRA_TITLE,
                "OpenVINS-"+latest.name+".zip")
        startActivityForResult(intent,7021)
    }

    @Deprecated("Use platform SAF document picker for export")
    override fun onActivityResult(code:Int,result:Int,data:android.content.Intent?){
        super.onActivityResult(code,result,data)
        if(code!=7021 || result!=RESULT_OK)return
        val uri=data?.data?:return
        val dir=pendingExportSession?:return
        pendingExportSession=null
        Thread {
            try{
                val base=dir.canonicalFile
                val photos=base.walkTopDown().filter { it.isFile &&
                    !it.name.endsWith(".part") }.toList()
                val count=photos.size
                contentResolver.openOutputStream(uri)?.use { output ->
                    java.util.zip.ZipOutputStream(output.buffered()).use { zip ->
                        for(file in photos){
                            val rel=file.canonicalFile.relativeTo(base).invariantSeparatorsPath
                            zip.putNextEntry(java.util.zip.ZipEntry(
                                base.name+"/"+rel))
                            file.inputStream().buffered().use {
                                it.copyTo(zip,64*1024)
                            }
                            zip.closeEntry()
                        }
                    }
                }?:error("Kein Speicherziel")
                runOnUiThread{
                    Toast.makeText(this,"VIO-ZIP: "+count+" Dateien gespeichert",
                        Toast.LENGTH_LONG).show()
                }
            }catch(t:Throwable){
                runOnUiThread{
                    Toast.makeText(this,"Export fehlgeschlagen: "+t.message,
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

'''
s=s.replace(marker,export_code+marker,1)
file.write_text(s)
layout="app/src/main/res/layout/activity_main.xml"
edit(layout,
    'android:text="OpenVINS TEST · Kamera/IMU unkalibriert · Trajektorie unvalidiert"',
    'android:id="@+id/vio_health_status"\n        android:text="OpenVINS · Kalibrierung nicht validiert"')
# Keep APK source-notices and strict verification in workflow/release.
print("Applied GPLv3 research RGB/VIO pose recording, live HUD and SAF ZIP export")
