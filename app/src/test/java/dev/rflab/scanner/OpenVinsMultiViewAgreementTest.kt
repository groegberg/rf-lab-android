package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsMultiViewAgreementTest {
    private fun surface(shift:Float,z:Float):List<SurveyDepth> =
        (0 until 24).flatMap{y->
            (0 until 24).map{x->
                SurveyDepth((x-12)*.07f+shift,(y-12)*.07f,z,.85f,
                    argb=0xff6688aa.toInt(),rawDepth=false)
            }
        }

    @Test fun physicallyOverlappingPhotographsAreAccepted(){
        val result=OpenVinsMultiViewAgreement.verify(listOf(
            OpenVinsMultiViewAgreement.Input(4,surface(0f,-2f)),
            OpenVinsMultiViewAgreement.Input(5,surface(.02f,-2.01f))))
        assertTrue(result.accepted)
        assertTrue(result.fraction>.90)
        assertEquals(2,result.supportedFrames)
        assertTrue(result.points.size>=100)
    }

    @Test fun duplicatedButDisplacedWallsAreNotMisreportedAsFused(){
        val result=OpenVinsMultiViewAgreement.verify(listOf(
            OpenVinsMultiViewAgreement.Input(4,surface(0f,-2f)),
            OpenVinsMultiViewAgreement.Input(5,surface(0f,-3f))))
        assertFalse(result.accepted)
        assertEquals(0,result.supported)
        assertTrue(result.points.isEmpty())
    }

    @Test fun pointsInOnlyOnePhotographAreNeverAnAgreement(){
        val result=OpenVinsMultiViewAgreement.verify(listOf(
            OpenVinsMultiViewAgreement.Input(4,surface(0f,-2f))))
        assertFalse(result.accepted)
    }
}
