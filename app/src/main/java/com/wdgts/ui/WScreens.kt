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
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.wdgts.sports.TeamWidget
import com.wdgts.widgets.CountdownBrutalWidget
import com.wdgts.widgets.CountdownShapeWidget
import com.wdgts.widgets.STREAKS
import com.wdgts.widgets.StreakDef
import com.wdgts.widgets.Streaks
import com.wdgts.widgets.StreaksWidget
import com.wdgts.widgets.TithiWidget
import com.wdgts.widgets.refreshEverything
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class Screen { Home, Hud, ChipApps, Panchang, Streaks, Countdown, Sports, Connections, Look }

/** Which screen is open, with a spring-scaled cross fade between them. [ssTick] bumps when Smartspacer's permission dialog returns. */
@Composable
fun WdgtsApp(ssTick: Int) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val toHome = targetState == Screen.Home
            (fadeIn(WMotion.effects()) + scaleIn(WMotion.spatial(), initialScale = if (toHome) 1.05f else 0.93f)) togetherWith
                (fadeOut(WMotion.fastEffects()) + scaleOut(WMotion.spatial(), targetScale = if (toHome) 0.93f else 1.05f))
        },
        label = "screen",
    ) { s ->
        val home = { screen = Screen.Home }
        when (s) {
            Screen.Home -> WHome { screen = it }
            Screen.Hud -> HudScreen(home) { screen = it }
            Screen.ChipApps -> ChipAppsScreen { screen = Screen.Hud }
            Screen.Panchang -> PanchangScreen(home)
            Screen.Streaks -> StreaksScreen(home)
            Screen.Countdown -> CountdownScreen(home)
            Screen.Sports -> SportsScreen(home)
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
    fun refresh() = HudWidget().refreshAll(c.applicationContext)
    var badge by remember { mutableIntStateOf(HudPrefs.badgeMode(c)) }
    var bySun by remember { mutableStateOf(HudPrefs.dayNightBySun(c)) }
    var layout by remember { mutableIntStateOf(HudPrefs.musicLayout(c)) }
    var control by remember { mutableIntStateOf(HudPrefs.musicControl(c)) }
    var fill by remember { mutableStateOf(HudPrefs.fill(c)) }
    var you by remember { mutableStateOf(HudPrefs.materialYou(c)) }

    WSubFrame(onBack) {
        WTitle("HUD")
        WCaption("4×2 · fixed date line, three dynamic lines")

        WPlate("Date line", "Between the month and the time. The Nothing clock puts a time-zone burst there.") {
            WChips(
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
        WPlate("Now playing style", "One line when it fits, otherwise the artist goes under the title.") {
            WChips(
                listOf(
                    "Vary by track", "Title, then artist", "TITLE (BY) ARTIST + rings", "TITLE © [ARTIST]",
                    "TITLE + progress rings + ARTIST", "TITLE + album-art strip + ARTIST",
                ),
                layout,
            ) { layout = it; HudPrefs.setMusicLayout(c, it); refresh() }
        }
        WPlate("Now playing button") {
            WChips(listOf("Arrow (opens the player)", "Pause pill", "Cassette pill (pause)"), control) {
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

    WSubFrame(onBack) {
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
                    })
                },
            )
        }
    }
}

// ==========================================================
// PANCHANG
// ==========================================================
@Composable
private fun PanchangScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var lat by remember { mutableStateOf(Prefs.lat(c).toString()) }
    var lon by remember { mutableStateOf(Prefs.lon(c).toString()) }
    var purni by remember { mutableStateOf(Prefs.purnimanta(c)) }
    var shukla16 by remember { mutableStateOf(Prefs.shukla16(c)) }
    val latOk = lat.toDoubleOrNull() != null
    val lonOk = lon.toDoubleOrNull() != null

    // The location is saved a moment after typing stops, so the widget is not redrawn on every key.
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(lat, lon) {
        if (first) { first = false; return@LaunchedEffect }
        delay(700)
        val a = lat.toDoubleOrNull(); val b = lon.toDoubleOrNull()
        if (a != null && b != null) { Prefs.setLocation(c, a, b); TithiWidget.refreshAll(c.applicationContext) }
    }

    WSubFrame(onBack) {
        WTitle("Panchang")
        WCaption("Tithi, paksha and month for the place below")
        WPlate("Location", "Sunrise and the tithi at sunrise are worked out for this point.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WField("Latitude", lat, { lat = it }, Modifier.weight(1f), KeyboardType.Text, error = !latOk)
                WField("Longitude", lon, { lon = it }, Modifier.weight(1f), KeyboardType.Text, error = !lonOk)
            }
        }
        WBlock(
            "Naming and counting",
            listOf(
                WItem("Purnimanta month names", "Months end on the full moon (Drik's default).", purni, {
                    purni = it; Prefs.setPurnimanta(c, it); TithiWidget.refreshAll(c.applicationContext)
                }),
                WItem("Count Shukla Paksha 16–30", "19 instead of 04, 20 instead of 05.", shukla16, {
                    shukla16 = it; Prefs.setShukla16(c, it); TithiWidget.refreshAll(c.applicationContext)
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

    WSubFrame(onBack) {
        WTitle("Streaks")
        WCaption("${STREAKS.size} tiles · tap one to edit its rules")
        WBlock(
            "Tiles",
            listOf(
                WItem("Material shapes on the small tiles", "Only the one-cell tiles; the big ones stay as they are.", shapes, {
                    shapes = it; Prefs.setStreakShapes(c, it); StreaksWidget.refreshAll(c.applicationContext)
                }),
            ),
        )
        WLabel("Rules")
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WField("Count", count, { count = it }, Modifier.weight(1f), KeyboardType.Number)
                    WField("Shields", shields, { shields = it }, Modifier.weight(1f), KeyboardType.Number)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WField("Earn / day", earn, { earn = it }, Modifier.weight(1f), KeyboardType.Number)
                    WField("Max", cap, { cap = it }, Modifier.weight(1f), KeyboardType.Number)
                    WField("Free", grace, { grace = it }, Modifier.weight(1f), KeyboardType.Number)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Reset on misses", style = wBody(16, 700))
                        Text("Off: the count simply waits for you.", style = wBody(13, 500, p.soft, 18))
                    }
                    WSwitch(reset) { reset = it }
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
        }
    }

    WSubFrame(onBack) {
        WTitle("Countdown")
        if (startDate != null && endDate != null) {
            val total = endDate.toEpochDay() - startDate.toEpochDay()
            val left = (endDate.toEpochDay() - LocalDate.now().toEpochDay()).coerceAtLeast(0)
            WCaption("$left of $total days left")
        }
        WPlate("Span", "Year-month-day, for example 2026-12-31.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
    fun refresh() { TeamWidget().refreshAll(c.applicationContext); F1Widget().refreshAll(c.applicationContext) }

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(team, tag) {
        if (first) { first = false; return@LaunchedEffect }
        delay(700)
        Prefs.setTeamId(c, team.trim()); Prefs.setHomeTag(c, tag.trim())
        refresh()
    }

    WSubFrame(onBack) {
        WTitle("Sports")
        WCaption("Team match and F1")
        WPlate("Team (ESPN)", "359 is Arsenal. The home tag is the short code shown for home games.") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
// ACCESS
// ==========================================================
@Composable
private fun ConnectionsScreen(ssTick: Int, onBack: () -> Unit) {
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

    WSubFrame(onBack) {
        WTitle("Access")
        WCaption("What the HUD and music widgets are allowed to read")
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
            "Smartspacer",
            listOf(
                WItem(
                    "Access",
                    when (access) {
                        Access.NOT_INSTALLED -> "Not installed."
                        Access.NEEDS_PERMISSION -> "Installed. Tap to grant access."
                        Access.GRANTED -> "Connected, ${HudSmartspacerSource.targets.size} targets right now."
                        Access.UNKNOWN -> "Could not reach it. Open Smartspacer once, then come back."
                    },
                    onClick = {
                        if ((access == Access.NEEDS_PERMISSION || access == Access.UNKNOWN) && activity != null) HudSmartspacerSource.requestAccess(activity)
                    },
                    value = when (access) {
                        Access.GRANTED -> "Granted"; Access.NOT_INSTALLED -> "Absent"; else -> "Missing"
                    },
                    valueMuted = access != Access.GRANTED,
                ),
                WItem("Copy targets", "For debugging.", onClick = { copy(c, "Smartspacer targets", HudSmartspacerSource.dump()) }),
            ),
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
        WPlate("Appearance", "Follow the phone, or force one side.") {
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
                    sysFont = it; Prefs.setSystemFont(c, it); refreshEverything(c.applicationContext)
                }),
            ),
        )
        WLabel("Fonts")
        WPlate {
            fun st(path: String, ok: String, bad: String) = if (WFonts.has(c, path)) ok else bad
            SpecLine("Google Sans Flex", st("fonts/display.ttf", "bundled", "missing"))
            SpecLine("Roboto Flex", st("fonts/roboto_flex.ttf", "bundled", "missing"))
            SpecLine(
                "Geist Mono",
                if (runCatching { c.assets.list("fonts").orEmpty().any { it.contains("geist", true) && it.contains("mono", true) } }.getOrDefault(false)) "bundled" else "missing",
            )
        }
        WBlock("About", listOf(WItem("WDGTS", "Version $version")))
    }
}
