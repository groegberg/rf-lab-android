package dev.rflab.scanner

import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Timestamped low-resolution *actual camera images* for offline visual odometry.
 * Single writer, nonblocking camera submission, finite memory/storage. Frames
 * are not derived from ARCore depth or a synthetic camera pose.
 *
 * RFVO1: 5-byte magic, then repeated [timestamp (int64), w (int32),
 * h (int32), compressedSize (int32), deflated grayscale pixels].
 */
internal class MotionFrameStore(private val session: File) : AutoCloseable {
    private val file = File(session, "motion_" + SystemClock.elapsedRealtimeNanos() + ".rfvo")
    private val writer = DataOutputStream(BufferedOutputStream(file.outputStream(), 128*1024))
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(5), ThreadPoolExecutor.AbortPolicy())
    @Volatile private var closed = false
    @Volatile var frames = 0
        private set
    @Volatile var dropped = 0
        private set
    @Volatile private var bytes = 0L
    init { writer.write(byteArrayOf(82, 70, 86, 79, 49)) }

    fun offer(timestampNs: Long, gray: ByteArray, width: Int, height: Int) {
        if (closed || timestampNs <= 0L || gray.size != width*height ||
            width !in 120..640 || height !in 90..480 ||
            frames >= 3600 || bytes >= 200L*1024*1024) return
        if (executor.queue.remainingCapacity() == 0) { dropped++; return }
        val copy = gray.copyOf()
        try {
            executor.execute {
                try {
                    val packed = ByteArrayOutputStream(copy.size/2)
                    val deflater=Deflater(Deflater.BEST_SPEED)
                    try {
                        DeflaterOutputStream(packed,deflater).use { it.write(copy) }
                    } finally { deflater.end() }
                    val data = packed.toByteArray()
                    synchronized(writer) {
                        writer.writeLong(timestampNs)
                        writer.writeInt(width);writer.writeInt(height)
                        writer.writeInt(data.size)
                        writer.write(data)
                        bytes += data.size + 20L
                        frames++
                        if(frames % 15 == 0) writer.flush()
                    }
                } catch (_: Exception) { dropped++ }
            }
        } catch (_: RejectedExecutionException) { dropped++ }
    }

    override fun close() {
        executor.shutdown()
        try { executor.awaitTermination(3,TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (!executor.isTerminated) {
            dropped += executor.shutdownNow().size
        }
        closed = true
        synchronized(writer) { writer.flush();writer.close() }
    }

    data class Frame(val timestampNs: Long, val width: Int,
                     val height: Int, val gray: ByteArray)

    companion object {
        fun files(session: File): List<File> = session.listFiles()
            ?.filter { it.isFile && it.name.startsWith("motion_") &&
                it.name.endsWith(".rfvo") }?.sortedBy { it.name } ?: emptyList()

        /** Stream sequentially; skip evenly so heavy offline VO stays bounded. */
        fun read(session: File, step: Int = 2, limit: Int = 600): List<Frame> {
            val result=ArrayList<Frame>(limit.coerceAtMost(500))
            var counter=0
            for (file in files(session)) {
                DataInputStream(BufferedInputStream(FileInputStream(file), 128*1024)).use { input ->
                    val magic=ByteArray(5)
                    if (input.read(magic)!=5 ||
                        !magic.contentEquals(byteArrayOf(82,70,86,79,49))) return@use
                    while (result.size<limit) {
                        try {
                            val timestamp=input.readLong()
                            val w=input.readInt()
                            val h=input.readInt()
                            val length=input.readInt()
                            if (w !in 120..640 || h !in 90..480 ||
                                length !in 50..(1024*1024)) break
                            val compressed=ByteArray(length)
                            input.readFully(compressed)
                            val idx=counter++
                            if (idx%step.coerceAtLeast(1)!=0) continue
                            // Bound decompression to the expected image size.
                            val inflated=InflaterInputStream(compressed.inputStream())
                                .use { it.readNBytes(w*h+1) }
                            if (inflated.size==w*h)
                                result.add(Frame(timestamp,w,h,inflated))
                        } catch (_: EOFException) { break }
                    }
                }
                if (result.size>=limit) break
            }
            return result.sortedBy { it.timestampNs }
        }
    }
}
