#!/usr/bin/env python3
"""Export the *actual* OpenVINS online calibration alongside EACH camera pose.

The starting Android Camera2/Kalibr-style YAML is NOT the current VIO state:
OpenVINS has enabled online intrinsics, extrinsics and time-offset estimation.
The old native CSV stored only time, position and quaternion. That made offline
DA3 project with a potentially inconsistent (stale) camera model.

No calibration is certified here: saved values can be unobservable/unstable.
This patch only makes camera/IMU estimates inspectable and reconstructible.
"""
from pathlib import Path
import sys

root=Path(sys.argv[1])
file=root/"app/src/main/cpp/native-lib.cpp"
s=file.read_text(encoding="utf-8")
def once(a,b):
    global s
    if s.count(a)!=1:
        raise RuntimeError("OpenVINS online calibration export patch drift: "+a[:100])
    s=s.replace(a,b,1)

once('#include <fstream>', '#include <fstream>\n#include <iomanip>')

once('pose_csv << "timestamp_ns,x_m_unvalidated,y_m_unvalidated,z_m_unvalidated,qw,qx,qy,qz" << std::endl;',
'''pose_csv << "timestamp_ns,x_m_unvalidated,y_m_unvalidated,z_m_unvalidated,qw,qx,qy,qz"
          << ",fx,fy,cx,cy,k1,k2,p1,p2"
          << ",qx_ItoC,qy_ItoC,qz_ItoC,qw_ItoC"
          << ",px_IinC,py_IinC,pz_IinC,cam_imu_timeoffset_s" << std::endl;''')

once('''            pose_csv << ts_ns << "," << p_cam(0) << "," << p_cam(1) << ","
                     << p_cam(2) << "," << q_cam(3) << "," << q_cam(0)
                     << "," << q_cam(1) << "," << q_cam(2) << "\\n";''',
'''            // Use calibration at the SAME VIO camera timestamp as the pose.
            // State variables change during online estimation and the
            // originally copied YAML can become stale within seconds.
            const auto intr = state->_cam_intrinsics.at(0)->value();
            const auto ex = state->_calib_IMUtoCAM.at(0);
            const auto qIc = ex->quat();
            const auto pIc = ex->pos();
            const double dt = state->_calib_dt_CAMtoIMU->value()(0);
            pose_csv << std::setprecision(12)
                     << ts_ns << "," << p_cam(0) << "," << p_cam(1) << ","
                     << p_cam(2) << "," << q_cam(3) << "," << q_cam(0)
                     << "," << q_cam(1) << "," << q_cam(2);
            for (int k = 0; k < 8; k++) pose_csv << "," << intr(k);
            for (int k = 0; k < 4; k++) pose_csv << "," << qIc(k);
            for (int k = 0; k < 3; k++) pose_csv << "," << pIc(k);
            pose_csv << "," << dt << "\\n";''')

file.write_text(s,encoding="utf-8")
print("Exporting actual online OpenVINS K, radtan, q_ItoC, p_IinC and time shift with every native camera pose")
