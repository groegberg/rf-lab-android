package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test

class OpenVinsChessboardDiversityTest {
    @Test fun repeatedSameFrontoParallelViewDoesNotPassCalibration(){
        val observations=List(25){
            OpenVinsChessboardDiversity.View(.50,.50,.12,.01)
        }
        val result=OpenVinsChessboardDiversity.inspect(observations)
        assertEquals(1,result.occupiedRegions)
        assertFalse(result.sufficientlyDiverse)
    }

    @Test fun spreadScaleAndObliquenessMakeObservableCameraViews(){
        val observations=(0 until 15).map{index->
            OpenVinsChessboardDiversity.View(
                .18+(index%3)*.32,
                .20+(index/3%3)*.30,
                .04+index*.006,
                if(index%3==0).35 else .06)
        }
        val result=OpenVinsChessboardDiversity.inspect(observations)
        assertTrue(result.occupiedRegions>=3)
        assertTrue(result.areaVariationRatio>1.25)
        assertTrue(result.obliqueViews>=2)
        assertTrue(result.sufficientlyDiverse)
    }

    @Test fun chessboardCornerGeometryHasCorrectNormalizedArea(){
        val corners=(0 until 6).flatMap{r->
            (0 until 9).map{c->(120.0+c*30.0) to (80.0+r*30.0)}
        }
        val view=OpenVinsChessboardDiversity.view(
            corners,640,480)!!
        assertEquals((240.0*150.0)/(640*480),
            view.boardAreaFraction,1e-9)
        assertEquals(0.0,view.obliqueness,1e-9)
    }
    @Test fun perspectiveForeshorteningCountsEvenWithoutSkew(){
        val corners=(0 until 6).flatMap{r->
            val t=r/5.0
            val left=120.0+30.0*t
            val edge=240.0-60.0*t
            (0 until 9).map{c->
                (left+edge*c/8.0) to (80.0+150.0*t)
            }
        }
        val view=OpenVinsChessboardDiversity.view(
            corners,640,480)!!
        assertTrue("Perspective view must be observable",
            view.obliqueness>.15)
    }

}
