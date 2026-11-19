package dev.rflab.scanner

import kotlin.math.max

/**
 * Show DA3's ACTUAL relative-depth geometry, not reciprocal depth.
 * When Camera2 K/radtan is available, back-project every pixel with the
 * REAL intrinsics, exactly undoing the DA3 rotation and aspect-fill crop.
 * Missing/unverified K only gets a clearly non-calibrated fallback relief.
 *
 * The robust depth normalization is solely an affine visualization scale,
 * NOT physical camera distance. World fusion requires multiview registration.
 */
internal object OpenVinsDa3RelativePreview {
    private const val STEP=8
    fun make(
        prediction:Da3DepthEngine.Prediction,
        calibration:OpenVinsDa3Geometry.Calibration?=null,
        sourceWidth:Int=0,sourceHeight:Int=0
    ):List<SurveyDepth> {
        val w=Da3DepthEngine.WIDTH
        val h=Da3DepthEngine.HEIGHT
        val depth=prediction.relativeDepth
        if(prediction.image.width!=w || prediction.image.height!=h ||
            depth.size!=w*h)return emptyList()
        val valid=ArrayList<Float>(w*h/100)
        for(v in 0 until h step 10)
            for(u in 0 until w step 10){
                val d=depth[v*w+u]
                if(d.isFinite() && d>1e-6f)valid.add(d)
            }
        if(valid.size<200)return emptyList()
        valid.sort()
        val near=valid[(valid.lastIndex*.03).toInt()]
        val far=valid[(valid.lastIndex*.97).toInt()]
        val span=max(1e-6f,far-near)
        val hasK=calibration!=null &&
            calibration.compatibleWith(sourceWidth,sourceHeight)
        val fallbackFocal=w*.86f
        val result=ArrayList<SurveyDepth>(w*h/(STEP*STEP))
        for(v in (h*.025f).toInt() until (h*.975f).toInt() step STEP)
            for(u in (w*.025f).toInt() until (w*.975f).toInt() step STEP){
                val predicted=depth[v*w+u]
                if(!predicted.isFinite() || predicted<=1e-6f)continue
                val relative=((predicted-near)/span)
                if(relative !in 0f..1f)continue
                // Same per-view relative display mapping; no manufactured SI.
                val z=.7f+3.0f*relative
                val ray=if(hasK)
                    OpenVinsDa3Geometry.modelRay(u.toDouble(),v.toDouble(),
                        calibration!!,sourceWidth,sourceHeight,
                        prediction.rotationDegrees)
                    else null
                if(hasK && ray==null)continue
                val x=if(ray!=null)ray[0].toFloat()*z
                    else (u+.5f-w*.5f)/fallbackFocal*z
                val y=if(ray!=null)-ray[1].toFloat()*z
                    else -(v+.5f-h*.5f)/fallbackFocal*z
                result.add(SurveyDepth(x,y,-z,confidence=.48f,
                    argb=prediction.image.getPixel(u,v) or (255 shl 24),
                    rawDepth=false))
            }
        return result
    }
}
