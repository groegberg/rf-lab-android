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
}
