package local.frameshot.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import local.frameshot.core.ExtractionSettings
import java.io.ByteArrayOutputStream

/**
 * Mengambil satu frame: untuk pratinjau, tombol "Ambil frame ini", dan perkiraan ukuran file.
 * Tidak thread-safe — pakai dari satu thread (dispatcher tunggal) saja.
 */
class FrameGrabber(private val context: Context) {
    private var currentUri: Uri? = null
    private var retriever: MediaMetadataRetriever? = null

    private fun retrieverFor(uri: Uri): MediaMetadataRetriever {
        if (uri == currentUri) retriever?.let { return it }
        retriever?.let { runCatching { it.release() } }
        val r = MediaMetadataRetriever()
        MediaSource.open(context, r, uri)
        retriever = r
        currentUri = uri
        return r
    }

    /** Frame tepat di `time` (detik), diperkecil agar muat dalam lebar `maxWidth`. */
    fun preview(uri: Uri, time: Double, maxWidth: Int, info: VideoInfo): Bitmap? {
        val r = retrieverFor(uri)
        val w = minOf(maxWidth, info.width)
        val h = maxOf(2, (info.height.toLong() * w / maxOf(1, info.width)).toInt())
        val us = (time * 1e6).toLong()
        return r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            ?: if (time > 0.5) r.getScaledFrameAtTime(((time - 0.5) * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            else null
    }

    /** Frame resolusi keluaran sesuai pengaturan. */
    fun fullFrame(uri: Uri, time: Double, settings: ExtractionSettings, info: VideoInfo): Bitmap {
        val r = retrieverFor(uri)
        val us = (time * 1e6).toLong()
        fun at(t: Long): Bitmap? = if (Build.VERSION.SDK_INT >= 30) {
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, params)
        } else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
        val frame = at(us) ?: (if (time > 0.5) at(((time - 0.5) * 1e6).toLong()) else null)
            ?: throw IllegalStateException("Frame tidak bisa dibaca.")
        val (w, h) = settings.outputSize(frame.width, frame.height)
        val argb = if (frame.config != Bitmap.Config.ARGB_8888) frame.copy(Bitmap.Config.ARGB_8888, false).also { frame.recycle() } else frame
        if (w == argb.width && h == argb.height) return argb
        return Bitmap.createScaledBitmap(argb, w, h, true).also { if (it !== argb) argb.recycle() }
    }

    /** Ukuran rata-rata satu gambar (byte), dari 3 sampel di sepanjang rentang. */
    fun sampleFrameBytes(uri: Uri, start: Double, end: Double, settings: ExtractionSettings, info: VideoInfo,
                         isCancelled: () -> Boolean): Double? {
        val sizes = ArrayList<Double>()
        val span = maxOf(0.0, end - start)
        for (p in listOf(0.25, 0.5, 0.75)) {
            if (isCancelled()) return null
            runCatching {
                val bmp = fullFrame(uri, start + span * p, settings, info)
                val out = ByteArrayOutputStream()
                ImageEncoder.write(bmp, settings.format, settings.quality, out, context.cacheDir)
                bmp.recycle()
                sizes.add(out.size().toDouble())
            }
        }
        return if (sizes.isEmpty()) null else sizes.average()
    }

    fun release() {
        retriever?.let { runCatching { it.release() } }
        retriever = null
        currentUri = null
    }
}
