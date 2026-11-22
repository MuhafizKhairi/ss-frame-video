package local.frameshot

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.IntentCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.frameshot.core.ExtractionMode
import local.frameshot.core.ImageFormat
import local.frameshot.media.OutputFolder
import local.frameshot.ui.FrameshotScreen
import local.frameshot.ui.FrameshotTheme
import local.frameshot.ui.UiActions
import java.io.File

class MainActivity : ComponentActivity() {
    private val state: AppState get() = (application as FrameshotApplication).state

    private val pickVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        state.addUris(uris)
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.addFolder(it) }
    }
    private val pickOutput = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { state.setOutputFolder(it) }
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        state.startExtraction()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val actions = UiActions(
            pickVideos = { pickVideos.launch(arrayOf("video/*")) },
            pickFolder = { pickFolder.launch(null) },
            pickOutputFolder = { pickOutput.launch(null) },
            startExtraction = ::startExtraction,
            openImage = ::openImage,
            openFolder = ::openFolder,
        )
        setContent { FrameshotTheme { FrameshotScreen(state, actions) } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        state.pausePlayback()
    }

    override fun onResume() {
        super.onResume()
        ExtractionService.clearDone(this)
    }

    /** File dari "Buka dengan" / "Bagikan". */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (BuildConfig.DEBUG) intent.getStringExtra("debug_script")?.let { runDebugScript(it); return }
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) state.addUris(uris)
    }

    private fun startExtraction() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("frameshot", MODE_PRIVATE).getBoolean("asked.notif", false)
        ) {
            getSharedPreferences("frameshot", MODE_PRIVATE).edit().putBoolean("asked.notif", true).apply()
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        state.startExtraction()
    }

    private fun openImage(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: ActivityNotFoundException) {
            state.showToast("Tidak ada aplikasi untuk membuka gambar.", ToastKind.ERROR)
        }
    }

    /** Buka folder di aplikasi File; jika tidak didukung, buka gambar pertama. */
    private fun openFolder(folder: OutputFolder, fallback: Uri?) {
        val uri = folder.viewUri
        if (uri != null) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                return
            } catch (_: Exception) {
            }
        }
        if (fallback != null) openImage(fallback)
        else state.showToast("Hasil ada di ${folder.displayPath}")
    }

    /**
     * Skrip uji otomatis untuk pengembangan (hanya build debug), mis.
     * adb shell am start -n local.frameshot.app/local.frameshot.MainActivity --es debug_script "open:/path/a.mp4;wait:3;extract"
     */
    private fun runDebugScript(script: String) {
        MainScope().launch {
            for (cmd in script.split(";")) {
                val name = cmd.substringBefore(":")
                val arg = cmd.substringAfter(":", "")
                when (name) {
                    "open" -> state.addUris(arg.split("|").map { Uri.fromFile(File(it)) })
                    "wait" -> delay(((arg.toDoubleOrNull() ?: 1.0) * 1000).toLong())
                    "mode" -> runCatching { ExtractionMode.valueOf(arg) }.getOrNull()?.let { m -> state.update { it.copy(mode = m) } }
                    "format" -> runCatching { ImageFormat.valueOf(arg) }.getOrNull()?.let { f -> state.update { it.copy(format = f) } }
                    "interval" -> arg.toDoubleOrNull()?.let { v -> state.update { it.copy(intervalSeconds = v) } }
                    "res" -> runCatching { local.frameshot.core.ResolutionPreset.valueOf(arg) }.getOrNull()?.let { r -> state.update { it.copy(resolution = r) } }
                    "naming" -> runCatching { local.frameshot.core.NamingStyle.valueOf(arg) }.getOrNull()?.let { n -> state.update { it.copy(naming = n) } }
                    "dups" -> state.update { it.copy(skipDuplicates = arg == "1") }
                    "seek" -> state.seek(arg.toDoubleOrNull() ?: 0.0)
                    "range" -> { state.seek(3.0); state.setRangeStart(); state.seek(9.0); state.setRangeEnd() }
                    "extract" -> {
                        // Tunggu semua video selesai dimuat dulu.
                        var waited = 0
                        while ((state.videos.isEmpty() || state.videos.any { it.info == null && it.loadError == null }) && waited < 120) { delay(500); waited++ }
                        state.startExtraction()
                    }
                    "capture" -> state.captureCurrentFrame()
                    "play" -> state.togglePlay()
                    "dismiss" -> state.dismissSummary()
                    "reset" -> state.resetSettings()
                    "clear" -> state.clearAll()
                }
            }
        }
    }
}
