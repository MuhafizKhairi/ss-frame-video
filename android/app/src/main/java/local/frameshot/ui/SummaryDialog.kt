package local.frameshot.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.frameshot.RunSummary
import local.frameshot.core.formatBytes
import local.frameshot.core.formatCount
import local.frameshot.core.formatTimecode
import local.frameshot.summarySubtitle
import local.frameshot.summaryTitle

@Composable
fun SummaryDialog() {
    val state = LocalState.current
    val actions = LocalActions.current
    val s = state.summary ?: RunSummary()
    Dialog(onDismissRequest = { state.dismissSummary() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val failed = s.errors.isNotEmpty() && s.frames == 0
            Box(
                Modifier.size(64.dp).clip(CircleShape).then(
                    when {
                        failed -> Modifier.background(Brand.red)
                        s.cancelled -> Modifier.background(Brand.orange)
                        else -> Modifier.background(Brand.gradient)
                    }
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (failed) Icons.Filled.Close else if (s.cancelled) Icons.Filled.Stop else Icons.Filled.Check, null,
                    tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(summaryTitle(s), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Tone.primary)
                Text(summarySubtitle(s), fontSize = 14.sp, color = Tone.secondary, textAlign = TextAlign.Center)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("Gambar", formatCount(s.frames), Icons.Filled.Collections, Modifier.weight(1f))
                StatBox("Ukuran", formatBytes(s.bytes.toDouble()), Icons.Filled.Storage, Modifier.weight(1f))
                StatBox("Waktu", formatTimecode(s.elapsed, millis = false), Icons.Filled.Schedule, Modifier.weight(1f))
            }
            if (s.samples.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.samples.take(8).chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { uri -> Thumbnail(uri, Modifier.weight(1f)) { actions.openImage(uri) } }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (s.errors.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Brand.red.copy(alpha = 0.08f)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    s.errors.forEach { (name, msg) ->
                        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary)
                        SelectionContainer {
                            Text(msg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Tone.secondary, maxLines = 5)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SoftButton("Tutup", null, Modifier.weight(1f)) { state.dismissSummary() }
                s.folders.firstOrNull()?.let { folder ->
                    PrimaryButton(if (s.folders.size > 1) "Buka Folder Pertama" else "Buka Folder", Icons.Filled.Folder,
                        Modifier.weight(1.6f)) { actions.openFolder(folder, s.samples.firstOrNull()) }
                }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Tone.cardFill).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Brand.accent, modifier = Modifier.size(16.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Tone.primary, maxLines = 1)
        Text(label, fontSize = 12.sp, color = Tone.secondary)
    }
}

/** Thumbnail ringan dari file hasil. */
@Composable
private fun Thumbnail(uri: Uri, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        image = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(360, 360), null) }.getOrNull()
                ?: runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                        val scale = maxOf(1, info.size.width / 360)
                        d.setTargetSampleSize(scale)
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }.getOrNull()
        }?.let { (if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it).asImageBitmap() }
    }
    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.85f)).clickable(onClick = onClick)
    ) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
