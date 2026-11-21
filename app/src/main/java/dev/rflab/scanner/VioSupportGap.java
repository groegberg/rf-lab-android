package dev.rflab.scanner;

/** A quality check for consecutive native camera poses used in map fusion. */
final class VioSupportGap {
    private static final long MIN_DURATION_NS = 2_000_000_000L;
    private static final long MAX_FRAME_GAP_NS = 500_000_000L;
    private static final double MIN_DISPLACEMENT_UNITS = 0.25;
    private long previousNs;
    private long startNs;
    private double startX, startY, startZ;

    boolean observe(long ns, double x, double y, double z, boolean bothCountersZero) {
        // Missing evidence and discontinuous timestamps cannot establish a run.
        if (ns <= previousNs || ns - previousNs > MAX_FRAME_GAP_NS || !bothCountersZero) {
            startNs = 0;
        }
        previousNs = ns;
        if (!bothCountersZero || ns <= 0) return false;
        if (startNs == 0) {
            startNs = ns;
            startX = x;
            startY = y;
            startZ = z;
        }
        double dx = x - startX;
        double dy = y - startY;
        double dz = z - startZ;
        return ns - startNs >= MIN_DURATION_NS
            && dx * dx + dy * dy + dz * dz >= MIN_DISPLACEMENT_UNITS * MIN_DISPLACEMENT_UNITS;
    }
}
