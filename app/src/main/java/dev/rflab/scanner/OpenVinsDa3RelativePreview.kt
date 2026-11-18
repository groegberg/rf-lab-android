package dev.rflab.scanner

import kotlin.math.max

/**
 * Immediately visible, COLORED 2.5D DA3 depth relief of exactly ONE RGB
 * photograph. It uses actual RGB pixels and actual inferred relative depth.
 *
 * Camera-local normalized visualization coordinates are chosen for display
 * ONLY: neither metric distance nor IMU pose, multi-view registration, room
 * dimensions or persistent floorplan is asserted. This is the diagnostics
 * fallback when unverified Pixel camera-IMU calibration blocks mapping.
 */
internal object OpenVinsDa3RelativePreview {
    private const val STEP=8
    fun make(prediction:Da3DepthEngine.Prediction):List<SurveyDepth> {
        val w=Da3DepthEngine.WIDTH
        val h=Da3DepthEngine.HEIGHT
        val depth=prediction.relativeDepth
        if(prediction.image.width!=w || prediction.image.height!=h ||
            depth.size!=w*h)return emptyList()
        val inverseSamples=ArrayList<Float>(w*h/100)
        for(v in 0 until h step 10)
            for(u in 0 until w step 10){
                val d=depth[v*w+u]
                if(d.isFinite() && d>1e-6f)
                    inverseSamples.add(1f/d)
            }
        if(inverseSamples.size<200)return emptyList()
        inverseSamples.sort()
        val near=inverseSamples[(inverseSamples.lastIndex*.03).toInt()]
        val far=inverseSamples[(inverseSamples.lastIndex*.97).toInt()]
        val span=max(1e-6f,far-near)
        // A normalized preview, not a manufactured physical depth scale.
        val focal=w*.86f
        val result=ArrayList<SurveyDepth>(w*h/(STEP*STEP))
        for(v in (h*.025f).toInt() until (h*.975f).toInt() step STEP)
            for(u in (w*.025f).toInt() until (w*.975f).toInt() step STEP){
                val predicted=depth[v*w+u]
                if(!predicted.isFinite() || predicted<=1e-6f)continue
                val pseudoDepth=(1f/predicted-near)/span
                if(pseudoDepth !in .0f..1.0f)continue
                // Relative 0.7..3.7 model units; never use as metres.
                val z=.7f+3.0f*pseudoDepth
                val x=(u+.5f-w*.5f)/focal*z
                val y=-(v+.5f-h*.5f)/focal*z
                result.add(SurveyDepth(x,y,-z,confidence=.48f,
                    argb=prediction.image.getPixel(u,v) or (255 shl 24),
                    rawDepth=false))
            }
        return result
    }
}
