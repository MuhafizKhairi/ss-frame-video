package local.frameshot.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import local.frameshot.core.ImageFormat
import local.frameshot.core.uniqueFolderName
import java.io.File
import java.io.OutputStream

/** Satu folder tujuan yang siap ditulisi. */
interface OutputFolder {
    /** Nama yang ditampilkan, mis. "Pictures/Frameshot/Episode 01 — frames". */
    val displayPath: String
    /** URI untuk membuka folder di aplikasi File (bisa saja tidak didukung). */
    val viewUri: Uri?
    fun existingNames(): Set<String>
    /** Tulis satu file. Mengembalikan URI file. */
    fun write(name: String, format: ImageFormat, writer: (OutputStream) -> Unit): Uri
}

/**
 * Lokasi output: kosong = Galeri (Pictures/Frameshot lewat MediaStore, tanpa izin apa pun),
 * atau folder pilihan pengguna (Storage Access Framework).
 */
class OutputLocation(private val context: Context, private val treeUri: String) {
    val isGallery: Boolean get() = treeUri.isEmpty()

    /** Siapkan folder untuk satu video (dengan subfolder unik opsional). */
    fun prepare(videoName: String, createSubfolder: Boolean): OutputFolder =
        if (isGallery) galleryFolder(videoName, createSubfolder) else treeFolder(videoName, createSubfolder)

    /** Folder dasar tanpa subfolder (untuk "Ambil frame ini"). */
    fun base(): OutputFolder = prepare("", false)

    val title: String
        get() = if (isGallery) "Galeri (Pictures/Frameshot)" else treeLabel(Uri.parse(treeUri)).substringAfterLast('/')

    val subtitle: String
        get() = if (isGallery) "Ketuk untuk memilih folder lain" else treeLabel(Uri.parse(treeUri))

    fun freeBytes(): Double? = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes.toDouble()
    }.getOrNull()

    // MARK: Galeri

    private fun galleryFolder(videoName: String, sub: Boolean): OutputFolder {
        val root = "${Environment.DIRECTORY_PICTURES}/Frameshot"
        val path = if (sub && videoName.isNotEmpty()) {
            val taken = galleryRelativePaths()
            val publicRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Frameshot")
            "$root/" + uniqueFolderName(videoName) { n -> "$root/$n/" in taken || File(publicRoot, n).exists() }
        } else root
        return GalleryFolder(context, path)
    }

    private fun galleryRelativePaths(): Set<String> {
        val out = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${Environment.DIRECTORY_PICTURES}/Frameshot/%"), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
        }
        return out
    }

    private class GalleryFolder(private val context: Context, private val relPath: String) : OutputFolder {
        override val displayPath: String get() = relPath
        override val viewUri: Uri?
            get() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relPath")

        override fun existingNames(): Set<String> {
            val out = HashSet<String>()
            runCatching {
                context.contentResolver.query(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$relPath/"), null,
                )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
            }
            runCatching { File(Environment.getExternalStorageDirectory(), relPath).list()?.let { out.addAll(it) } }
            return out
        }

        override fun write(name: String, format: ImageFormat, writer: (OutputStream) -> Unit): Uri {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relPath/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IllegalStateException("Tidak bisa membuat file di galeri.")
            try {
                resolver.openOutputStream(uri, "w")?.use(writer) ?: throw IllegalStateException("Tidak bisa menulis $name.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return uri
        }
    }

    // MARK: Folder pilihan (SAF)

    private fun treeFolder(videoName: String, sub: Boolean): OutputFolder {
        val tree = Uri.parse(treeUri)
        val rootDoc = DocumentsContract.getTreeDocumentId(tree)
        var docId = rootDoc
        var label = treeLabel(tree)
        if (sub && videoName.isNotEmpty()) {
            val children = listChildren(tree, rootDoc)
            val name = uniqueFolderName(videoName) { it in children }
            val created = DocumentsContract.createDocument(
                context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootDoc),
                DocumentsContract.Document.MIME_TYPE_DIR, name,
            ) ?: throw IllegalStateException("Tidak bisa membuat subfolder \"$name\".")
            docId = DocumentsContract.getDocumentId(created)
            label = "$label/$name"
        }
        return TreeFolder(context, tree, docId, label)
    }

    private fun listChildren(tree: Uri, docId: String): Set<String> {
        val out = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out.add(it) } }
        }
        return out
    }

    private inner class TreeFolder(
        private val context: Context,
        private val tree: Uri,
        private val docId: String,
        override val displayPath: String,
    ) : OutputFolder {
        private val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        override val viewUri: Uri? get() = parent
        override fun existingNames(): Set<String> = listChildren(tree, docId)

        override fun write(name: String, format: ImageFormat, writer: (OutputStream) -> Unit): Uri {
            // Pakai MIME yang dikenal sistem agar penyedia tidak menambah ekstensi lain.
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(format.ext)?.takeIf { it == format.mime }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(format.ext) ?: "application/octet-stream"
            val uri = DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
                ?: throw IllegalStateException("Tidak bisa membuat $name di folder tujuan.")
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use(writer)
                    ?: throw IllegalStateException("Tidak bisa menulis $name.")
            } catch (e: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                throw e
            }
            return uri
        }
    }

    companion object {
        /** "primary:DCIM/Shots" → "DCIM/Shots"; selain itu nama folder dari penyedia. */
        fun treeLabel(tree: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
            val path = id.substringAfter(':', id)
            val vol = id.substringBefore(':', "")
            return when {
                vol == "primary" -> path.ifEmpty { "Penyimpanan internal" }
                vol.isNotEmpty() && path.isNotEmpty() -> "$vol/$path"
                else -> path.ifEmpty { id }
            }
        }
    }
}
