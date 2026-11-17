package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class ExternalVioTrajectoryTest {
    private fun csv(rows:String)=sequenceOf(
        "timestamp_ns,x_m_unvalidated,y_m_unvalidated,z_m_unvalidated,qw,qx,qy,qz"
    )+rows.trimIndent().lines().asSequence()

    @Test fun parsesOnlyTimestampedRealNativePoses() {
        val samples=ExternalVioTrajectory.parse(csv("""
            1000000000000,0,0,0,1,0,0,0
            1000050000000,0.1,0.0,0.0,1,0,0,0
            1000100000000,0.2,0.01,0,1,0,0,0
        """))
        assertEquals(3,samples.size)
        assertEquals(0.1,samples[1].x,1e-10)
        assertEquals(1000050000000L,samples[1].timestampNs)
    }

    @Test fun refusesDuplicatedOutOfOrderOrCorruptNativeStates() {
        val samples=ExternalVioTrajectory.parse(csv("""
            1000000000000,0,0,0,1,0,0,0
            1000000000000,5,0,0,1,0,0,0
            999999999999,6,0,0,1,0,0,0
            1000050000000,1,0,0,0,0,0,0
            1000060000000,1,0,0,1,0,0,0
        """))
        assertEquals(2,samples.size)
        assertEquals(1000060000000L,samples[1].timestampNs)
    }

    @Test fun cameraTimestampAssociationRejectsDistantFrames() {
        val poses=ExternalVioTrajectory.parse(csv("""
            1000000000000,0,0,0,1,0,0,0
            1000100000000,0.1,0,0,1,0,0,0
            1000200000000,0.2,0,0,1,0,0,0
        """))
        assertEquals(1,ExternalVioTrajectory.closestIndex(
            poses,1000125000000L))
        assertEquals(-1,ExternalVioTrajectory.closestIndex(
            poses,1001000000000L))
        assertEquals(-1,ExternalVioTrajectory.closestIndex(
            poses,0L))
        assertEquals(-1,ExternalVioTrajectory.closestIndex(
            emptyList(),1000000000000L))
    }
    @Test fun parsesActualOnlineOpenVinsCameraStateAtSamePoseTime() {
        val header="timestamp_ns,x,y,z,qw,qx,qy,qz,"+
            "fx,fy,cx,cy,k1,k2,p1,p2,qx_ItoC,qy_ItoC,qz_ItoC,qw_ItoC,"+
            "px_IinC,py_IinC,pz_IinC,cam_imu_timeoffset_s"
        val row="1000000000000,0,0,0,1,0,0,0,"+
            "478.5,479,321,239,0.03,-0.02,0.001,-0.001,"+
            "0,0,0,1,0.015,0.021,-0.003,0.007"
        val poses=ExternalVioTrajectory.parse(sequenceOf(header,row))
        assertEquals(1,poses.size)
        val camera=poses.single().onlineCamera!!
        assertEquals(478.5,camera.intrinsics[0],1e-8)
        assertEquals(.007,camera.timeOffsetSeconds,1e-10)
        val seed=OpenVinsDa3Geometry.Calibration(
            500.0,500.0,320.0,240.0,640,480,0.0,0.0,0.0,0.0)
        assertEquals(478.5,camera.cameraCalibration(seed)!!.fx,1e-8)
        val R=camera.rotationCamFromImu()!!
        assertArrayEquals(doubleArrayOf(1.0,0.0,0.0,
            0.0,1.0,0.0,0.0,0.0,1.0),R,1e-9)
    }

    @Test fun onlineJplQuaternionUsesOppositeHamiltonSkew() {
        // JPL qz=sin(45), qw=cos(45) gives R_ItoC clockwise 90,
        // which must NOT be interpreted as Android's Hamilton pose.
        val c=ExternalVioTrajectory.CameraEstimate(
            doubleArrayOf(500.0,500.0,320.0,240.0,0.0,0.0,0.0,0.0),
            doubleArrayOf(0.0,0.0,kotlin.math.sqrt(.5),
                kotlin.math.sqrt(.5)),
            doubleArrayOf(0.0,0.0,0.0),0.0)
        val R=c.rotationCamFromImu()!!
        assertEquals(1.0,R[1],1e-10)
        assertEquals(-1.0,R[3],1e-10)
    }


}
