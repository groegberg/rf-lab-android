package dev.rflab.scanner

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import java.io.File

/**
 * Camera2 repeating-capture timing log, timestamp-keyed for YUV frames.
 * Exposure midpoint and rolling shutter are essential for future precise VIO.
 * Owned by the Camera2 callback thread; never guess timing from arrival time.
 */
internal class CameraTimingRecorder(session: File) : AutoCloseable {
    private val file=File(session,
        "camera_timing_"+SystemClock.elapsedRealtimeNanos()+".csv")
    private val writer=file.bufferedWriter(Charsets.UTF_8,128*1024)
    private var rows=0
    init {
        writer.write("sensor_timestamp_ns,exposure_ns,frame_duration_ns,"+
            "rolling_shutter_skew_ns,iso,focus_diopters,crop_left,crop_top,"+
            "crop_right,crop_bottom\n")
    }

    fun append(result: TotalCaptureResult) {
        val timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if(timestamp<=0L)return
        val exposure=result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val duration=result.get(CaptureResult.SENSOR_FRAME_DURATION)
        val shutter=result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
        val iso=result.get(CaptureResult.SENSOR_SENSITIVITY)
        val focus=result.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val crop=result.get(CaptureResult.SCALER_CROP_REGION)
        writer.write(timestamp.toString())
        writer.write(","+(exposure?.toString()?:""))
        writer.write(","+(duration?.toString()?:""))
        writer.write(","+(shutter?.toString()?:""))
        writer.write(","+(iso?.toString()?:""))
        writer.write(","+(focus?.toString()?:""))
        writer.write(","+(crop?.left?.toString()?:""))
        writer.write(","+(crop?.top?.toString()?:""))
        writer.write(","+(crop?.right?.toString()?:""))
        writer.write(","+(crop?.bottom?.toString()?:""))
        writer.newLine()
        rows++
        if(rows%60==0)writer.flush()
    }

    override fun close() {
        writer.flush();writer.close()
    }
}
