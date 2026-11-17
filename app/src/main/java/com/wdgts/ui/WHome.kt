package com.wdgts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.wdgts.core.Prefs
import com.wdgts.widgets.STREAKS
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

// ==========================================================
// HOME
// A poster over a gallery. The poster is today's date numeral, set the way the HUD sets it, with the hour scale
// under it. Below, every widget is a tile that shows that widget live and opens its own screen; the screen grows
// out of the tile you tapped. "Add widgets" is the loud tile.
// ==========================================================
@Composable
fun WHome(go: (Screen, Offset) -> Unit) {
    val p = LocalW.current
    val c = LocalContext.current
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) }
    val version = remember {
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "dev"
    }

    val listenerOn = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    val calendarOn = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    val granted = (if (listenerOn) 1 else 0) + (if (calendarOn) 1 else 0)
    val daysLeft = (Prefs.cdEnd(c) - today.toEpochDay()).coerceAtLeast(0)

    val tall = 214.dp

    Box(Modifier.fillMaxSize().background(p.paper)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // ---- poster
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // the numeral gives way to the caption column on a narrow phone
                val numeral = ((maxWidth.value - 120f) / 1.45f).coerceIn(80f, 132f).toInt()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        today.dayOfMonth.toString().padStart(2, '0'),
                        style = wDisplay(numeral, p.ink, wdth = 125f, lineHeight = (numeral * 0.88f).toInt(), spacing = -4f),
                        maxLines = 1,
                    )
                    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(today.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).uppercase(), style = wMono(15, p.ink, FontWeight.Bold, 2f))
                        Text(today.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)).uppercase(), style = wMono(11, p.soft))
                        Text("WK ${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · $zone", style = wMono(11, p.soft))
                    }
                }
            }
            if (UiPrefs.micro) {
                HoursScale(LocalTime.now().hour)
                SpecLine("WDGTS", "v$version")
            }

            // ---- widgets
            WLabel("Widgets", count = 8)
            WTile(
                Modifier.fillMaxWidth().height(220.dp), "HU", "HUD", "5×2 · live", Poly.Sunny, Poly.Cookie9,
                preview = Kinds.Hud,
            ) { go(Screen.Hud, it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(tall), "PN", "Panchang", "%.2f, %.2f".format(Prefs.lat(c), Prefs.lon(c)),
                    Poly.Cookie6, Poly.Circle, preview = Kinds.Tithi,
                ) { go(Screen.Panchang, it) }
                WTile(
                    Modifier.weight(1f).height(tall), "ST", "Streaks", "${STREAKS.size} tiles",
                    Poly.Flower, Poly.Puffy, preview = Kinds.Streaks,
                ) { go(Screen.Streaks, it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(tall), "CD", "Countdown", "$daysLeft days left",
                    Poly.Pentagon, Poly.Cookie12, preview = Kinds.CountdownSwiss,
                ) { go(Screen.Countdown, it) }
                WTile(
                    Modifier.weight(1f).height(tall), "SP", "Sports", "team ${Prefs.teamId(c)} · F1",
                    Poly.SoftBurst, Poly.Sunny, preview = Kinds.Team,
                ) { go(Screen.Sports, it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(tall), "MU", "Music", "3 widgets",
                    Poly.Cookie9, Poly.Flower, preview = Kinds.MusicRound,
                ) { go(Screen.Music, it) }
                WTile(
                    Modifier.weight(1f).height(tall), "KJ", "Study", "kanji · N${Prefs.jlpt(c)}",
                    Poly.Sunny, Poly.Pentagon, preview = Kinds.Kanji,
                ) { go(Screen.Study, it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(tall), "WK", "Wikipedia", "article of the day",
                    Poly.Puffy, Poly.Cookie6, preview = Kinds.Wiki,
                ) { go(Screen.Wiki, it) }
                WTile(
                    Modifier.weight(1f).height(tall), "+", "Add widgets", "${Kinds.all.size} available",
                    Poly.Cookie12, Poly.SoftBurst, loud = true,
                ) { go(Screen.Gallery, it) }
            }

            // ---- system
            WLabel("System", count = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(150.dp), "AX", "Access", "$granted/2 granted",
                    Poly.Puffy, Poly.Flower,
                ) { go(Screen.Connections, it) }
                WTile(
                    Modifier.weight(1f).height(150.dp), "UI", "Look", "${UiPrefs.appearance} · ${if (UiPrefs.materialYou) "material you" else "acid"}",
                    Poly.Cookie12, Poly.Cookie6,
                ) { go(Screen.Look, it) }
            }

            // ---- footer
            Spacer(Modifier.height(10.dp))
            if (UiPrefs.micro) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SixRay(color = p.soft, dp = 9.dp)
                    Text("WDGTS_SYS_V$version", style = wMono(10, p.soft, FontWeight.Medium, 1.6f))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
