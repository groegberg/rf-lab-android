package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsPlanarityAuditTest {
    @Test fun planarWallAndCurvedWallHaveDifferentMeasuredSupport(){
        val flat=ArrayList<SurveyDepth>()
        val curved=ArrayList<SurveyDepth>()
        for(y in 0..40)for(x in 0..40){
            val px=(x-20)*.06f
            val py=(y-20)*.06f
            flat.add(SurveyDepth(px,py,-2f,.9f))
            curved.add(SurveyDepth(px,py,
                -2f-.9f*px*px,.9f))
        }
        val a=OpenVinsPlanarityAudit.inspect(flat)
        val b=OpenVinsPlanarityAudit.inspect(curved)
        assertTrue(a.diagnosticOnly)
        assertTrue(a.dominantPlaneFraction>.95)
        assertTrue("Curvature should reduce planar support: $b",
            b.dominantPlaneFraction<a.dominantPlaneFraction*.8)
        assertEquals(a.samples,b.samples)
    }

    @Test fun verySmallOrDegenerateCloudCannotClaimPlanarity(){
        val one=List(100){SurveyDepth(0f,0f,0f,.4f)}
        val report=OpenVinsPlanarityAudit.inspect(one)
        assertEquals(0.0,report.dominantPlaneFraction,0.0)
        assertTrue(report.medianResidualToSpan.isNaN())
        assertEquals(0.0,OpenVinsPlanarityAudit.inspect(
            emptyList()).dominantPlaneFraction,0.0)
    }
}
