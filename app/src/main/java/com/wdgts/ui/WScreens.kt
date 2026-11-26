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
import androidx.health.connect.client.PermissionController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wdgts.core.Prefs
import com.wdgts.f1.F1Widget
import com.wdgts.hud.Aqi
import com.wdgts.hud.HudFootball
import com.wdgts.hud.HudHealth
import com.wdgts.hud.HudMock
import com.wdgts.hud.HudNative
import com.wdgts.hud.HudNativePrefs
import com.wdgts.hud.HudPrefs
import com.wdgts.hud.HudSources
import com.wdgts.hud.HudWidget
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource.Access
import com.wdgts.music.MusicPrefs
import com.wdgts.music.MusicState
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

enum class Screen { Home, Hud, ChipApps, HudComplications, HudTest, Panchang, Streaks, Countdown, Sports, Music, Study, Wiki, Gallery, Connections, Look }

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
            Screen.HudComplications -> HudComplicationsScreen({ screen = Screen.Hud }, { screen = it })
            Screen.HudTest -> HudTestScreen { screen = Screen.Hud }
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
    var source by remember { mutableIntStateOf(HudPrefs.source(c)) }
    var edge by remember { mutableIntStateOf(HudPrefs.edge(c)) }
    var bounds by remember { mutableStateOf(HudPrefs.showBounds(c)) }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Hud)) }) {
        WTitle("HUD")
        WCaption("The date line, then up to three lines (a 4x1 shows two)")

        WLabel("Content")
        WCard(
            "Data source",
            "Smartspacer costs some battery. Without it the HUD loses only what Smartspacer reads from other widgets (At a Glance, Google Home).",
            on = null,
        ) {
            WPick(listOf("Smartspacer and the app's own sources", "The app's own sources only", "Smartspacer only"), source) {
                source = it; HudPrefs.setSource(c, it)
                if (HudPrefs.usesSmartspacer(c)) HudSmartspacerSource.start(c.applicationContext) { HudWidget().refreshAll(c.applicationContext) }
                else HudSmartspacerSource.stop()
                refresh()
            }
        }
        WBlock(
            "What it shows",
            listOf(
                WItem("Complications", "Sunrise and sunset, birthdays, bedtime, steps, air quality, rain, events, football: when each one shows.", onClick = { go(Screen.HudComplications) }),
                WItem("Try them with sample data", "See any complication on the HUD without waiting for it.", onClick = { go(Screen.HudTest) }),
                WItem("Apps that get a notification chip", "Messaging apps, counts and missed calls.", onClick = { go(Screen.ChipApps) }),
            ),
        )

        WLabel("Layout")
        WCard("Edge padding", "The air between the text and the widget's edge. Auto goes tight where the launcher already pads the widget (2 rows or taller).", on = null) {
            WPick(listOf("Tight", "Normal", "Roomy", "Auto"), edge) { edge = it; HudPrefs.setEdge(c, it); refresh() }
        }
        WCard("Date line", "Between the month and the time. The Nothing clock puts a time-zone burst there.", on = null) {
            WPick(listOf("Nothing", "Sun or moon (day / night)", "Time zone, always", "Time zone, only away from home"), badge) {
                badge = it; HudPrefs.setBadgeMode(c, it); refresh()
            }
            WSetting("Day and night", "Switch the sun and moon glyph at sunrise and sunset instead of 06:00 and 18:00.") {
                WChips(listOf("At 06:00 and 18:00", "At sunrise and sunset"), if (bySun) 1 else 0) { bySun = it == 1; HudPrefs.setDayNightBySun(c, bySun); refresh() }
            }
        }
        WBlock(
            "Look",
            listOf(
                WItem("Fill the widget", "Scale the text up to the space the launcher gives it.", fill, { fill = it; HudPrefs.setFill(c, it); refresh() }),
                WItem("Material You accents", "On the date numeral, the weather bubble and the action pills.", you, { you = it; HudPrefs.setMaterialYou(c, it); refresh() }),
            ),
        )

        WLabel("Now playing")
        WCard("Music line", "One line when it fits, otherwise the artist goes under the title.", on = null) {
            WSetting("Style") {
                WPick(
                    listOf(
                        "Vary by track", "Title, then artist", "TITLE (BY) ARTIST + rings", "TITLE © [ARTIST]",
                        "TITLE + progress rings + ARTIST", "TITLE + album-art strip + ARTIST",
                    ),
                    layout,
                ) { layout = it; HudPrefs.setMusicLayout(c, it); refresh() }
            }
            WSetting("Button") {
                WPick(listOf("Arrow (opens the player)", "Pause pill", "Cassette pill (pause)"), control) { control = it; HudPrefs.setMusicControl(c, it); refresh() }
            }
        }

        WBlock(
            "Debug",
            listOf(
                WItem("Smartspacer and permissions", "Access, status and the debug dump.", onClick = { go(Screen.Connections) }),
                WItem("Copy charging info", "For debugging.", onClick = { copy(c, "Charging info", HudSources.chargingDump(c)) }),
                WItem("Show widget bounds", "A thin red outline at the edge of the HUD's picture, to see where the launcher leaves a margin.", bounds, {
                    bounds = it; HudPrefs.setShowBounds(c, it); refresh()
                }),
            ),
        )
    }
}

/** A row of preset values for one number setting of the HUD's own sources. */
@Composable
private fun HudNumber(label: String, key: String, def: Int, values: List<Int>, show: (Int) -> String, hint: String? = null, onDone: () -> Unit) {
    val c = LocalContext.current
    var v by remember { mutableIntStateOf(HudNativePrefs.num(c, key, def)) }
    WSetting(label, hint) {
        WChips(values.map(show), values.indexOf(v).coerceAtLeast(0)) { v = values[it]; HudNativePrefs.setNum(c, key, v); onDone() }
    }
}

@Composable
private fun HudComplicationsScreen(onBack: () -> Unit, go: (Screen) -> Unit) {
    val c = LocalContext.current
    fun refresh() { HudWidget().refreshAll(c.applicationContext); HudNative.armEdges(c.applicationContext); Preview.bump() }
    fun flag(key: String, def: Boolean) = HudNativePrefs.flag(c, key, def)
    var sun by remember { mutableStateOf(flag(HudNativePrefs.SUN, true)) }
    var bday by remember { mutableStateOf(flag(HudNativePrefs.BDAY, true)) }
    var steps by remember { mutableStateOf(flag(HudNativePrefs.STEPS, true)) }
    var aqi by remember { mutableStateOf(flag(HudNativePrefs.AQI, true)) }
    var rain by remember { mutableStateOf(flag(HudNativePrefs.RAIN, true)) }
    var event by remember { mutableStateOf(flag(HudNativePrefs.EVENT, true)) }
    var alarm by remember { mutableStateOf(flag(HudNativePrefs.ALARM, true)) }
    var bed by remember { mutableIntStateOf(HudNativePrefs.num(c, HudNativePrefs.BED_MODE, 0)) }
    var foot by remember { mutableStateOf(flag(HudFootball.FOOT, true)) }
    var crest by remember { mutableIntStateOf(HudNativePrefs.num(c, HudFootball.FOOT_CREST, HudFootball.CREST_MONO)) }
    var stepsSrc by remember { mutableIntStateOf(HudNativePrefs.num(c, HudNativePrefs.STEPS_SRC, 0)) }
    var aqiScale by remember { mutableIntStateOf(HudNativePrefs.num(c, HudNativePrefs.AQI_SCALE, Aqi.US)) }
    var hcGranted by remember { mutableStateOf(false) }
    val hcAsk = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        hcGranted = it.contains(HudHealth.STEPS_PERMISSION)
        refresh()
    }
    LaunchedEffect(stepsSrc) {
        if (stepsSrc == 1) hcGranted = withContext(Dispatchers.IO) { HudHealth.granted(c) }.contains(HudHealth.STEPS_PERMISSION)
    }
    val min = { m: Int -> "$m min" }
    val clock = { m: Int -> "%02d:%02d".format(m / 60, m % 60) }

    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Hud)) }) {
        WTitle("Complications")
        WCaption("Each one appears only while it is relevant, then leaves so the space goes to something else")
        WButton("Try them with sample data", { go(Screen.HudTest) }, filled = false)

        WLabel("On the clock")
        WCard("Sunrise and sunset", "SUNSET IN 20 MIN, then the time until the window closes.", sun, { sun = it; HudNativePrefs.setFlag(c, HudNativePrefs.SUN, it); refresh() }) {
            HudNumber("Starts before", HudNativePrefs.SUN_BEFORE, 30, listOf(15, 30, 45, 60), min) { refresh() }
            HudNumber("Stays after", HudNativePrefs.SUN_AFTER, 15, listOf(0, 15, 30), min) { refresh() }
        }
        WCard("Bedtime", "BEDTIME IN 25 MIN, until the time you go to bed.", bed != 0, {
            bed = if (it) 1 else 0; HudNativePrefs.setNum(c, HudNativePrefs.BED_MODE, bed); refresh()
        }) {
            WSetting("Bedtime comes from", "Digital Wellbeing's bedtime is read through Do Not Disturb access; the set time is the fallback.") {
                WChips(listOf("A set time", "Digital Wellbeing"), (bed - 1).coerceAtLeast(0)) { bed = it + 1; HudNativePrefs.setNum(c, HudNativePrefs.BED_MODE, bed); refresh() }
            }
            HudNumber("Set time", HudNativePrefs.BED_MIN, 23 * 60, listOf(21 * 60, 21 * 60 + 30, 22 * 60, 22 * 60 + 30, 23 * 60, 23 * 60 + 30), clock) { refresh() }
            HudNumber("Starts before", HudNativePrefs.BED_LEAD, 30, listOf(15, 30, 45, 60), min) { refresh() }
            WButton("Copy bedtime info", { copy(c, "Bedtime info", HudNative.bedtimeDump(c)) }, filled = false)
        }
        WCard("Next event", "From the calendar. Ambient until ten minutes before, then urgent.", event, { event = it; HudNativePrefs.setFlag(c, HudNativePrefs.EVENT, it); refresh() }) {
            HudNumber("Starts before", HudNativePrefs.EVENT_LEAD, 60, listOf(30, 60, 90, 120), min) { refresh() }
        }
        WCard("Alarm", "ALARM IN 1H 20M before the next alarm clock.", alarm, { alarm = it; HudNativePrefs.setFlag(c, HudNativePrefs.ALARM, it); refresh() }) {
            HudNumber("Starts before", HudNativePrefs.ALARM_LEAD, 120, listOf(60, 120, 240), min) { refresh() }
        }

        WLabel("People and play")
        WCard("Birthdays", "From midnight, from the Birthdays calendar. A tap dismisses it for the day.", bday, { bday = it; HudNativePrefs.setFlag(c, HudNativePrefs.BDAY, it); refresh() }) {
            HudNumber("Until", HudNativePrefs.BDAY_END, 9, listOf(8, 9, 10, 12), { "%02d:00".format(it) }) { refresh() }
        }
        WCard(
            "Football", "ARS 2 (67') 1 CHE for the team set in the Team widget. A live match is urgent.", foot,
            {
                foot = it; HudNativePrefs.setFlag(c, HudFootball.FOOT, it)
                kotlin.concurrent.thread { HudFootball.sync(c.applicationContext); HudWidget().refreshAll(c.applicationContext) }
                refresh()
            },
        ) {
            WSetting("Crests", "The first thing to go when the line gets too long.") {
                WChips(listOf("None", "Colour", "Mono", "In a ring"), crest) { crest = it; HudNativePrefs.setNum(c, HudFootball.FOOT_CREST, it); refresh() }
            }
            HudNumber("Starts before kickoff", HudFootball.FOOT_BEFORE, 180, listOf(60, 120, 180, 240), min) { refresh() }
            HudNumber("Stays after full time", HudFootball.FOOT_AFTER, 180, listOf(60, 180, 360), min) { refresh() }
        }

        WLabel("Body and air")
        WCard("Steps", "A shoe, the count, and a thumbs up once the goal is reached.", steps, { steps = it; HudNativePrefs.setFlag(c, HudNativePrefs.STEPS, it); refresh() }) {
            HudNumber("Goal", HudNativePrefs.STEPS_GOAL, 10_000, listOf(6_000, 8_000, 10_000, 12_000), { "%,d".format(it) }) { refresh() }
            WSetting("Counted by", "The phone's step sensor needs no account. Health Connect matches your watch or Google Fit.") {
                WChips(listOf("This phone", "Health Connect"), stepsSrc) { stepsSrc = it; HudNativePrefs.setNum(c, HudNativePrefs.STEPS_SRC, it); refresh() }
            }
            if (stepsSrc == 1) {
                WButton(
                    if (hcGranted) "Health Connect: allowed" else if (HudHealth.available(c)) "Allow Health Connect" else "Health Connect is not available",
                    { if (HudHealth.available(c)) hcAsk.launch(HudHealth.PERMISSIONS) },
                    filled = !hcGranted,
                )
            }
        }
        WCard("Air quality", "A mask and the index, only while it is high.", aqi, { aqi = it; HudNativePrefs.setFlag(c, HudNativePrefs.AQI, it); refresh() }) {
            WSetting("Scale", "India's index is worked out from 24 hours of PM2.5 and PM10, as the CPCB defines it.") {
                WChips(listOf("US AQI", "India (national AQI)"), aqiScale) { aqiScale = it; HudNativePrefs.setNum(c, HudNativePrefs.AQI_SCALE, it); refresh() }
            }
            if (aqiScale == Aqi.US) HudNumber("Shows from", HudNativePrefs.AQI_THR, 150, listOf(100, 150, 200, 300), { "$it" }) { refresh() }
            else HudNumber("Shows from", HudNativePrefs.AQI_THR_IN, 200, listOf(100, 200, 300, 400), { "$it" }) { refresh() }
            HudNumber("Leaves when it drops by", HudNativePrefs.AQI_MARGIN, 5, listOf(0, 5, 10, 20), { "$it" }, "Keeps it from flickering around the limit.") { refresh() }
        }
        WCard("Rain soon", "RAIN IN 20 MIN when rain is on its way and it is not raining yet.", rain, { rain = it; HudNativePrefs.setFlag(c, HudNativePrefs.RAIN, it); refresh() }) {
            HudNumber("Looks ahead", HudNativePrefs.RAIN_WIN, 60, listOf(30, 60, 90, 120), min) { refresh() }
        }

        WLabel("Behaviour")
        WCard("Ambient items", "Steps, countdowns and the alarm step aside after a few unlocks and come back when they change.", on = null) {
            HudNumber("Step aside after", HudNativePrefs.SEEN_N, 3, listOf(0, 1, 3, 5), { if (it == 0) "Never" else "$it unlocks" }) { refresh() }
        }
    }
}

/** Puts one sample complication on the HUD (preview and home screen) for ten minutes, to check its look. */
@Composable
private fun HudTestScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var active by remember { mutableStateOf(HudMock.current(c)) }
    LaunchedEffect(active) {            // the crests of a football sample arrive a moment after the sample itself
        delay(1500); Preview.bump()
        delay(2500); Preview.bump()
    }
    WSubFrame(onBack, pinned = { PreviewStage(listOf(Kinds.Hud)) }) {
        WTitle("Test")
        WCaption("Sample data on the HUD for ten minutes, then it goes by itself")
        if (active != null) {
            WButton("Stop the sample", { HudMock.set(c, null); active = null; Preview.bump() })
        }
        for (g in HudMock.GROUPS) {
            WPlate(g.title) {
                WChips(g.cases.map { it.label }, g.cases.indexOfFirst { it.id == active }) { i ->
                    val id = g.cases[i].id
                    if (active == id) { HudMock.set(c, null); active = null } else { HudMock.set(c, id); active = id }
                    Preview.bump()
                }
            }
        }
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
    var hideDup by remember { mutableStateOf(MusicPrefs.hideDuplicates(c)) }
    var artist by remember { mutableStateOf(MusicPrefs.depthArtist(c)) }
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
                WItem("Show the artist (Depth)", "The small line above the title. Off gives the title the room.", artist, {
                    artist = it; MusicPrefs.setDepthArtist(c, it); refresh()
                }),
                WItem("Hide what the cover already says", "Leave out the title or the artist line when the cover shows it in readable lettering.", hideDup, {
                    hideDup = it; MusicPrefs.setHideDuplicates(c, it); refresh()
                }),
                WItem("Copy music debug", "The art and cut-out the Depth widget is using, for a wrong cut-out.", onClick = { copy(c, "Music debug", MusicState.debug()) }),
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
    var stepsOn by remember(resume) {
        mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED)
    }
    val stepsAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { stepsOn = it }
    val policyOn = remember(resume) { c.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted }

    var access by remember { mutableStateOf(Access.UNKNOWN) }
    LaunchedEffect(ssTick, resume) {
        access = HudSmartspacerSource.checkAccess(c)
        if (access == Access.GRANTED && HudPrefs.usesSmartspacer(c)) HudSmartspacerSource.start(c.applicationContext) { HudWidget().refreshAll(c.applicationContext) }
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
            WItem(
                "Physical activity", "Steps on the HUD, counted by the phone's step sensor.",
                onClick = { if (!stepsOn) stepsAsk.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                value = if (stepsOn) "Granted" else "Missing", valueMuted = !stepsOn,
            ),
            WItem(
                "Do Not Disturb access", "Lets the HUD read Digital Wellbeing's bedtime. Optional: a set time works without it.",
                onClick = { c.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
                value = if (policyOn) "Granted" else "Missing", valueMuted = !policyOn,
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
