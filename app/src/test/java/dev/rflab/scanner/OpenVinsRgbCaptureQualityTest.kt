package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsRgbCaptureQualityTest {
    private fun grid(low:Int,high:Int):IntArray =
        IntArray(160*120){i->
            val x=i%160
            val y=i/160
            val v=if(((x/10)+(y/10))%2==0)low else high
            (255 shl 24) or (v shl 16) or (v shl 8) or v
        }

    @Test fun wellLitStructuredSceneCanBeUsedForMultiView(){
        val r=OpenVinsRgbCaptureQuality.inspectArgb(
            grid(85,210),160,120)
        assertTrue(r.usableForMultiView)
        assertTrue(r.medianLuma>=35)
        assertTrue(r.gradientContrast>=5)
        assertEquals(0.0,r.nearBlackFraction,1e-9)
    }

    @Test fun darkNightPhotoIsRetainedButRejectedFromGeometry(){
        val r=OpenVinsRgbCaptureQuality.inspectArgb(
            grid(2,10),160,120)
        assertFalse(r.usableForMultiView)
        assertTrue(r.medianLuma<35)
        assertTrue(r.nearBlackFraction>.99)
        assertTrue(r.warnings.any{it.contains("unterbelichtet")})
    }

    @Test fun flatWhiteWallCannotProveTrackableStructure(){
        val pixels=IntArray(160*120){0xffffffff.toInt()}
        val r=OpenVinsRgbCaptureQuality.inspectArgb(pixels,160,120)
        assertFalse(r.usableForMultiView)
        assertTrue(r.gradientContrast<5.0)
        assertTrue(r.warnings.any{it.contains("Bildstruktur")})
    }
}
