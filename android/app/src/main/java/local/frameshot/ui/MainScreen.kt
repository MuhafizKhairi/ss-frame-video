package local.frameshot.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.frameshot.AppState
import local.frameshot.Phase
import local.frameshot.ToastKind
import local.frameshot.media.OutputFolder

/** Aksi yang butuh Activity (pemilih file, membuka aplikasi lain). */
class UiActions(
    val pickVideos: () -> Unit,
    val pickFolder: () -> Unit,
    val pickOutputFolder: () -> Unit,
    val startExtraction: () -> Unit,
    val openImage: (Uri) -> Unit,
    val openFolder: (OutputFolder, Uri?) -> Unit,
)

val LocalState = staticCompositionLocalOf<AppState> { error("AppState belum disediakan") }
val LocalActions = staticCompositionLocalOf<UiActions> { error("UiActions belum disediakan") }

@Composable
fun FrameshotScreen(state: AppState, actions: UiActions) {
    CompositionLocalProvider(LocalState provides state, LocalActions provides actions) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown) handleKey(state, actions, e) else false }
        ) {
            val wide = maxWidth >= 840.dp
            if (wide) WideLayout(state, tall = maxHeight >= 640.dp) else PhoneLayout(state)

            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = if (wide) 380.dp else 0.dp, bottom = if (wide) 24.dp else 150.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                AnimatedVisibility(
                    visible = state.toast != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                ) { state.toast?.let { ToastView(it) } }
            }
        }
        if (state.showSummary) SummaryDialog()
    }
}

private fun handleKey(state: AppState, actions: UiActions, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
    if (state.selected?.info == null) return false
    return when {
        e.key == Key.DirectionLeft -> { if (e.isShiftPressed) state.stepSeconds(-1.0) else state.stepFrames(-1); true }
        e.key == Key.DirectionRight -> { if (e.isShiftPressed) state.stepSeconds(1.0) else state.stepFrames(1); true }
        e.key == Key.Spacebar -> { state.togglePlay(); true }
        e.key == Key.I -> { state.setRangeStart(); true }
        e.key == Key.O -> { state.setRangeEnd(); true }
        e.key == Key.S && e.isCtrlPressed -> { state.captureCurrentFrame(); true }
        e.key == Key.Enter && e.isCtrlPressed -> { actions.startExtraction(); true }
        else -> false
    }
}

// MARK: - Tata letak

@Composable
private fun PhoneLayout(state: AppState) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(compact = true)
            if (state.videos.isEmpty()) {
                EmptyDropZone(Modifier.heightIn(min = 460.dp))
            } else {
                state.selected?.let { PreviewSection(it, fill = false) }
                QueueStrip()
            }
            HorizontalDivider(color = Tone.cardStroke)
            SettingsSections()
            Spacer(Modifier.height(8.dp))
        }
        if (state.videos.isNotEmpty() || state.phase == Phase.RUNNING) FooterBar()
    }
}

@Composable
private fun WideLayout(state: AppState, tall: Boolean) {
    Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Layar tinggi (tablet): pratinjau mengisi ruang. Layar pendek (HP landscape): kolom bisa digulir.
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (tall) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(compact = false)
            if (state.videos.isEmpty()) {
                EmptyDropZone(if (tall) Modifier.weight(1f) else Modifier)
            } else {
                val item = state.selected
                if (tall) {
                    if (item != null) Box(Modifier.weight(1f)) { PreviewSection(item, fill = true) } else Spacer(Modifier.weight(1f))
                } else {
                    item?.let { PreviewSection(it, fill = false) }
                }
                QueueStrip()
            }
        }
        VerticalDivider(color = Tone.cardStroke)
        Column(Modifier.width(380.dp).fillMaxHeight().background(Tone.fill(0.018f))) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) { SettingsSections() }
            FooterBar()
        }
    }
}

// MARK: - Kepala

@Composable
private fun Header(compact: Boolean) {
    val state = LocalState.current
    val actions = LocalActions.current
    Row(
        Modifier.fillMaxWidth().padding(top = if (compact) 10.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppLogo(36.dp)
        Column(Modifier.weight(1f)) {
            Text("Frameshot", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Tone.primary)
            Text("Screenshot video per frame", fontSize = 12.5.sp, color = Tone.secondary, maxLines = 1)
        }
        if (state.videos.isNotEmpty()) {
            val enabled = state.phase != Phase.RUNNING
            if (compact) {
                SoftButton("", Icons.Filled.VideoCall, enabled = enabled, onClick = actions.pickVideos)
                SoftButton("", Icons.Filled.CreateNewFolder, enabled = enabled, onClick = actions.pickFolder)
            } else {
                SoftButton("Tambah Video", Icons.Filled.VideoCall, enabled = enabled, onClick = actions.pickVideos)
                SoftButton("Folder", Icons.Filled.CreateNewFolder, enabled = enabled, onClick = actions.pickFolder)
            }
        }
    }
}

// MARK: - Zona kosong

@Composable
private fun EmptyDropZone(modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val stroke = Tone.fill(0.14f)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .androidxDashedBorder(stroke)
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(Brand.softGradient), contentAlignment = Alignment.Center) {
            GradientIcon(Icons.Filled.PlayCircle, 52.dp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pilih video untuk mulai", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Tone.primary, textAlign = TextAlign.Center)
            Text("atau bagikan video dari Galeri ke Frameshot. Bisa banyak sekaligus.",
                color = Tone.secondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Pilih Video", Icons.Filled.Movie, Modifier.width(170.dp), onClick = actions.pickVideos)
            SoftButton("Pilih Folder", Icons.Filled.Folder, onClick = actions.pickFolder)
        }
        Text("MP4 · MKV · MOV · WEBM · 3GP · TS · dan lainnya", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tone.tertiary)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tip(Icons.Filled.AutoAwesome, "Deteksi ganti adegan", "pas untuk anime")
            Tip(Icons.Filled.Timer, "Interval waktu", "misal tiap 1 detik")
            Tip(Icons.Filled.CameraAlt, "Ambil 1 frame", "dari pratinjau")
        }
    }
}

private fun Modifier.androidxDashedBorder(color: Color): Modifier = this.then(
    Modifier.drawWithDash(color)
)

private fun Modifier.drawWithDash(color: Color) = this.drawBehind {
    val px = 1.5.dp.toPx()
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()),
        style = Stroke(width = px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 7.dp.toPx()))),
    )
}

@Composable
private fun Tip(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = Brand.accent, modifier = Modifier.size(20.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary)
            Text(sub, fontSize = 12.sp, color = Tone.secondary)
        }
    }
}

// MARK: - Toast

@Composable
private fun ToastView(toast: local.frameshot.Toast) {
    val state = LocalState.current
    val actions = LocalActions.current
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .shadow(14.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, Tone.cardStroke, CircleShape)
            .clickable { state.dismissToast() }
            .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val icon = when (toast.kind) {
            ToastKind.SUCCESS -> Icons.Filled.CheckCircle
            ToastKind.CAMERA -> Icons.Filled.PhotoCamera
            ToastKind.ERROR -> Icons.Filled.Error
        }
        if (toast.kind == ToastKind.ERROR) Icon(icon, null, tint = Brand.red, modifier = Modifier.size(20.dp))
        else GradientIcon(icon, 20.dp)
        Text(toast.message, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Tone.primary,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        toast.openUri?.let { uri ->
            SoftButton("Tampilkan", compact = true) { actions.openImage(uri) }
        } ?: Spacer(Modifier.width(6.dp))
    }
}
