package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.max

/**
 * Explicitly NONMETRIC world-view diagnostic. Every source RGB sample is
 * back-projected with its own actual timestamp-associated OpenVINS camera
 * orientation and position. Fitted ORB/triangulated DA3 depths take priority.
 * Frames without a valid fit get the SAME bounded display depth range, not a
 * claim that DA3's independently predicted depths share a physical scale.
 *
 * The result MUST NOT enter the trusted, persistent RoomScanStore namespace.
 */
internal object OpenVinsDa3DiagnosticWorld {
    data class Frame(
        val photoIndex:Int,
        val poseIndex:Int,
        val rays:List<OpenVinsDa3Fusion.RaySample>,
        val rawLow:Float,
        val rawHigh:Float,
        val rotationReliable:Boolean
    )

    data class Result(
        val points:List<SurveyDepth>,
        val frames:Int,
        val geometryAlignedFrames:Int,
        val approximateFrames:Int,
        val unreliableRotationFrames:Int
    )

    fun assemble(
        frames:List<Frame>,
        fits:Map<Int,OpenVinsDa3Alignment.Fit>,
        useGeometricFits:Boolean,
        maxPoints:Int=140_000
    ):Result {
        require(maxPoints in 100..180_000)
        val out=ArrayList<SurveyDepth>(minOf(80_000,maxPoints))
        var accepted=0
        var aligned=0
        var approximated=0
        var badRotation=0
        for(frame in frames.sortedBy{it.photoIndex}) {
            if(out.size>=maxPoints)break
            val fit=if(useGeometricFits) fits[frame.photoIndex] else null
            var mapped=if(fit!=null)
                OpenVinsDa3Fusion.align(frame.rays,fit) else emptyList()
            val fitted=fit!=null && mapped.size>=50
            if(!fitted)
                mapped=approximate(frame)
            if(mapped.size<50)continue
            val remaining=maxPoints-out.size
            if(mapped.size<=remaining)out.addAll(mapped)
            else{
                val step=max(1,(mapped.size+remaining-1)/remaining)
                for(k in mapped.indices step step)
                    if(out.size<maxPoints)out.add(mapped[k])
            }
            accepted++
            if(fitted)aligned++ else approximated++
            if(!frame.rotationReliable)badRotation++
        }
        return Result(out,accepted,aligned,approximated,badRotation)
    }

    /** Fixed visualization depth range across frames, NOT physical scale. */
    private fun approximate(frame:Frame):List<SurveyDepth> {
        val low=frame.rawLow.toDouble()
        val high=frame.rawHigh.toDouble()
        if(!low.isFinite() || !high.isFinite() || low<=0.0 ||
            high<=low*1.025)return emptyList()
        // Same inverse-depth percentile direction as the DA3 2.5D preview.
        val near=1.0/high
        val far=1.0/low
        val spread=far-near
        if(spread<=1e-6 || !spread.isFinite())return emptyList()
        val out=ArrayList<SurveyDepth>(frame.rays.size)
        for(p in frame.rays) {
            val raw=p.relativeInverse.toDouble()
            if(raw<=0 || !raw.isFinite())continue
            val ratio=((1.0/raw-near)/spread)
            if(!ratio.isFinite() || ratio<0.0 || ratio>1.0)continue
            val displayZ=.7+3.0*ratio
            val x=p.center[0]+(p.worldAtAxialUnit[0]-p.center[0])*displayZ
            val y=p.center[1]+(p.worldAtAxialUnit[1]-p.center[1])*displayZ
            val z=p.center[2]+(p.worldAtAxialUnit[2]-p.center[2])*displayZ
            if(!x.isFinite() || !y.isFinite() || !z.isFinite() ||
                maxOf(abs(x),abs(y),abs(z))>300.0)continue
            out.add(SurveyDepth(x.toFloat(),y.toFloat(),z.toFloat(),
                confidence=.33f,argb=p.argb,rawDepth=false))
        }
        return out
    }
}
