package com.openvins.android;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;
import android.util.Log;

public final class TrackingSensors {
    // MainActivity creates a dedicated new HandlerThread each time it registers sensors.
    private static final ThreadLocal<ImuSynchronizer> STREAM = new ThreadLocal<>();
    private TrackingSensors() {}

    public static ImuSynchronizer accept(SensorEvent e) {
        if (e == null || e.values.length < 3) return null;
        int type = e.sensor.getType();
        if (type != Sensor.TYPE_ACCELEROMETER && type != Sensor.TYPE_GYROSCOPE) return null;
        ImuSynchronizer s = STREAM.get();
        if (s == null) { s = new ImuSynchronizer(); STREAM.set(s); }
        float[] v = e.values;
        if (type == Sensor.TYPE_ACCELEROMETER) s.addAccel(e.timestamp, v[0], v[1], v[2]);
        else s.addGyro(e.timestamp, v[0], v[1], v[2]);
        return s;
    }

    // Keep the selected camera geometry independent of layout changes.
    public static Size preview(Context context, String id) {
        if (id == null) return null;
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            StreamConfigurationMap map = cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map == null ? null : choose(map.getOutputSizes(ImageFormat.YUV_420_888));
        } catch (CameraAccessException ex) {
            Log.w("OpenVINS", "Cannot query YUV tracking sizes", ex);
            return null;
        }
    }

    static Size choose(Size[] sizes) {
        if (sizes == null) return null;
        Size best = null;
        long rank = Long.MAX_VALUE;
        for (Size size : sizes) {
            int w = size.getWidth(), h = size.getHeight();
            if (w <= 0 || h <= 0 || (w & 1) != 0 || (h & 1) != 0) continue;
            long diff = Math.abs(w - 640L) + Math.abs(h - 480L);
            long score = diff + ((w > 640 || h > 480) ? (1L << 32) : 0L);
            if (score < rank || (score == rank && w > best.getWidth())) {
                rank = score;
                best = size;
            }
        }
        return best;
    }
}
