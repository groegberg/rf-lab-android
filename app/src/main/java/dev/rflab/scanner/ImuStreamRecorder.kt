package dev.rflab.scanner

import android.hardware.SensorEvent
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Full-rate local gyro/accelerometer stream for future visual-inertial SLAM.
 * On-device and continuous during each camera session. Uses original
 * SensorEvent.timestamp, not UI callback arrival time.
 *
 * No claim of synchronized camera/IMU timestamps is made unless Camera2
 * explicitly declares SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME.
 */
internal class ImuStreamRecorder(private val session: File) : AutoCloseable {
    private val file = File(session, "imu_" +
        SystemClock.elapsedRealtimeNanos().toString() + ".csv")
    private val writer: BufferedWriter = file.outputStream()
        .bufferedWriter(Charsets.UTF_8, 128 * 1024)
    private var rows = 0
    init {
        writer.write("sensor_timestamp_ns,sensor_type,x,y,z,accuracy,callback_elapsed_ns\n")
    }
    fun append(event: SensorEvent) {
        if (event.values.size < 3) return
        val v = event.values
        val type = event.sensor.type
        writer.write(event.timestamp.toString())
        writer.write(",")
        writer.write(type.toString())
        writer.write(",")
        writer.write(String.format(Locale.US,"%.9f,%.9f,%.9f",
            v[0],v[1],v[2]))
        writer.write(",")
        writer.write(event.accuracy.toString())
        writer.write(",")
        writer.write(SystemClock.elapsedRealtimeNanos().toString())
        writer.newLine()
        rows++
        if (rows % 200 == 0) writer.flush()
    }
    override fun close() {
        writer.flush()
        writer.close()
    }
}
