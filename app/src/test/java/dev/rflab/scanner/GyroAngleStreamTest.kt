package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.PI

class GyroAngleStreamTest {
    @Test fun calibratedGyroscopeIsIntegratedInActualNanoseconds() {
        val dir=Files.createTempDirectory("rf-lab-gyro").toFile()
        try {
            File(dir,"imu_1.csv").writeText(buildString {
                append("sensor_timestamp_ns,sensor_type,x,y,z,accuracy,callback_elapsed_ns\n")
                for(i in 0..100)append(
                    (i*10_000_000L).toString()+",4,0,0,"+
                        PI+",3,"+(i*10_000_000L)+"\n")
            })
            val gyro=GyroAngleStream.fromSession(dir)
            val rad=gyro.angleBetween(0L,1_000_000_000L)
            assertNotNull(rad)
            assertEquals(PI,rad!!,.11)
        }finally{dir.deleteRecursively()}
    }
    @Test fun gyroCameraRotationUsesReportedExtrinsic() {
        val dir=Files.createTempDirectory("rf-lab-gyro-rotation").toFile()
        try {
            File(dir,"imu_2.csv").writeText(buildString {
                append("sensor_timestamp_ns,sensor_type,x,y,z,accuracy,callback_elapsed_ns\n")
                for(i in 0..100)append(
                    (i*10_000_000L).toString()+",4,0,0,"+
                        (PI/2).toString()+",3,"+(i*10_000_000L)+"\n")
            })
            val gyro=GyroAngleStream.fromSession(dir)
            val R=gyro.cameraRotationBetween(0L,1_000_000_000L,
                doubleArrayOf(0.0,0.0,0.0,1.0))
            assertNotNull(R)
            assertEquals(0.0,R!![0],.15)
            assertEquals(1.0,R[1],.15)
            assertEquals(-1.0,R[3],.15)
        } finally {dir.deleteRecursively()}
    }

    @Test fun noSensorSamplesDoesNotInventAnOrientation() {
        assertNull(GyroAngleStream.empty().angleBetween(0L,1_000_000_000L))
    }
}
