package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class VisualFeatureTrackerTest {
    private fun textured(shiftX: Int = 0, shiftY: Int = 0): ByteArray {
        val w=160;val h=120
        return ByteArray(w*h) { i ->
            val x=i%w-shiftX
            val y=i/w-shiftY
            if(x !in 0 until w || y !in 0 until h) 127.toByte()
            else {
                var hsh=x*73856093 xor y*19349663
                hsh=hsh xor (hsh ushr 13)
                (hsh and 255).toByte()
            }
        }
    }

    @Test fun blankWallNeverTriggersAutoCapture() {
        val blank=VisualFeatureTracker.analyze(
            ByteArray(160*120){128.toByte()},160,120)
        assertEquals(0,blank.features.size)
        assertFalse(VisualFeatureTracker.autoEligible(blank,null,true))
    }

    @Test fun independentOpticalFeaturesDetectSceneTexture() {
        val rich=VisualFeatureTracker.analyze(textured(),160,120)
        assertTrue("Need robust features on textured photo",rich.features.size>25)
        assertTrue(rich.sharpness>9f)
        assertTrue(rich.textureCoverage>.22f)
        assertTrue(VisualFeatureTracker.autoEligible(rich,null,true))
    }

    @Test fun opticalTrackingDetectsMovementBetweenImages() {
        val first=VisualFeatureTracker.analyze(textured(),160,120)
        val next=VisualFeatureTracker.analyze(textured(5,3),160,120)
        val comparison=VisualFeatureTracker.compare(first,next)
        assertTrue("Independent images should match",comparison.count>5)
        assertTrue("Motion needs nonzero displacement",comparison.medianMotionPx>1f)
    }

    @Test fun missingFeaturesCannotClaimTracking() {
        val blank=VisualFeatureTracker.analyze(
            ByteArray(160*120){42},160,120)
        val rich=VisualFeatureTracker.analyze(textured(),160,120)
        assertEquals(0,VisualFeatureTracker.compare(blank,rich).count)
    }
}
