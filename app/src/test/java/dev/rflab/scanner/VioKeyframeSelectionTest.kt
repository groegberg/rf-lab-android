package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class VioKeyframeSelectionTest {
    private fun pose(t:Long)=ExternalVioTrajectory.Sample(
        t,0.0,0.0,0.0,1.0,0.0,0.0,0.0)

    @Test fun selectsAllPostInitializationFramesRatherThanWastingSlots(){
        // Reproduce the uploaded Pixel capture: 32 frames at ~1Hz,
        // native VIO only initializes ~13.1s into the recording.
        val first=967205843428500L
        val rgb=(0 until 32).map{first+it*933_296_875L}
        val vio=(0..461).map{pose(first+13_100_000_000L+
            it*34_673_000L)}
        val chosen=VioKeyframeSelection.selectIndices(rgb,vio,24)
        assertEquals(rgb.indices.count{
            ExternalVioTrajectory.closestIndex(vio,rgb[it])>=0
        },chosen.size)
        assertTrue(chosen.all{it>=14})
    }

    @Test fun spreadsAcrossAllPoseAssociatedFramesInLongRecordings(){
        val pos=(100L..200L).map{pose(it*1_000_000_000L)}
        val photos=(0L..300L).map{it*1_000_000_000L}
        val idx=VioKeyframeSelection.selectIndices(photos,pos,24)
        assertEquals(24,idx.size)
        assertEquals(100,idx.first())
        assertEquals(200,idx.last())
    }

    @Test fun keepsDepthPreviewForPhotoOnlySessions(){
        assertEquals(listOf(0,2,4),
            VioKeyframeSelection.selectIndices(
                (0L..4L).toList(),emptyList(),3))
    }
}
