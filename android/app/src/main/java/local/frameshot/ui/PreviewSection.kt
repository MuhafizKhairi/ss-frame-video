package local.frameshot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.interaction.MutableInteractionSource
import local.frameshot.ItemStatus
import local.frameshot.Phase
import local.frameshot.VideoItem
import local.frameshot.core.formatBytes
import local.frameshot.core.formatCount
import local.frameshot.core.formatFps
import local.frameshot.core.formatTimecode
import local.frameshot.media.VideoInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// MARK: - Pratinjau

@Composable
fun PreviewSection(item: VideoItem, fill: Boolean) {
    val state = LocalState.current
    val info = item.info
    Column(if (fill) Modifier.fillMaxSize() else Modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val aspect = info?.let { (it.width.toFloat() / max(1, it.height)).coerceIn(0.75f, 2.4f) } ?: (16f / 9f)
        Box(
            (if (fill) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(aspect).heightIn(max = 520.dp))
                .heightIn(min = 180.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .clickable(
                    enabled = info != null && item.loadError == null,
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClickLabel = if (state.playing) "Jeda" else "Putar video",
                ) { state.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            val img = state.previewImage
            when {
                img != null -> Image(img, null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize())
                item.loadError != null -> Column(
                    Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFD60A), modifier = Modifier.size(36.dp))
                    Text("Video tidak bisa dibaca", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(item.loadError ?: "", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
                else -> CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            }
            if (info != null && state.playerVisible) {
                VideoSurface(Modifier.aspectRatio(info.width.toFloat() / max(1, info.height)))
            }
            if (info != null && item.loadError == null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(formatTimecode(state.currentTime, forceHours = item.duration >= 3600), color = Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    Text("·", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    Text("Frame ${formatCount(state.currentFrameNumber)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (state.previewLoading) CircularProgressIndicator(Modifier.size(11.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
                Row(
                    Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable(onClickLabel = "Simpan frame ini dengan resolusi penuh") { state.captureCurrentFrame() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.PhotoCamera, null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("Ambil frame ini", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (info != null && item.loadError == null) {
            RangeScrubber(item, info)
            Controls(info)
            InfoPills(info)
        }
    }
}

/** Permukaan tempat pemutar menggambar video. TextureView agar ikut sudut membulat kotak pratinjau. */
@Composable
private fun VideoSurface(modifier: Modifier) {
    val state = LocalState.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        surface = Surface(st).also { state.player.setSurface(it) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        surface?.let { state.player.clearSurface(it); it.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

@Composable
private fun PlayButton() {
    val state = LocalState.current
    val label = if (state.playing) "Jeda" else "Putar video"
    Box(
        Modifier.size(46.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(Brand.gradient)
            .clickable(onClickLabel = label) { state.togglePlay() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, label, tint = Color.White,
            modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun Controls(info: VideoInfo) {
    val state = LocalState.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconCircleButton(Icons.Filled.KeyboardDoubleArrowLeft, "Mundur 1 detik") { state.stepSeconds(-1.0) }
            IconCircleButton(Icons.Filled.ChevronLeft, "Frame sebelumnya") { state.stepFrames(-1) }
            PlayButton()
            IconCircleButton(Icons.Filled.ChevronRight, "Frame berikutnya") { state.stepFrames(1) }
            IconCircleButton(Icons.Filled.KeyboardDoubleArrowRight, "Maju 1 detik") { state.stepSeconds(1.0) }
            Text(
                "${formatTimecode(state.currentTime, forceHours = info.duration >= 3600)} / ${formatTimecode(info.duration, millis = false)}",
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = Tone.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp).weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Set Awal", Icons.Filled.FirstPage, Modifier.weight(1f), compact = true) { state.setRangeStart() }
            SoftButton("Set Akhir", Icons.Filled.LastPage, Modifier.weight(1f), compact = true) { state.setRangeEnd() }
        }
    }
}

@Composable
private fun InfoPills(info: VideoInfo) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoPill(Icons.Filled.AspectRatio, "${info.width}×${info.height}")
        InfoPill(Icons.Filled.Speed, formatFps(info.fps))
        InfoPill(Icons.Filled.Schedule, formatTimecode(info.duration, millis = false))
        InfoPill(Icons.Filled.Layers, "${formatCount(info.frameCount)} frame")
        InfoPill(Icons.Filled.Memory, info.codecDisplay + if (info.isTenBit) " 10-bit" else "")
        InfoPill(Icons.Filled.Storage, formatBytes(info.fileSize.toDouble()))
        if (info.subtitleStreams > 0) InfoPill(Icons.Filled.ClosedCaption, "${info.subtitleStreams} subtitle")
    }
}

// MARK: - Scrubber dengan rentang

private enum class Handle { PLAYHEAD, START, END }

@Composable
fun RangeScrubber(item: VideoItem, info: VideoInfo) {
    val state = LocalState.current
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(36.dp)) {
        val wPx = with(density) { maxWidth.toPx() }
        val d = max(info.duration, 0.001)
        fun xOf(t: Double) = (t / d * wPx).toFloat()
        fun dpOf(px: Float) = with(density) { px.toDp() }
        val grab = with(density) { 22.dp.toPx() }

        Box(
            Modifier.fillMaxSize().pointerInput(item.id, wPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Selama digeser, tampilkan frame tepat; lanjut putar setelah dilepas.
                    val wasPlaying = state.playing
                    if (wasPlaying) state.pausePlayback()
                    var handle = Handle.PLAYHEAD
                    if (item.customRange) {
                        val ds = abs(down.position.x - xOf(item.rangeStart))
                        val de = abs(down.position.x - xOf(item.rangeEnd))
                        if (min(ds, de) < grab) handle = if (ds <= de) Handle.START else Handle.END
                    }
                    fun apply(x: Float) {
                        val t = (min(max(0f, x), wPx) / wPx).toDouble() * d
                        when (handle) {
                            Handle.START -> {
                                item.rangeStart = min(t, item.rangeEnd - info.frameDuration)
                                state.seek(item.rangeStart)
                            }
                            Handle.END -> {
                                item.rangeEnd = max(t, item.rangeStart + info.frameDuration)
                                state.seek(item.rangeEnd - info.frameDuration)
                            }
                            Handle.PLAYHEAD -> state.seek(t)
                        }
                    }
                    apply(down.position.x)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        apply(ch.position.x)
                        ch.consume()
                    }
                    if (wasPlaying) state.startPlayback()
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Tone.fill(0.09f)))
            if (item.customRange) {
                val x0 = xOf(item.rangeStart)
                val x1 = xOf(item.rangeEnd)
                Box(Modifier.offset(x = dpOf(x0)).width(maxOf(dpOf(x1 - x0), 4.dp)).height(6.dp).clip(CircleShape).background(Brand.gradient))
                RangeHandle(dpOf(x0) - 5.dp, Icons.Filled.ChevronLeft)
                RangeHandle(dpOf(x1) - 5.dp, Icons.Filled.ChevronRight)
            } else {
                Box(Modifier.fillMaxWidth().height(6.dp).graphicsLayer(alpha = 0.35f).clip(CircleShape).background(Brand.gradient))
            }
            Box(
                Modifier.offset(x = dpOf(xOf(state.currentTime)) - 9.dp).size(18.dp)
                    .shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White)
                    .border(2.5.dp, Brand.violet, CircleShape)
            )
        }
    }
}

@Composable
private fun RangeHandle(x: androidx.compose.ui.unit.Dp, icon: ImageVector) {
    Box(
        Modifier.offset(x = x).width(10.dp).height(24.dp).clip(RoundedCornerShape(3.dp)).background(Brand.violet),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
}

// MARK: - Antrean video

@Composable
fun QueueStrip() {
    val state = LocalState.current
    val actions = LocalActions.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Antrean", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary)
            Text("${state.includedVideos.size}/${state.videos.size} dipilih", fontSize = 13.sp, color = Tone.secondary)
            Spacer(Modifier.weight(1f))
            if (state.videos.size > 1 && state.phase != Phase.RUNNING) {
                Text("Hapus semua", fontSize = 13.sp, color = Tone.secondary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { state.clearAll() }.padding(4.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            state.videos.forEach { item -> QueueCard(item, item.id == state.selectedId) }
            Column(
                Modifier.size(width = 92.dp, height = 104.dp).clip(RoundedCornerShape(12.dp))
                    .dashed(Tone.fill(0.16f))
                    .clickable(enabled = state.phase != Phase.RUNNING, onClick = actions.pickVideos),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Filled.Add, null, tint = Tone.secondary, modifier = Modifier.size(22.dp))
                Text("Tambah", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tone.secondary)
            }
        }
    }
}

private fun Modifier.dashed(color: Color) = this.drawBehind {
    drawRoundRect(
        color = color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
        style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.2.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
        ),
    )
}

@Composable
private fun QueueCard(item: VideoItem, selected: Boolean) {
    val state = LocalState.current
    val shape = RoundedCornerShape(14.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .then(if (selected) Modifier.background(Brand.softGradient) else Modifier.background(Tone.cardFill))
                .then(if (selected) Modifier.border(1.5.dp, Brand.gradient, shape) else Modifier.border(1.dp, Tone.cardStroke, shape))
                .clickable { state.select(item) }
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center) {
                val t = item.thumbnail
                when {
                    t != null -> Image(t, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    item.loadError != null -> Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFD60A))
                    else -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                }
                item.info?.let {
                    Text(formatTimecode(it.duration, millis = false), color = Color.White, fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
            Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusLabel(item)
                val canToggle = item.info != null && state.phase != Phase.RUNNING
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = canToggle) { item.included = !item.included }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(if (item.included) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                        tint = if (item.included) Brand.violet else Tone.secondary, modifier = Modifier.size(18.dp))
                    Text("Ikut diekstrak", fontSize = 12.sp, color = if (canToggle) Tone.primary else Tone.secondary)
                }
            }
        }
        if (state.phase != Phase.RUNNING) {
            Icon(
                Icons.Filled.Cancel, "Hapus dari antrean", tint = Tone.secondary.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).clip(CircleShape)
                    .clickable { state.remove(item) },
            )
        }
    }
}

@Composable
private fun StatusLabel(item: VideoItem) {
    val (text, icon, color) = when (val s = item.status) {
        ItemStatus.Ready -> when {
            item.loadError != null -> Triple("Gagal dibaca", Icons.Filled.Warning, Brand.orange)
            item.customRange -> Triple("${formatTimecode(item.rangeStart, millis = false)}–${formatTimecode(item.rangeEnd, millis = false)}",
                Icons.Filled.ContentCut, Tone.secondary)
            else -> Triple("Seluruh video", Icons.Filled.Movie, Tone.secondary)
        }
        ItemStatus.Running -> Triple("Memproses…", Icons.Filled.Sync, Brand.accent)
        is ItemStatus.Done -> Triple("${formatCount(s.frames)} frame", Icons.Filled.CheckCircle, Brand.green)
        is ItemStatus.Failed -> Triple("Gagal", Icons.Filled.Cancel, Brand.red)
        ItemStatus.Cancelled -> Triple("Dibatalkan", Icons.Filled.StopCircle, Brand.orange)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(text, fontSize = 12.sp, color = color, maxLines = 1)
    }
}
