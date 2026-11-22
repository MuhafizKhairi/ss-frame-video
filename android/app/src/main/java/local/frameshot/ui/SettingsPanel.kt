package local.frameshot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.frameshot.Phase
import local.frameshot.VideoItem
import local.frameshot.core.ExtractionMode
import local.frameshot.core.ImageFormat
import local.frameshot.core.NamingStyle
import local.frameshot.core.ResolutionPreset
import local.frameshot.core.exampleFilename
import local.frameshot.core.formatBytes
import local.frameshot.core.formatCount
import local.frameshot.core.formatTimecode
import local.frameshot.media.VideoInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

val ExtractionMode.icon: ImageVector
    get() = when (this) {
        ExtractionMode.ALL_FRAMES -> Icons.Filled.Collections
        ExtractionMode.EVERY_N_FRAMES -> Icons.Filled.Tag
        ExtractionMode.INTERVAL -> Icons.Filled.Timer
        ExtractionMode.FIXED_COUNT -> Icons.Filled.GridView
        ExtractionMode.SCENE_CHANGE -> Icons.Filled.AutoAwesome
    }

@Composable
fun SettingsSections() {
    val state = LocalState.current
    val running = state.phase == Phase.RUNNING
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.alpha(if (running) 0.55f else 1f).then(if (running) Modifier.clickable(enabled = false) {} else Modifier),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pengaturan", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Tone.primary)
            Spacer(Modifier.weight(1f))
            Box {
                Icon(Icons.Filled.MoreVert, "Menu pengaturan", tint = Tone.secondary,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = !running) { menu = true }.padding(5.dp))
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Kembalikan ke bawaan") }, onClick = { menu = false; state.resetSettings() })
                }
            }
        }
        // Saat berjalan, pengaturan dikunci (sama seperti versi macOS).
        Box {
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                ModeSection()
                RangeSection()
                FormatSection()
                SizeSection()
                NamingSection()
                OutputSection()
                AdvancedSection()
            }
            if (running) Box(Modifier.matchParentSize().clickable(enabled = true, onClick = {}, indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }))
        }
    }
}

// MARK: - Mode

@Composable
private fun ModeSection() {
    val state = LocalState.current
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Mode Screenshot", Icons.Filled.Camera)
        val modes = ExtractionMode.entries
        for (row in modes.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in row) ModeTile(m, s.mode == m, Modifier.weight(1f)) { state.update { it.copy(mode = m) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SoftCard(padding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeControls()
                Text(s.mode.explanation, fontSize = 12.5.sp, color = Tone.secondary)
            }
        }
    }
}

@Composable
private fun ModeTile(mode: ExtractionMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .heightIn(min = 72.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(Brand.softGradient) else Modifier.background(Tone.fill(0.04f)))
            .then(if (selected) Modifier.border(1.5.dp, Brand.gradient, shape) else Modifier.border(1.dp, Tone.cardStroke, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        if (selected) GradientIcon(mode.icon, 21.dp) else Icon(mode.icon, null, tint = Tone.secondary, modifier = Modifier.size(21.dp))
        Text(mode.title, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = Tone.primary, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable
private fun ModeControls() {
    val state = LocalState.current
    val s = state.settings
    when (s.mode) {
        ExtractionMode.ALL_FRAMES -> {
            val n = state.estimatedFrames
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientIcon(Icons.Filled.Collections, 18.dp)
                Text(if (n != null) "${formatCount(n)} frame akan disimpan" else "Semua frame akan disimpan",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary)
            }
            if (n != null && n > 5000) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Warning, null, tint = Brand.orange, modifier = Modifier.size(15.dp))
                    Text("Jumlahnya besar. Pastikan ruang penyimpanan cukup.", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Brand.orange)
                }
            }
        }
        ExtractionMode.EVERY_N_FRAMES -> {
            LabeledRow("Ambil 1 dari tiap") {
                NumberStepper(s.everyN.toDouble(), { v -> state.update { it.copy(everyN = v.toInt()) } }, 1.0..100_000.0, 1.0, suffix = "frame")
            }
            QuickChips(listOf(2, 6, 12, 24, 48, 120).map { "$it" to it.toDouble() }, s.everyN.toDouble()) { v ->
                state.update { it.copy(everyN = v.toInt()) }
            }
            state.selected?.info?.fps?.let { fps ->
                Text("≈ ${String.format(Locale.US, "%.2f", fps / max(1, s.everyN))} gambar per detik",
                    fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Brand.accent)
            }
        }
        ExtractionMode.INTERVAL -> {
            LabeledRow("Ambil setiap") {
                NumberStepper(s.intervalSeconds, { v -> state.update { it.copy(intervalSeconds = v) } }, 0.04..3600.0,
                    if (s.intervalSeconds < 1) 0.1 else 1.0, decimals = 2, suffix = "detik")
            }
            QuickChips(listOf("0.5s" to 0.5, "1s" to 1.0, "2s" to 2.0, "5s" to 5.0, "10s" to 10.0, "30s" to 30.0, "1m" to 60.0),
                s.intervalSeconds) { v -> state.update { it.copy(intervalSeconds = v) } }
        }
        ExtractionMode.FIXED_COUNT -> {
            LabeledRow("Jumlah gambar") {
                NumberStepper(s.fixedCount.toDouble(), { v -> state.update { it.copy(fixedCount = v.toInt()) } }, 1.0..100_000.0, 10.0)
            }
            QuickChips(listOf(10, 25, 50, 100, 250, 500).map { "$it" to it.toDouble() }, s.fixedCount.toDouble()) { v ->
                state.update { it.copy(fixedCount = v.toInt()) }
            }
            val item = state.selected
            if (item?.info != null) {
                val gap = item.effectiveDuration / max(1, s.fixedCount)
                Text("≈ 1 gambar tiap " + if (gap >= 1) String.format(Locale("id", "ID"), "%.1f detik", gap)
                else String.format(Locale.US, "%.0f ms", gap * 1000),
                    fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Brand.accent)
            }
        }
        ExtractionMode.SCENE_CHANGE -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivitas", fontSize = 14.sp, color = Tone.primary)
                    Spacer(Modifier.weight(1f))
                    Text(sensitivityLabel(s.sceneThreshold), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand.accent)
                }
                // Sensitivitas tinggi = ambang rendah.
                Slider(
                    value = (1 - s.sceneThreshold).toFloat(),
                    onValueChange = { v -> state.update { it.copy(sceneThreshold = Math.round((1 - v) * 100) / 100.0) } },
                    valueRange = 0.4f..0.95f,
                    colors = brandSlider(),
                )
                Row {
                    Text("Sedikit gambar", fontSize = 11.5.sp, color = Tone.tertiary)
                    Spacer(Modifier.weight(1f))
                    Text("Banyak gambar", fontSize = 11.5.sp, color = Tone.tertiary)
                }
            }
        }
    }
}

private fun sensitivityLabel(t: Double) = when {
    t < 0.15 -> "Sangat tinggi"
    t < 0.25 -> "Tinggi"
    t < 0.38 -> "Sedang"
    t < 0.5 -> "Rendah"
    else -> "Sangat rendah"
}

@Composable
private fun brandSlider() = SliderDefaults.colors(
    thumbColor = Brand.violet, activeTrackColor = Brand.violet, inactiveTrackColor = Tone.fill(0.1f),
)

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Tone.primary, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun QuickChips(items: List<Pair<String, Double>>, current: Double, onSet: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        items.forEach { (title, v) ->
            Chip(title, abs(current - v) < 0.0001, Modifier.weight(1f)) { onSet(v) }
        }
    }
}

// MARK: - Rentang

@Composable
private fun RangeSection() {
    val state = LocalState.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Rentang Waktu", Icons.Filled.ContentCut)
        val item = state.selected
        val info = item?.info
        if (item != null && info != null) RangeEditor(item, info)
        else Text("Pilih video untuk mengatur rentang.", fontSize = 13.sp, color = Tone.secondary)
    }
}

@Composable
private fun RangeEditor(item: VideoItem, info: VideoInfo) {
    val state = LocalState.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SegmentedChoice(item.customRange, listOf(false to "Seluruh video", true to "Pilih rentang")) { v ->
            item.customRange = v
            if (v && item.rangeEnd <= item.rangeStart) { item.rangeStart = 0.0; item.rangeEnd = info.duration }
        }
        AnimatedVisibility(item.customRange, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SoftCard(padding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeField("Dari", item.rangeStart, info.duration, Modifier.weight(1f)) { v ->
                            item.rangeStart = minOf(v, item.rangeEnd - info.frameDuration)
                            state.seek(item.rangeStart)
                        }
                        Icon(Icons.Filled.ArrowForward, null, tint = Tone.tertiary, modifier = Modifier.padding(bottom = 10.dp).size(18.dp))
                        TimeField("Sampai", item.rangeEnd, info.duration, Modifier.weight(1f)) { v ->
                            item.rangeEnd = maxOf(v, item.rangeStart + info.frameDuration)
                            state.seek(item.rangeEnd - info.frameDuration)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Durasi ${formatTimecode(item.effectiveDuration)}", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Brand.accent)
                        Spacer(Modifier.weight(1f))
                        Text("Tip: pakai Set Awal / Set Akhir", fontSize = 11.5.sp, color = Tone.tertiary)
                    }
                }
            }
        }
    }
}

// MARK: - Format

@Composable
private fun FormatSection() {
    val state = LocalState.current
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Format Gambar", Icons.Filled.Image)
        for (row in ImageFormat.entries.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in row) Chip(f.label, s.format == f, Modifier.weight(1f), enabled = state.isAvailable(f)) {
                    state.update { it.copy(format = f) }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        SoftCard(padding = 12.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (s.format.hasQuality) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Kualitas", fontSize = 14.sp, color = Tone.primary)
                        Spacer(Modifier.weight(1f))
                        Text("${s.quality}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Brand.accent)
                    }
                    Slider(
                        value = s.quality.toFloat(),
                        onValueChange = { v -> state.update { it.copy(quality = Math.round(v)) } },
                        valueRange = 40f..100f, colors = brandSlider(),
                    )
                }
                Text(s.format.hint, fontSize = 12.5.sp, color = Tone.secondary)
                val unavailable = ImageFormat.entries.filter { !state.isAvailable(it) }
                if (unavailable.isNotEmpty()) {
                    Text("${unavailable.joinToString(" & ") { it.label }} tidak tersedia: perangkat ini tidak punya encodernya.",
                        fontSize = 11.5.sp, color = Tone.tertiary)
                }
            }
        }
    }
}

// MARK: - Resolusi

@Composable
private fun SizeSection() {
    val state = LocalState.current
    val s = state.settings
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Resolusi", Icons.Filled.AspectRatio)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Tone.fill(0.065f)).clickable { open = true }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(s.resolution.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Tone.primary)
                    Icon(Icons.Filled.ExpandMore, null, tint = Tone.secondary, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    ResolutionPreset.entries.forEach { r ->
                        DropdownMenuItem(text = { Text(r.label) }, onClick = { open = false; state.update { it.copy(resolution = r) } })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            state.selected?.info?.let { info ->
                val (w, h) = s.outputSize(info.width, info.height)
                Text("→ $w × $h", fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace, color = Tone.secondary)
            }
        }
        if (s.resolution == ResolutionPreset.CUSTOM) {
            LabeledRow("Lebar") {
                NumberStepper(s.customWidth.toDouble(), { v -> state.update { it.copy(customWidth = v.toInt()) } }, 64.0..7680.0, 64.0, suffix = "px")
            }
        }
        Text("Video tidak akan diperbesar melebihi ukuran aslinya.", fontSize = 12.sp, color = Tone.tertiary)
    }
}

// MARK: - Nama file

@Composable
private fun NamingSection() {
    val state = LocalState.current
    val s = state.settings
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Nama File", Icons.Filled.TextFields)
        PlainField(
            value = s.customPrefix, onValueChange = { v -> state.update { it.copy(customPrefix = v) } },
            placeholder = "Awalan nama (kosong = nama video)", modifier = Modifier.fillMaxWidth(), focusedBorder = true,
        )
        SegmentedChoice(s.naming, NamingStyle.entries.map { it to it.label }) { v -> state.update { it.copy(naming = v) } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 2.dp)) {
            Icon(Icons.Filled.Description, null, tint = Tone.secondary, modifier = Modifier.size(14.dp))
            Text(exampleFilename(s, state.selected?.name ?: "Episode 01"), fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                color = Tone.secondary, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

// MARK: - Simpan ke

@Composable
private fun OutputSection() {
    val state = LocalState.current
    val actions = LocalActions.current
    val s = state.settings
    val loc = state.outputLocation
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader("Simpan ke", Icons.Filled.Folder)
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Tone.cardFill).border(1.dp, Tone.cardStroke, shape)
                .clickable(onClick = actions.pickOutputFolder).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GradientIcon(if (loc.isGallery) Icons.Filled.PhotoLibrary else Icons.Filled.Folder, 24.dp)
            Column(Modifier.weight(1f)) {
                Text(loc.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(loc.subtitle, fontSize = 12.sp, color = Tone.secondary, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
            Text("Ubah", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Brand.accent)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Buat subfolder per video", fontSize = 14.sp, color = Tone.primary, modifier = Modifier.weight(1f))
            BrandSwitch(s.createSubfolder, { v -> state.update { it.copy(createSubfolder = v) } })
        }
        if (!loc.isGallery) {
            Text("Pakai Galeri lagi", fontSize = 12.5.sp, color = Tone.secondary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { state.setOutputFolder(null) }.padding(vertical = 2.dp))
        }
    }
}

// MARK: - Lanjutan

@Composable
private fun AdvancedSection() {
    val state = LocalState.current
    val s = state.settings
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "chev")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Lanjutan", Icons.Filled.Tune, Modifier.weight(1f))
            Icon(Icons.Filled.ExpandMore, null, tint = Tone.secondary, modifier = Modifier.size(18.dp).rotate(rot))
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            SoftCard(padding = 12.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptionRow("Lewati frame duplikat",
                        "Buang frame yang identik dengan sebelumnya. Anime sering mengulang frame yang sama.",
                        s.skipDuplicates) { v -> state.update { it.copy(skipDuplicates = v) } }
                    HorizontalDivider(color = Tone.cardStroke)
                    OptionRow("Akselerasi hardware",
                        "Pakai decoder hardware perangkat untuk decode video lebih cepat.",
                        s.hardwareDecode) { v -> state.update { it.copy(hardwareDecode = v) } }
                    HorizontalDivider(color = Tone.cardStroke)
                    OptionRow("Tampilkan subtitle di gambar",
                        "Belum tersedia di Android: decoder bawaan sistem tidak bisa menempel subtitle.",
                        false, enabled = false) {}
                }
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.alpha(if (enabled) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Tone.primary)
            Text(desc, fontSize = 12.sp, color = Tone.secondary)
        }
        BrandSwitch(checked, onChange, enabled)
    }
}

// MARK: - Footer: perkiraan + tombol

@Composable
fun FooterBar() {
    val state = LocalState.current
    val actions = LocalActions.current
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        HorizontalDivider(color = Tone.cardStroke)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.phase == Phase.RUNNING) RunningView() else {
                EstimateRow()
                val n = state.includedVideos.size
                PrimaryButton(if (n > 1) "Ekstrak $n Video" else "Mulai Ekstrak", Icons.Filled.Bolt, Modifier.fillMaxWidth(),
                    enabled = state.canStart, onClick = actions.startExtraction)
            }
        }
    }
}

@Composable
private fun EstimateRow() {
    val state = LocalState.current
    val s = state.settings
    val free = remember(state.settings.outputFolderUri, state.phase) { state.freeDiskBytes() }
    val est = state.estimatedBytes
    val sizeText = when {
        state.includedVideos.isEmpty() -> "—"
        s.mode == ExtractionMode.SCENE_CHANGE -> "—"
        est != null -> "≈ " + formatBytes(est)
        state.sampleLoading -> "menghitung…"
        else -> "—"
    }
    Row(Modifier.fillMaxWidth()) {
        Stat("Gambar", state.estimatedFrames?.let { "≈ " + formatCount(it) } ?: "Otomatis", Modifier.weight(1f))
        Stat("Perkiraan ukuran", sizeText, Modifier.weight(1.2f), warn = est != null && free != null && est > free,
            align = Alignment.CenterHorizontally)
        Stat("Sisa ruang", free?.let { formatBytes(it) } ?: "—", Modifier.weight(1f), align = Alignment.End)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, warn: Boolean = false, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontSize = 11.5.sp, color = Tone.secondary, maxLines = 1)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (warn) Brand.red else Tone.primary, maxLines = 1)
    }
}

@Composable
private fun RunningView() {
    val state = LocalState.current
    val p = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (state.runTotal > 1) "Video ${state.runIndex + 1} dari ${state.runTotal}" else "Mengekstrak…",
                    fontSize = 12.sp, color = Tone.secondary)
                Text(state.runName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tone.primary,
                    maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            Text("${(state.overallFraction * 100).toInt()}%", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.gradientTint())
        }
        GradientProgressBar(state.overallFraction)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Collections, null, tint = Tone.secondary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("${formatCount(p.frames)} frame", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Tone.secondary)
            Spacer(Modifier.weight(1f))
            if (p.fps > 0) Text(String.format(Locale.US, "%.0f fps", p.fps), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Tone.secondary)
            Spacer(Modifier.weight(1f))
            Text(p.eta?.let { "sisa ${formatTimecode(it, millis = false)}" } ?: formatTimecode(p.elapsed, millis = false),
                fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Tone.secondary)
        }
        SoftButton("Batalkan", Icons.Filled.Stop, Modifier.fillMaxWidth()) { state.cancelExtraction() }
    }
}
