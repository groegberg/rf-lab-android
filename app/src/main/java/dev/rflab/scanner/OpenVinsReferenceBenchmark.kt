package dev.rflab.scanner

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A real physical-reference test, not an estimator self-consistency check.
 * User must actually record start/end of a known translation, a return
 * to the same location, or a controlled 90-degree phone rotation.
 *
 * No result is called certified: native VIO units are not metrically
 * calibrated and the endpoint alignment can include initialization delay.
 */
internal object OpenVinsReferenceBenchmark {
    enum class Kind { KNOWN_TRANSLATION, CLOSED_ROUTE, KNOWN_ROTATION }
    data class Result(val kind:Kind,val poseCount:Int,
                      val pathLengthUnvalidated:Double,
                      val netDisplacementUnvalidated:Double,
                      val relativeTranslationError:Double?,
                      val referenceMetres:Double?,
                      val rotationAngleDegrees:Double,
                      val rotationErrorDegrees:Double?,
                      val warnings:List<String>)
    fun evaluate(poses:List<ExternalVioTrajectory.Sample>,
                 kind:Kind,knownMetres:Double=1.0,
                 knownDegrees:Double=90.0):Result {
        val warnings=ArrayList<String>()
        if(poses.size<15)warnings+="Zu wenige initialisierte OpenVINS-Posen"
        val path=poses.zipWithNext{a,b->
            sqrt((b.x-a.x)*(b.x-a.x)+(b.y-a.y)*(b.y-a.y)+
                (b.z-a.z)*(b.z-a.z))
        }.sum()
        val first=poses.firstOrNull()
        val last=poses.lastOrNull()
        val net=if(first!=null && last!=null)
            sqrt((last.x-first.x)*(last.x-first.x)+
                (last.y-first.y)*(last.y-first.y)+
                (last.z-first.z)*(last.z-first.z))
            else 0.0
        val angle=if(first!=null && last!=null){
            val dot=first.qw*last.qw+first.qx*last.qx+
                first.qy*last.qy+first.qz*last.qz
            val a=sqrt(first.qw*first.qw+first.qx*first.qx+
                first.qy*first.qy+first.qz*first.qz)
            val b=sqrt(last.qw*last.qw+last.qx*last.qx+
                last.qy*last.qy+last.qz*last.qz)
            if(a<.85 || b<.85)Double.NaN else
                2*acos((abs(dot)/(a*b)).coerceIn(0.0,1.0))*180/Math.PI
        }else Double.NaN
        val reference=if(kind==Kind.KNOWN_TRANSLATION &&
            knownMetres in .1..25.0)knownMetres else null
        val translationError=when(kind){
            Kind.KNOWN_TRANSLATION -> if(reference!=null)
                abs(net-reference)/reference else null
            Kind.CLOSED_ROUTE -> if(path>.1)net/path else null
            else -> null
        }
        val rotationError=if(kind==Kind.KNOWN_ROTATION &&
            knownDegrees in 5.0..180.0 && angle.isFinite())
            abs(angle-knownDegrees) else null
        if(kind==Kind.KNOWN_TRANSLATION && reference==null)
            warnings+="Ungültige bekannte Referenzstrecke"
        if(kind==Kind.KNOWN_TRANSLATION && translationError!=null &&
            translationError>.25)
            warnings+="Großer VIO-Skalen-/Translationsfehler"
        if(kind==Kind.CLOSED_ROUTE && translationError!=null &&
            translationError>.10)
            warnings+="Geschlossene Runde hat großen Schließfehler"
        if(kind==Kind.KNOWN_ROTATION && rotationError!=null &&
            rotationError>10)
            warnings+="90-Grad-Rotation deutlich abweichend"
        warnings+="Prüfung nur gültig, wenn erster/letzter VIO-Posewert "+
            "den physischen Anfangs-/Endzustand der Referenzbewegung beschreibt"
        return Result(kind,poses.size,path,net,
            translationError,reference,angle,rotationError,warnings)
    }
}
