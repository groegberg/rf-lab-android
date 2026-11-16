package dev.rflab.scanner

/** Native VIO, legacy ARCore and controlled Camera2 photo acquisition. */
internal enum class ScanTrackingBackend(val title: String, val hint: String) {
    OPENVINS("OpenVINS · Kamera + IMU",
        "Native MSCKF-VIO · separate Koordinatenbasis · unkalibriert"),
    ARCORE("ARCore · RGB-D-Scan",
        "Bisheriger räumlicher Scan mit ARCore und Depth"),
    CAMERA2("Camera2 · Foto / Offline",
        "Fotoaufnahme mit anschließender Rekonstruktion und DA3")
}
