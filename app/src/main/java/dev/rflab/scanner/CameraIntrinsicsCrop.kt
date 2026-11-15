package dev.rflab.scanner

import kotlin.math.min

/**
 * Correctly recognizes that Camera2 lens intrinsics are referenced to the
 * PRE-CORRECTION active array, not necessarily the processed YUV active array.
 * Any crop from precorrection sensor pixels into a YUV buffer is still a
 * *calibration approximation*, not grounds for a metric reconstruction claim.
 */
internal object CameraIntrinsicsCrop {
    data class K(val fx:Double,val fy:Double,val cx:Double,val cy:Double)

    fun approximate(
        lens:DoubleArray, preRect:DoubleArray,
        width:Int,height:Int
    ):K?{
        if(lens.size<4||preRect.size!=4||width<1||height<1 ||
            lens[0]<=0||lens[1]<=0)return null
        val aw=preRect[2]-preRect[0]
        val ah=preRect[3]-preRect[1]
        if(aw<=0||ah<=0)return null
        val ratio=width.toDouble()/height
        val sourceW=min(aw,ah*ratio)
        val sourceH=min(ah,aw/ratio)
        val x0=preRect[0]+(aw-sourceW)/2
        val y0=preRect[1]+(ah-sourceH)/2
        val sx=width/sourceW
        val sy=height/sourceH
        return K(lens[0]*sx,lens[1]*sy,
            (lens[2]-x0)*sx,(lens[3]-y0)*sy)
    }
}
