@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalTextApi::class)

package com.wdgts.ui

import android.app.Activity
import android.content.Context
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

// ==========================================================
// APP-ONLY SETTINGS
// How the app itself looks. Kept apart from Prefs (which belongs to the widgets) and held as Compose state,
// so a switch recolours the whole app the moment it flips.
// ==========================================================
object UiPrefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("wdgts_ui", Context.MODE_PRIVATE)
    private var loaded = false

    /** Swap the acid green for the system accent colour. */
    var materialYou by mutableStateOf(false); private set

    /** "system", "light" or "dark". */
    var appearance by mutableStateOf("system"); private set
    var reducedMotion by mutableStateOf(false); private set
    var haptics by mutableStateOf(true); private set

    /** The small technical marks (ticks, spec lines, six-ray stars). */
    var micro by mutableStateOf(true); private set

    /** False until the first-run intro has been finished or skipped. */
    var onboarded by mutableStateOf(true); private set

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        val s = sp(c)
        materialYou = s.getBoolean("material_you", false)
        appearance = s.getString("appearance", "system") ?: "system"
        reducedMotion = s.getBoolean("reduced_motion", false)
        haptics = s.getBoolean("haptics", true)
        micro = s.getBoolean("micro", true)
        onboarded = s.getBoolean("onboarded", false)
    }

    fun setMaterialYou(c: Context, v: Boolean) { materialYou = v; sp(c).edit().putBoolean("material_you", v).apply() }
    fun setAppearance(c: Context, v: String) { appearance = v; sp(c).edit().putString("appearance", v).apply() }
    fun setReducedMotion(c: Context, v: Boolean) { reducedMotion = v; sp(c).edit().putBoolean("reduced_motion", v).apply() }
    fun setHaptics(c: Context, v: Boolean) { haptics = v; sp(c).edit().putBoolean("haptics", v).apply() }
    fun setMicro(c: Context, v: Boolean) { micro = v; sp(c).edit().putBoolean("micro", v).apply() }
    fun setOnboarded(c: Context, v: Boolean) { onboarded = v; sp(c).edit().putBoolean("onboarded", v).apply() }
}

// ==========================================================
// PALETTE
// Same three roles as the widgets and as microtag:
//   paper    the page
//   ink      text
//   acid     everything switched on (the loud colour)
// Fixed ink, paper and acid green by default, exactly the widgets' own set. Material You swaps the acid
// and the greys for the system palette.
// ==========================================================
@Immutable
class WPalette(
    val dark: Boolean,
    val paper: Color,
    val ink: Color,
    val soft: Color,
    val surface: Color,
    val outline: Color,
    val acid: Color,
    val onAcid: Color,
    /** The accent as text: acid green is unreadable on paper, so the light palette uses a deep olive. */
    val acidText: Color,
)

private val Acid = Color(0xFFC6F135)

private fun fixedPalette(dark: Boolean) = if (dark) WPalette(
    dark = true,
    paper = Color(0xFF0C0C0C), ink = Color(0xFFF3F1EA), soft = Color(0xFF8E8C85),
    surface = Color(0xFF1B1B19), outline = Color(0xFF3A3935),
    acid = Acid, onAcid = Color(0xFF0C0C0C), acidText = Acid,
) else WPalette(
    dark = false,
    paper = Color(0xFFF3F1EA), ink = Color(0xFF0C0C0C), soft = Color(0xFF6E6C66),
    surface = Color(0xFFE5E2D7), outline = Color(0xFFC9C5B8),
    acid = Acid, onAcid = Color(0xFF0C0C0C), acidText = Color(0xFF4A6300),
)

private fun dynamicPalette(c: Context, dark: Boolean): WPalette {
    val cs = if (dark) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
    return WPalette(
        dark = dark,
        paper = cs.surface, ink = cs.onSurface, soft = cs.onSurfaceVariant,
        surface = cs.surfaceContainerHigh, outline = cs.outlineVariant,
        acid = cs.primary, onAcid = cs.onPrimary, acidText = cs.primary,
    )
}

val LocalW = staticCompositionLocalOf { fixedPalette(true) }

@Composable
fun WdgtsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    UiPrefs.load(ctx)
    val systemDark = isSystemInDarkTheme()
    val dark = when (UiPrefs.appearance) { "light" -> false; "dark" -> true; else -> systemDark }
    val you = UiPrefs.materialYou
    val palette = remember(dark, you) { if (you) dynamicPalette(ctx, dark) else fixedPalette(dark) }

    // The system bars' icons follow the app's own light or dark, which may differ from the phone's.
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalW provides palette, content = content)
}

// ==========================================================
// TYPE
// Google Sans Flex for words, set sharp (ROND 0) and wide for headings: that is the editorial half of WDGTS.
// Geist Mono for every caption, the same face as the HUD.
// Each file is looked for first; a missing one falls back to the system font, so nothing crashes.
// ==========================================================
object WFonts {
    private val cache = HashMap<String, FontFamily>()
    private val present = HashMap<String, Boolean>()

    fun has(c: Context, path: String): Boolean = present.getOrPut(path) {
        runCatching { c.assets.open(path).close(); true }.getOrDefault(false)
    }

    private fun variable(c: Context, key: String, path: String, weight: Int, vararg s: FontVariation.Setting): FontFamily =
        cache.getOrPut(key) {
            if (!has(c, path)) FontFamily.Default
            else runCatching {
                FontFamily(Font(path, c.assets, FontWeight(weight), variationSettings = FontVariation.Settings(*s)))
            }.getOrDefault(FontFamily.Default)
        }

    /** Headings. Heavy, wide, sharp terminals. */
    fun display(c: Context, wdth: Float = 118f): FontFamily = variable(
        c, "display|$wdth", "fonts/display.ttf", 900,
        FontVariation.weight(900), FontVariation.width(wdth),
        FontVariation.Setting("ROND", 0f), FontVariation.Setting("opsz", 72f),
    )

    /** Running text. A little round so it stays friendly next to the heavy headings. */
    fun body(c: Context, weight: Int = 500): FontFamily = variable(
        c, "body|$weight", "fonts/display.ttf", weight,
        FontVariation.weight(weight), FontVariation.width(100f),
        FontVariation.Setting("ROND", 40f), FontVariation.Setting("opsz", 16f),
    )

    /** Captions: Geist Mono, found the way the HUD finds it. */
    fun mono(c: Context): FontFamily = cache.getOrPut("mono") {
        val file = runCatching { c.assets.list("fonts") }.getOrNull().orEmpty()
            .firstOrNull { it.contains("geist", true) && it.contains("mono", true) }
        if (file == null) FontFamily.Monospace
        else runCatching { FontFamily(Font("fonts/$file", c.assets, FontWeight.Medium)) }.getOrDefault(FontFamily.Monospace)
    }
}

// ==========================================================
// MOTION
// Material 3 Expressive's springs and nothing else: spatial specs move and reshape things and may overshoot a
// little, effects specs fade and recolour and never do. Switched off by "Reduce motion".
// ==========================================================
object WMotion {
    private val scheme: MotionScheme = MotionScheme.expressive()
    private val on get() = !UiPrefs.reducedMotion

    fun <T> fastSpatial(): FiniteAnimationSpec<T> = if (on) scheme.fastSpatialSpec() else snap()
    fun <T> spatial(): FiniteAnimationSpec<T> = if (on) scheme.defaultSpatialSpec() else snap()
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = if (on) scheme.slowSpatialSpec() else snap()
    fun <T> fastEffects(): FiniteAnimationSpec<T> = if (on) scheme.fastEffectsSpec() else snap()
    fun <T> effects(): FiniteAnimationSpec<T> = if (on) scheme.defaultEffectsSpec() else snap()
    val enabled: Boolean get() = on
}
