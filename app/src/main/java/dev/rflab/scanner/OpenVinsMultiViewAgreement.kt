package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.floor

/**
 * Conservative, deterministic geometric overlap gate for already aligned
 * DA3 RGB world points. Unrelated single-view points NEVER qualify merely
 * by belonging to the same world-space frame.
 *
 * A colored point is supported only if another PHOTOGRAPH contains a
 * spatially close point. Approximate/nonmetric diagnostic clouds are never
 * passed into this gate. Hash buckets make neighbor search bounded.
 *
 * This is NOT a substitute for pose graph BA, semantic planes or true
 * visibility/occlusion consistency, but rejects gross double surfaces.
 */
internal object OpenVinsMultiViewAgreement {
    data class Input(val photoIndex:Int,val points:List<SurveyDepth>)
    data class Result(val points:List<SurveyDepth>,
                      val tested:Int,val supported:Int,
                      val fraction:Double,val sourceFrames:Int,
                      val supportedFrames:Int,
                      val accepted:Boolean)

    private data class Cell(val x:Int,val y:Int,val z:Int)
    private data class Record(val point:SurveyDepth,val frame:Int)
    fun verify(frames:List<Input>,
               radius:Float=.16f,
               maxTotal:Int=80_000):Result {
        require(radius in .02f..1f && maxTotal in 100..180_000)
        if(frames.map{it.photoIndex}.distinct().size<2)
            return Result(emptyList(),0,0,0.0,frames.size,0,false)
        val records=ArrayList<Record>()
        for(frame in frames){
            if(records.size>=maxTotal)break
            val step=maxOf(1,(frame.points.size+2200-1)/2200)
            for(k in frame.points.indices step step){
                if(records.size>=maxTotal)break
                val p=frame.points[k]
                if(!p.x.isFinite() || !p.y.isFinite() ||
                   !p.z.isFinite() ||
                   maxOf(abs(p.x),abs(p.y),abs(p.z))>40f)continue
                records.add(Record(p,frame.photoIndex))
            }
        }
        if(records.size<100)return Result(emptyList(),records.size,0,
            0.0,frames.size,0,false)
        fun cell(p:SurveyDepth):Cell=Cell(
            floor(p.x/radius).toInt(),floor(p.y/radius).toInt(),
            floor(p.z/radius).toInt())
        val bins=HashMap<Cell,MutableList<Int>>()
        records.forEachIndexed{index,record->
            bins.getOrPut(cell(record.point)){ArrayList()}.add(index)
        }
        val supported=ArrayList<SurveyDepth>()
        val supportedFrames=HashSet<Int>()
        val d2=radius*radius
        for(record in records){
            val p=record.point
            val key=cell(p)
            var corroborated=false
            scan@for(dx in -1..1)for(dy in -1..1)for(dz in -1..1){
                for(index in bins[Cell(key.x+dx,key.y+dy,key.z+dz)]
                    ?:continue){
                    val q=records[index]
                    if(q.frame==record.frame)continue
                    val a=p.x-q.point.x
                    val b=p.y-q.point.y
                    val c=p.z-q.point.z
                    if(a*a+b*b+c*c<=d2){
                        corroborated=true
                        supportedFrames.add(record.frame)
                        supportedFrames.add(q.frame)
                        break@scan
                    }
                }
            }
            if(corroborated)supported.add(p)
        }
        val fraction=supported.size.toDouble()/records.size
        val passed=supported.size>=120 && fraction>=.18 &&
            supportedFrames.size>=2
        return Result(if(passed)supported else emptyList(),
            records.size,supported.size,fraction,frames.size,
            supportedFrames.size,passed)
    }
}
