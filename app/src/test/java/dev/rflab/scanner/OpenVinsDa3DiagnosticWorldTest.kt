package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsDa3DiagnosticWorldTest {
    private fun frame(index:Int,x:Float):OpenVinsDa3DiagnosticWorld.Frame {
        val rays=(0 until 100).map{i->
            val center=floatArrayOf(x,0f,0f)
            val direction=floatArrayOf(x+(i%10)*.003f,0f,-1f)
            OpenVinsDa3Fusion.RaySample(center,direction,
                1f+(i%4)*.22f,0xff998877.toInt())
        }
        return OpenVinsDa3DiagnosticWorld.Frame(
            index,index,rays,.9f,1.75f,true)
    }

    @Test fun everyPoseMatchedFrameContributesColorInSameWorldAxes(){
        val result=OpenVinsDa3DiagnosticWorld.assemble(
            listOf(frame(0,0f),frame(1,2f)),emptyMap(),
            useGeometricFits=false)
        assertEquals(2,result.frames)
        assertEquals(2,result.approximateFrames)
        assertEquals(0,result.geometryAlignedFrames)
        assertTrue(result.points.size>=100)
        assertTrue(result.points.any{it.x<.1f})
        assertTrue(result.points.any{it.x>1.9f})
        assertTrue(result.points.all{it.argb==0xff998877.toInt()})
    }

    @Test fun realGeometricFitsAreUsedOnlyWhenMotionGateAllows(){
        val fit=OpenVinsDa3Alignment.Fit(
            1.0,0.0,30,40,.02,.9,1.75,4)
        val frames=listOf(frame(0,0f),frame(1,2f))
        val certified=OpenVinsDa3DiagnosticWorld.assemble(frames,
            mapOf(0 to fit),true)
        assertEquals(1,certified.geometryAlignedFrames)
        assertEquals(1,certified.approximateFrames)
        val diverged=OpenVinsDa3DiagnosticWorld.assemble(frames,
            mapOf(0 to fit),false)
        assertEquals(0,diverged.geometryAlignedFrames)
        assertEquals(2,diverged.approximateFrames)
    }
}
