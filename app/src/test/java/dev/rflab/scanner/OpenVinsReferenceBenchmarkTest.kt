package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class OpenVinsReferenceBenchmarkTest {
    @Test fun oneMetreTranslationAssessesUnvalidatedScale(){
        val poses=(0 until 60).map{i->
            ExternalVioTrajectory.Sample(i*33_000_000L+1L,
                i/59.0,0.0,0.0,1.0,0.0,0.0,0.0)
        }
        val r=OpenVinsReferenceBenchmark.evaluate(poses,
            OpenVinsReferenceBenchmark.Kind.KNOWN_TRANSLATION,1.0)
        assertEquals(1.0,r.netDisplacementUnvalidated,1e-8)
        assertEquals(0.0,r.relativeTranslationError!!,1e-8)
    }

    @Test fun closedLoopWithOffsetHasNonzeroClosureError(){
        val poses=(0..40).map{i->
            val x=if(i<=20)i*.1 else (40-i)*.1+.01
            ExternalVioTrajectory.Sample(i*33_000_000L+1L,
                x,0.0,0.0,1.0,0.0,0.0,0.0)
        }
        val r=OpenVinsReferenceBenchmark.evaluate(poses,
            OpenVinsReferenceBenchmark.Kind.CLOSED_ROUTE)
        assertEquals(.01,r.netDisplacementUnvalidated,.00001)
        assertTrue(r.relativeTranslationError!!>0.0)
    }

    @Test fun ninetyDegreePhoneRollProducesNinetyDegreeReference(){
        val q=sqrt(.5)
        val poses=(0 until 30).map{i->
            val t=(i/29.0)*(Math.PI/2)
            ExternalVioTrajectory.Sample(i*33_000_000L+1L,
                0.0,0.0,0.0,kotlin.math.cos(t/2),0.0,0.0,
                kotlin.math.sin(t/2))
        }
        val r=OpenVinsReferenceBenchmark.evaluate(poses,
            OpenVinsReferenceBenchmark.Kind.KNOWN_ROTATION)
        assertEquals(90.0,r.rotationAngleDegrees,.001)
        assertEquals(0.0,r.rotationErrorDegrees!!,.001)
    }
}
