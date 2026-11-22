package local.frameshot

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.frameshot.core.ExtractionSettings
import local.frameshot.core.formatCount
import local.frameshot.core.prefixFor
import local.frameshot.core.timestampName
import local.frameshot.media.Capabilities
import local.frameshot.media.ExtractionJob
import local.frameshot.media.ExtractionProgress
import local.frameshot.media.ExtractionRunner
import local.frameshot.media.FrameGrabber
import local.frameshot.media.ImageEncoder
import local.frameshot.media.OutputFolder
import local.frameshot.media.OutputLocation
import local.frameshot.media.PreviewPlayer
import local.frameshot.media.VideoInfo
import local.frameshot.media.VideoProbe
import java.text.Collator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

// MARK: - Model video di antrean

sealed interface ItemStatus {
    data object Ready : ItemStatus
    data object Running : ItemStatus
    data class Done(val frames: Int) : ItemStatus
    data class Failed(val message: String) : ItemStatus
    data object Cancelled : ItemStatus
}

class VideoItem(val uri: Uri, fileName: String) {
    val id: String = UUID.randomUUID().toString()
    val name: String = fileName.substringBeforeLast('.', fileName)
    val ext: String = fileName.substringAfterLast('.', "").lowercase()
    var info by mutableStateOf<VideoInfo?>(null)
    var loadError by mutableStateOf<String?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var included by mutableStateOf(true)
    var customRange by mutableStateOf(false)
    var rangeStart by mutableDoubleStateOf(0.0)
    var rangeEnd by mutableDoubleStateOf(0.0)
    var status by mutableStateOf<ItemStatus>(ItemStatus.Ready)
    var lastTime = 0.0

    val duration: Double get() = info?.duration ?: 0.0
    val effectiveStart: Double get() = if (customRange) rangeStart else 0.0
    val effectiveEnd: Double get() = if (customRange) rangeEnd else duration
    val effectiveDuration: Double get() = max(0.0, effectiveEnd - effectiveStart)
}

data class RunSummary(
    val frames: Int = 0,
    val bytes: Long = 0,
    val elapsed: Double = 0.0,
    val folders: List<OutputFolder> = emptyList(),
    val samples: List<Uri> = emptyList(),
    val errors: List<Pair<String, String>> = emptyList(),
    val cancelled: Boolean = false,
    val videos: Int = 0,
)

enum class Phase { IDLE, RUNNING, FINISHED }

enum class ToastKind { SUCCESS, CAMERA, ERROR }

data class Toast(
    val message: String,
    val kind: ToastKind = ToastKind.SUCCESS,
    val openUri: Uri? = null,
    val id: String = UUID.randomUUID().toString(),
)

// MARK: - State aplikasi (satu untuk seluruh proses, bertahan saat Activity dibuat ulang)

class AppState(private val app: Application) {
    companion object {
        val videoExtensions = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "flv", "wmv", "ts", "m2ts", "mts", "mpg", "mpeg",
            "3gp", "3g2", "ogv", "vob", "divx", "rmvb", "f4v", "asf",
        )
    }

    private val scope = MainScope()
    private val prefs = app.getSharedPreferences("frameshot", Context.MODE_PRIVATE)
    private val previewThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val thumbThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val sampleThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val previewGrabber = FrameGrabber(app)
    private val thumbGrabber = FrameGrabber(app)
    private val sampleGrabber = FrameGrabber(app)

    val videos = mutableStateListOf<VideoItem>()
    var selectedId by mutableStateOf<String?>(null)
        private set

    private var _settings by mutableStateOf(ExtractionSettings.fromJson(prefs.getString("settings.v1", null)))
    var settings: ExtractionSettings
        get() = _settings
        set(value) {
            if (value == _settings) return
            _settings = value
            prefs.edit().putString("settings.v1", value.toJson()).apply()
            scheduleSampleUpdate()
        }

    fun update(block: (ExtractionSettings) -> ExtractionSettings) { settings = block(settings) }

    // Pratinjau
    var currentTime by mutableDoubleStateOf(0.0)
        private set
    var previewImage by mutableStateOf<ImageBitmap?>(null)
        private set
    var previewLoading by mutableStateOf(false)
        private set

    // Pemutar video
    val player = PreviewPlayer(app)
    var playing by mutableStateOf(false)
        private set
    /** Tampilan video pemutar ada di atas gambar pratinjau (saat diputar, dan sesaat setelah dijeda). */
    var playerVisible by mutableStateOf(false)
        private set
    private var playJob: Job? = null

    // Perkiraan ukuran
    var sampleBytes by mutableStateOf<Double?>(null)
        private set
    var sampleLoading by mutableStateOf(false)
        private set

    // Proses
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var progress by mutableStateOf(ExtractionProgress())
        private set
    var runIndex by mutableStateOf(0)
        private set
    var runTotal by mutableStateOf(0)
        private set
    var runName by mutableStateOf("")
        private set
    var summary by mutableStateOf<RunSummary?>(null)
        private set
    var showSummary by mutableStateOf(false)
        private set

    var toast by mutableStateOf<Toast?>(null)
        private set

    private var previewRequest: Pair<VideoItem, Double>? = null
    private var previewJob: Job? = null
    private var sampleJob: Job? = null
    private var sampleKey = ""
    private var currentRunner: ExtractionRunner? = null
    private var cancelRequested = false
    private var toastJob: Job? = null

    init {
        player.onCompletion = { onPlaybackEnded() }
        player.onError = { msg ->
            stopPlayback()
            showToast(msg, ToastKind.ERROR)
        }
        // Format yang tidak didukung perangkat ini kembali ke JPG.
        if (!Capabilities.isAvailable(settings.format)) settings = settings.copy(format = local.frameshot.core.ImageFormat.JPG)
        // Folder output yang izinnya sudah dicabut kembali ke Galeri.
        if (settings.outputFolderUri.isNotEmpty() && app.contentResolver.persistedUriPermissions.none {
                it.uri.toString() == settings.outputFolderUri && it.isWritePermission
            }) settings = settings.copy(outputFolderUri = "")
    }

    fun isAvailable(f: local.frameshot.core.ImageFormat) = Capabilities.isAvailable(f)

    // MARK: Video

    val selected: VideoItem? get() = videos.firstOrNull { it.id == selectedId }

    fun select(item: VideoItem) {
        if (item.id == selectedId) return
        stopPlayback()
        selected?.lastTime = currentTime
        selectedId = item.id
        currentTime = item.lastTime
        previewImage = item.thumbnail
        requestPreview()
        scheduleSampleUpdate()
    }

    /** Tambah video dari URI file (picker, "Bagikan", "Buka dengan"). */
    fun addUris(uris: List<Uri>) {
        if (phase == Phase.RUNNING || uris.isEmpty()) return
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                uris.map { it to VideoProbe.displayName(app, it) }
            }
            addEntries(entries, fromFolder = false)
        }
    }

    /** Tambah semua video di dalam folder (tidak rekursif). */
    fun addFolder(tree: Uri) {
        if (phase == Phase.RUNNING) return
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                val out = ArrayList<Pair<Uri, String>>()
                runCatching {
                    val doc = DocumentsContract.getTreeDocumentId(tree)
                    app.contentResolver.query(
                        DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc),
                        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val name = c.getString(1) ?: continue
                            val mime = c.getString(2) ?: ""
                            if (name.startsWith(".")) continue
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (mime.startsWith("video/") || ext in videoExtensions) {
                                out.add(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)) to name)
                            }
                        }
                    }
                }
                val collator = Collator.getInstance(Locale("id", "ID"))
                out.sortedWith { a, b -> naturalCompare(a.second, b.second, collator) }
            }
            addEntries(entries, fromFolder = true)
        }
    }

    private fun addEntries(entries: List<Pair<Uri, String>>, fromFolder: Boolean) {
        val files = entries
        if (files.isEmpty()) {
            showToast(if (fromFolder) "Tidak ada file video di folder itu." else "Tidak ada file video yang dikenali.", ToastKind.ERROR)
            return
        }
        val existing = videos.map { it.uri.toString() }.toSet()
        val newItems = files.filter { it.first.toString() !in existing }.map { VideoItem(it.first, it.second) }
        videos.addAll(newItems)
        if (selectedId == null) newItems.firstOrNull()?.let {
            selectedId = it.id
            currentTime = 0.0
            previewImage = null
        }
        newItems.forEach(::load)
    }

    fun remove(item: VideoItem) {
        if (phase == Phase.RUNNING) return
        if (selectedId == item.id) stopPlayback()
        val idx = videos.indexOfFirst { it.id == item.id }
        videos.removeAll { it.id == item.id }
        if (selectedId == item.id) {
            selectedId = null
            previewImage = null
            if (idx >= 0 && videos.isNotEmpty()) select(videos[min(idx, videos.size - 1)])
        }
    }

    fun clearAll() {
        if (phase == Phase.RUNNING) return
        stopPlayback()
        videos.clear()
        selectedId = null
        previewImage = null
    }

    private fun load(item: VideoItem) {
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) { VideoProbe.probe(app, item.uri) }
                item.info = info
                item.rangeEnd = info.duration
                if (item.id == selectedId) {
                    requestPreview()
                    scheduleSampleUpdate()
                }
                val thumb = withContext(thumbThread) {
                    runCatching { thumbGrabber.preview(item.uri, info.duration * 0.1, 360, info) }.getOrNull()
                }
                if (thumb != null) {
                    item.thumbnail = thumb.asImageBitmap()
                    if (item.id == selectedId && previewImage == null) previewImage = item.thumbnail
                } else {
                    // Info terbaca tapi frame tidak bisa di-decode: decoder perangkat tidak sanggup.
                    item.loadError = "Perangkat ini tidak bisa men-decode video ${info.codecDisplay}" +
                        (if (info.isTenBit) " 10-bit" else "") + " ${info.width}×${info.height}."
                    item.included = false
                }
            } catch (e: Exception) {
                item.loadError = e.message ?: "Video tidak bisa dibaca."
                item.included = false
            }
        }
    }

    // MARK: Pratinjau & navigasi

    fun requestPreview() {
        val item = selected ?: return
        if (item.info == null) return
        previewRequest = item to currentTime
        if (previewJob?.isActive == true) return
        previewLoading = true
        previewJob = scope.launch {
            while (true) {
                val (it, t) = previewRequest ?: break
                previewRequest = null
                val info = it.info ?: continue
                val bmp = withContext(previewThread) {
                    runCatching { previewGrabber.preview(it.uri, t, 1280, info) }.getOrNull()
                }
                if (bmp != null && selectedId == it.id) {
                    previewImage = bmp.asImageBitmap()
                    if (!playing && previewRequest == null) playerVisible = false
                }
            }
            previewLoading = false
        }
    }

    fun seek(t: Double) {
        val info = selected?.info ?: return
        currentTime = min(max(0.0, t), max(0.0, info.duration - info.frameDuration))
        if (playing) player.seekTo(currentTime) else requestPreview()
    }

    fun stepFrames(frames: Int) {
        val info = selected?.info ?: return
        pausePlayback()
        val n = Math.round(currentTime * info.fps) + frames
        seek(n / info.fps + 0.0001)
    }

    fun stepSeconds(seconds: Double) = seek(currentTime + seconds)

    val currentFrameNumber: Int
        get() {
            val info = selected?.info ?: return 0
            return Math.round(currentTime * info.fps).toInt()
        }

    // MARK: Putar video

    fun togglePlay() = if (playing) pausePlayback() else startPlayback()

    fun startPlayback() {
        val item = selected ?: return
        val info = item.info ?: return
        if (playing || item.loadError != null || phase == Phase.RUNNING) return
        // Sudah di ujung: mulai lagi dari awal.
        val from = if (currentTime >= info.duration - info.frameDuration * 2) 0.0 else currentTime
        currentTime = from
        player.play(item.uri, from)
        playing = true
        playerVisible = true
        playJob?.cancel()
        playJob = scope.launch {
            while (isActive && playing) {
                val p = player.position
                if (p != null && player.isPlaying) currentTime = min(max(0.0, p), max(0.0, info.duration - info.frameDuration))
                delay(33)
            }
        }
    }

    /** Jeda di frame yang sedang tampil; pratinjau lalu diganti frame tepat di posisi itu. */
    fun pausePlayback() {
        if (!playing) return
        playing = false
        playJob?.cancel()
        val info = selected?.info
        val p = player.pause()
        if (p != null && info != null) currentTime = min(max(0.0, p), max(0.0, info.duration - info.frameDuration))
        requestPreview()
    }

    /** Hentikan dan lepaskan pemutar (ganti video, hapus, mulai ekstraksi, aplikasi ditutup). */
    fun stopPlayback() {
        playing = false
        playJob?.cancel()
        player.release()
        playerVisible = false
    }

    private fun onPlaybackEnded() {
        val info = selected?.info ?: return
        playing = false
        playJob?.cancel()
        currentTime = max(0.0, info.duration - info.frameDuration)
        requestPreview()
    }

    fun setRangeStart() {
        val item = selected ?: return
        val fd = item.info?.frameDuration ?: 0.04
        item.customRange = true
        item.rangeStart = min(currentTime, max(0.0, item.rangeEnd - fd))
        if (item.rangeEnd <= item.rangeStart) item.rangeEnd = item.duration
    }

    fun setRangeEnd() {
        val item = selected ?: return
        val fd = item.info?.frameDuration ?: 0.04
        item.customRange = true
        val end = min(item.duration, currentTime + fd)
        item.rangeEnd = max(end, item.rangeStart + fd)
    }

    /** Simpan frame yang sedang dipratinjau, resolusi penuh. */
    fun captureCurrentFrame() {
        pausePlayback()
        val item = selected ?: return
        val info = item.info ?: return
        val s = settings
        val t = currentTime
        scope.launch {
            try {
                val (uri, name) = withContext(previewThread) {
                    val bmp = previewGrabber.fullFrame(item.uri, t, s, info)
                    try {
                        val folder = OutputLocation(app, s.outputFolderUri).base()
                        val names = folder.existingNames()
                        val stamp = timestampName((t * 1000).roundToLong())
                        val prefix = prefixFor(s, item.name)
                        var name = "${prefix}_$stamp.${s.format.ext}"
                        var k = 2
                        while (name in names) { name = "${prefix}_$stamp ($k).${s.format.ext}"; k++ }
                        folder.write(name, s.format) { ImageEncoder.write(bmp, s.format, s.quality, it, app.cacheDir) } to name
                    } finally {
                        bmp.recycle()
                    }
                }
                showToast("Tersimpan: $name", ToastKind.CAMERA, uri)
            } catch (e: Exception) {
                showToast("Gagal menyimpan: ${e.message ?: e.javaClass.simpleName}", ToastKind.ERROR)
            }
        }
    }

    // MARK: Perkiraan

    val includedVideos: List<VideoItem> get() = videos.filter { it.included && it.info != null }

    val estimatedFrames: Int?
        get() {
            val items = includedVideos
            if (items.isEmpty()) return 0
            var total = 0
            for (v in items) {
                val info = v.info ?: continue
                total += settings.estimatedFrames(v.effectiveDuration, info.fps) ?: return null
            }
            return total
        }

    val estimatedBytes: Double?
        get() {
            val n = estimatedFrames ?: return null
            val b = sampleBytes ?: return null
            return n * b
        }

    val outputLocation: OutputLocation get() = OutputLocation(app, settings.outputFolderUri)

    fun freeDiskBytes(): Double? = outputLocation.freeBytes()

    fun scheduleSampleUpdate() {
        val item = selected ?: return
        val info = item.info ?: return
        val s = settings
        val key = "${item.uri}|${s.format}|${s.quality}|${s.resolution}|${s.customWidth}"
        if (key == sampleKey) return
        sampleKey = key
        sampleJob?.cancel()
        sampleLoading = true
        val start = item.effectiveStart
        val end = if (item.effectiveEnd > 0) item.effectiveEnd else info.duration
        sampleJob = scope.launch {
            delay(350)
            val job = this
            val bytes = withContext(sampleThread) {
                sampleGrabber.sampleFrameBytes(item.uri, start, end, s, info) { !job.isActive }
            }
            if (!isActive) return@launch
            sampleBytes = bytes
            sampleLoading = false
        }
    }

    // MARK: Ekstraksi

    val canStart: Boolean get() = phase != Phase.RUNNING && includedVideos.isNotEmpty()

    val overallFraction: Double
        get() = if (runTotal <= 0) 0.0 else min(1.0, (runIndex + progress.fraction) / runTotal)

    fun startExtraction() {
        if (!canStart) return
        stopPlayback()
        val items = includedVideos
        val s = settings
        selected?.lastTime = currentTime
        cancelRequested = false
        phase = Phase.RUNNING
        runTotal = items.size
        runIndex = 0
        progress = ExtractionProgress()
        items.forEach { it.status = ItemStatus.Ready }
        ExtractionService.start(app)

        scope.launch {
            var frames = 0
            var bytes = 0L
            val folders = ArrayList<OutputFolder>()
            val samples = ArrayList<Uri>()
            val errors = ArrayList<Pair<String, String>>()
            var cancelled = false
            var count = 0
            val started = System.nanoTime()
            for ((i, item) in items.withIndex()) {
                if (cancelRequested) break
                val info = item.info ?: continue
                runIndex = i
                runName = item.name
                progress = ExtractionProgress()
                item.status = ItemStatus.Running
                val job = ExtractionJob(item.uri, item.name, info, item.effectiveStart, item.effectiveEnd, s)
                val runner = ExtractionRunner(app, job)
                currentRunner = runner
                val result = runner.run { p ->
                    scope.launch {
                        progress = p
                        ExtractionService.update(app, runName, runIndex, runTotal, overallFraction, p.frames)
                    }
                }
                currentRunner = null
                count++
                frames += result.frames
                bytes += result.totalBytes
                if (result.folder != null && (result.frames > 0 || result.errorMessage == null)) folders.add(result.folder)
                if (samples.size < 12) samples.addAll(result.sampleFiles.take(12 - samples.size))
                when {
                    result.errorMessage != null -> {
                        item.status = ItemStatus.Failed(result.errorMessage)
                        errors.add(item.name to result.errorMessage)
                    }
                    result.cancelled -> {
                        item.status = ItemStatus.Cancelled
                        cancelled = true
                    }
                    else -> item.status = ItemStatus.Done(result.frames)
                }
                if (cancelled) break
            }
            val sum = RunSummary(
                frames = frames, bytes = bytes, elapsed = (System.nanoTime() - started) / 1e9,
                folders = folders, samples = samples, errors = errors,
                cancelled = cancelled || cancelRequested, videos = count,
            )
            summary = sum
            phase = Phase.FINISHED
            showSummary = true
            ExtractionService.finish(app, summaryTitle(sum), summarySubtitle(sum))
        }
    }

    fun cancelExtraction() {
        cancelRequested = true
        currentRunner?.cancel()
    }

    fun dismissSummary() {
        showSummary = false
        phase = Phase.IDLE
    }

    // MARK: Lain-lain

    fun showToast(message: String, kind: ToastKind = ToastKind.SUCCESS, openUri: Uri? = null) {
        toast = Toast(message, kind, openUri)
        toastJob?.cancel()
        toastJob = scope.launch {
            delay(3500)
            toast = null
        }
    }

    fun dismissToast() { toast = null }

    fun resetSettings() {
        val folder = settings.outputFolderUri
        settings = ExtractionSettings(outputFolderUri = folder)
    }

    fun setOutputFolder(tree: Uri?) {
        if (tree == null) {
            update { it.copy(outputFolderUri = "") }
            return
        }
        runCatching {
            app.contentResolver.takePersistableUriPermission(tree,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        update { it.copy(outputFolderUri = tree.toString()) }
    }
}

fun summaryTitle(s: RunSummary): String = when {
    s.errors.isNotEmpty() && s.frames == 0 -> "Ekstraksi gagal"
    s.cancelled -> "Dibatalkan"
    s.errors.isNotEmpty() -> "Selesai, dengan kendala"
    else -> "Selesai!"
}

fun summarySubtitle(s: RunSummary): String = when {
    s.cancelled -> "${formatCount(s.frames)} gambar sempat tersimpan."
    s.videos > 1 -> "${formatCount(s.frames)} gambar dari ${s.videos} video sudah tersimpan."
    else -> "${formatCount(s.frames)} gambar sudah tersimpan."
}

/** Urutan alami: "Episode 2" sebelum "Episode 10". */
fun naturalCompare(a: String, b: String, collator: Collator): Int {
    val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
    val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
    for (i in 0 until min(ra.size, rb.size)) {
        val x = ra[i]
        val y = rb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
        } else collator.compare(x, y)
        if (c != 0) return c
    }
    return ra.size.compareTo(rb.size)
}
