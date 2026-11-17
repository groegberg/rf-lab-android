package dev.rflab.scanner

import java.io.File
import kotlin.math.abs

/**
 * Safety gate for experimental COLOR 3D fusion, not an absolute camera/IMU
 * calibration certification. Every field comes from a real Camera2 capture
 * result, not merely a requested Camera2 setting. Missing evidence fails
 * closed, including old sessions captured before this audit was introduced.
 *
 * Camera pose/extrinsics, rolling shutter, temporal calibration, and metric
 * drift still require an independent multi-pose calibration/validation run.
 */
internal object OpenVinsCameraEvidence {
    data class Verdict(val allowedForExperimentalFusion:Boolean,
                       val problems:List<String>,
                       val cameraId:String?,
                       val testedFrames:Int,
                       val metrics:Map<String,String>)

    fun read(session:File):Verdict {
        val file=File(session,"external_calibration/PIXEL_CAMERA_CAPTURE_EVIDENCE.txt")
        val text=if(file.isFile && file.length() in 30..128_000)
            file.readText() else null
        return inspect(text,OpenVinsDa3Fusion.calibration(session))
    }

    fun inspect(raw:String?,
                calibration:OpenVinsDa3Geometry.Calibration?):Verdict {
        if(raw==null)return Verdict(false,
            listOf("Camera2-CaptureResult-Nachweis fehlt: "+
                "alte/unkalibrierte Aufnahme. Keine 3D-Fusion."),
            null,0,emptyMap())
        val map=raw.lineSequence().mapNotNull{line->
            val ix=line.indexOf('=')
            if(ix<=0 || ix>=line.length-1)null
            else line.substring(0,ix).trim() to line.substring(ix+1).trim()
        }.toMap()
        val problems=ArrayList<String>()
        fun required(key:String,expected:String,issue:String){
            if(map[key]!=expected) problems+=issue+
                " (Ist: "+(map[key]?:"nicht erfasst")+")"
        }
        required("schema","rf-lab-camera-capture-evidence-v1",
            "Kamera-Diagnosedaten haben falsches Format")
        required("timestamp_source","REALTIME",
            "Kamera- und Android-IMU-Zeitstempel nicht garantiert vergleichbar")
        required("lens_pose_reference","GYROSCOPE",
            "Kamera-IMU-Translation nicht durch Gyroskop-Referenz gedeckt")
        required("intrinsics_available","true",
            "Kamera-Intrinsik nicht von Camera2 geliefert")
        required("af_mode","OFF",
            "Autofokus aktiv oder nicht nachgewiesen")
        required("ois_mode","OFF",
            "Optische Stabilisierung aktiv oder nicht nachgewiesen")
        required("eis_mode","OFF",
            "Digitale Bildstabilisierung aktiv oder nicht nachgewiesen")
        required("distortion_correction_mode","OFF",
            "Bildentzerrung widerspricht OpenVINS-Rohbildkalibrierung")
        required("physical_camera_changed","false",
            "Während des Scans hat das physische Kameraobjektiv gewechselt")
        if(map["logical_multi_camera"]=="true" &&
            map["active_physical_id"].isNullOrBlank().let{
                it || map["active_physical_id"]=="UNKNOWN"
            })
            problems+="Aktives physisches Kameraobjektiv bei Multikamera unbekannt"
        val frames=map["capture_result_count"]?.toIntOrNull()?:0
        if(frames<6)problems+="Weniger als sechs geprüfte Camera2-Aufnahmen"
        val zoom=map["zoom_ratio"]?.toDoubleOrNull()
        if(zoom==null || !zoom.isFinite() || abs(zoom-1.0)>.02)
            problems+="Kamerazoom != 1.0 oder nicht im CaptureResult bestätigt"
        val focal=map["max_focal_relative_drift"]?.toDoubleOrNull()
        if(focal==null || !focal.isFinite() || focal<0 || focal>.01)
            problems+="Brennweite änderte sich um >1% oder ist unbekannt"
        val focus=map["max_focus_diopter_drift"]?.toDoubleOrNull()
        if(focus==null || !focus.isFinite() || focus<0 || focus>.05)
            problems+="Fokus änderte sich während des Scans"
        if(calibration==null){
            problems+="Keine gültige OpenVINS-Pinhole/Radtan-Kalibrierung"
        }else{
            val width=map["image_width"]?.toIntOrNull()
            val height=map["image_height"]?.toIntOrNull()
            if(width!=calibration.width || height!=calibration.height)
                problems+="Kamerastream-Auflösung stimmt nicht mit "+
                    "gespeicherter OpenVINS-Kalibrierung überein"
        }
        return Verdict(problems.isEmpty(),problems,map["camera_id"],
            frames,map)
    }
}
