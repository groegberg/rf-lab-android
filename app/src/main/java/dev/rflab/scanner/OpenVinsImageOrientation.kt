package dev.rflab.scanner

import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Align *native* unrotated Camera2 YUV pixels with gravity before passing
 * the JPEG to the fixed portrait DA3-Small LiteRT model.
 *
 * Device UI orientation is intentionally NOT used: the VIO activity is
 * landscape-locked while the person can physically roll the phone.
 * Gravity is measured in the Android IMU sensor frame, then converted via
 * the very T_cam_imu seed recorded with this session.
 *
 * This corrects IMAGE DISPLAY/DA3 INPUT orientation only. VIO receives
 * exactly the same raw camera sensor frames; its K and R are NOT rotated.
 * Gravity does not disambiguate arbitrary camera yaw or a vertical optical
 * axis. Unknown/fast/oblique cases are rejected for 3D fusion.
 */
internal object OpenVinsImageOrientation {
    data class Imu(val timestampNs:Long, val ax:Double,val ay:Double,
                   val az:Double, val wx:Double,val wy:Double,val wz:Double)
    data class Estimate(val degrees:Int,val errorDegrees:Double,
                        val gravityPlanarRatio:Double,
                        val samples:Int,val usableForFusion:Boolean)

    fun readImu(session:File):List<Imu> {
        val file=File(session,"imu_openvins.csv")
        if(!file.isFile || file.length() !in 10L..(36L*1024*1024))return emptyList()
        return file.useLines{parseImu(it).toList()}
    }

    fun parseImu(lines:Sequence<String>,limit:Int=70_000):List<Imu>{
        val result=ArrayList<Imu>()
        var last=0L
        for(line in lines.drop(1)){
            if(result.size>=limit)break
            val parts=line.split(',')
            if(parts.size<7)continue
            val time=parts[0].trim().toLongOrNull() ?:continue
            if(time<=last)continue
            val values=DoubleArray(6){i->
                parts[i+1].trim().toDoubleOrNull()?:Double.NaN
            }
            if(values.any{!it.isFinite()})continue
            result.add(Imu(time,values[3],values[4],values[5],
                values[0],values[1],values[2]))
            last=time
        }
        return result
    }

    /** Parse the initial Kalibr-style camera <- IMU rotation, no translation. */
    fun camFromImu(calibrationYaml:String):DoubleArray?{
        val lines=calibrationYaml.lines()
        val index=lines.indexOfFirst{it.trim()=="T_cam_imu:"}
        if(index<0 || index+3>=lines.size)return null
        val rotation=DoubleArray(9)
        for(row in 0..2){
            val line=lines[index+row+1]
            val match=Regex("""-\s*\[([^]]+)]""").find(line)?:return null
            val values=match.groupValues[1].split(',').map{
                it.trim().toDoubleOrNull() ?:return null
            }
            if(values.size!=4 || values.any{!it.isFinite()})return null
            for(col in 0..2)rotation[row*3+col]=values[col]
        }
        for(i in 0..2){
            val len=sqrt((0..2).sumOf{j->
                rotation[i*3+j]*rotation[i*3+j]
            })
            if(abs(len-1.0)>.04)return null
            for(j in i+1..2){
                val dot=(0..2).sumOf{k->rotation[i*3+k]*rotation[j*3+k]}
                if(abs(dot)>.045)return null
            }
        }
        val r=rotation
        val det=r[0]*(r[4]*r[8]-r[5]*r[7]) -
            r[1]*(r[3]*r[8]-r[5]*r[6]) +
            r[2]*(r[3]*r[7]-r[4]*r[6])
        if(det !in .90..1.10)return null
        return rotation
    }

    fun estimate(imu:List<Imu>,timestampNs:Long,
                 camFromImu:DoubleArray,
                 windowNs:Long=220_000_000L):Estimate?{
        if(timestampNs<=0 || imu.isEmpty() || camFromImu.size!=9 ||
            camFromImu.any{!it.isFinite()})return null
        var lo=0;var hi=imu.size
        while(lo<hi){
            val mid=(lo+hi)/2
            if(imu[mid].timestampNs<timestampNs-windowNs)lo=mid+1
            else hi=mid
        }
        var ax=0.0;var ay=0.0;var az=0.0
        var count=0
        var spin=0.0
        for(i in lo until imu.size){
            val p=imu[i]
            if(p.timestampNs>timestampNs+windowNs)break
            val length=sqrt(p.ax*p.ax+p.ay*p.ay+p.az*p.az)
            if(length !in 7.5..12.2)continue
            ax+=p.ax;ay+=p.ay;az+=p.az
            spin+=sqrt(p.wx*p.wx+p.wy*p.wy+p.wz*p.wz)
            count++
        }
        if(count<7)return null
        ax/=count;ay/=count;az/=count;spin/=count
        val upX=camFromImu[0]*ax+camFromImu[1]*ay+camFromImu[2]*az
        val upY=camFromImu[3]*ax+camFromImu[4]*ay+camFromImu[5]*az
        val planar=hypot(upX,upY)
        val g=sqrt(ax*ax+ay*ay+az*az)
        if(g !in 8.2..11.5 || planar/g<.32)return null
        // Bitmap rotations are clockwise in the Android pixel coordinate
        // convention (x-right, y-down). Select up-vector to model -Y.
        val scores=doubleArrayOf(-upY,-upX,upY,upX)
        val rotations=intArrayOf(0,90,180,270)
        val best=scores.indices.maxByOrNull{scores[it]} ?: return null
        val err=acos((scores[best]/planar).coerceIn(-1.0,1.0))*180.0/Math.PI
        val safe=err<=24.0 && spin<1.8 && planar/g>=.48
        return Estimate(rotations[best],err,planar/g,count,safe)
    }
}
