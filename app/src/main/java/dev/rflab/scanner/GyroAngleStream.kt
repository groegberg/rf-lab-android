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
