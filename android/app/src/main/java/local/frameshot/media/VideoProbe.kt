package local.frameshot.media

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** Informasi video. */
data class VideoInfo(
    val duration: Double,       // detik
    val width: Int,             // lebar tampilan (sudah memperhitungkan rotasi)
    val height: Int,
    val rotation: Int,          // 0/90/180/270 searah jarum jam
    val fps: Double,
    val mime: String,
    val frameCount: Int,
    val fileSize: Long,
    val bitRate: Long,
    val subtitleStreams: Int,
    val isTenBit: Boolean,
    val trackIndex: Int,
) {
    val frameDuration: Double get() = if (fps > 0) 1 / fps else 1.0 / 24
    val codecWidth: Int get() = if (rotation % 180 == 90) height else width
    val codecHeight: Int get() = if (rotation % 180 == 90) width else height

    val codecDisplay: String
        get() = when (mime.lowercase()) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> "H.264"
            MediaFormat.MIMETYPE_VIDEO_HEVC -> "HEVC"
            MediaFormat.MIMETYPE_VIDEO_AV1 -> "AV1"
            MediaFormat.MIMETYPE_VIDEO_VP9 -> "VP9"
            MediaFormat.MIMETYPE_VIDEO_VP8 -> "VP8"
            MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "MPEG-4"
            MediaFormat.MIMETYPE_VIDEO_MPEG2 -> "MPEG-2"
            MediaFormat.MIMETYPE_VIDEO_H263 -> "H.263"
            MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION -> "Dolby Vision"
            else -> mime.substringAfter("/").uppercase()
        }
}

class ProbeException(message: String) : Exception(message)

object VideoProbe {
    fun displayName(context: Context, uri: Uri): String {
        val fromQuery = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromQuery ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }

    fun fileSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull() ?: runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull() ?: 0L

    fun probe(context: Context, uri: Uri): VideoInfo {
        val ex = MediaExtractor()
        try {
            try {
                MediaSource.open(context, ex, uri)
            } catch (e: Exception) {
                android.util.Log.w("Frameshot", "setDataSource gagal untuk $uri", e)
                throw ProbeException("Format ini tidak bisa dibaca Android. Coba MP4, MKV, WEBM, atau MOV.")
            }
            var track = -1
            var subs = 0
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && track < 0) track = i
                if (mime.startsWith("text/") || mime.contains("subrip") || mime.contains("ssa") ||
                    mime.contains("vtt") || mime.contains("pgs") || mime.contains("cea")) subs++
            }
            if (track < 0) throw ProbeException("Tidak ada stream video di file ini.")
            val f = ex.getTrackFormat(track)
            val mime = f.getString(MediaFormat.KEY_MIME)!!

            val mmr = MediaMetadataRetriever()
            val meta = runCatching { MediaSource.open(context, mmr, uri); true }.getOrDefault(false)
            fun md(key: Int): String? = if (meta) runCatching { mmr.extractMetadata(key) }.getOrNull() else null

            var durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) durationUs = (md(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val duration = durationUs / 1_000_000.0

            val rotation = ((if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION)
            else md(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0) % 360 + 360) % 360

            var w = f.getInteger(MediaFormat.KEY_WIDTH)
            var h = f.getInteger(MediaFormat.KEY_HEIGHT)
            if (rotation % 180 == 90) { val t = w; w = h; h = t }

            val mmrFrames = md(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            var fps = if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching {
                f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble()
            }.getOrNull() ?: 0.0 else 0.0
            if (fps <= 0 || fps > 1000) fps = measureFps(ex, track)
            if ((fps <= 0 || fps > 1000) && mmrFrames > 0 && duration > 0) fps = mmrFrames / duration
            if (fps <= 0 || fps > 1000) fps = 24.0

            var frames = mmrFrames
            if (frames <= 0) frames = Math.round(duration * fps).toInt()

            val profile = if (f.containsKey(MediaFormat.KEY_PROFILE)) f.getInteger(MediaFormat.KEY_PROFILE) else -1
            val tenBit = isTenBitProfile(mime, profile) ||
                (md(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
                    ?.let { it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG } ?: false)

            val bitRate = md(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            if (meta) runCatching { mmr.release() }

            return VideoInfo(
                duration = duration, width = w, height = h, rotation = rotation, fps = fps, mime = mime,
                frameCount = frames, fileSize = fileSize(context, uri), bitRate = bitRate,
                subtitleStreams = subs, isTenBit = tenBit, trackIndex = track,
            )
        } finally {
            ex.release()
        }
    }

    /** Ukur fps dari selisih timestamp sampel pertama (untuk MKV yang sering tidak menyimpan fps). */
    private fun measureFps(ex: MediaExtractor, track: Int): Double {
        return runCatching {
            ex.selectTrack(track)
            val times = ArrayList<Long>()
            while (times.size < 120) {
                val t = ex.sampleTime
                if (t < 0) break
                times.add(t)
                if (!ex.advance()) break
            }
            ex.unselectTrack(track)
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (times.size < 3) return 0.0
            times.sort()
            // Rata-rata selang di seluruh sampel (timestamp MKV dibulatkan ke milidetik, jadi median kurang tepat).
            val span = times.last() - times.first()
            if (span <= 0) return 0.0
            val fps = (times.size - 1) * 1_000_000.0 / span
            // Bulatkan ke fps umum jika sangat dekat (23.976, 24, 25, 29.97, 30, 50, 59.94, 60).
            listOf(24000.0 / 1001, 24.0, 25.0, 30000.0 / 1001, 30.0, 50.0, 60000.0 / 1001, 60.0)
                .firstOrNull { kotlin.math.abs(it - fps) < 0.02 } ?: fps
        }.getOrDefault(0.0)
    }

    private fun isTenBitProfile(mime: String, p: Int): Boolean {
        if (p < 0) return false
        return when (mime) {
            MediaFormat.MIMETYPE_VIDEO_HEVC -> p == CodecProfileLevel.HEVCProfileMain10 ||
                p == CodecProfileLevel.HEVCProfileMain10HDR10 || p == CodecProfileLevel.HEVCProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_AV1 -> p == CodecProfileLevel.AV1ProfileMain10 ||
                p == CodecProfileLevel.AV1ProfileMain10HDR10 || p == CodecProfileLevel.AV1ProfileMain10HDR10Plus
            MediaFormat.MIMETYPE_VIDEO_VP9 -> p == CodecProfileLevel.VP9Profile2 || p == CodecProfileLevel.VP9Profile3 ||
                p == CodecProfileLevel.VP9Profile2HDR || p == CodecProfileLevel.VP9Profile3HDR
            MediaFormat.MIMETYPE_VIDEO_AVC -> p == CodecProfileLevel.AVCProfileHigh10
            else -> false
        }
    }
}
