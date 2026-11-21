package com.openvins.android;

import java.io.File;
import java.nio.file.Files;

public final class CameraSeedTest {
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    private static void close(double[] actual, double... expected) {
        check(actual != null && actual.length == expected.length);
        for (int i = 0; i < actual.length; i++) check(Math.abs(actual[i] - expected[i]) < 1e-6);
    }

    public static void main(String[] args) throws Exception {
        float[] k = {2800, 2700, 2010, 1490};
        close(CameraSeed.previewIntrinsics(k, 4000, 3000, 640, 480), 448, 432, 321.1, 237.9);
        close(CameraSeed.previewIntrinsics(k, 4000, 3000, 640, 360), 448, 432, 321.1, 177.9);
        close(CameraSeed.previewIntrinsics(k, 4000, 3000, 480, 480), 448, 432, 241.1, 237.9);
        check(CameraSeed.previewIntrinsics(k, 0, 3000, 640, 480) == null);
        k[2] = Float.NaN;
        check(CameraSeed.previewIntrinsics(k, 4000, 3000, 640, 480) == null);

        File folder = Files.createTempDirectory("rflab-calibration").toFile();
        File chain = new File(folder, "kalibr_imucam_chain.yaml");
        File marker = new File(folder, "rflab_user_calibrated.marker");
        try {
            check(!CameraSeed.hasUserCalibration(folder));
            check(chain.createNewFile());
            check(!CameraSeed.hasUserCalibration(folder));
            check(marker.createNewFile());
            check(CameraSeed.hasUserCalibration(folder));
        } finally {
            marker.delete();
            chain.delete();
            folder.delete();
        }
    }
}
