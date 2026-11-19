package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenVinsRgbCloudCacheTest {
    @get:Rule val temp=TemporaryFolder()

    @Test fun eachRGBAndXYZSampleSurvivesPrivateCacheRoundtrip(){
        val folder=temp.newFolder()
        val samples=listOf(
            SurveyDepth(1.2f,-.7f,3.0f,.5f,argb=0xff00aa91.toInt(),rawDepth=false),
            SurveyDepth(-2.1f,.3f,1.5f,.5f,argb=0xffbb0a44.toInt(),rawDepth=false))
        val file=OpenVinsRgbCloudCache.write(folder,
            "relative_0007.rgbxyz",samples)
        val loaded=OpenVinsRgbCloudCache.read(file)
        assertEquals(2,loaded.size)
        assertEquals(samples[0].x,loaded[0].x,1e-6f)
        assertEquals(samples[1].z,loaded[1].z,1e-6f)
        assertEquals(samples[0].argb,loaded[0].argb)
        assertEquals(samples[1].argb,loaded[1].argb)
    }

    @Test fun corruptedCountAndTruncatedPointsAreRejected(){
        val file=OpenVinsRgbCloudCache.write(temp.newFolder(),
            "test.rgbxyz",listOf(SurveyDepth(0f,0f,1f,.3f)))
        file.writeBytes(file.readBytes().copyOf(13))
        assertTrue(OpenVinsRgbCloudCache.read(file).isEmpty())
    }

    @Test(expected=IllegalArgumentException::class)
    fun noPathTraversalOutsideOfflineRun(){
        OpenVinsRgbCloudCache.write(temp.newFolder(),
            "../outside.rgbxyz",emptyList())
    }
}
