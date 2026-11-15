package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OfflinePoseAssociationTest {
    private val I=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
    private fun pose(t:Long)=OfflineVisualOdometry.Pose(t,I.copyOf(),
        doubleArrayOf(0.0,0.0,0.0),true,23,35,null,null,"Test")

    @Test fun photoMatchesNearestRealCameraTimestamp() {
        val times=listOf(pose(1_000_000_000L),pose(1_100_000_000L),
            pose(1_200_000_000L))
        assertEquals(1,OfflineReconstruction.closestIndex(times,1_115_000_000L))
        assertEquals(2,OfflineReconstruction.closestIndex(times,1_191_000_000L))
    }

    @Test fun missingOrDistantFramesAreNeverInvented() {
        val poses=listOf(pose(1_000_000_000L),pose(1_100_000_000L))
        assertEquals(-1,OfflineReconstruction.closestIndex(poses,1_500_000_000L))
        assertEquals(-1,OfflineReconstruction.closestIndex(poses,0L))
        assertEquals(-1,OfflineReconstruction.closestIndex(emptyList(),1L))
    }
}
