package dev.rflab.scanner

import android.graphics.BitmapFactory
import kotlin.math.abs

/**
 * Measure the *recorded* Camera2 JPEG, BEFORE neural preprocessing and
 * synthetic brightness/color maps. The previous Pixel night scan produced
 * median grayscale values around 6/255 and only ~15 VIO poses/s.
 *
 * These are coarse, conservative photograph-quality gates, NOT measured
 * camera ISO/AE or a guarantee of good tracking. Rejected frames remain
 * in the individual DA3 viewer, but cannot corrupt multi-view triangulation.
 */
internal object OpenVinsRgbCaptureQuality {
    data class Report(
        val width:Int,val height:Int,val medianLuma:Int,
        val nearBlackFraction:Double,val gradientContrast:Double,
        val usableForMultiView:Boolean,val warnings:List<String>
    )

    fun inspectArgb(pixels:IntArray,width:Int,height:Int):Report {
        require(width>=4 && height>=4 && width.toLong()*height==pixels.size.toLong())
        val histogram=IntArray(256)
        val luminance=IntArray(pixels.size)
        var nearBlack=0
        for(i in pixels.indices){
            val c=pixels[i]
            val l=(77*((c ushr 16) and 255) +
                150*((c ushr 8) and 255)+
                29*(c and 255)) shr 8
            luminance[i]=l
            histogram[l]++
            if(l<16)nearBlack++
        }
        val half=pixels.size/2
        var count=0
        var median=0
        for(i in histogram.indices){
            count+=histogram[i]
            if(count>half){median=i;break}
        }
        val black=nearBlack.toDouble()/pixels.size
        // The JPEG is already downsampled. Skipping every second position
        // here can miss every edge of an otherwise well-lit checkerboard.
        var contrastSum = 0L
        for (y in 0 until height) {
            val start = y * width
            for (i in start + 1 until start + width) {
                contrastSum += abs(luminance[i] - luminance[i - 1])
            }
        }
        for (i in width until luminance.size) {
            contrastSum += abs(luminance[i] - luminance[i - width])
        }
        val adjacentPairs = (width - 1L) * height + (height - 1L) * width
        val gradient = contrastSum.toDouble() / adjacentPairs
        val warnings=ArrayList<String>()
        if(median<35 || black>.62)
            warnings+="RGB-Kamera stark unterbelichtet; bitte Raumlicht einschalten"
        if(gradient<5.0)
            warnings+="Zu wenig erkennbare Bildstruktur/Schärfe"
        val usable=median>=35 && black<=.62 && gradient>=5.0
        return Report(width,height,median,black,gradient,usable,warnings)
    }

    fun inspectJpeg(jpeg:ByteArray):Report? {
        if(jpeg.size<100)return null
        val options=BitmapFactory.Options().apply{
            inSampleSize=4
            inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap=BitmapFactory.decodeByteArray(
            jpeg,0,jpeg.size,options)?:return null
        return try{
            val pixels=IntArray(bitmap.width*bitmap.height)
            bitmap.getPixels(pixels,0,bitmap.width,0,0,
                bitmap.width,bitmap.height)
            inspectArgb(pixels,bitmap.width,bitmap.height)
        }finally{bitmap.recycle()}
    }
}
