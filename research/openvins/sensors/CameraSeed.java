package com.openvins.android;

import java.io.File;

/** Geometry of an uncorrected, centered Camera2 preview at 1x zoom. */
public final class CameraSeed {
    private CameraSeed() {}

    public static boolean hasUserCalibration(File folder) {
        return new File(folder, "rflab_user_calibrated.marker").isFile()
            && new File(folder, "kalibr_imucam_chain.yaml").isFile();
    }

    public static double[] previewIntrinsics(float[] k, int arrayWidth, int arrayHeight,
                                             int width, int height) {
        if (k == null || k.length < 4 || arrayWidth <= 0 || arrayHeight <= 0 ||
                width <= 0 || height <= 0 || k[0] <= 0 || k[1] <= 0) return null;
        for (int i = 0; i < 4; i++) {
            if (!Float.isFinite(k[i])) return null;
        }

        // Camera2 reports K relative to the top-left of the pre-correction array.
        // A centered crop removes excess pixels along one axis before scaling.
        double scale = Math.max((double) width / arrayWidth, (double) height / arrayHeight);
        double cropLeft = (arrayWidth - width / scale) / 2;
        double cropTop = (arrayHeight - height / scale) / 2;
        // Camera2 uses half-integer pixel centers. OpenCV uses integer centers.
        return new double[] {k[0] * scale, k[1] * scale,
            (k[2] - cropLeft) * scale - 0.5, (k[3] - cropTop) * scale - 0.5};
    }
}
