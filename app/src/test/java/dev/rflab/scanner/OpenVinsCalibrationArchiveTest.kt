package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class OpenVinsCalibrationArchiveTest {
    @Test fun exportsOnlyNativeImuPoseCameraCalibrationAndRgb() {
        val root=java.nio.file.Files.createTempDirectory("rflab-vio-test").toFile()
        try{
            val session=File(root,"native-session").apply{mkdirs()}
            File(session,"vio_camera_trajectory.csv").writeText(
                "timestamp_ns,x,y,z,qw,qx,qy,qz\n123456789,0,0,0,1,0,0,0")
            File(session,"imu0.csv").writeText(
                "timestamp,omega_x,omega_y,omega_z,alpha_x,alpha_y,alpha_z")
            File(session,"config").mkdirs()
            File(session,"config/kalibr_imucam_chain.yaml")
                .writeText("cam0: test camera data")
            File(session,"rgb").mkdirs()
            File(session,"rgb/1234567890123.jpg")
                .writeBytes(byteArrayOf(0x7f,0x45,0x4c,0x46))
            File(session,"other_password.txt").writeText("SECRET")
            val out=ByteArrayOutputStream()
            val result=OpenVinsCalibrationArchive.export(
                session,out,"VIO alignment diagnostics")
            assertEquals(4,result.files)
            val entries=ArrayList<String>()
            ZipInputStream(out.toByteArray().inputStream()).use{zip->
                while(true){
                    val entry=zip.nextEntry?:break
                    entries.add(entry.name)
                    zip.closeEntry()
                }
            }
            assertTrue(entries.contains(
                "OpenVINS/config/kalibr_imucam_chain.yaml"))
            assertTrue(entries.contains(
                "OpenVINS/RF_LAB_CAPTURE_DIAGNOSTICS.txt"))
            assertFalse(entries.any{it.contains("other_password")})
        }finally{
            root.deleteRecursively()
        }
    }
}
