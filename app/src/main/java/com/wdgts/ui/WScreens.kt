package com.wdgts.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wdgts.core.Prefs
import com.wdgts.f1.F1Widget
import com.wdgts.hud.HudPrefs
import com.wdgts.hud.HudSources
import com.wdgts.hud.HudWidget
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource.Access
import com.wdgts.music.MusicPrefs
import com.wdgts.music.MusicWidgets
import com.wdgts.sports.TeamWidget
import com.wdgts.widgets.CountdownBrutalWidget
import com.wdgts.widgets.CountdownShapeWidget
import com.wdgts.widgets.KanjiWidget
import com.wdgts.widgets.STREAKS
import com.wdgts.widgets.StreakDef
import com.wdgts.widgets.Streaks
import com.wdgts.widgets.StreaksWidget
import com.wdgts.widgets.TithiWidget
import com.wdgts.widgets.refreshEverything
import com.wdgts.widgets.refreshKanjiWidgets
import com.wdgts.wiki.WikiWidget
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Hud, ChipApps, Panchang, Streaks, Countdown, Sports, Music, Study, Wiki, Gallery, Connections, Look }

/** First run shows the intro; after that, the home screen and the screens behind it. [ssTick] bumps when Smartspacer's permission dialog returns. */
@Composable
fun WdgtsApp(ssTick: Int) {
    val c = LocalContext.current
    Crossfade(targetState = UiPrefs.onboarded, animationSpec = WMotion.effects(), label = "root") { done ->
        if (!done) WOnboarding(ssTick) { UiPrefs.setOnboarded(c, true) } else Navigator(ssTick)
    }
}

/** Which screen is open. A screen grows out of the tile that was tapped and shrinks back into it. */
@Composable
private fun Navigator(ssTick: Int) {
    val view = LocalView.current
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var origin by remember { mutableStateOf(TransformOrigin.Center) }
    val open = { s: Screen, at: Offset ->
        origin = TransformOrigin(at.x / view.width.coerceAtLeast(1), at.y / view.height.coerceAtLeast(1))
        screen = s
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val toHome = targetState == Screen.Home
            (fadeIn(WMotion.effects()) + scaleIn(WMotion.spatial(), initialScale = if (toHome) 1.04f else 0.86f, transformOrigin = origin)) togetherWith
                (fadeOut(WMotion.fastEffects()) + scaleOut(WMotion.spatial(), targetScale = if (toHome) 0.86f else 1.04f, transformOrigin = origin))
        },
        label = "screen",
    ) { s ->
        val home = { screen = Screen.Home }
        val go = { to: Screen -> screen = to }
        when (s) {
            Screen.Home -> WHome(open)
            Screen.Hud -> HudScreen(home, go)
            Screen.ChipApps -> ChipAppsScreen { screen = Screen.Hud }
            Screen.Panchang -> PanchangScreen(home)
            Screen.Streaks -> StreaksScreen(home)
            Screen.Countdown -> CountdownScreen(home)
            Screen.Sports -> SportsScreen(home)
            Screen.Music -> MusicScreen(home, go)
            Screen.Study -> StudyScreen(home)
            Screen.Wiki -> WikiScreen(home)
            Screen.Gallery -> GalleryScreen(home)
            Screen.Connections -> ConnectionsScreen(ssTick, home)
            Screen.Look -> LookScreen(home)
        }
    }
}

private fun copy(c: Context, label: String, text: String) {
    c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(c, "Copied ${text.length} characters", Toast.LENGTH_SHORT).show()
}

// ==========================================================
// HUD
// ==========================================================
@Composable
private fun HudScreen(onBack: () -> Unit, go: (Screen) -> Unit) {
    val c = LocalContext.current
    fun refresh() { HudWidget().refreshAll(c.applicationContext); Preview.bump() }
    var badge by remember { mutableIntStateOf(HudPrefs.badgeMode(c)) }
    var bySun by remember { mutableStateOf(HudPrefs.dayNightBySun(c)) }
    var layout by remember { mutableIntStateOf(HudPrefs.musicLayout(c)) }
    var control by remember { mutableIntStateOf(HudPrefs.musicControl(c)) }
    var fill by remember { mutableStateOf(HudPrefs.fill(c)) }
    var you by remember { mutableStateOf(HudPrefs.materialYou(c)) }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Hud)) }) {
        WTitle("HUD")
        WCaption("Fixed date line, three dynamic lines")

        WPlate("Date line", "Between the month and the time. The Nothing clock puts a time-zone burst there.", count = 4) {
            WPick(
                listOf("Nothing", "Sun or moon (day / night)", "Time zone, always", "Time zone, only away from home"), badge,
            ) { badge = it; HudPrefs.setBadgeMode(c, it); refresh() }
        }
        WBlock(
            "Day and night",
            listOf(
                WItem("Switch at sunrise and sunset", "Instead of 06:00 and 18:00.", bySun, {
                    bySun = it; HudPrefs.setDayNightBySun(c, it); refresh()
                }),
            ),
        )
        WPlate("Now playing style", "One line when it fits, otherwise the artist goes under the title.", count = 6) {
            WPick(
                listOf(
                    "Vary by track", "Title, then artist", "TITLE (BY) ARTIST + rings", "TITLE © [ARTIST]",
                    "TITLE + progress rings + ARTIST", "TITLE + album-art strip + ARTIST",
                ),
                layout,
            ) { layout = it; HudPrefs.setMusicLayout(c, it); refresh() }
        }
        WPlate("Now playing button", count = 3) {
            WPick(listOf("Arrow (opens the player)", "Pause pill", "Cassette pill (pause)"), control) {
                control = it; HudPrefs.setMusicControl(c, it); refresh()
            }
        }
        WBlock(
            "Look",
            listOf(
                WItem("Fill the widget", "Scale the text up to the space the launcher gives it.", fill, {
                    fill = it; HudPrefs.setFill(c, it); refresh()
                }),
                WItem("Material You accents", "On the date numeral, the weather bubble and the action pills.", you, {
                    you = it; HudPrefs.setMaterialYou(c, it); refresh()
                }),
            ),
        )
        WBlock(
            "Sources",
            listOf(
                WItem("Apps that get a notification chip", "Messaging apps, counts and missed calls.", onClick = { go(Screen.ChipApps) }),
                WItem("Smartspacer and permissions", "Access, status and the debug dump.", onClick = { go(Screen.Connections) }),
                WItem("Copy charging info", "For debugging.", onClick = { copy(c, "Charging info", HudSources.chargingDump(c)) }),
            ),
        )
    }
}

@Composable
private fun ChipAppsScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var chosen by remember { mutableStateOf(HudPrefs.chipApps(c) ?: HudSources.defaultChipApps()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { HudSources.candidateApps(c) } }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Hud)) }) {
        WTitle("Chips")
        WCaption("Which apps get a notification chip on the HUD")
        val list = apps
        if (list == null) {
            WCaption("loading apps…")
        } else {
            WBlock(
                "Apps",
                list.map { (pkg, label) ->
                    WItem(label, pkg, pkg in chosen, { on ->
                        chosen = if (on) chosen + pkg else chosen - pkg
                        HudPrefs.setChipApps(c, chosen)
                        HudWidget().refreshAll(c.applicationContext)
                        Preview.bump()
                    })
                },
            )
        }
    }
}

// ==========================================================
// PANCHANG
// ==========================================================
private val CITIES = listOf(
    Triple("New Delhi", 28.6139, 77.2090),
    Triple("Mumbai", 19.0760, 72.8777),
    Triple("Bengaluru", 12.9716, 77.5946),
    Triple("Kolkata", 22.5726, 88.3639),
    Triple("Chennai", 13.0827, 80.2707),
    Triple("Hyderabad", 17.3850, 78.4867),
)

private fun num(v: Double) = String.format(Locale.US, "%.4f", v)

/** City chips and two coordinate fields. Saved a moment after typing stops, so the widget is not redrawn on every key. */
@Composable
fun LocationEditor() {
    val c = LocalContext.current
    var lat by remember { mutableStateOf(num(Prefs.lat(c))) }
    var lon by remember { mutableStateOf(num(Prefs.lon(c))) }
    val latV = lat.trim().toDoubleOrNull()
    val lonV = lon.trim().toDoubleOrNull()
    val picked = CITIES.indexOfFirst { latV != null && lonV != null && abs(it.second - latV) < 0.02 && abs(it.third - lonV) < 0.02 }

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(lat, lon) {
        if (first) { first = false; return@LaunchedEffect }
        delay(700)
        val a = lat.trim().toDoubleOrNull(); val b = lon.trim().toDoubleOrNull()
        if (a != null && b != null) {
            Prefs.setLocation(c, a, b)
            TithiWidget.refreshAll(c.applicationContext)
            Preview.bump()
        }
    }

    WPlate("Location", "Sunrise, and the tithi at sunrise, are worked out for this point.") {
        WChips(CITIES.map { it.first }, picked) { i -> lat = num(CITIES[i].second); lon = num(CITIES[i].third) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WField("Latitude", lat, { lat = it }, Modifier.weight(1f), KeyboardType.Text, error = latV == null)
            WField("Longitude", lon, { lon = it }, Modifier.weight(1f), KeyboardType.Text, error = lonV == null)
        }
    }
}

@Composable
private fun PanchangScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var purni by remember { mutableStateOf(Prefs.purnimanta(c)) }
    var shukla16 by remember { mutableStateOf(Prefs.shukla16(c)) }
    fun refresh() { TithiWidget.refreshAll(c.applicationContext); Preview.bump() }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Tithi)) }) {
        WTitle("Panchang")
        WCaption("Tithi, paksha and month for the place below")
        LocationEditor()
        WBlock(
            "Naming and counting",
            listOf(
                WItem("Purnimanta month names", "Months end on the full moon (Drik's default).", purni, {
                    purni = it; Prefs.setPurnimanta(c, it); refresh()
                }),
                WItem("Count Shukla Paksha 16–30", "19 instead of 04, 20 instead of 05.", shukla16, {
                    shukla16 = it; Prefs.setShukla16(c, it); refresh()
                }),
            ),
        )
    }
}

// ==========================================================
// STREAKS
// ==========================================================
@Composable
private fun StreaksScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val today = remember { LocalDate.now().toEpochDay() }
    var shapes by remember { mutableStateOf(Prefs.streakShapes(c)) }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Streaks), height = 190.dp) }) {
        WTitle("Streaks")
        WCaption("${STREAKS.size} tiles · tap one to edit its rules")
        WBlock(
            "Tiles",
            listOf(
                WItem("Material shapes on the small tiles", "Only the one-cell tiles; the big ones stay as they are.", shapes, {
                    shapes = it; Prefs.setStreakShapes(c, it); StreaksWidget.refreshAll(c.applicationContext); Preview.bump()
                }),
            ),
        )
        WLabel("Rules", count = STREAKS.size)
        STREAKS.forEach { def -> StreakCard(def, today) }
    }
}

@Composable
private fun StreakCard(def: StreakDef, today: Long) {
    val c = LocalContext.current
    val p = LocalW.current
    val v = remember { Streaks.state(c, def, today) }
    val r = remember { Streaks.rule(c, def) }
    var open by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(v.count.toString()) }
    var shields by remember { mutableStateOf(v.shields.toString()) }
    var earn by remember { mutableStateOf(r.earn.toString()) }
    var cap by remember { mutableStateOf(r.cap.toString()) }
    var grace by remember { mutableStateOf(r.grace.toString()) }
    var reset by remember { mutableStateOf(r.autoReset) }

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(count, shields, earn, cap, grace, reset) {
        if (first) { first = false; return@LaunchedEffect }
        delay(600)
        fun i(s: String) = s.trim().toIntOrNull() ?: 0
        Streaks.setRule(c, def, Streaks.Rule(i(earn), i(cap), i(grace), reset))
        Streaks.setCount(c, def, i(count), i(shields))
        StreaksWidget.refreshAll(c.applicationContext)
        Preview.bump()
    }

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        WRow(
            WItem(
                def.label, "${count.ifBlank { "0" }} days · ${shields.ifBlank { "0" }} shields",
                onClick = { open = !open }, value = if (open) "close" else "edit", valueMuted = !open,
            ),
            groupCorners(0, if (open) 2 else 1),
        )
        if (open) {
            WPlate {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WField("Count", count, { count = it }, Modifier.weight(1f), KeyboardType.Number)
                    WField("Shields", shields, { shields = it }, Modifier.weight(1f), KeyboardType.Number)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WField("Earn / day", earn, { earn = it }, Modifier.weight(1f), KeyboardType.Number)
                    WField("Max", cap, { cap = it }, Modifier.weight(1f), KeyboardType.Number)
                    WField("Free", grace, { grace = it }, Modifier.weight(1f), KeyboardType.Number)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Reset on misses", style = wBody(16, 700))
                        Text("Off: the count simply waits for you.", style = wBody(13, 500, p.soft, 18))
                    }
                    WSwitch(reset, { reset = it })
                }
            }
        }
    }
}

// ==========================================================
// COUNTDOWN
// ==========================================================
@Composable
private fun CountdownScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var start by remember { mutableStateOf(LocalDate.ofEpochDay(Prefs.cdStart(c)).toString()) }
    var end by remember { mutableStateOf(LocalDate.ofEpochDay(Prefs.cdEnd(c)).toString()) }
    var quote by remember { mutableStateOf(Prefs.cdQuote(c)) }
    val startDate = runCatching { LocalDate.parse(start.trim()) }.getOrNull()
    val endDate = runCatching { LocalDate.parse(end.trim()) }.getOrNull()

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(start, end, quote) {
        if (first) { first = false; return@LaunchedEffect }
        delay(700)
        if (startDate != null && endDate != null) {
            Prefs.setCountdown(c, startDate.toEpochDay(), endDate.toEpochDay(), quote)
            CountdownBrutalWidget().refreshAll(c.applicationContext)
            CountdownShapeWidget().refreshAll(c.applicationContext)
            Preview.bump()
        }
    }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.CountdownSwiss, Kinds.CountdownShape)) }) {
        WTitle("Countdown")
        if (startDate != null && endDate != null) {
            val total = endDate.toEpochDay() - startDate.toEpochDay()
            val left = (endDate.toEpochDay() - LocalDate.now().toEpochDay()).coerceAtLeast(0)
            WCaption("$left of $total days left")
        } else WCaption("Two widgets, one span")
        WPlate("Span", "Year-month-day, for example 2026-12-31.") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WField("Start", start, { start = it }, Modifier.weight(1f), error = startDate == null)
                WField("End", end, { end = it }, Modifier.weight(1f), error = endDate == null)
            }
        }
        WPlate("Quote") { WField("Shown on the widget", quote, { quote = it }) }
    }
}

// ==========================================================
// SPORTS
// ==========================================================
@Composable
private fun SportsScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var team by remember { mutableStateOf(Prefs.teamId(c)) }
    var tag by remember { mutableStateOf(Prefs.homeTag(c)) }
    var you by remember { mutableStateOf(Prefs.sportsMaterialYou(c)) }
    fun refresh() { TeamWidget().refreshAll(c.applicationContext); F1Widget().refreshAll(c.applicationContext); Preview.bump() }

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(team, tag) {
        if (first) { first = false; return@LaunchedEffect }
        delay(700)
        Prefs.setTeamId(c, team.trim()); Prefs.setHomeTag(c, tag.trim())
        refresh()
    }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Team, Kinds.F1)) }) {
        WTitle("Sports")
        WCaption("Team match and F1")
        WPlate("Team (ESPN)", "359 is Arsenal. The home tag is the short code shown for home games.") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WField("ESPN team id", team, { team = it }, Modifier.weight(1f), KeyboardType.Number)
                WField("Home tag", tag, { tag = it }, Modifier.weight(1f))
            }
        }
        WBlock(
            "Colours",
            listOf(
                WItem("Material You colours", "Off: fixed ink, paper and acid green.", you, {
                    you = it; Prefs.setSportsMaterialYou(c, it); refresh()
                }),
            ),
        )
        WLabel("Last ESPN fetch")
        WPlate { Text(Prefs.espnDebug(c), style = wMono(11, LocalW.current.ink, spacing = 0.4f)) }
    }
}

// ==========================================================
// MUSIC
// ==========================================================
@Composable
private fun MusicScreen(onBack: () -> Unit, go: (Screen) -> Unit) {
    val c = LocalContext.current
    // Frame keeps its controls switch under its class name; a tap on the widget's corner flips it too.
    var frameControls by remember { mutableStateOf(Prefs.musicControls(c, "MusicFrameWidget")) }
    var lyrics by remember { mutableStateOf(MusicPrefs.lyrics(c)) }
    var style by remember { mutableIntStateOf(MusicPrefs.buttonStyle(c)) }
    fun refresh() { MusicWidgets.refreshAll(c.applicationContext); Preview.bump() }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.MusicDepth, Kinds.MusicFrame, Kinds.MusicRound)) }) {
        WTitle("Music")
        WCaption("Preview shows the idle card when nothing is playing")
        WBlock(
            "Depth",
            listOf(
                WItem("Synced lyrics", "The line being sung, above the buttons. Looked up on LRCLIB, one request per song.", lyrics, {
                    lyrics = it; MusicPrefs.setLyrics(c, it); refresh()
                }),
            ),
        )
        WPlate("Depth buttons", "Colours taken from the cover, or frosted glass over it.") {
            WChips(listOf("Cover colours", "Glass"), style) { style = it; MusicPrefs.setButtonStyle(c, it); refresh() }
        }
        WBlock(
            "Frame",
            listOf(
                WItem("Show controls", "Previous, pause and next in place of the cover.", frameControls, { on ->
                    frameControls = on
                    Prefs.setMusicControls(c, "MusicFrameWidget", on)
                    refresh()
                }),
            ),
        )
        WCaption("Round shows its shape frame while a song plays and the contour lines when it does not.")
        WBlock(
            "Elsewhere",
            listOf(WItem("Now playing on the HUD", "Style and button are set on the HUD screen.", onClick = { go(Screen.Hud) })),
        )
    }
}

// ==========================================================
// STUDY (kanji)
// ==========================================================
@Composable
private fun StudyScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableIntStateOf(Prefs.jlpt(c).coerceIn(1, 5)) }
    var busy by remember { mutableStateOf(false) }

    fun sync() {
        scope.launch {
            busy = true
            withContext(Dispatchers.IO) { runCatching { KanjiWidget.sync(c.applicationContext) } }
            busy = false
            Preview.bump()
        }
    }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Kanji, Kinds.Examples)) }) {
        WTitle("Study")
        WCaption("A kanji a day, and ten words that use it")
        WPlate("JLPT level", "Which level the daily kanji is drawn from. Tapping the level on the widget steps it down too.", count = 5) {
            WChips(listOf("N5", "N4", "N3", "N2", "N1"), 5 - level) { i ->
                level = 5 - i
                Prefs.setJlpt(c, level)
                refreshKanjiWidgets(c.applicationContext)
                Preview.bump()
                sync()
            }
        }
        WBlock(
            "Data",
            listOf(
                WItem(
                    "Refresh today's kanji", "Fetches again if the cache is stale.", onClick = { if (!busy) sync() },
                    value = if (busy) "Working" else "Go", valueMuted = busy,
                ),
            ),
        )
    }
}

// ==========================================================
// WIKIPEDIA
// ==========================================================
@Composable
private fun WikiScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Wiki, Kinds.WikiBody)) }) {
        WTitle("Wikipedia")
        WCaption("An article a day, as a picture and as text")
        WBlock(
            "Article",
            listOf(
                WItem(
                    "Pick another article", "Same as tapping the widget. The cutout can take a few seconds.",
                    onClick = {
                        if (!busy) scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) { runCatching { WikiWidget.sync(c.applicationContext, true) } }
                            busy = false
                            Preview.bump()
                        }
                    },
                    value = if (busy) "Fetching" else "Shuffle", valueMuted = busy,
                ),
            ),
        )
        WCaption("A new article arrives every day at midnight")
    }
}

// ==========================================================
// GALLERY
// ==========================================================
@Composable
private fun GalleryScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    WSubFrame(onBack) {
        WTitle("Widgets")
        WCaption("${Kinds.all.size} widgets · each preview is the real thing")
        Kinds.all.forEach { kind -> WidgetCard(kind) { pinWidget(c, kind) } }
    }
}

// ==========================================================
// ACCESS
// ==========================================================

/** The permission rows, shared by the Access screen and the intro. [ssTick] bumps when Smartspacer's dialog returns. */
@Composable
fun AccessBlocks(ssTick: Int) {
    val c = LocalContext.current
    val activity = c as? Activity
    var resume by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resume++ }

    val listenerOn = remember(resume) { NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName) }
    var calendarOn by remember(resume) {
        mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    val calendarAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarOn = it }

    var access by remember { mutableStateOf(Access.UNKNOWN) }
    LaunchedEffect(ssTick, resume) {
        access = HudSmartspacerSource.checkAccess(c)
        if (access == Access.GRANTED) HudSmartspacerSource.start(c.applicationContext) { HudWidget().refreshAll(c.applicationContext) }
    }

    WBlock(
        "Permissions",
        listOf(
            WItem(
                "Notification access", "Media session, missed calls, counts and screen state.",
                onClick = { c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                value = if (listenerOn) "Granted" else "Missing", valueMuted = !listenerOn,
            ),
            WItem(
                "Calendar", "The HUD shows your next event.",
                onClick = { if (!calendarOn) calendarAsk.launch(Manifest.permission.READ_CALENDAR) },
                value = if (calendarOn) "Granted" else "Missing", valueMuted = !calendarOn,
            ),
        ),
    )
    WBlock(
        "Smartspacer (optional)",
        listOf(
            WItem(
                "Access",
                when (access) {
                    Access.NOT_INSTALLED -> "Not installed. The HUD works without it."
                    Access.NEEDS_PERMISSION -> "Installed. Tap to grant access."
                    Access.GRANTED -> "Connected, ${HudSmartspacerSource.targets.size} targets right now."
                    Access.UNKNOWN -> "Could not reach it. Open Smartspacer once, then come back."
                },
                onClick = {
                    if ((access == Access.NEEDS_PERMISSION || access == Access.UNKNOWN) && activity != null) HudSmartspacerSource.requestAccess(activity)
                },
                value = when (access) {
                    Access.GRANTED -> "Granted"
                    Access.NOT_INSTALLED -> "Absent"
                    else -> "Missing"
                },
                valueMuted = access != Access.GRANTED,
            ),
        ),
    )
}

@Composable
private fun ConnectionsScreen(ssTick: Int, onBack: () -> Unit) {
    val c = LocalContext.current
    WSubFrame(onBack) {
        WTitle("Access")
        WCaption("What the HUD and music widgets are allowed to read")
        AccessBlocks(ssTick)
        WBlock(
            "Debug",
            listOf(WItem("Copy Smartspacer targets", "For debugging.", onClick = { copy(c, "Smartspacer targets", HudSmartspacerSource.dump()) })),
        )
    }
}

// ==========================================================
// LOOK
// ==========================================================
@Composable
private fun LookScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var sysFont by remember { mutableStateOf(Prefs.systemFont(c)) }
    val version = remember {
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
    val appearance = listOf("system", "light", "dark")

    WSubFrame(onBack) {
        WTitle("Look")
        WCaption("This app only; the widgets have their own settings")
        WPlate("Appearance", "Follow the phone, or force one side.", count = 3) {
            WChips(listOf("System", "Light", "Dark"), appearance.indexOf(UiPrefs.appearance).coerceAtLeast(0)) {
                UiPrefs.setAppearance(c, appearance[it])
            }
        }
        WBlock(
            "App",
            listOf(
                WItem("Material You", "Swap the acid green for the system accent colour.", UiPrefs.materialYou, { UiPrefs.setMaterialYou(c, it) }),
                WItem("Micrographics", "The small technical marks. Off keeps it clean.", UiPrefs.micro, { UiPrefs.setMicro(c, it) }),
                WItem("Reduce motion", "Skip the springs and entrances.", UiPrefs.reducedMotion, { UiPrefs.setReducedMotion(c, it) }),
                WItem("Haptics", "Taps and toggles.", UiPrefs.haptics, { UiPrefs.setHaptics(c, it) }),
            ),
        )
        WBlock(
            "Widgets",
            listOf(
                WItem("System font", "Use the phone's font in widgets. The HUD and music keep theirs.", sysFont, {
                    sysFont = it; Prefs.setSystemFont(c, it); refreshEverything(c.applicationContext); Preview.bump()
                }),
            ),
        )
        WBlock(
            "Intro",
            listOf(WItem("Replay the intro", "Access, place and the widget gallery, once more.", onClick = { UiPrefs.setOnboarded(c, false) })),
        )
        WLabel("Fonts")
        WPlate {
            fun st(path: String) = if (WFonts.has(c, path)) "bundled" else "missing"
            SpecLine("Google Sans Flex", st("fonts/display.ttf"))
            SpecLine("Roboto Flex", st("fonts/roboto_flex.ttf"))
            SpecLine(
                "Geist Mono",
                if (runCatching { c.assets.list("fonts").orEmpty().any { it.contains("geist", true) && it.contains("mono", true) } }.getOrDefault(false)) "bundled" else "missing",
            )
        }
        WBlock("About", listOf(WItem("WDGTS", "Version $version")))
    }
}
