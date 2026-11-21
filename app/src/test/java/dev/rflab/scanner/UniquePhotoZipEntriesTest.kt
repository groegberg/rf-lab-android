package dev.rflab.scanner

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class UniquePhotoZipEntriesTest {
    @Test fun repeatedOpenVinsImuCsvIsIncludedExactlyOnce() {
        val folder=Files.createTempDirectory("rflab-openvins-export-test").toFile()
        try{
            val imu=java.io.File(folder,"imu_openvins.csv").apply{
                writeText("timestamp,omega_x\n12345,0.2")
            }
            val pose=java.io.File(folder,"external_vio_trajectory.csv").apply{
                writeText("timestamp_ns,x\n12345,0.1")
            }
            val out=ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                val writer=UniquePhotoZipEntries(zip)
                assertTrue(writer.add(pose))
                assertTrue(writer.add(imu))
                assertFalse(writer.add(imu))
                assertTrue(writer.add(imu,
                    "external_calibration/imu_openvins.csv"))
            }
            val names=ArrayList<String>()
            ZipInputStream(out.toByteArray().inputStream()).use{zip->
                while(true){
                    val entry=zip.nextEntry?:break
                    names.add(entry.name)
                    zip.closeEntry()
                }
            }
            assertEquals(3,names.size)
            assertEquals(1,names.count{it=="imu_openvins.csv"})
        }finally{folder.deleteRecursively()}
    }

    @Test fun rejectsUnsafeArchiveNames() {
        val folder=Files.createTempDirectory("rflab-zip-path-test").toFile()
        try{
            val data=java.io.File(folder,"test.csv").apply{writeText("ok")}
            ZipOutputStream(ByteArrayOutputStream()).use{zip->
                val writer=UniquePhotoZipEntries(zip)
                assertThrows(IllegalArgumentException::class.java){
                    writer.add(data,"../test.csv")
                }
            }
        }finally{folder.deleteRecursively()}
    }
}
