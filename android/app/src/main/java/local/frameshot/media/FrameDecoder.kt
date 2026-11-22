package local.frameshot.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import local.frameshot.BuildConfig

/** Apa yang dilakukan pada tiap frame hasil decode (dipanggil dari thread decoder). */
interface FramePolicy {
    /** Sebelum render: perlukah frame ini dirender ke GPU (untuk dianalisis atau disimpan)? */
    fun wantsRender(ptsUs: Long): Boolean
    /** Setelah render: frame ada di `renderer`, siap dibaca. */
    fun onRendered(ptsUs: Long, renderer: GlFrameRenderer)
    /** Waktu frame berikutnya yang dibutuhkan, untuk melompati bagian yang tidak dipakai. */
    fun nextWantedUs(): Long? = null
    fun onProgress(ptsUs: Long) {}
    val finished: Boolean
}

/**
 * Decode video berurutan dengan MediaCodec ke SurfaceTexture (GPU), mulai dari keyframe sebelum
 * `startUs` sampai `endUs`. Blocking — jalankan di thread latar (thread itu memegang konteks GL).
 */
class FrameDecoder(
    private val context: Context,
    private val uri: Uri,
    private val info: VideoInfo,
    private val preferHardware: Boolean,
) {
    private var delivered = 0
    private var outputs = 0
    private var lastPts = -1L
    private var waitNs = 0L
    private var useNs = 0L

    /**
     * Jalankan dengan decoder terbaik; jika decoder gagal sebelum menghasilkan frame apa pun
     * (mis. tidak sanggup 10-bit), coba decoder berikutnya.
     */
    fun run(startUs: Long, endUs: Long, policy: FramePolicy, isCancelled: () -> Boolean) {
        val candidates = decoderCandidates()
        if (candidates.isEmpty()) throw IllegalStateException(noDecoderMessage(null))
        var lastError: Exception? = null
        // Tiap decoder dicoba dengan permintaan tone-map SDR dulu, lalu tanpa (tidak semua decoder mendukungnya).
        val attempts = candidates.flatMap { n -> if (Build.VERSION.SDK_INT >= 31) listOf(n to true, n to false) else listOf(n to false) }
        for ((name, toneMap) in attempts) {
            val started = System.nanoTime()
            try {
                runWith(name, toneMap, startUs, endUs, policy, isCancelled)
                if (BuildConfig.DEBUG) Log.d(TAG, "$name: $outputs output, $delivered di rentang, pts akhir $lastPts, tunggu ${waitNs / 1_000_000} ms, olah ${useNs / 1_000_000} ms, " +
                    "${(System.nanoTime() - started) / 1_000_000} ms")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Decoder $name (tonemap=$toneMap) gagal (setelah $delivered frame)", e)
                if (delivered > 0 || isCancelled()) throw e
                lastError = e
            }
        }
        throw IllegalStateException(noDecoderMessage(lastError))
    }

    private fun runWith(codecName: String, toneMap: Boolean, startUs: Long, endUs: Long, policy: FramePolicy, isCancelled: () -> Boolean) {
        outputs = 0
        lastPts = -1L
        val ex = MediaExtractor()
        var probe: MediaExtractor? = null
        var codec: MediaCodec? = null
        var renderer: GlFrameRenderer? = null
        try {
            MediaSource.open(context, ex, uri)
            ex.selectTrack(info.trackIndex)
            val format = ex.getTrackFormat(info.trackIndex)
            // Rotasi diterapkan sendiri di GPU; jangan biarkan decoder ikut memutar.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            if (toneMap && Build.VERSION.SDK_INT >= 31) {
                // Video HDR/10-bit di-tone-map ke SDR agar warnanya tidak pucat.
                format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            renderer = GlFrameRenderer(info.width, info.height, info.rotation)
            codec = MediaCodec.createByCodecName(codecName)
            codec.configure(format, renderer.surface, null, 0)
            codec.start()
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val bi = MediaCodec.BufferInfo()
            val inputLimitUs = endUs + 1_500_000 // sedikit melewati akhir: urutan B-frame
            var inputDone = false
            var outputDone = false
            var lastQueuedPts = -1L
            var lastActivity = System.currentTimeMillis()

            while (!outputDone && !isCancelled()) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t > inputLimitUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, t, 0)
                            if (t > lastQueuedPts) lastQueuedPts = t
                            ex.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bi, 2_000)
                if (outIdx < 0) {
                    if (System.currentTimeMillis() - lastActivity > 20_000) {
                        throw IllegalStateException("Decoder video tidak merespons.")
                    }
                    continue
                }
                lastActivity = System.currentTimeMillis()
                val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val pts = bi.presentationTimeUs
                val hasFrame = bi.size != 0 || !eos
                if (hasFrame) { outputs++; lastPts = pts }
                when {
                    hasFrame && pts >= endUs -> {
                        codec.releaseOutputBuffer(outIdx, false)
                        outputDone = true
                    }
                    hasFrame && pts >= startUs && !policy.finished -> {
                        delivered++
                        val render = policy.wantsRender(pts)
                        codec.releaseOutputBuffer(outIdx, render)
                        if (render) {
                            val t0 = System.nanoTime()
                            if (!renderer.awaitFrame()) throw IllegalStateException("Frame dari decoder tidak kunjung datang.")
                            val t1 = System.nanoTime()
                            policy.onRendered(pts, renderer)
                            waitNs += t1 - t0
                            useNs += System.nanoTime() - t1
                        }
                    }
                    else -> codec.releaseOutputBuffer(outIdx, false)
                }
                if (hasFrame) policy.onProgress(pts)
                if (eos || policy.finished) outputDone = true

                // Lompat ke frame berikutnya yang dibutuhkan jika masih jauh (mode interval).
                if (!outputDone && !inputDone) {
                    val target = policy.nextWantedUs()
                    if (target != null && target - pts > SEEK_MIN_GAP_US && target < endUs) {
                        val pr = probe ?: MediaExtractor().also {
                            MediaSource.open(context, it, uri)
                            it.selectTrack(info.trackIndex)
                            probe = it
                        }
                        pr.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        val sync = pr.sampleTime
                        if (sync > lastQueuedPts) {
                            ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            codec.flush()
                            lastQueuedPts = -1L
                        }
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { ex.release() }
            runCatching { probe?.release() }
            runCatching { renderer?.release() }
        }
    }

    /** Urutan decoder: yang mendukung format & sesuai pilihan hardware/software lebih dulu. */
    private fun decoderCandidates(): List<String> {
        val ex = MediaExtractor()
        val format = try {
            MediaSource.open(context, ex, uri)
            ex.getTrackFormat(info.trackIndex)
        } finally {
            ex.release()
        }
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        fun supports(ci: MediaCodecInfo) = runCatching {
            ci.getCapabilitiesForType(mime).isFormatSupported(format)
        }.getOrDefault(false)
        return infos.sortedWith(compareBy(
            { if (supports(it)) 0 else 1 },
            { if (it.isHardwareAccelerated == preferHardware) 0 else 1 },
        )).map { it.name }
    }

    private fun noDecoderMessage(e: Exception?) =
        "Perangkat ini tidak punya decoder untuk video ${info.codecDisplay}" +
            (if (info.isTenBit) " 10-bit" else "") + " ${info.width}×${info.height}." +
            (e?.message?.let { " ($it)" } ?: "")

    companion object {
        private const val TAG = "Frameshot"
        private const val SEEK_MIN_GAP_US = 2_000_000L
    }
}
