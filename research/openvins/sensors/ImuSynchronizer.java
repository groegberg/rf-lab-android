package com.openvins.android;

/** Both sensor streams have monotonic hardware timestamps but either can arrive first. */
public final class ImuSynchronizer {
    private static final int CAPACITY = 32;
    private static final long MAX_GAP_NS = 20_000_000L;
    private final Samples accel = new Samples();
    private final Samples gyro = new Samples();
    public float ax, ay, az, gx, gy, gz;
    public long timeNs;

    private static final class Samples {
        final long[] times = new long[CAPACITY];
        final float[] x = new float[CAPACITY], y = new float[CAPACITY], z = new float[CAPACITY];
        int first, count;
        long lastTime;
        int at(int offset) { return (first + offset) % CAPACITY; }
        void pop() { first = at(1); count--; }
        void add(long t, float vx, float vy, float vz) {
            if (t <= lastTime || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(vz)) return;
            lastTime = t;
            if (count == CAPACITY) pop();
            int i = at(count++);
            times[i] = t;
            x[i] = vx; y[i] = vy; z[i] = vz;
        }
    }

    public void addAccel(long t, float x, float y, float z) { accel.add(t, x, y, z); }
    public void addGyro(long t, float x, float y, float z) { gyro.add(t, x, y, z); }

    /** Emit each gyro once, with exact or linearly interpolated specific force at its timestamp. */
    public boolean poll() {
        while (gyro.count > 0 && accel.count > 0) {
            int g = gyro.first;
            long t = gyro.times[g];
            while (accel.count > 1 && accel.times[accel.at(1)] <= t) accel.pop();
            int a = accel.first;
            if (t < accel.times[a]) { gyro.pop(); continue; }
            int b = a;
            float f = 0f;
            if (t > accel.times[a]) {
                if (accel.count == 1) return false;
                b = accel.at(1);
                long gap = accel.times[b] - accel.times[a];
                if (gap > MAX_GAP_NS) { gyro.pop(); continue; }
                f = (float)(t - accel.times[a]) / gap;
            }
            ax = accel.x[a] + f * (accel.x[b] - accel.x[a]);
            ay = accel.y[a] + f * (accel.y[b] - accel.y[a]);
            az = accel.z[a] + f * (accel.z[b] - accel.z[a]);
            gx = gyro.x[g]; gy = gyro.y[g]; gz = gyro.z[g]; timeNs = t;
            gyro.pop();
            return true;
        }
        return false;
    }
}
