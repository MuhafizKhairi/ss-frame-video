package local.frameshot.core

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ExtractionMode(val title: String, val explanation: String) {
    ALL_FRAMES("Semua Frame", "Ambil setiap frame tanpa terlewat. Hasilnya banyak — cocok untuk analisis frame-per-frame."),
    EVERY_N_FRAMES("Tiap N Frame", "Ambil 1 frame setiap N frame. Misal N = 24 di video 24 fps ≈ 1 gambar per detik."),
    INTERVAL("Interval Waktu", "Ambil 1 frame setiap beberapa detik sekali."),
    FIXED_COUNT("Jumlah Tetap", "Tentukan jumlah gambar; frame dibagi rata sepanjang rentang."),
    SCENE_CHANGE("Ganti Adegan", "Otomatis ambil frame saat adegan berganti. Pas untuk anime & cuplikan film."),
}

enum class ImageFormat(
    val label: String,
    val ext: String,
    val mime: String,
    val hasQuality: Boolean,
    val hint: String,
) {
    JPG("JPG", "jpg", "image/jpeg", true, "Ukuran kecil, kualitas bagus. Pilihan paling umum."),
    PNG("PNG", "png", "image/png", false, "Lossless (tanpa kompresi rusak). File lebih besar."),
    HEIC("HEIC", "heic", "image/heic", true, "Format HEIF: kualitas setara JPG dengan ukuran ±50% lebih kecil."),
    WEBP("WebP", "webp", "image/webp", true, "Modern & hemat ruang, kualitas tinggi."),
    AVIF("AVIF", "avif", "image/avif", true, "Paling hemat ruang, tapi proses lebih lambat."),
    TIFF("TIFF", "tiff", "image/tiff", false, "Lossless, untuk editing profesional. Sangat besar."),
    BMP("BMP", "bmp", "image/bmp", false, "Tanpa kompresi sama sekali. Paling besar."),
}

enum class ResolutionPreset(val label: String, val height: Int?) {
    ORIGINAL("Asli", null),
    P2160("4K (2160p)", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
    CUSTOM("Lebar kustom…", null),
}

enum class NamingStyle(val label: String) {
    SEQUENCE("Nomor urut"),
    TIMESTAMP("Timestamp"),
}

data class ExtractionSettings(
    val mode: ExtractionMode = ExtractionMode.INTERVAL,
    val everyN: Int = 24,
    val intervalSeconds: Double = 1.0,
    val fixedCount: Int = 50,
    val sceneThreshold: Double = 0.30,

    val format: ImageFormat = ImageFormat.JPG,
    val quality: Int = 92,                 // 1–100

    val resolution: ResolutionPreset = ResolutionPreset.ORIGINAL,
    val customWidth: Int = 1280,

    val naming: NamingStyle = NamingStyle.SEQUENCE,
    val customPrefix: String = "",         // kosong = pakai nama video
    val startNumber: Int = 1,
    val digits: Int = 6,

    val outputFolderUri: String = "",      // kosong = Galeri (Pictures/Frameshot)
    val createSubfolder: Boolean = true,

    val skipDuplicates: Boolean = false,
    val hardwareDecode: Boolean = true,
) {
    /** Perkiraan jumlah frame untuk rentang tertentu. null = tidak bisa diperkirakan (deteksi adegan). */
    fun estimatedFrames(duration: Double, fps: Double): Int? {
        val d = max(0.0, duration)
        val total = max(1, (d * fps).roundToInt())
        return when (mode) {
            ExtractionMode.ALL_FRAMES -> total
            ExtractionMode.EVERY_N_FRAMES -> ceil(total.toDouble() / max(1, everyN)).toInt()
            ExtractionMode.INTERVAL -> min(total, max(1, ceil(d / max(0.001, intervalSeconds) - 1e-9).toInt()))
            ExtractionMode.FIXED_COUNT -> min(total, max(1, fixedCount))
            ExtractionMode.SCENE_CHANGE -> null
        }
    }

    /** Ukuran keluaran setelah skala (hanya mengecilkan, tidak memperbesar). */
    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return w to h
        if (resolution == ResolutionPreset.CUSTOM) {
            val cw = min(w, max(16, customWidth))
            return cw to (h.toDouble() * cw / w / 2).roundToLong().toInt() * 2
        }
        val th = resolution.height ?: return w to h
        if (th >= h) return w to h
        return (w.toDouble() * th / h / 2).roundToLong().toInt() * 2 to th
    }

    fun toJson(): String = JSONObject().apply {
        put("mode", mode.name); put("everyN", everyN); put("intervalSeconds", intervalSeconds)
        put("fixedCount", fixedCount); put("sceneThreshold", sceneThreshold)
        put("format", format.name); put("quality", quality)
        put("resolution", resolution.name); put("customWidth", customWidth)
        put("naming", naming.name); put("customPrefix", customPrefix)
        put("startNumber", startNumber); put("digits", digits)
        put("outputFolderUri", outputFolderUri); put("createSubfolder", createSubfolder)
        put("skipDuplicates", skipDuplicates); put("hardwareDecode", hardwareDecode)
    }.toString()

    companion object {
        /** JSON lama tanpa field baru tetap terbaca; nilai yang hilang/rusak memakai bawaan. */
        fun fromJson(text: String?): ExtractionSettings {
            val d = ExtractionSettings()
            if (text.isNullOrBlank()) return d
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return d
            fun <T> get(key: String, def: T, read: (String) -> T): T =
                if (o.has(key)) runCatching { read(key) }.getOrDefault(def) else def
            return ExtractionSettings(
                mode = get("mode", d.mode) { ExtractionMode.valueOf(o.getString(it)) },
                everyN = get("everyN", d.everyN) { o.getInt(it) },
                intervalSeconds = get("intervalSeconds", d.intervalSeconds) { o.getDouble(it) },
                fixedCount = get("fixedCount", d.fixedCount) { o.getInt(it) },
                sceneThreshold = get("sceneThreshold", d.sceneThreshold) { o.getDouble(it) },
                format = get("format", d.format) { ImageFormat.valueOf(o.getString(it)) },
                quality = get("quality", d.quality) { o.getInt(it) },
                resolution = get("resolution", d.resolution) { ResolutionPreset.valueOf(o.getString(it)) },
                customWidth = get("customWidth", d.customWidth) { o.getInt(it) },
                naming = get("naming", d.naming) { NamingStyle.valueOf(o.getString(it)) },
                customPrefix = get("customPrefix", d.customPrefix) { o.getString(it) },
                startNumber = get("startNumber", d.startNumber) { o.getInt(it) },
                digits = get("digits", d.digits) { o.getInt(it) },
                outputFolderUri = get("outputFolderUri", d.outputFolderUri) { o.getString(it) },
                createSubfolder = get("createSubfolder", d.createSubfolder) { o.getBoolean(it) },
                skipDuplicates = get("skipDuplicates", d.skipDuplicates) { o.getBoolean(it) },
                hardwareDecode = get("hardwareDecode", d.hardwareDecode) { o.getBoolean(it) },
            )
        }
    }
}
