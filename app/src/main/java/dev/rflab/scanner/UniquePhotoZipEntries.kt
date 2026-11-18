package dev.rflab.scanner

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Guards all photo-session ZIP entry names against duplicate selection.
 *  OpenVINS's imu_openvins.csv matches multiple historic export filters.
 */
internal class UniquePhotoZipEntries(private val zip:ZipOutputStream) {
    private val added=HashSet<String>()
    fun add(file:File,name:String=file.name):Boolean {
        require(name.isNotBlank() && !name.startsWith('/') &&
            !name.contains('\\') &&
            name.split('/').none{it=="." || it==".." || it.isBlank()}){
            "Invalid ZIP entry"
        }
        require(file.isFile){"Missing ZIP source: "+file.name}
        if(!added.add(name))return false
        zip.putNextEntry(ZipEntry(name))
        try{
            file.inputStream().buffered().use{it.copyTo(zip,64*1024)}
        }finally{zip.closeEntry()}
        return true
    }
}
