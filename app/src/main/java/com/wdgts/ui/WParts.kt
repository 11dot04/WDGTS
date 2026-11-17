@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.wdgts.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CancellationException

// ==========================================================
// TYPE STYLES (all read the palette, so they follow light, dark and Material You)
// ==========================================================
@Composable
fun wDisplay(size: Int, color: Color = LocalW.current.ink, wdth: Float = 118f, lineHeight: Int = (size * 1.05f).toInt(), spacing: Float = -1f): TextStyle {
    val c = LocalContext.current
    return TextStyle(
        fontFamily = WFonts.display(c, wdth), fontWeight = FontWeight.Black, fontSize = size.sp,
        lineHeight = lineHeight.sp, letterSpacing = spacing.sp, color = color,
    )
}

@Composable
fun wBody(size: Int = 15, weight: Int = 500, color: Color = LocalW.current.ink, lineHeight: Int = (size * 1.3f).toInt()): TextStyle {
    val c = LocalContext.current
    return TextStyle(
        fontFamily = WFonts.body(c, weight), fontWeight = FontWeight(weight), fontSize = size.sp,
        lineHeight = lineHeight.sp, color = color,
    )
}

@Composable
fun wMono(size: Int = 11, color: Color = LocalW.current.soft, weight: FontWeight = FontWeight.Medium, spacing: Float = 1.2f): TextStyle {
    val c = LocalContext.current
    return TextStyle(
        fontFamily = WFonts.mono(c), fontWeight = weight, fontSize = size.sp,
        lineHeight = (size * 1.35f).sp, letterSpacing = spacing.sp, color = color,
    )
}

/** Shrinks the size until the text fits on one line (same idea as microtag's FitText). */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, minSize: TextUnit = 14.sp) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val fitted = remember(text, style, maxW) {
            val ratio = if (style.lineHeight.isSpecified && style.fontSize.isSpecified) style.lineHeight.value / style.fontSize.value else 0f
            fun at(size: Float) = style.copy(fontSize = size.sp, lineHeight = if (ratio > 0f) (size * ratio).sp else style.lineHeight)
            var size = style.fontSize.value
            while (size > minSize.value) {
                val w = measurer.measure(text = AnnotatedString(text), style = at(size), softWrap = false).size.width
                if (w <= maxW) break
                size *= 0.96f
            }
            at(size.coerceAtLeast(minSize.value))
        }
        Text(text, style = fitted, softWrap = false, maxLines = 1)
    }
}

// ==========================================================
// SHAPES
// ==========================================================

/** A rounded rectangle that opens every corner to a full round as t goes 0 to 1 (a pressed button in a connected group). */
class PressCornerShape(private val tl: Dp, private val tr: Dp, private val br: Dp, private val bl: Dp, private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val full = min(size.width, size.height) / 2f
        fun r(d: Dp): CornerRadius {
            val from = with(density) { d.toPx() }.coerceAtMost(full)
            val v = from + (full - from) * t
            return CornerRadius(v, v)
        }
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, r(tl), r(tr), r(br), r(bl)))
    }
}

/** 0 at rest, 1 while pressed, on a spring. */
@Composable
fun pressMorph(source: InteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val t by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (WMotion.enabled) spring(dampingRatio = 0.55f, stiffness = 650f) else snap(),
        label = "pressMorph",
    )
    return t
}

/** Corner radii (tl, tr, br, bl) for a row in a stacked group: big on the outside, tight where rows meet. */
fun groupCorners(index: Int, count: Int): List<Dp> {
    val top = if (index == 0) 22.dp else 6.dp
    val bottom = if (index == count - 1) 22.dp else 6.dp
    return listOf(top, top, bottom, bottom)
}

/** The Material 3 Expressive family of shapes, on the stable graphics-shapes library. All centred on 0,0 with radius 1. */
object Poly {
    val Circle: RoundedPolygon = RoundedPolygon.circle(numVertices = 8)
    val Sunny: RoundedPolygon = RoundedPolygon.star(8, innerRadius = 0.8f, rounding = CornerRounding(0.15f))
    val Cookie9: RoundedPolygon = RoundedPolygon.star(9, innerRadius = 0.8f, rounding = CornerRounding(0.5f))
    val Cookie6: RoundedPolygon = RoundedPolygon.star(6, innerRadius = 0.75f, rounding = CornerRounding(0.5f))
    val Cookie12: RoundedPolygon = RoundedPolygon.star(12, innerRadius = 0.8f, rounding = CornerRounding(0.5f))
    val Pentagon: RoundedPolygon = RoundedPolygon(numVertices = 5, rounding = CornerRounding(0.17f))
    val Flower: RoundedPolygon = RoundedPolygon.star(8, innerRadius = 0.6f, rounding = CornerRounding(0.5f), innerRounding = CornerRounding.Unrounded)
    val Puffy: RoundedPolygon = RoundedPolygon.star(6, innerRadius = 0.72f, rounding = CornerRounding(0.55f), innerRounding = CornerRounding(0.4f))
    val SoftBurst: RoundedPolygon = RoundedPolygon.star(10, innerRadius = 0.65f, rounding = CornerRounding(0.1f), innerRounding = CornerRounding(0.2f))
}

/** A [Shape] that is a morph between two polygons at `progress` (0 = first, 1 = second). */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        var started = false
        for (c in morph.asCubics(progress.coerceIn(0f, 1f))) {
            if (!started) { path.moveTo(c.anchor0X, c.anchor0Y); started = true }
            path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
        }
        path.close()
        val matrix = Matrix()
        matrix.scale(size.width / 2f, size.height / 2f)
        matrix.translate(1f, 1f)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

fun View.tick() {
    if (UiPrefs.haptics) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

// ==========================================================
// MICROGRAPHICS
// Tiny technical marks, drawn in code so they take any colour. The "Micrographics" switch turns them off.
// ==========================================================

/** Three crossing strokes: the six-ray star the HUD uses where a plain dot would go. */
@Composable
fun SixRay(modifier: Modifier = Modifier, color: Color = LocalW.current.ink, dp: Dp = 10.dp) {
    Canvas(modifier.size(dp)) {
        val r = this.size.minDimension / 2f
        val c = center
        for (i in 0 until 3) {
            val a = i * PI / 3.0
            val dx = (cos(a) * r).toFloat()
            val dy = (sin(a) * r).toFloat()
            drawLine(color, Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), strokeWidth = this.size.minDimension * 0.17f, cap = StrokeCap.Round)
        }
    }
}

/** A section label: star, mono caps, a hairline that runs out to the edge, and how many rows follow. */
@Composable
fun WLabel(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    val p = LocalW.current
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (UiPrefs.micro) SixRay(color = p.acidText, dp = 9.dp)
        Text(text.uppercase(), style = wMono(11, p.soft, FontWeight.Bold, 1.6f))
        Box(Modifier.weight(1f).height(1.dp).background(p.outline))
        if (count != null) Text(count.toString().padStart(2, '0'), style = wMono(11, p.soft, FontWeight.Medium, 1f))
    }
}

/** label ........ value, as a printed spec sheet. */
@Composable
fun SpecLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalW.current.ink) {
    val p = LocalW.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(label.uppercase(), style = wMono(11, p.soft))
        Box(
            Modifier.weight(1f).height(10.dp).padding(horizontal = 6.dp).drawBehind {
                drawLine(
                    p.outline, Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 7f)),
                )
            },
        )
        Text(value.uppercase(), style = wMono(11, valueColor, FontWeight.Bold))
    }
}

/** 24 ticks, one per hour; the current hour is long and in the accent. */
@Composable
fun HoursScale(hourNow: Int, modifier: Modifier = Modifier) {
    val p = LocalW.current
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val gap = size.width / 24f
        for (h in 0 until 24) {
            val x = gap * (h + 0.5f)
            val now = h == hourNow
            val major = h % 6 == 0
            val top = if (now) 0f else if (major) size.height * 0.35f else size.height * 0.6f
            drawLine(
                if (now) p.acidText else if (major) p.ink else p.outline,
                Offset(x, top), Offset(x, size.height), strokeWidth = if (now) 5f else 3f, cap = StrokeCap.Round,
            )
        }
    }
}

// ==========================================================
// FRAME
// The same frame as microtag's sub screens: no top bar, content scrolls edge to edge and fades under the status
// bar, predictive back shrinks and rounds the whole page. The back button is WDGTS's own: a mono pill (the HUD's
// action-pill language) floating bottom-left where the thumb is.
// ==========================================================
@Composable
fun WSubFrame(onBack: () -> Unit, pinned: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalW.current
    val view = LocalView.current
    var gesture by remember { mutableFloatStateOf(0f) }
    var fromRight by remember { mutableStateOf(false) }
    val g by animateFloatAsState(gesture, spring(stiffness = Spring.StiffnessHigh), label = "predictiveBack")

    PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                gesture = e.progress
                fromRight = e.swipeEdge == BackEventCompat.EDGE_RIGHT
            }
            onBack()
        } catch (c: CancellationException) {
            gesture = 0f
            throw c
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f - 0.1f * g
                scaleX = s
                scaleY = s
                translationX = (if (fromRight) -1f else 1f) * g * 20.dp.toPx()
                shape = RoundedCornerShape((32f * g).dp)
                clip = true
            }
            .background(p.paper),
    ) {
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The preview stays put while everything under it scrolls, so a setting and its effect are on screen together.
            if (pinned != null) Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 4.dp)) { pinned() }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Spacer(Modifier.height(if (pinned != null) 8.dp else 12.dp))
                    content()
                    Spacer(Modifier.height(bottom + 110.dp))
                }
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(18.dp).background(Brush.verticalGradient(listOf(p.paper, Color.Transparent))))
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottom + 100.dp).background(Brush.verticalGradient(listOf(Color.Transparent, p.paper))))

        val source = remember { MutableInteractionSource() }
        val t = pressMorph(source)
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 18.dp, bottom = 16.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(p.ink)
                .clickable(interactionSource = source, indication = null) { view.tick(); onBack() }
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Canvas(Modifier.size(width = 18.dp, height = 12.dp).offset(x = (-3 * t).dp)) {
                val w = size.width; val h = size.height
                drawLine(p.paper, Offset(w, h / 2), Offset(1f, h / 2), strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(p.paper, Offset(h / 2 + 1f, 1f), Offset(1f, h / 2), strokeWidth = 4f, cap = StrokeCap.Round)
                drawLine(p.paper, Offset(h / 2 + 1f, h - 1f), Offset(1f, h / 2), strokeWidth = 4f, cap = StrokeCap.Round)
            }
            Text("BACK", style = wMono(13, p.paper, FontWeight.Bold, 2f))
        }
    }
}

/**
 * The big screen title. Long words are fitted by narrowing the font's width axis, step by step, and the size is only
 * touched when even the narrowest setting is too wide: a title stays the same size on every screen.
 */
@Composable
fun WTitle(text: String) {
    val c = LocalContext.current
    val measurer = rememberTextMeasurer()
    val base = wDisplay(56, wdth = 125f, spacing = -1.5f)
    val t = text.uppercase()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val steps = listOf(125f, 114f, 104f, 94f, 84f, 75f)
        val fit = remember(t, maxW) {
            steps.firstOrNull { w ->
                measurer.measure(AnnotatedString(t), style = base.copy(fontFamily = WFonts.display(c, w)), softWrap = false).size.width <= maxW
            }
        }
        if (fit != null) Text(t, style = base.copy(fontFamily = WFonts.display(c, fit)), softWrap = false, maxLines = 1)
        else FitText(t, base.copy(fontFamily = WFonts.display(c, 75f)), minSize = 24.sp)
    }
}

/** A line of mono caps under a title. */
@Composable
fun WCaption(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = wMono(11, LocalW.current.soft, FontWeight.Medium, 1.4f), modifier = modifier.padding(start = 2.dp, bottom = 4.dp))
}

// ==========================================================
// SWITCH
// ==========================================================
@Composable
fun WSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalW.current
    val view = LocalView.current
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, WMotion.fastSpatial(), label = "thumb")
    val track by animateColorAsState(if (checked) p.acid else p.ink, label = "track")
    val thumb by animateColorAsState(if (checked) p.onAcid else p.paper, label = "thumbColor")
    Box(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .clip(CircleShape)
            .background(track)
            .clickable { view.tick(); onChange(!checked) }
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(thumb))
    }
}

// ==========================================================
// BLOCKS AND ROWS
// ==========================================================

/** One row inside a block. `checked == null` means the row is a plain action (or just information). */
class WItem(
    val title: String,
    val sub: String? = null,
    val checked: Boolean? = null,
    val onChange: (Boolean) -> Unit = {},
    val onClick: (() -> Unit)? = null,
    /** Shown in the accent at the end of an action row instead of the chevron. */
    val value: String? = null,
    val valueMuted: Boolean = false,
)

/** A labelled stack of rows that interlock like a button group. */
@Composable
fun WBlock(label: String, items: List<WItem>) {
    WLabel(label, count = items.size)
    Column(Modifier.animateContentSizeIfMotion(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEachIndexed { i, item -> WRow(item, groupCorners(i, items.size)) }
    }
}

@Composable
private fun Modifier.animateContentSizeIfMotion(): Modifier = if (WMotion.enabled) this.animateContentSize(WMotion.spatial()) else this

@Composable
fun WRow(item: WItem, corners: List<Dp>) {
    val p = LocalW.current
    val view = LocalView.current
    val interactive = item.checked != null || item.onClick != null
    val source = remember { MutableInteractionSource() }
    val onP by animateFloatAsState(if (item.checked == true) 1f else 0f, WMotion.spatial(), label = "rowOn")
    val shape = PressCornerShape(corners[0], corners[1], corners[2], corners[3], maxOf(if (interactive) pressMorph(source) else 0f, onP))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.surface)
            .then(
                if (interactive) Modifier.clickable(interactionSource = source, indication = null) {
                    if (item.checked != null) item.onChange(!item.checked) else { view.tick(); item.onClick?.invoke() }
                } else Modifier,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(item.title, style = wBody(17, 700))
            if (item.sub != null) Text(item.sub, style = wBody(13, 500, p.soft, 18))
        }
        when {
            item.checked != null -> WSwitch(item.checked, item.onChange)
            item.onClick != null && item.value != null -> Text(
                item.value.uppercase(),
                style = wMono(13, if (item.valueMuted) p.soft else p.acidText, FontWeight.Bold, 1.2f),
            )
            item.onClick != null -> Text("→", style = wMono(18, p.ink, FontWeight.Bold, 0f))
        }
    }
}

/** A plate for anything that is not a row: chips, fields, a sentence. */
@Composable
fun WPlate(label: String? = null, hint: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalW.current
    if (label != null) WLabel(label)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(p.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hint != null) Text(hint, style = wBody(13, 500, p.soft, 18))
        content()
    }
}

/** One option of a small set: pill outline when off, filled accent when picked. */
@Composable
fun WChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalW.current
    val view = LocalView.current
    val fill by animateColorAsState(if (selected) p.acid else Color.Transparent, label = "chipFill")
    Box(
        Modifier
            .clip(CircleShape)
            .background(fill)
            .border(if (selected) 0.dp else 1.5.dp, p.outline, CircleShape)
            .clickable { view.tick(); onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = wBody(14, 700, if (selected) p.onAcid else p.ink))
    }
}

/** Single choice from a list, as wrapping chips. */
@Composable
fun WChips(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, o -> WChip(o, i == selected) { onPick(i) } }
    }
}

/** A text field on a paper inset, with a mono label. */
@Composable
fun WField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, error: Boolean = false) {
    val p = LocalW.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label.uppercase(), style = wMono(10, if (error) Color(0xFFFF4A2B) else p.soft, FontWeight.Bold, 1.3f))
        val shape = RoundedCornerShape(14.dp)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = wBody(16, 600),
            cursorBrush = SolidColor(p.acidText),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().clip(shape).background(p.paper)
                        .border(if (error) 2.dp else 1.dp, if (error) Color(0xFFFF4A2B) else p.outline, shape)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) { inner() }
            },
        )
    }
}

/** A pill button. Filled is the main action; outlined is everything else. */
@Composable
fun WButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val p = LocalW.current
    val view = LocalView.current
    val shape = CircleShape
    Box(
        modifier
            .height(48.dp)
            .clip(shape)
            .background(if (filled) p.acid else Color.Transparent)
            .border(if (filled) 0.dp else 1.5.dp, p.outline, shape)
            .clickable { view.tick(); onClick() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = wMono(13, if (filled) p.onAcid else p.ink, FontWeight.Bold, 1.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ==========================================================
// HOME TILE
// A square or wide tile. A widget's tile shows that widget live; every tile also carries a Material 3 Expressive
// shape badge that morphs into a second shape while the tile is pressed. The loud tile is filled with the accent.
// onClick hands back the tile's centre, so the screen it opens can grow out of it.
// ==========================================================
@Composable
fun WTile(
    modifier: Modifier,
    code: String,
    title: String,
    caption: String,
    from: RoundedPolygon,
    to: RoundedPolygon,
    loud: Boolean = false,
    preview: WidgetKind? = null,
    onClick: (Offset) -> Unit,
) {
    val p = LocalW.current
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val t = pressMorph(source)
    val morph = remember(from, to) { Morph(from, to) }
    val fill = if (loud) p.acid else p.surface
    val ink = if (loud) p.onAcid else p.ink
    val sub = if (loud) p.onAcid.copy(alpha = 0.7f) else p.soft
    var center by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(PressCornerShape(30.dp, 30.dp, 30.dp, 30.dp, t))
            .background(fill)
            .clickable(interactionSource = source, indication = null) { view.tick(); onClick(center) }
            .padding(14.dp),
    ) {
        if (preview != null) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                    .background(if (loud) p.onAcid.copy(alpha = 0.10f) else p.paper).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                WidgetImage(preview, Modifier.fillMaxSize(), radius = 14.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(30.dp).clip(MorphShape(morph, t)).background(if (loud) p.onAcid else p.acid),
                    contentAlignment = Alignment.Center,
                ) { Text(code, style = wMono(11, if (loud) p.acid else p.onAcid, FontWeight.Bold, 0.3f)) }
                Column {
                    Text(title.uppercase(), style = wDisplay(20, ink, wdth = 125f, spacing = -0.4f), maxLines = 1)
                    Text(caption.uppercase(), style = wMono(10, sub, FontWeight.Medium, 1.1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Box(
                Modifier.size(46.dp).clip(MorphShape(morph, t)).background(if (loud) p.onAcid else p.acid),
                contentAlignment = Alignment.Center,
            ) { Text(code, style = wMono(11, if (loud) p.acid else p.onAcid, FontWeight.Bold, 0.3f)) }
            Spacer(Modifier.weight(1f))
            Text(title.uppercase(), style = wDisplay(24, ink, wdth = 125f, spacing = -0.5f), maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(caption.uppercase(), style = wMono(10, sub, FontWeight.Medium, 1.1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
