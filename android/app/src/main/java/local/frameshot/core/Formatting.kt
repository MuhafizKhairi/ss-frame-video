package local.frameshot.core

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** "01:02:03.500" / "02:03" / "75.5" → detik. */
fun parseTimecode(text: String): Double? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val parts = t.split(":")
    if (parts.size > 3) return null
    var total = 0.0
    for (p in parts) {
        val v = p.toDoubleOrNull() ?: return null
        if (v < 0 || v.isNaN() || v.isInfinite()) return null
        total = total * 60 + v
    }
    return total
}

/** detik → "1:02:03.500" atau "02:03.500". */
fun formatTimecode(seconds: Double, millis: Boolean = true, forceHours: Boolean = false): String {
    val totalMs = (max(0.0, seconds) * 1000).roundToLong()
    val h = totalMs / 3_600_000
    val m = (totalMs / 60_000) % 60
    val s = (totalMs / 1000) % 60
    val ms = totalMs % 1000
    var out = if (h > 0 || forceHours) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
    if (millis) out += String.format(Locale.US, ".%03d", ms)
    return out
}

fun formatBytes(bytes: Double): String {
    val b = max(0.0, bytes)
    val units = listOf("KB", "MB", "GB", "TB")
    var v = b / 1000
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
    val text = when {
        i == 0 -> String.format(Locale.US, "%.0f", max(if (b > 0) 1.0 else 0.0, v))
        v >= 100 -> String.format(Locale.US, "%.0f", v)
        else -> String.format(Locale("id", "ID"), "%.1f", v)
    }
    return "$text ${units[i]}"
}

fun formatCount(n: Int): String = NumberFormat.getIntegerInstance(Locale("id", "ID")).format(n)

fun formatFps(fps: Double): String {
    val r = Math.round(fps * 1000) / 1000.0
    if (abs(r - Math.rint(r)) < 1e-9) return String.format(Locale.US, "%.0f fps", r)
    return String.format(Locale.US, "%.3f", r).trimEnd('0').trimEnd('.') + " fps"
}

fun sanitizeFilename(s: String): String {
    val cleaned = buildString {
        for (ch in s) append(if (ch in "/\\:?*\"<>|" || ch.isISOControl()) '_' else ch)
    }.trim()
    return cleaned.ifEmpty { "frame" }
}

/** Nama file untuk mode timestamp: 00-12-30-500 (tanpa ":" agar aman di semua sistem file). */
fun timestampName(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms / 60_000) % 60
    val s = (ms / 1000) % 60
    val r = ms % 1000
    return String.format(Locale.US, "%02d-%02d-%02d-%03d", h, m, s, r)
}

fun prefixFor(settings: ExtractionSettings, videoName: String): String {
    val p = settings.customPrefix.trim()
    return sanitizeFilename(p.ifEmpty { videoName })
}

/** Contoh nama file untuk ditampilkan di UI. */
fun exampleFilename(settings: ExtractionSettings, videoName: String): String {
    val prefix = prefixFor(settings, videoName)
    val ext = settings.format.ext
    if (settings.naming == NamingStyle.TIMESTAMP) return "${prefix}_00-12-30-500.$ext"
    return "${prefix}_${String.format(Locale.US, "%0${max(1, settings.digits)}d", settings.startNumber)}.$ext"
}

/**
 * Penamaan file hasil satu video. Tidak pernah menimpa: jika awalan sudah dipakai di folder,
 * diberi akhiran " (2)", " (3)", dst. Aman dipanggil dari beberapa thread.
 */
class FrameNamer(
    basePrefix: String,
    private val settings: ExtractionSettings,
    existing: Set<String>,
) {
    private val ext = settings.format.ext
    private val used = HashSet(existing)
    val prefix: String = availablePrefix(basePrefix, existing)
    private var seq = settings.startNumber

    private fun availablePrefix(p: String, names: Set<String>): String {
        fun taken(x: String) = names.any { it.startsWith(x + "_") && it.lowercase().endsWith(".$ext") }
        if (!taken(p)) return p
        var i = 2
        while (taken("$p ($i)")) i++
        return "$p ($i)"
    }

    /** Nama untuk frame berikutnya. `ptsMs` = posisi frame di video (milidetik). */
    @Synchronized
    fun next(ptsMs: Long): String {
        if (settings.naming == NamingStyle.SEQUENCE) {
            val name = "${prefix}_${String.format(Locale.US, "%0${max(1, settings.digits)}d", seq)}.$ext"
            seq++
            used.add(name)
            return name
        }
        val stamp = timestampName(ptsMs)
        var name = "${prefix}_$stamp.$ext"
        var k = 2
        while (name in used) { name = "${prefix}_$stamp ($k).$ext"; k++ }
        used.add(name)
        return name
    }
}

/** Nama subfolder unik per video: "Episode 01 — frames", "Episode 01 — frames 2", … */
fun uniqueFolderName(videoName: String, exists: (String) -> Boolean): String {
    val base = sanitizeFilename(videoName) + " — frames"
    if (!exists(base)) return base
    var i = 2
    while (exists("$base $i")) i++
    return "$base $i"
}
