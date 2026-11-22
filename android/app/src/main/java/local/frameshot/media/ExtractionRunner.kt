package local.frameshot.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.frameshot.core.DuplicateDetector
import local.frameshot.core.ExtractionMode
import local.frameshot.core.ExtractionSettings
import local.frameshot.core.FrameNamer
import local.frameshot.core.FrameSelector
import local.frameshot.core.ImageFormat
import local.frameshot.core.SceneScorer
import local.frameshot.core.prefixFor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Satu pekerjaan ekstraksi untuk satu video. */
data class ExtractionJob(
    val uri: Uri,
    val videoName: String,
    val info: VideoInfo,
    val start: Double,
    val end: Double,
    val settings: ExtractionSettings,
) {
    val rangeDuration: Double get() = max(0.0, end - start)
    val expectedFrames: Int? get() = settings.estimatedFrames(rangeDuration, info.fps)
}

data class ExtractionProgress(
    val frames: Int = 0,
    val fraction: Double = 0.0,
    val fps: Double = 0.0,
    val elapsed: Double = 0.0,
    val eta: Double? = null,
)

data class ExtractionResult(
    val folder: OutputFolder?,
    val frames: Int,
    val totalBytes: Long,
    val elapsed: Double,
    val sampleFiles: List<Uri>,
    val cancelled: Boolean,
    val errorMessage: String?,
)

/**
 * Menjalankan satu ExtractionJob: decode di GPU (1 thread), lalu encode & tulis paralel.
 * Pembatalan lewat [cancel]: frame yang sudah tersimpan tetap ada.
 */
class ExtractionRunner(
    private val context: Context,
    val job: ExtractionJob,
) {
    private val cancelled = AtomicBoolean(false)
    fun cancel() = cancelled.set(true)

    suspend fun run(onProgress: (ExtractionProgress) -> Unit): ExtractionResult = coroutineScope {
        val s = job.settings
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1e9

        val folder = try {
            withContext(Dispatchers.IO) { OutputLocation(context, s.outputFolderUri).prepare(job.videoName, s.createSubfolder) }
        } catch (e: Exception) {
            return@coroutineScope ExtractionResult(null, 0, 0, 0.0, emptyList(), false,
                "Tidak bisa menyiapkan folder output: ${e.message ?: e.javaClass.simpleName}")
        }
        val namer = FrameNamer(prefixFor(s, job.videoName), s, withContext(Dispatchers.IO) { folder.existingNames() })

        val (outW, outH) = s.outputSize(job.info.width, job.info.height)
        val frameBytes = outW.toLong() * outH * 4
        // Antrean dibatasi memori (±256 MB untuk frame yang menunggu di-encode).
        val queueCap = (256L * 1024 * 1024 / max(1L, frameBytes)).toInt().coerceIn(2, 8)
        val encoderCount = if (s.format == ImageFormat.HEIC || s.format == ImageFormat.AVIF) 1
        else min(4, max(1, Runtime.getRuntime().availableProcessors() - 2))
        val pool = ArrayBlockingQueue<Bitmap>(queueCap + encoderCount + 2)
        val channel = Channel<Pending>(queueCap)

        val written = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val samples = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Uri>>())
        val firstError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val tempDir = context.cacheDir

        val encoders = (0 until encoderCount).map {
            launch(Dispatchers.Default) {
                for (p in channel) {
                    try {
                        if (firstError.get() == null) {
                            val counter = CountingStream()
                            val t0 = System.nanoTime()
                            var tEnc = 0L
                            val uri = folder.write(p.name, s.format) { out ->
                                counter.target = out
                                val e0 = System.nanoTime()
                                ImageEncoder.write(p.bitmap, s.format, s.quality, counter, tempDir)
                                tEnc = System.nanoTime() - e0
                            }
                            if (local.frameshot.BuildConfig.DEBUG && p.index < 3) android.util.Log.d("Frameshot",
                                "simpan ${p.name}: total ${(System.nanoTime() - t0) / 1_000_000} ms, encode ${tEnc / 1_000_000} ms")
                            bytes.addAndGet(counter.count)
                            written.incrementAndGet()
                            if (p.index < 12) samples.add(p.index to uri)
                        }
                    } catch (e: Throwable) {
                        firstError.compareAndSet(null, "Gagal menyimpan ${p.name}: ${e.message ?: e.javaClass.simpleName}")
                        cancelled.set(true)
                    } finally {
                        pool.offer(p.bitmap)
                    }
                }
            }
        }

        var decodeError: String? = null
        withContext(Dispatchers.IO) {
            try {
                FrameDecoder(context, job.uri, job.info, s.hardwareDecode).run(
                    startUs = (job.start * 1e6).toLong(),
                    endUs = (job.end * 1e6).toLong(),
                    policy = Policy(namer, outW, outH, pool, channel, written, ::elapsed, onProgress),
                    isCancelled = { cancelled.get() },
                )
            } catch (e: Throwable) {
                decodeError = e.message ?: e.javaClass.simpleName
            } finally {
                channel.close()
            }
        }
        encoders.joinAll()
        pool.forEach { it.recycle() }
        pool.clear()

        val wasCancelled = cancelled.get() && firstError.get() == null
        val error = firstError.get() ?: decodeError?.let { if (wasCancelled) null else "Ekstraksi gagal: $it" }
        onProgress(ExtractionProgress(written.get(), if (error == null && !wasCancelled) 1.0 else 0.0, 0.0, elapsed()))
        ExtractionResult(
            folder = folder, frames = written.get(), totalBytes = bytes.get(), elapsed = elapsed(),
            sampleFiles = samples.sortedBy { it.first }.map { it.second },
            cancelled = wasCancelled, errorMessage = error,
        )
    }

    private class Pending(val index: Int, val name: String, val bitmap: Bitmap)

    private inner class Policy(
        private val namer: FrameNamer,
        private val outW: Int,
        private val outH: Int,
        private val pool: ArrayBlockingQueue<Bitmap>,
        private val channel: Channel<Pending>,
        private val written: AtomicInteger,
        private val elapsed: () -> Double,
        private val onProgress: (ExtractionProgress) -> Unit,
    ) : FramePolicy {
        private val s = job.settings
        private val startUs = (job.start * 1e6).toLong()
        private val selector = FrameSelector(s, job.rangeDuration, job.info.frameDuration)
        private val needAnalysis = s.skipDuplicates || s.mode == ExtractionMode.SCENE_CHANGE
        // Deteksi duplikat butuh detail (perubahan kecil seperti mulut bergerak di anime): ±640 px.
        // Deteksi adegan cukup gambar kecil.
        private val aw = (if (s.skipDuplicates) minOf(640, job.info.width) else 160).coerceAtLeast(8) / 8 * 8
        private val ah = (if (s.skipDuplicates) (aw.toLong() * job.info.height / maxOf(1, job.info.width)).toInt() else 96)
            .coerceAtLeast(8) / 8 * 8
        private val small = ByteBuffer.allocateDirect(aw * ah * 4).order(ByteOrder.nativeOrder())
        private val smallBytes = ByteArray(aw * ah * 4)
        private val dup = if (s.skipDuplicates) DuplicateDetector(aw, ah) else null
        private val scene = if (s.mode == ExtractionMode.SCENE_CHANGE) SceneScorer() else null
        private var emitted = 0
        private var lastProgressAt = 0L
        private var fraction = 0.0

        override val finished: Boolean get() = selector.isDone

        private fun rel(ptsUs: Long) = (ptsUs - startUs) / 1e6

        override fun wantsRender(ptsUs: Long): Boolean {
            if (needAnalysis) return true
            return selector.offer(rel(ptsUs))
        }

        override fun onRendered(ptsUs: Long, renderer: GlFrameRenderer) {
            if (needAnalysis) {
                renderer.readRgba(aw, ah, small, fast = true)
                small.get(smallBytes)
                small.rewind()
                if (dup?.isDuplicate(smallBytes) == true) return
                val score = scene?.score(smallBytes) ?: 0.0
                if (!selector.offer(rel(ptsUs), score)) return
            }
            val bmp = renderer.readBitmap(outW, outH, pool.poll())
            val name = namer.next(ptsUs / 1000)
            channel.trySendBlocking(Pending(emitted++, name, bmp))
        }

        override fun nextWantedUs(): Long? {
            if (s.skipDuplicates) return null
            val t = selector.nextWantedTime() ?: return null
            return startUs + (t * 1e6).toLong()
        }

        override fun onProgress(ptsUs: Long) {
            val range = job.rangeDuration
            if (range > 0) fraction = max(fraction, min(1.0, rel(ptsUs) / range))
            val n = job.expectedFrames
            if (s.mode == ExtractionMode.FIXED_COUNT && n != null && n > 0) {
                fraction = max(fraction, min(1.0, selector.selectedCount.toDouble() / n))
            }
            val now = System.currentTimeMillis()
            if (now - lastProgressAt < 150) return
            lastProgressAt = now
            val el = elapsed()
            val frames = written.get()
            val eta = if (fraction > 0.01 && fraction < 1) el * (1 - fraction) / fraction else null
            onProgress(ExtractionProgress(frames, fraction, if (el > 0) frames / el else 0.0, el, eta))
        }
    }
}

/** Menghitung byte yang ditulis (untuk ringkasan ukuran total). */
private class CountingStream : java.io.OutputStream() {
    var target: java.io.OutputStream? = null
    var count = 0L
    override fun write(b: Int) { target!!.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { target!!.write(b, off, len); count += len }
    override fun flush() { target?.flush() }
}
