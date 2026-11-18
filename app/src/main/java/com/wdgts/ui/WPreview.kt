package com.wdgts.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wdgts.f1.F1Widget
import com.wdgts.hud.HudWidget
import com.wdgts.music.MusicDepthWidget
import com.wdgts.music.MusicFrameWidget
import com.wdgts.music.MusicRoundWidget
import com.wdgts.sports.TeamWidget
import com.wdgts.widgets.CountdownBrutalWidget
import com.wdgts.widgets.CountdownShapeWidget
import com.wdgts.widgets.ExamplesWidget
import com.wdgts.widgets.KanjiWidget
import com.wdgts.widgets.StreaksWidget
import com.wdgts.widgets.TithiWidget
import com.wdgts.wiki.WikiBodyWidget
import com.wdgts.wiki.WikiWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ==========================================================
// LIVE PREVIEWS
// Every widget draws itself into a bitmap; the app asks the very same drawing code for a picture at the widget's
// usual size. So a preview is never a mock-up: change a setting and the picture is what the home screen will show.
// ==========================================================

/** Bumped whenever a setting changes; every preview on screen redraws shortly after. */
object Preview {
    var tick by mutableIntStateOf(0)
        private set

    fun bump() { tick++ }
}

class WidgetKind(
    val id: String,
    val label: String,
    /** Short label for the little switcher under a preview. */
    val short: String,
    /** Grid size as launchers count it, for example "2×2". */
    val size: String,
    val cls: Class<*>,
    /** The size to draw at, in dp (what a launcher usually gives that many cells). */
    val wDp: Int,
    val hDp: Int,
    val draw: (Context, Int, Int) -> Bitmap,
) {
    val ratio: Float get() = wDp.toFloat() / hDp
}

object Kinds {
    val Hud = WidgetKind("hud", "HUD", "HUD", "5×2", HudWidget::class.java, 350, 150) { c, w, h -> HudWidget().preview(c, w, h) }
    val Tithi = WidgetKind("tithi", "Panchang", "TITHI", "2×2", TithiWidget::class.java, 156, 156) { c, w, h -> TithiWidget.preview(c, w, h) }
    val Streaks = WidgetKind("streaks", "Streaks", "STREAKS", "3×3", StreaksWidget::class.java, 240, 240) { c, w, h -> StreaksWidget.preview(c, w, h) }
    val Kanji = WidgetKind("kanji", "Kanji", "KANJI", "2×2", KanjiWidget::class.java, 156, 156) { c, w, h -> KanjiWidget.preview(c, w, h) }
    val Examples = WidgetKind("examples", "Kanji examples", "WORDS", "2×2", ExamplesWidget::class.java, 156, 156) { c, w, h -> ExamplesWidget.preview(c, w, h) }
    val CountdownSwiss = WidgetKind("cd_swiss", "Countdown · Swiss", "SWISS", "2×2", CountdownBrutalWidget::class.java, 156, 156) { c, w, h -> CountdownBrutalWidget().preview(c, w, h) }
    val CountdownShape = WidgetKind("cd_shape", "Countdown · Shape", "SHAPE", "2×2", CountdownShapeWidget::class.java, 156, 156) { c, w, h -> CountdownShapeWidget().preview(c, w, h) }
    val MusicDepth = WidgetKind("music_depth", "Music · Depth", "DEPTH", "2×2", MusicDepthWidget::class.java, 156, 156) { c, w, h -> MusicDepthWidget().preview(c, w, h) }
    val MusicFrame = WidgetKind("music_frame", "Music · Frame", "FRAME", "2×2", MusicFrameWidget::class.java, 156, 156) { c, w, h -> MusicFrameWidget().preview(c, w, h) }
    val MusicRound = WidgetKind("music_round", "Music · Round", "ROUND", "2×2", MusicRoundWidget::class.java, 156, 156) { c, w, h -> MusicRoundWidget().preview(c, w, h) }
    val Team = WidgetKind("team", "Team match", "TEAM", "2×2", TeamWidget::class.java, 156, 156) { c, w, h -> TeamWidget().preview(c, w, h) }
    val F1 = WidgetKind("f1", "Formula 1", "F1", "4×2", F1Widget::class.java, 320, 150) { c, w, h -> F1Widget().preview(c, w, h) }
    val Wiki = WidgetKind("wiki", "Wikipedia", "ARTICLE", "2×2", WikiWidget::class.java, 156, 156) { c, w, h -> WikiWidget().preview(c, w, h) }
    val WikiBody = WidgetKind("wiki_body", "Wikipedia text", "TEXT", "2×2", WikiBodyWidget::class.java, 156, 156) { c, w, h -> WikiBodyWidget().preview(c, w, h) }

    /** Gallery order. */
    val all = listOf(Hud, Tithi, Streaks, CountdownSwiss, CountdownShape, MusicDepth, MusicFrame, MusicRound, Team, F1, Kanji, Examples, Wiki, WikiBody)
}

/** The widget's picture, scaled to fit the space it is given (keeps the widget's own proportions). */
@Composable
fun WidgetImage(kind: WidgetKind, modifier: Modifier = Modifier, radius: Dp = 24.dp) {
    val c = LocalContext.current
    val density = LocalDensity.current.density
    // Widgets are drawn for a wallpaper and follow the phone's light or dark, not the app's: the backdrop does the same.
    val sysDark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val back = if (sysDark) Color(0xFF0C0C0C) else Color(0xFFF3F1EA)
    var bmp by remember(kind.id) { mutableStateOf<ImageBitmap?>(null) }
    val tick = Preview.tick
    LaunchedEffect(kind.id, tick) {
        // The widget itself is redrawn first; wait a moment so the picture agrees with it.
        if (bmp != null) delay(300)
        val w = (kind.wDp * density).toInt()
        val h = (kind.hDp * density).toInt()
        val b = withContext(Dispatchers.Default) { runCatching { kind.draw(c.applicationContext, w, h).asImageBitmap() }.getOrNull() }
        if (b != null) bmp = b
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val r = kind.ratio
        val fitW = if (maxWidth / maxHeight > r) maxHeight * r else maxWidth
        val fitH = fitW / r
        Box(Modifier.size(fitW, fitH).clip(RoundedCornerShape(radius)).background(back)) {
            val shown = bmp
            if (shown != null) Image(shown, kind.label, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

/** The dotted drawing-board under a preview, with crop marks in the corners. Off with "Micrographics". */
@Composable
fun StageGrid(modifier: Modifier = Modifier) {
    val p = LocalW.current
    if (!UiPrefs.micro) return
    Canvas(modifier) {
        val step = 14.dp.toPx()
        val r = 1.1.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) { drawCircle(p.outline, r, Offset(x, y)); x += step }
            y += step
        }
        val m = 10.dp.toPx(); val l = 10.dp.toPx(); val sw = 1.5.dp.toPx()
        fun mark(cx: Float, cy: Float, sx: Float, sy: Float) {
            drawLine(p.soft, Offset(cx, cy), Offset(cx + sx * l, cy), strokeWidth = sw, cap = StrokeCap.Round)
            drawLine(p.soft, Offset(cx, cy), Offset(cx, cy + sy * l), strokeWidth = sw, cap = StrokeCap.Round)
        }
        mark(m, m, 1f, 1f); mark(size.width - m, m, -1f, 1f)
        mark(m, size.height - m, 1f, -1f); mark(size.width - m, size.height - m, -1f, -1f)
    }
}

/** A small accent dot that breathes: "this is live". Still when motion is reduced. */
@Composable
private fun LiveDot() {
    val p = LocalW.current
    val a = if (WMotion.enabled) {
        val t by rememberInfiniteTransition(label = "live").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "liveAlpha",
        )
        t
    } else 1f
    Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(p.acidText))
}

@Composable
private fun MiniChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalW.current
    Box(
        Modifier.height(26.dp).clip(CircleShape)
            .background(if (selected) p.acid else p.paper)
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = wMono(10, if (selected) p.onAcid else p.soft, FontWeight.Bold, 1f)) }
}

/**
 * The pinned preview at the top of a settings screen. With several widgets on one screen (the two countdowns, the
 * three music widgets) a small switcher picks which one is shown.
 */
@Composable
fun PreviewStage(kinds: List<WidgetKind>, modifier: Modifier = Modifier, height: Dp = 148.dp) {
    val p = LocalW.current
    var sel by remember(kinds.first().id) { mutableIntStateOf(0) }
    val kind = kinds[sel.coerceIn(0, kinds.lastIndex)]
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface)) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            StageGrid(Modifier.fillMaxSize())
            WidgetImage(kind, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LiveDot()
                Text("LIVE · ${kind.size}", style = wMono(10, p.soft, FontWeight.Bold, 1.4f))
            }
            if (kinds.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                kinds.forEachIndexed { i, k -> MiniChip(k.short, i == sel) { sel = i } }
            }
        }
    }
}

/** Asks the launcher to pin the widget; if it can't, says where to find it. */
fun pinWidget(c: Context, kind: WidgetKind) {
    val m = AppWidgetManager.getInstance(c)
    if (m.isRequestPinAppWidgetSupported) {
        m.requestPinAppWidget(ComponentName(c, kind.cls), null, null)
    } else {
        Toast.makeText(c, "Long-press the home screen, then Widgets, then WDGTS", Toast.LENGTH_LONG).show()
    }
}

/** One widget in the gallery: its preview, its name and size, and the Add button. */
@Composable
fun WidgetCard(kind: WidgetKind, onAdd: () -> Unit) {
    val p = LocalW.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(p.surface)) {
        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            StageGrid(Modifier.fillMaxSize())
            WidgetImage(kind, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind.label, style = wBody(17, 700))
                Spacer(Modifier.height(2.dp))
                Text(kind.size, style = wMono(11, p.soft, FontWeight.Medium, 1.4f))
            }
            WButton("Add", onAdd)
        }
    }
}
