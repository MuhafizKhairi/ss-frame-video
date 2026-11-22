package local.frameshot.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface

/**
 * Pemutar video untuk pratinjau (dengan suara), memakai decoder hardware perangkat lewat MediaPlayer.
 * Dipakai dari thread utama saja.
 */
class PreviewPlayer(private val context: Context) {
    private var mp: MediaPlayer? = null
    private var uri: Uri? = null
    private var prepared = false
    private var pendingStart: Double? = null
    private var surface: Surface? = null

    var onCompletion: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isPlaying: Boolean get() = runCatching { mp?.isPlaying == true }.getOrDefault(false)

    /** Posisi sekarang (detik), atau null jika belum siap. */
    val position: Double? get() = if (prepared) runCatching { mp!!.currentPosition / 1000.0 }.getOrNull() else null

    fun setSurface(s: Surface?) {
        surface = s
        runCatching { mp?.setSurface(s) }
    }

    /** Lepas `s` hanya jika itu permukaan yang sedang dipakai (TextureView lama bisa hancur belakangan). */
    fun clearSurface(s: Surface) {
        if (surface === s) setSurface(null)
    }

    /** Putar `uri` mulai dari `from` (detik). Menyiapkan ulang pemutar jika videonya berbeda. */
    fun play(uri: Uri, from: Double) {
        val cur = mp
        if (cur != null && this.uri == uri) {
            if (prepared) {
                seekTo(from)
                cur.start()
            } else pendingStart = from
            return
        }
        release()
        val p = MediaPlayer()
        mp = p
        this.uri = uri
        prepared = false
        pendingStart = from
        p.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        p.setOnPreparedListener {
            if (mp !== it) return@setOnPreparedListener
            prepared = true
            pendingStart?.let { t -> seekTo(t); it.start() }
            pendingStart = null
        }
        p.setOnCompletionListener { if (mp === it) onCompletion?.invoke() }
        p.setOnErrorListener { mp2, what, extra ->
            if (mp === mp2) {
                release()
                onError?.invoke("Video tidak bisa diputar (kode $what/$extra).")
            }
            true
        }
        try {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd != null) afd.use { p.setDataSource(it) } else p.setDataSource(context, uri)
            surface?.let { p.setSurface(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            release()
            onError?.invoke("Video tidak bisa diputar: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Jeda; mengembalikan posisi terakhir (detik) jika tersedia. */
    fun pause(): Double? {
        pendingStart = null
        runCatching { if (mp?.isPlaying == true) mp?.pause() }
        return position
    }

    fun seekTo(t: Double) {
        val p = mp ?: return
        if (!prepared) { pendingStart = t; return }
        runCatching { p.seekTo((t * 1000).toLong(), MediaPlayer.SEEK_CLOSEST) }
    }

    fun release() {
        mp?.let { runCatching { it.reset(); it.release() } }
        mp = null
        uri = null
        prepared = false
        pendingStart = null
    }
}
