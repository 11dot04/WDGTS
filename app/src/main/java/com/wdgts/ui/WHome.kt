package com.wdgts.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
// Not a settings list: a poster and a grid, like microtag's dashboard. The hero is today's date numeral, set the
// way the HUD sets it, with the hour scale under it. Each tile is one widget (or one concern) and opens its own
// screen. Grid: 2 columns; the HUD is the wide, loud one.
// ==========================================================
@Composable
fun WHome(go: (Screen) -> Unit) {
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

    Box(Modifier.fillMaxSize().background(p.paper)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(10.dp))

            // ---- poster
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    today.dayOfMonth.toString().padStart(2, '0'),
                    style = wDisplay(132, p.ink, wdth = 151f, lineHeight = 116, spacing = -6f),
                    maxLines = 1,
                )
                Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(today.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).uppercase(), style = wMono(15, p.ink, FontWeight.Bold, 2f))
                    Text(today.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)).uppercase(), style = wMono(11, p.soft))
                    Text("WK ${today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)} · $zone", style = wMono(11, p.soft))
                }
            }
            if (UiPrefs.micro) {
                HoursScale(LocalTime.now().hour)
                SpecLine("WDGTS", "v$version")
            }
            Spacer(Modifier.height(6.dp))

            // ---- grid
            WTile(
                Modifier.fillMaxWidth().height(150.dp), "HUD", "HUD", "4×2 · the one that talks",
                Poly.Sunny, Poly.Cookie9, loud = true,
            ) { go(Screen.Hud) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(170.dp), "PN", "Panchang", "%.2f, %.2f".format(Prefs.lat(c), Prefs.lon(c)),
                    Poly.Cookie6, Poly.Circle,
                ) { go(Screen.Panchang) }
                WTile(
                    Modifier.weight(1f).height(170.dp), "ST", "Streaks", "${STREAKS.size} tiles",
                    Poly.Flower, Poly.Puffy,
                ) { go(Screen.Streaks) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(170.dp), "CD", "Countdown", "$daysLeft days left",
                    Poly.Pentagon, Poly.Cookie12,
                ) { go(Screen.Countdown) }
                WTile(
                    Modifier.weight(1f).height(170.dp), "SP", "Sports", "team ${Prefs.teamId(c)} · F1",
                    Poly.SoftBurst, Poly.Sunny,
                ) { go(Screen.Sports) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WTile(
                    Modifier.weight(1f).height(170.dp), "AX", "Access", "$granted/2 granted",
                    Poly.Puffy, Poly.Flower,
                ) { go(Screen.Connections) }
                WTile(
                    Modifier.weight(1f).height(170.dp), "UI", "Look", "${UiPrefs.appearance} · ${if (UiPrefs.materialYou) "material you" else "acid"}",
                    Poly.Cookie12, Poly.Cookie6,
                ) { go(Screen.Look) }
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
