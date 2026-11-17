package dev.rflab.scanner

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/**
 * User-selected GPLv3 OpenVINS research archive -> RF Lab's private Camera2
 * photo session. Imports actual RGB stills, their native camera timestamps,
 * the timestamped MSCKF trajectory, raw IMU and exact calibration snapshots.
 *
 * Does not copy GPL source into RF Lab, create missing poses, claim a metric
 * calibration, or share access to arbitrary app-private data.
 */
internal class OpenVinsSessionImport(private val context: Context) {
    data class Result(val session:File,val photos:Int,val poses:Int,
                      val calibration:Boolean)

    /**
     * OpenVINS and RF Lab now use the SAME app-private storage.
     * Import the latest completed native recording directly, without SAF ZIP.
     * Retain the native originals and reuse our defensive ZIP importer.
     */
    fun importLocalLatest(update: (String) -> Unit = {}): Result {
        val root = File(context.getExternalFilesDir(null), "sessions")
        val latest = root.listFiles()?.filter {
            it.isDirectory && File(it, "vio_camera_trajectory.csv").isFile &&
                File(it, "rgb").isDirectory
        }?.maxByOrNull { it.lastModified() }
            ?: error("Keine interne OpenVINS-Aufnahme. Erst Tracking und Aufnahme stoppen.")
        return importLocalDirectory(latest, update)
    }

    fun importLocalDirectory(directory: File,
                             update: (String) -> Unit = {}): Result {
        val sessions = File(context.getExternalFilesDir(null), "sessions").canonicalFile
        val source = directory.canonicalFile
        require(source.parentFile == sessions && source.isDirectory) {
            "Quelle liegt nicht im OpenVINS-Sitzungsordner"
        }
        val files = ArrayList<File>()
        fun add(file: File) { if(file.isFile) files.add(file) }
        add(File(source,"vio_camera_trajectory.csv"))
        add(File(source,"imu0.csv"))
        configNames.sorted().forEach { name ->
            add(File(source,"config/$name"))
        }
        val rgb=File(source,"rgb").listFiles()
            ?.filter { it.isFile && rgbRegex.containsMatchIn("rgb/"+it.name) }
            ?.sortedBy { it.name }
            ?.take(180) ?: emptyList()
        require(rgb.size>=2) { "OpenVINS hat zu wenige Farbbilder gespeichert" }
        files.addAll(rgb)
        val size=files.sumOf { it.length() }
        require(size in 1L..(450L*1024*1024)) {
            "Aufnahme ist für die lokale Übernahme zu groß"
        }
        val temp=File.createTempFile("rflab-vio-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                for(file in files) {
                    val actual=file.canonicalFile
                    require(actual.path.startsWith(source.path+File.separator)) {
                        "Unerlaubter Dateipfad"
                    }
                    val rel=file.relativeTo(source).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(source.name+"/"+rel))
                    file.inputStream().buffered().use { it.copyTo(zip,64*1024) }
                    zip.closeEntry()
                }
            }
            return temp.inputStream().buffered().use { import(it,update) }
        }finally { temp.delete() }
    }

    private val rgbRegex=Regex("""(?:^|/)rgb/([0-9]{12,20})\.jpe?g$""",
        RegexOption.IGNORE_CASE)
    private val configNames=setOf("estimator_config.yaml",
        "kalibr_imu_chain.yaml","kalibr_imucam_chain.yaml",
        "PIXEL_CALIBRATION_WARNING.txt",
        "PIXEL_CAMERA_CAPTURE_EVIDENCE.txt")

    private fun readBounded(input:ZipInputStream,max:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream()
        val buffer=ByteArray(64*1024)
        var count=0
        while(true){
            val n=input.read(buffer)
            if(n<0)break
            count+=n
            require(count<=max){"ZIP entry exceeds per-file safety limit"}
            out.write(buffer,0,n)
        }
        return out.toByteArray()
    }

    fun import(input:InputStream,update:(String)->Unit={}):Result {
        val store=PhotoSessionStore(context)
        val session=store.newSession()
        val staging=File(session,"_stage_rgb").apply{mkdirs()}
        var poseWritten=false
        var sourceFrames=0
        try {
            val fileIndex=HashSet<Long>()
            ZipInputStream(input.buffered()).use { zip ->
                var entries=0
                var selectedBytes=0L
                while(true){
                    val entry=zip.nextEntry?:break
                    entries++
                    require(entries<=35_000){"Zu viele ZIP-Dateien"}
                    if(entry.isDirectory){zip.closeEntry();continue}
                    // Reject absolute/traversal paths even though our importer
                    // never extracts the raw entry name to the filesystem.
                    val name=entry.name.replace('\\','/')
                    require(!name.startsWith('/') &&
                        name.split('/').none { it==".." || it=="." }) {
                        "Unsicherer Dateipfad im ZIP"
                    }
                    val match=rgbRegex.find(name)
                    when {
                        match!=null && sourceFrames<180 -> {
                            val stamp=match.groupValues[1].toLongOrNull()
                            if(stamp!=null && stamp>0L &&
                                fileIndex.add(stamp)){
                                val data=readBounded(zip,15*1024*1024)
                                selectedBytes+=data.size
                                require(selectedBytes<=360L*1024*1024){
                                    "RGB-Aufnahmen zu groß"
                                }
                                if(data.size>=20_000 &&
                                    data.size>=3 &&
                                    (data[0].toInt() and 255)==255 &&
                                    (data[1].toInt() and 255)==216){
                                    File(staging,"$stamp.jpg").writeBytes(data)
                                    sourceFrames++
                                }
                            }
                        }
                        name.endsWith("/vio_camera_trajectory.csv") &&
                            !poseWritten -> {
                            val text=readBounded(zip,24*1024*1024)
                            val imported=File(session,"external_vio_trajectory.csv")
                            imported.writeBytes(text)
                            poseWritten=true
                        }
                        name.endsWith("/imu0.csv") -> {
                            File(session,"imu_openvins.csv").writeBytes(
                                readBounded(zip,35*1024*1024))
                        }
                        name.contains("/config/") &&
                            name.substringAfterLast('/') in configNames -> {
                            val out=File(session,"external_calibration").apply{mkdirs()}
                            File(out,name.substringAfterLast('/')).writeBytes(
                                readBounded(zip,256*1024))
                        }
                    }
                    zip.closeEntry()
                }
            }
            val rawPoses=ExternalVioTrajectory.read(session)
            require(rawPoses.isNotEmpty()){
                "Kein gültiger nativer VIO-Kamerapfad im ZIP gefunden"
            }
            val rgb=staging.listFiles()
                ?.filter { it.isFile && it.extension=="jpg" }
                ?.sortedBy {it.nameWithoutExtension.toLongOrNull()?:0L}
                ?: emptyList()
            require(rgb.size>=2){
                "Keine RGB-Aufnahmen gefunden. VIO vor dem Aufzeichnen starten."
            }
            for ((i,photo) in rgb.withIndex()){
                val ts=photo.nameWithoutExtension.toLongOrNull()?:continue
                val match=ExternalVioTrajectory.closestIndex(rawPoses,ts)
                val meta=JSONObject()
                    .put("timestamp_ns",ts)
                    .put("pose_source","OpenVINS native MSCKF research, uncalibrated")
                    .put("image_source","OpenVINS Mobile Camera2 RGB frame")
                    .put("source_file",photo.name)
                    .put("external_vio_pose_index",match)
                    .put("external_vio_pose_timed",match>=0)
                    .put("metric_calibration_validated",false)
                store.save(session,photo.readBytes(),meta)
                if(i%8==0)update("RGB-Aufnahmen importiert: "+(i+1)+"/"+rgb.size)
            }
            File(session,"session.json").writeText(JSONObject()
                .put("schema","rflab-camera2-session-v1")
                .put("source","OpenVINS Mobile GPLv3 separate research app")
                .put("pose_source","Imported native MSCKF VIO, uncalibrated")
                .put("metric_calibration_validated",false)
                .put("source_timestamp_epoch","Camera2 sensor nanoseconds")
                .put("source_pose_count",rawPoses.size)
                .put("source_photo_count",rgb.size)
                .put("imported_unix_ms",System.currentTimeMillis())
                .toString(2))
            val hasCalibration=File(session,
                "external_calibration/kalibr_imucam_chain.yaml").isFile
            return Result(session,store.frames(session).size,
                rawPoses.size,hasCalibration)
        }catch(t:Throwable){
            session.deleteRecursively()
            throw t
        }finally{staging.deleteRecursively()}
    }
}
