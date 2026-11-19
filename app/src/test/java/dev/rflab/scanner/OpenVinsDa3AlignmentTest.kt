package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

class OpenVinsDa3AlignmentTest {
    private fun ray(origin:DoubleArray, endpoint:DoubleArray):
        OpenVinsDa3Alignment.Ray {
        val v=DoubleArray(3){endpoint[it]-origin[it]}
        val n=sqrt(v.sumOf{it*it})
        return OpenVinsDa3Alignment.Ray(
            origin, DoubleArray(3){v[it]/n},n/endpoint[2].let{
                // Tests' two camera centers have zero Z, so endpoint Z is
                // the axial depth along the optical camera frame.
                it
            })
    }

    @Test fun actualTranslationTriangulatesSameWorldPoint() {
        val a=OpenVinsDa3Alignment.Ray(doubleArrayOf(0.0,0.0,0.0),
            doubleArrayOf(0.0,0.0,1.0),1.0)
        val n=sqrt(10.0)
        val b=OpenVinsDa3Alignment.Ray(doubleArrayOf(1.0,0.0,0.0),
            doubleArrayOf(-1/n,0.0,3/n),n/3)
        val p=OpenVinsDa3Alignment.triangulate(a,b)!!
        assertEquals(3.0,p.axialA,1e-9)
        assertEquals(3.0,p.axialB,1e-9)
        assertEquals(0.0,p.rayGap,1e-9)
    }

    @Test fun rejectsPureRotationAndNearParallelRays() {
        val a=OpenVinsDa3Alignment.Ray(doubleArrayOf(0.0,0.0,0.0),
            doubleArrayOf(0.0,0.0,1.0),1.0)
        val b=OpenVinsDa3Alignment.Ray(doubleArrayOf(0.0,0.0,0.0),
            doubleArrayOf(0.0,0.0,1.0),1.0)
        assertNull(OpenVinsDa3Alignment.triangulate(a,b))
        val distant=OpenVinsDa3Alignment.Ray(doubleArrayOf(0.1,0.0,0.0),
            doubleArrayOf(-.001,0.0,sqrt(1-.001*.001)),1.0)
        assertNull(OpenVinsDa3Alignment.triangulate(a,distant))
    }

    @Test fun robustAffineFitRecoversScaleAndShiftDespiteOutliers() {
        val noise=Random(33)
        val points=ArrayList<OpenVinsDa3Alignment.Observation>()
        for(i in 0 until 120){
            val d=.6 + (i%60)*.032
            val actual=1.43*d+.49+
                (noise.nextDouble()-.5)*.024
            points.add(OpenVinsDa3Alignment.Observation(
                d,actual,(i%12)/12.0,(i/12)/10.0))
        }
        repeat(48){i->
            points.add(OpenVinsDa3Alignment.Observation(
                .6+(i%35)*.05,5.9+(i%15)*.12,
                (i%8)/8.0,(i/8)/6.0))
        }
        val fit=OpenVinsDa3Alignment.fit(points)!!
        assertEquals(1.43,fit.scale,.06)
        assertEquals(.49,fit.shift,.09)
        assertTrue(fit.inliers>=100)
        assertTrue(fit.medianRelativeError<.03)
        assertTrue(fit.coverageQuadrants>=2)
        assertTrue(fit.depthAt(.8)!! in 1.3..1.9)
    }

    @Test fun insufficientParallaxOrInconsistentDepthCannotProduceMap() {
        val flat=List(90){i->
            OpenVinsDa3Alignment.Observation(
                2.0, 1.5+i*.00001, (i%10)/10.0, (i/10)/9.0)
        }
        assertNull(OpenVinsDa3Alignment.fit(flat))
        val reversed=List(90){i->
            val pred=.2+i*.02
            OpenVinsDa3Alignment.Observation(
                pred,4.9-.7*pred, (i%10)/10.0, (i/10)/9.0)
        }
        assertNull(OpenVinsDa3Alignment.fit(reversed))
    }
}
