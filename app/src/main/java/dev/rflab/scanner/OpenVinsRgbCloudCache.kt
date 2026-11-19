package dev.rflab.scanner

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small, deterministic, local-only RGBXYZ cache for DA3 depth diagnostics.
 * No coordinates in this format imply SI metres; see the accompanying manifest.
 *
 * Header: little-endian magic (4), count (4); then count times
 * Float32 x/y/z + Int32 ARGB. Reject corrupt/truncated data and unsafe names.
 */
internal object OpenVinsRgbCloudCache {
    private const val MAGIC=0x52464331 // RFC1
    private const val MAX_SAMPLES=180_000

    fun write(folder:File,name:String,points:List<SurveyDepth>):File {
        require(name.matches(Regex("[a-zA-Z0-9_.-]{1,90}")))
        require(points.size<=MAX_SAMPLES)
        require(folder.isDirectory)
        val bytes=ByteBuffer.allocate(8+points.size*16)
            .order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(MAGIC)
        bytes.putInt(points.size)
        for(p in points) {
            require(p.x.isFinite() && p.y.isFinite() && p.z.isFinite())
            bytes.putFloat(p.x)
            bytes.putFloat(p.y)
            bytes.putFloat(p.z)
            bytes.putInt(p.argb)
        }
        val target=File(folder,name)
        val temporary=File(folder,name+".part")
        try {
            temporary.writeBytes(bytes.array())
            check(temporary.renameTo(target)){"Could not commit RGBXYZ cache"}
        } finally {
            temporary.delete()
        }
        return target
    }

    fun read(file:File):List<SurveyDepth> {
        if(!file.isFile || file.length()<8 ||
            file.length()>8L+MAX_SAMPLES*16L)return emptyList()
        val buffer=ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        if(buffer.int!=MAGIC)return emptyList()
        val count=buffer.int
        if(count<0 || count>MAX_SAMPLES ||
            buffer.remaining()!=count*16)return emptyList()
        val out=ArrayList<SurveyDepth>(count)
        repeat(count) {
            val x=buffer.float
            val y=buffer.float
            val z=buffer.float
            val color=buffer.int
            if(x.isFinite() && y.isFinite() && z.isFinite() &&
                maxOf(kotlin.math.abs(x),kotlin.math.abs(y),
                    kotlin.math.abs(z))<1000f){
                out.add(SurveyDepth(x,y,z,.42f,
                    argb=color,rawDepth=false))
            }
        }
        return out
    }
}
