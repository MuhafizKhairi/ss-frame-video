package local.frameshot.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Buka video lewat file descriptor milik aplikasi sendiri. Lebih andal daripada memberi URI ke
 * proses media sistem (yang mungkin tidak punya izin membaca file/URI tersebut).
 */
object MediaSource {
    fun open(context: Context, ex: MediaExtractor, uri: Uri) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.declaredLength >= 0) ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            else ex.setDataSource(afd.fileDescriptor)
            return
        }
        ex.setDataSource(context, uri, null)
    }

    fun open(context: Context, mmr: MediaMetadataRetriever, uri: Uri) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            if (afd.declaredLength >= 0) mmr.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
            else mmr.setDataSource(afd.fileDescriptor)
            return
        }
        mmr.setDataSource(context, uri)
    }
}
