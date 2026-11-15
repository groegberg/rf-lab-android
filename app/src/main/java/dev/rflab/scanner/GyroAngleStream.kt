package dev.rflab.scanner

import java.io.File
import kotlin.math.sqrt

/** Calibrated gyro angular travel; only comparable to the camera if
 * Camera2 declares SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME. Does not
 * assume a calibrated IMU-to-camera extrinsic or infer translation. */
internal class GyroAngleStream private constructor(
    private val samples: List<Sample>) {
    data class Sample(val ns:Long, val wx:Double,
                      val wy:Double, val wz:Double)
    fun angleBetween(from:Long,to:Long):Double? {
        if(to<=from||samples.size<2)return null
        val part=samples.filter{it.ns>=from-20_000_000L&&it.ns<=to+20_000_000L}
        if(part.size<3 || part.first().ns>from+40_000_000L ||
            part.last().ns<to-40_000_000L)return null
        var sum=0.0
        for(i in 1 until part.size){
            val a=part[i-1];val b=part[i]
            val mid=(a.ns+b.ns)/2
            if(mid !in from..to)continue
            val dt=(b.ns-a.ns)*1e-9
            if(dt<=0.0||dt>.1)continue
            val gx=(a.wx+b.wx)/2;val gy=(a.wy+b.wy)/2
            val gz=(a.wz+b.wz)/2
            sum+=sqrt(gx*gx+gy*gy+gz*gz)*dt
        }
        return sum
    }
    /**
     * SO(3) delta from calibrated gyro, with Android-reported camera extrinsic.
     * The lens quaternion maps Android sensor axes -> camera axes. Only valid
     * when timestamps share REALTIME and poseReference is not UNDEFINED.
     * Returns old-camera -> new-camera rotation, row-major.
     */
    fun cameraRotationBetween(from:Long,to:Long,
                              sensorToCamera:DoubleArray):DoubleArray? {
        if(sensorToCamera.size!=4||to<=from)return null
        val n= kotlin.math.sqrt(sensorToCamera.sumOf{it*it})
        if(n<.95||n>1.05)return null
        val x=sensorToCamera[0]/n;val y=sensorToCamera[1]/n
        val z=sensorToCamera[2]/n;val w=sensorToCamera[3]/n
        val extrinsic=doubleArrayOf(
            1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),
            2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),
            2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y))
        fun mul(a:DoubleArray,b:DoubleArray):DoubleArray {
            val out=DoubleArray(9)
            for(i in 0..2)for(j in 0..2)for(k in 0..2)
                out[3*i+j]+=a[3*i+k]*b[3*k+j]
            return out
        }
        var R=doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0)
        val subset=samples.filter{it.ns>=from-20_000_000L&&
            it.ns<=to+20_000_000L}
        if(subset.size<3||subset.first().ns>from+40_000_000L||
            subset.last().ns<to-40_000_000L)return null
        var nUpdates=0
        for(i in 1 until subset.size){
            val a=subset[i-1];val b=subset[i]
            val middle=(a.ns+b.ns)/2
            if(middle !in from..to)continue
            val dt=(b.ns-a.ns)*1e-9
            if(dt<=0.0||dt>.1)continue
            val o=doubleArrayOf((a.wx+b.wx)/2,(a.wy+b.wy)/2,
                (a.wz+b.wz)/2)
            val v=DoubleArray(3){j->
                -(extrinsic[3*j]*o[0]+extrinsic[3*j+1]*o[1]+
                    extrinsic[3*j+2]*o[2])*dt
            }
            val theta=kotlin.math.sqrt(v.sumOf{it*it})
            val dq=if(theta<1e-8){
                doubleArrayOf(1.0,-v[2],v[1],
                    v[2],1.0,-v[0],-v[1],v[0],1.0)
            } else {
                val xx=v[0]/theta;val yy=v[1]/theta;val zz=v[2]/theta
                val c=kotlin.math.cos(theta)
                val ss=kotlin.math.sin(theta)
                val a1=1-c
                doubleArrayOf(
                    c+a1*xx*xx,a1*xx*yy-ss*zz,a1*xx*zz+ss*yy,
                    a1*yy*xx+ss*zz,c+a1*yy*yy,a1*yy*zz-ss*xx,
                    a1*zz*xx-ss*yy,a1*zz*yy+ss*xx,c+a1*zz*zz)
            }
            R=mul(dq,R)
            nUpdates++
        }
        return if(nUpdates>=2)R else null
    }

    companion object {
        fun empty()=GyroAngleStream(emptyList())
        fun fromSession(session:File):GyroAngleStream{
            val samples=ArrayList<Sample>()
            session.listFiles()?.filter{
                it.name.startsWith("imu_")&&it.name.endsWith(".csv")
            }?.sortedBy{it.name}?.forEach{file->
                file.useLines{lines->
                    lines.drop(1).forEach{line->
                        val p=line.split(',')
                        // Android Sensor.TYPE_GYROSCOPE = 4
                        if(p.size<5||p[1]!="4")return@forEach
                        try{samples.add(Sample(p[0].toLong(),p[2].toDouble(),
                            p[3].toDouble(),p[4].toDouble()))}
                        catch(_:NumberFormatException){}
                    }
                }
            }
            return GyroAngleStream(samples.sortedBy{it.ns})
        }
    }
}
