package local.frameshot.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Memutuskan frame mana yang disimpan — logikanya sama dengan filter ffmpeg di versi macOS:
 *  - Semua Frame   : semua frame
 *  - Tiap N Frame  : select='not(mod(n,N))'
 *  - Interval      : frame pertama di tiap "ember" waktu floor(t/step), tanpa drift
 *  - Jumlah Tetap  : interval = durasi/jumlah, dibatasi `jumlah` gambar
 *  - Ganti Adegan  : frame pertama + frame dengan skor adegan > ambang
 * `t` adalah detik relatif terhadap awal rentang. Frame duplikat (jika dilewati) tidak boleh diberikan ke sini.
 */
class FrameSelector(
    private val settings: ExtractionSettings,
    rangeDuration: Double,
    frameDuration: Double,
) {
    private var n = 0
    private var prevSelectedT: Double? = null
    var selectedCount = 0
        private set

    val limit: Int? = if (settings.mode == ExtractionMode.FIXED_COUNT) max(1, settings.fixedCount) else null

    /** Langkah waktu untuk mode berbasis interval; null = tidak berbasis waktu. */
    val step: Double? = when (settings.mode) {
        ExtractionMode.INTERVAL -> max(0.001, settings.intervalSeconds)
        ExtractionMode.FIXED_COUNT -> (rangeDuration / max(1, settings.fixedCount)).takeIf { it > frameDuration }
        else -> null
    }

    val needsSceneScore: Boolean get() = settings.mode == ExtractionMode.SCENE_CHANGE
    val threshold: Double = min(0.95, max(0.01, settings.sceneThreshold))

    val isDone: Boolean get() = limit?.let { selectedCount >= it } ?: false

    fun offer(t: Double, sceneScore: Double = 0.0): Boolean {
        if (isDone) return false
        val index = n++
        val selected = when (settings.mode) {
            ExtractionMode.ALL_FRAMES -> true
            ExtractionMode.EVERY_N_FRAMES -> index % max(1, settings.everyN) == 0
            ExtractionMode.INTERVAL, ExtractionMode.FIXED_COUNT -> {
                val st = step
                if (st == null) true
                else prevSelectedT.let { p -> p == null || floor(t / st) > floor(p / st) }
            }
            ExtractionMode.SCENE_CHANGE -> index == 0 || sceneScore > threshold
        }
        if (selected) {
            prevSelectedT = t
            selectedCount++
        }
        return selected
    }

    /**
     * Waktu (relatif) frame berikutnya yang mungkin dipilih, untuk melompat (seek) melewati bagian
     * yang pasti tidak dipakai. null = harus decode setiap frame.
     */
    fun nextWantedTime(): Double? {
        val st = step ?: return null
        val p = prevSelectedT ?: return null
        return (floor(p / st) + 1) * st
    }
}

/**
 * Skor pergantian adegan ala filter `scene` ffmpeg: min(mafd, |mafd − mafd sebelumnya|) / 100,
 * dengan mafd = rata-rata selisih absolut piksel (skala 0–255) terhadap frame sebelumnya.
 */
class SceneScorer {
    private var prev: ByteArray? = null
    private var prevMafd = 0.0

    /** `rgba` = piksel kecil RGBA (ukuran tetap sepanjang video). */
    fun score(rgba: ByteArray): Double {
        val p = prev
        if (p == null || p.size != rgba.size) {
            prev = rgba.copyOf()
            prevMafd = 0.0
            return 0.0
        }
        var sad = 0L
        var count = 0
        var i = 0
        while (i < rgba.size) {
            sad += abs((rgba[i].toInt() and 0xFF) - (p[i].toInt() and 0xFF))
            sad += abs((rgba[i + 1].toInt() and 0xFF) - (p[i + 1].toInt() and 0xFF))
            sad += abs((rgba[i + 2].toInt() and 0xFF) - (p[i + 2].toInt() and 0xFF))
            count += 3
            i += 4
        }
        val mafd = sad.toDouble() / max(1, count)
        val diff = abs(mafd - prevMafd)
        prevMafd = mafd
        System.arraycopy(rgba, 0, p, 0, rgba.size)
        return min(1.0, max(0.0, min(mafd, diff) / 100.0))
    }
}

/**
 * Pendeteksi frame duplikat ala `mpdecimate` ffmpeg. Untuk tiap bidang Y, U, V (U/V setengah resolusi):
 * frame dianggap berbeda jika ada blok 8×8 dengan selisih > `hi`, atau cukup banyak blok dengan
 * selisih > `lo`. Jika tidak ada bidang yang berbeda → duplikat (dibuang).
 * Dibandingkan dengan frame terakhir yang DISIMPAN (bukan frame sebelumnya).
 */
class DuplicateDetector(
    private val width: Int,
    private val height: Int,
    private val hi: Int = 64 * 12,
    private val lo: Int = 64 * 5,
    private val frac: Double = 0.33,
) {
    private val cw = width / 2
    private val ch = height / 2
    private var last: Array<ByteArray>? = null

    fun isDuplicate(rgba: ByteArray): Boolean {
        val planes = toYuv(rgba)
        val ref = last
        if (ref == null) {
            last = planes
            return false
        }
        val different = differs(planes[0], ref[0], width, height) ||
            differs(planes[1], ref[1], cw, ch) || differs(planes[2], ref[2], cw, ch)
        if (different) last = planes
        return !different
    }

    /** Sama persis dengan diff_planes() di mpdecimate: blok 8×8 bertumpuk tiap 4 piksel. */
    private fun differs(cur: ByteArray, ref: ByteArray, w: Int, h: Int): Boolean {
        val t = ((w / 16) * (h / 16) * frac).toInt()
        var c = 0
        var y = 0
        while (y < h - 7) {
            var x = 8
            while (x < w - 7) {
                var sad = 0
                for (dy in 0 until 8) {
                    var idx = (y + dy) * w + x
                    for (dx in 0 until 8) {
                        sad += abs((cur[idx].toInt() and 0xFF) - (ref[idx].toInt() and 0xFF))
                        idx++
                    }
                }
                if (sad > hi) return true
                if (sad > lo && ++c > t) return true
                x += 4
            }
            y += 4
        }
        return false
    }

    /** RGBA → bidang Y (penuh) serta U, V (2×2 dirata-rata), BT.601 limited range. */
    private fun toYuv(rgba: ByteArray): Array<ByteArray> {
        val yp = ByteArray(width * height)
        val up = ByteArray(cw * ch)
        val vp = ByteArray(cw * ch)
        val uAcc = IntArray(cw * ch)
        val vAcc = IntArray(cw * ch)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 4
                if (i + 2 >= rgba.size) continue
                val r = rgba[i].toInt() and 0xFF
                val g = rgba[i + 1].toInt() and 0xFF
                val b = rgba[i + 2].toInt() and 0xFF
                yp[y * width + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                val cx = x / 2
                val cy = y / 2
                if (cx < cw && cy < ch) {
                    val ci = cy * cw + cx
                    uAcc[ci] += ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    vAcc[ci] += ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                }
            }
        }
        for (i in up.indices) {
            up[i] = (uAcc[i] / 4).toByte()
            vp[i] = (vAcc[i] / 4).toByte()
        }
        return arrayOf(yp, up, vp)
    }
}
