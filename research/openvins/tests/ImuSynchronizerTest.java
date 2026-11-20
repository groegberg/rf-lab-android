package com.openvins.android;

public final class ImuSynchronizerTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    static void accel(ImuSynchronizer s, int ms) { s.addAccel(ms*1000000L, ms, -ms, 2*ms); }
    static void gyro(ImuSynchronizer s, int ms) { s.addGyro(ms*1000000L, 1, 2, 3); }
    static void sample(ImuSynchronizer s, int ms) {
        check(s.poll() && s.timeNs == ms*1000000L && s.ax == ms && s.ay == -ms && s.az == 2*ms && s.gy == 2);
    }
    public static void main(String[] args) {
        ImuSynchronizer s = new ImuSynchronizer();
        gyro(s, 1); accel(s, 2); check(!s.poll());
        gyro(s, 3); gyro(s, 4); check(!s.poll());
        accel(s, 6); sample(s, 3); sample(s, 4); check(!s.poll());
        gyro(s, 6); sample(s, 6); gyro(s, 6); check(!s.poll());
        accel(s, 10); accel(s, 14); gyro(s, 8); sample(s, 8);
        gyro(s, 12); sample(s, 12);
        gyro(s, 15); accel(s, 40); check(!s.poll());
        gyro(s, 40); sample(s, 40);
        s.addAccel(41000000, Float.NaN, 0, 0); accel(s, 42); gyro(s, 41); sample(s, 41);
        for (int t = 50; t <= 100; t++) gyro(s, t);
        accel(s, 99); accel(s, 100); sample(s, 99); sample(s, 100); check(!s.poll());
    }
}
