package com.wdgts.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon

// ==========================================================
// ONBOARDING
// Five short steps, shown once: welcome, access, place, widgets, done. Same bones as microtag's intro (a pile of
// morphing shapes, permission pills that fill when granted, then the real thing), with WDGTS's own parts: the date
// stays in the corner, and the widgets step shows live previews. Skip is always there.
// ==========================================================
private const val LAST_STEP = 4

@Composable
fun WOnboarding(ssTick: Int, onDone: () -> Unit) {
    val p = LocalW.current
    var step by rememberSaveable { mutableStateOf(0) }

    Box(Modifier.fillMaxSize().background(p.paper)) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(WMotion.spatial()) { if (forward) it / 4 else -it / 4 } + fadeIn(WMotion.effects())) togetherWith
                    (slideOutHorizontally(WMotion.spatial()) { if (forward) -it / 4 else it / 4 } + fadeOut(WMotion.fastEffects()))
            },
            label = "onboarding",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Spacer(Modifier.height(54.dp))
                when (s) {
                    0 -> Welcome()
                    1 -> StepAccess(ssTick)
                    2 -> StepPlace()
                    3 -> StepWidgets()
                    else -> Finished()
                }
                Spacer(Modifier.height(150.dp))
            }
        }

        // ---- progress and skip
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..LAST_STEP) {
                    val w by animateDpAsState(if (i == step) 34.dp else 14.dp, WMotion.fastSpatial(), label = "tick")
                    Box(
                        Modifier.height(4.dp).width(w).clip(CircleShape)
                            .background(if (i == step) p.acid else if (i < step) p.ink else p.outline),
                    )
                }
            }
            if (step < LAST_STEP) {
                Text(
                    "SKIP", style = wMono(12, p.soft, FontWeight.Bold, 2f),
                    modifier = Modifier.clip(CircleShape).clickable { onDone() }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }

        // ---- buttons
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step > 0 && step < LAST_STEP) WButton("Back", { step-- }, filled = false)
            Spacer(Modifier.weight(1f))
            WButton(
                when (step) { 0 -> "Begin"; LAST_STEP -> "Open WDGTS"; else -> "Continue" },
                { if (step >= LAST_STEP) onDone() else step++ },
            )
        }
    }
}

@Composable
private fun Welcome() {
    val p = LocalW.current
    ShapePile(Modifier.fillMaxWidth().height(280.dp))
    WCaption("Widgets that talk")
    WTitle("WDGTS")
    Text(
        "A few quiet widgets for your home screen: a HUD, the panchang, streaks, music, countdowns and more. " +
            "Four short steps and you are set.",
        style = wBody(16, 500, p.soft, 23),
    )
}

@Composable
private fun StepAccess(ssTick: Int) {
    val p = LocalW.current
    WCaption("Step 1 of 3")
    WTitle("Access")
    Text(
        "The HUD and the music widgets read what your phone is already showing: the player, missed calls, your next event. " +
            "Grant what you want; everything else keeps working without it.",
        style = wBody(16, 500, p.soft, 23),
    )
    AccessBlocks(ssTick)
}

@Composable
private fun StepPlace() {
    val p = LocalW.current
    WCaption("Step 2 of 3")
    WTitle("Place")
    Text(
        "The panchang finds sunrise, and the tithi at sunrise, for one point on the map. Pick a city or type your own.",
        style = wBody(16, 500, p.soft, 23),
    )
    LocationEditor()
}

@Composable
private fun StepWidgets() {
    val p = LocalW.current
    val c = LocalContext.current
    WCaption("Step 3 of 3")
    WTitle("Widgets")
    Text(
        "Add a few now, or all of them later from Add widgets on the home screen. Each preview is the real widget.",
        style = wBody(16, 500, p.soft, 23),
    )
    for (kind in listOf(Kinds.Hud, Kinds.Tithi, Kinds.Streaks, Kinds.MusicRound, Kinds.CountdownSwiss, Kinds.Wiki)) {
        WidgetCard(kind) { pinWidget(c, kind) }
    }
}

@Composable
private fun Finished() {
    val p = LocalW.current
    ShapePile(Modifier.fillMaxWidth().height(220.dp))
    WCaption("All set")
    WTitle("Done")
    Text(
        "Every setting is one tap away on the home screen, and each one shows its widget live while you change it.",
        style = wBody(16, 500, p.soft, 23),
    )
}

// ==========================================================
// SHAPE PILE
// Eight Material 3 Expressive shapes in WDGTS's colours, turning slowly at their own speeds.
// ==========================================================
private class Piece(val poly: RoundedPolygon, val x: Float, val y: Float, val r: Float, val tone: Int, val spin: Float)

private fun RoundedPolygon.unitPath(): Path {
    val path = Path()
    var started = false
    for (c in Morph(this, this).asCubics(0f)) {
        if (!started) { path.moveTo(c.anchor0X, c.anchor0Y); started = true }
        path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
    }
    path.close()
    return path
}

@Composable
private fun ShapePile(modifier: Modifier = Modifier) {
    val p = LocalW.current
    val turn = if (WMotion.enabled) {
        val t by rememberInfiniteTransition(label = "pile").animateFloat(
            0f, 360f, infiniteRepeatable(tween(60000, easing = LinearEasing)), label = "pileTurn",
        )
        t
    } else 0f
    val pieces = remember {
        listOf(
            Piece(Poly.Cookie9, 0.30f, 0.50f, 0.30f, 0, 1f),
            Piece(Poly.Sunny, 0.64f, 0.34f, 0.21f, 1, -1.5f),
            Piece(Poly.Flower, 0.76f, 0.68f, 0.25f, 2, 0.8f),
            Piece(Poly.Puffy, 0.46f, 0.84f, 0.17f, 3, -1f),
            Piece(Poly.SoftBurst, 0.90f, 0.22f, 0.14f, 0, 2f),
            Piece(Poly.Cookie6, 0.10f, 0.16f, 0.12f, 2, -0.7f),
            Piece(Poly.Pentagon, 0.52f, 0.10f, 0.10f, 0, 1f),
            Piece(Poly.Cookie12, 0.92f, 0.92f, 0.11f, 1, -1f),
        ).map { it to it.poly.unitPath() }
    }
    Canvas(modifier) {
        val u = size.minDimension
        for ((piece, path) in pieces) {
            val color: Color = when (piece.tone) { 0 -> p.acid; 1 -> p.ink; 2 -> p.surface; else -> p.outline }
            translate(size.width * piece.x, size.height * piece.y) {
                rotate(turn * piece.spin, Offset.Zero) {
                    scale(u * piece.r, u * piece.r, Offset.Zero) {
                        drawPath(path, color)
                    }
                }
            }
        }
    }
}
