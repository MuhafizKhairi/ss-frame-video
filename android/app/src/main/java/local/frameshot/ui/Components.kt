package local.frameshot.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.frameshot.core.formatTimecode
import local.frameshot.core.parseTimecode
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** Isi apa pun (ikon/teks) diwarnai gradien merek. */
fun Modifier.gradientTint(brush: Brush = Brand.gradient): Modifier =
    graphicsLayer(alpha = 0.99f).drawWithCache {
        onDrawWithContent {
            drawContent()
            drawRect(brush, blendMode = BlendMode.SrcAtop)
        }
    }

@Composable
fun GradientIcon(icon: ImageVector, size: Dp, modifier: Modifier = Modifier) {
    Icon(icon, null, modifier = modifier.size(size).gradientTint(), tint = Color.Black)
}

/** Kartu dengan latar lembut. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Tone.cardFill)
            .border(1.dp, Tone.cardStroke, shape)
            .padding(padding)
    ) { content() }
}

/** Judul bagian pengaturan. */
@Composable
fun SectionHeader(title: String, icon: ImageVector?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, tint = Brand.accent, modifier = Modifier.size(14.dp))
        Text(
            title.uppercase(Locale("id", "ID")),
            fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp, color = Tone.secondary,
        )
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Pilihan kecil berbentuk kapsul. */
@Composable
fun Chip(title: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Brand.gradient) else Modifier.background(Tone.fill(0.06f)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title, maxLines = 1, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when {
                selected -> Color.White
                !enabled -> Tone.secondary.copy(alpha = 0.45f)
                else -> Tone.primary
            },
        )
    }
}

/** Tombol utama bergradien. */
@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(120), label = "press")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .scale(scale)
            .then(if (enabled) Modifier.shadow(if (pressed) 3.dp else 8.dp, shape, ambientColor = Brand.violet, spotColor = Brand.violet) else Modifier)
            .clip(shape)
            .then(if (enabled) Modifier.background(Brand.gradient) else Modifier.background(Tone.secondary.copy(alpha = 0.3f)))
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Tombol sekunder halus. */
@Composable
fun SoftButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(shape)
            .background(Tone.fill(0.065f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) Tone.primary else Tone.secondary.copy(alpha = 0.55f)
        if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(if (compact) 15.dp else 17.dp))
            if (text.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (text.isNotEmpty()) Text(text, color = c, fontSize = if (compact) 13.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Tombol ikon bulat. */
@Composable
fun IconCircleButton(icon: ImageVector, description: String, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Tone.fill(0.065f))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Tone.primary, modifier = Modifier.size(size * 0.5f))
    }
}

/** Info kecil: ikon + teks. */
@Composable
fun InfoPill(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(Tone.fill(0.055f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Tone.secondary, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = Tone.primary, maxLines = 1)
    }
}

@Composable
fun AppLogo(size: Dp = 36.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(size * 0.2f, RoundedCornerShape(size * 0.28f), ambientColor = Brand.violet, spotColor = Brand.violet)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brand.gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.VideoLibrary, null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun BrandSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked, onCheckedChange = onChange, enabled = enabled,
        colors = SwitchDefaults.colors(checkedTrackColor = Brand.violet, checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent),
    )
}

/** Kolom input polos bergaya aplikasi. */
@Composable
fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    align: TextAlign = TextAlign.Start,
    fontSize: Float = 14f,
    weight: FontWeight = FontWeight.Normal,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    onDone: () -> Unit = {},
    onFocus: (Boolean) -> Unit = {},
    background: Boolean = true,
    focusedBorder: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val border by animateColorAsState(if (focusedBorder && focused) Brand.accent.copy(alpha = 0.7f) else Color.Transparent, label = "b")
    val shape = RoundedCornerShape(9.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Tone.primary, fontSize = fontSize.sp, fontWeight = weight, textAlign = align,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
        cursorBrush = SolidColor(Brand.accent),
        keyboardOptions = keyboard,
        keyboardActions = KeyboardActions(onDone = { onDone(); focus.clearFocus() }),
        modifier = modifier
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
        decorationBox = { inner ->
            Box(
                (if (background) Modifier.clip(shape).background(Tone.fill(0.06f)).border(1.5.dp, border, shape)
                else Modifier).padding(horizontal = 10.dp, vertical = 9.dp),
                contentAlignment = if (align == TextAlign.Center) Alignment.Center else Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = Tone.tertiary, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}

/** Kolom input waktu (HH:MM:SS.mmm / MM:SS / detik). */
@Composable
fun TimeField(label: String, value: Double, maxValue: Double, modifier: Modifier = Modifier, onCommit: (Double) -> Unit) {
    val forceHours = maxValue >= 3600
    var text by remember { mutableStateOf(formatTimecode(value, forceHours = forceHours)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = formatTimecode(value, forceHours = forceHours) }
    fun commit() {
        parseTimecode(text)?.let { onCommit(min(max(0.0, it), maxValue)) }
        text = formatTimecode(value, forceHours = forceHours)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Tone.secondary)
        PlainField(
            value = text, onValueChange = { text = it }, mono = true, fontSize = 14f, weight = FontWeight.Medium,
            placeholder = "00:00.000",
            keyboard = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            onDone = { commit() },
            onFocus = { f -> if (focused && !f) commit(); focused = f },
            focusedBorder = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Kolom angka dengan tombol −/+. */
@Composable
fun NumberStepper(
    value: Double,
    onChange: (Double) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    decimals: Int = 0,
    suffix: String = "",
) {
    fun format(v: Double) = if (decimals == 0) v.roundToLong().toString() else String.format(Locale.US, "%.${decimals}f", v)
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = format(value) }
    fun set(v: Double) {
        val f = 10.0.pow(decimals)
        val nv = min(range.endInclusive, max(range.start, Math.round(v * f) / f))
        onChange(nv)
        text = format(nv)
    }
    fun commit() { text.replace(',', '.').toDoubleOrNull()?.let { set(it) } ?: run { text = format(value) } }

    Row(
        Modifier.clip(RoundedCornerShape(11.dp)).background(Tone.fill(0.06f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Filled.Remove, "Kurangi") { set(value - step) }
        PlainField(
            value = text, onValueChange = { text = it }, align = TextAlign.Center, fontSize = 16f, weight = FontWeight.SemiBold,
            keyboard = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            onDone = { commit() }, onFocus = { f -> if (focused && !f) commit(); focused = f },
            background = false,
            modifier = Modifier.widthIn(min = 56.dp, max = 84.dp),
        )
        if (suffix.isNotEmpty()) {
            Text(suffix, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Tone.secondary, modifier = Modifier.padding(end = 6.dp))
        }
        StepButton(Icons.Filled.Add, "Tambah") { set(value + step) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(Tone.fill(0.07f)).clickable(onClickLabel = desc, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Tone.primary, modifier = Modifier.size(16.dp)) }
}

/** Segmented control bergaya aplikasi. */
@Composable
fun <T> SegmentedChoice(selection: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Tone.fill(0.07f)).padding(3.dp)
    ) {
        val idx = options.indexOfFirst { it.first == selection }.coerceAtLeast(0)
        val segW = maxWidth / options.size
        val offset by androidx.compose.animation.core.animateDpAsState(segW * idx, label = "seg")
        Box(
            Modifier.offset(x = offset).width(segW).height(34.dp)
                .shadow(1.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        )
        Row(Modifier.fillMaxWidth().height(34.dp)) {
            options.forEach { (value, label) ->
                val on = value == selection
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Tone.primary else Tone.secondary, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun GradientProgressBar(value: Double, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(value.toFloat().coerceIn(0f, 1f), tween(350), label = "p")
    BoxWithConstraints(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Tone.fill(0.08f))) {
        Box(Modifier.fillMaxHeight().width(max(8.dp.value, maxWidth.value * v).dp).clip(CircleShape).background(Brand.gradient))
    }
}
