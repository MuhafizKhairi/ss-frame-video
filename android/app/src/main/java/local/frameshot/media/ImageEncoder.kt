package local.frameshot.media

import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.heifwriter.AvifWriter
import androidx.heifwriter.HeifWriter
import local.frameshot.core.ImageFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Format yang bisa dibuat perangkat ini. */
object Capabilities {
    private val encoders: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()
    }
    val heic: Boolean by lazy {
        MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC in encoders || MediaFormat.MIMETYPE_VIDEO_HEVC in encoders
    }
    val avif: Boolean by lazy { Build.VERSION.SDK_INT >= 34 && MediaFormat.MIMETYPE_VIDEO_AV1 in encoders }

    fun isAvailable(f: ImageFormat): Boolean = when (f) {
        ImageFormat.HEIC -> heic
        ImageFormat.AVIF -> avif
        else -> true
    }
}

/** Menulis Bitmap ke format pilihan. */
object ImageEncoder {
    fun write(bitmap: Bitmap, format: ImageFormat, quality: Int, out: OutputStream, tempDir: File) {
        val q = min(100, max(1, quality))
        when (format) {
            ImageFormat.JPG -> compress(bitmap, Bitmap.CompressFormat.JPEG, q, out)
            ImageFormat.PNG -> compress(bitmap, Bitmap.CompressFormat.PNG, 100, out)
            ImageFormat.WEBP -> compress(bitmap, Bitmap.CompressFormat.WEBP_LOSSY, q, out)
            ImageFormat.BMP -> BufferedOutputStream(out, 1 shl 16).let { writeBmp(bitmap, it); it.flush() }
            ImageFormat.TIFF -> BufferedOutputStream(out, 1 shl 16).let { writeTiff(bitmap, it); it.flush() }
            ImageFormat.HEIC, ImageFormat.AVIF -> writeHeif(bitmap, format, q, out, tempDir)
        }
    }

    private fun compress(b: Bitmap, f: Bitmap.CompressFormat, q: Int, out: OutputStream) {
        check(b.compress(f, q, out)) { "Gagal mengodekan gambar." }
    }

    /** HEIC/AVIF lewat encoder perangkat (heifwriter) ke file sementara, lalu disalin. */
    private fun writeHeif(b: Bitmap, format: ImageFormat, q: Int, out: OutputStream, tempDir: File) {
        val tmp = File.createTempFile("frameshot-", "." + format.ext, tempDir)
        try {
            // Encoder video butuh ukuran genap.
            val w = b.width and 1.inv()
            val h = b.height and 1.inv()
            val src = if (w != b.width || h != b.height) Bitmap.createBitmap(b, 0, 0, w, h) else b
            if (format == ImageFormat.AVIF) {
                AvifWriter.Builder(tmp.absolutePath, w, h, AvifWriter.INPUT_MODE_BITMAP)
                    .setQuality(q).setMaxImages(1).setPrimaryIndex(0).setGridEnabled(true).build().use { wr ->
                        wr.start(); wr.addBitmap(src); wr.stop(15_000)
                    }
            } else {
                HeifWriter.Builder(tmp.absolutePath, w, h, HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(q).setMaxImages(1).setPrimaryIndex(0).setGridEnabled(true).build().use { wr ->
                        wr.start(); wr.addBitmap(src); wr.stop(15_000)
                    }
            }
            if (src !== b) src.recycle()
            check(tmp.length() > 0) { "Encoder ${format.label} tidak menghasilkan file." }
            tmp.inputStream().use { it.copyTo(out, 1 shl 16) }
        } finally {
            tmp.delete()
        }
    }

    private fun rowRgba(b: Bitmap, y: Int, row: IntArray) = b.getPixels(row, 0, b.width, 0, y, b.width, 1)

    /** BMP 24-bit, baris dari bawah ke atas. */
    fun writeBmp(b: Bitmap, out: OutputStream) {
        val w = b.width
        val h = b.height
        val rowSize = (w * 3 + 3) and 3.inv()
        val dataSize = rowSize * h
        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + dataSize).putInt(0).putInt(54)
        header.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(dataSize)
            .putInt(2835).putInt(2835).putInt(0).putInt(0)
        out.write(header.array())
        val px = IntArray(w)
        val line = ByteArray(rowSize)
        for (y in h - 1 downTo 0) {
            rowRgba(b, y, px)
            var i = 0
            for (c in px) {
                line[i++] = (c and 0xFF).toByte()
                line[i++] = (c shr 8 and 0xFF).toByte()
                line[i++] = (c shr 16 and 0xFF).toByte()
            }
            out.write(line)
        }
    }

    /** TIFF baseline RGB 8-bit tanpa kompresi, satu strip. */
    fun writeTiff(b: Bitmap, out: OutputStream) {
        val w = b.width
        val h = b.height
        val dataSize = w * h * 3
        val entries = 10
        val ifdOffset = 8
        val ifdSize = 2 + entries * 12 + 4
        val bpsOffset = ifdOffset + ifdSize
        val dataOffset = bpsOffset + 6
        val hdr = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdOffset)
        hdr.putShort(entries.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Int) {
            hdr.putShort(tag.toShort()).putShort(type.toShort()).putInt(count)
            if (type == 3 && count == 1) hdr.putShort(value.toShort()).putShort(0) else hdr.putInt(value)
        }
        entry(256, 4, 1, w)                 // ImageWidth
        entry(257, 4, 1, h)                 // ImageLength
        entry(258, 3, 3, bpsOffset)         // BitsPerSample → 8,8,8
        entry(259, 3, 1, 1)                 // Compression: none
        entry(262, 3, 1, 2)                 // Photometric: RGB
        entry(273, 4, 1, dataOffset)        // StripOffsets
        entry(277, 3, 1, 3)                 // SamplesPerPixel
        entry(278, 4, 1, h)                 // RowsPerStrip
        entry(279, 4, 1, dataSize)          // StripByteCounts
        entry(284, 3, 1, 1)                 // PlanarConfiguration: chunky
        hdr.putInt(0)
        hdr.putShort(8).putShort(8).putShort(8)
        out.write(hdr.array())
        val px = IntArray(w)
        val line = ByteArray(w * 3)
        for (y in 0 until h) {
            rowRgba(b, y, px)
            var i = 0
            for (c in px) {
                line[i++] = (c shr 16 and 0xFF).toByte()
                line[i++] = (c shr 8 and 0xFF).toByte()
                line[i++] = (c and 0xFF).toByte()
            }
            out.write(line)
        }
    }
}
