package dev.rflab.scanner

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * User-triggered, private on-device diagnostic export of the original
 * OpenVINS camera/IMU session. No network, no logging of Wi-Fi or smart-home
 * data, no fabricated calibration. Raw images enable actual future Kalibr.
 */
internal object OpenVinsCalibrationArchive {
    data class Result(val files:Int, val rawBytes:Long)
    private val configFiles=setOf(
        "estimator_config.yaml","kalibr_imu_chain.yaml",
        "kalibr_imucam_chain.yaml","PIXEL_CALIBRATION_WARNING.txt",
        "PIXEL_CAMERA_CAPTURE_EVIDENCE.txt")

    fun latest(root:File):File? = root.listFiles()?.filter{
        it.isDirectory && File(it,"vio_camera_trajectory.csv").isFile
    }?.maxByOrNull{File(it,"vio_camera_trajectory.csv").lastModified()}

    fun export(session:File,output:OutputStream,
               humanDiagnostic:String):Result {
        val base=session.canonicalFile
        require(base.isDirectory && base.name.isNotBlank()){
            "OpenVINS native session directory missing"
        }
        val files=ArrayList<File>()
        for(name in listOf("vio_camera_trajectory.csv","imu0.csv")){
            val file=File(base,name)
            if(file.isFile)files.add(file)
        }
        for(name in configFiles){
            val file=File(base,"config/$name")
            if(file.isFile)files.add(file)
        }
        for(subdir in listOf("rgb","cam0")){
            files.addAll(File(base,subdir).listFiles()?.filter{
                it.isFile && it.name.matches(
                    Regex("""\d{12,20}\.(?:jpe?g|png)""",
                        RegexOption.IGNORE_CASE))
            }?.sortedBy{it.name}?.take(
                if(subdir=="rgb")220 else 1800) ?: emptyList())
        }
        require(files.isNotEmpty()){"OpenVINS session has no data"}
        require(files.size<2500){"Too many VIO frames"}
        val total=files.sumOf{it.length()}
        require(total in 1..(1100L*1024*1024)){
            "OpenVINS session exceeds 1.1 GiB diagnostic limit"
        }
        ZipOutputStream(output.buffered()).use{zip->
            for(file in files){
                val child=file.canonicalFile
                require(child.path.startsWith(base.path+File.separator)){
                    "Unexpected path in diagnostic archive"
                }
                val rel=child.relativeTo(base).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("OpenVINS/"+rel))
                file.inputStream().buffered().use{it.copyTo(zip,64*1024)}
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("OpenVINS/RF_LAB_CAPTURE_DIAGNOSTICS.txt"))
            zip.write(humanDiagnostic.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return Result(files.size,total)
    }
}
