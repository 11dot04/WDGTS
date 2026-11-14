package com.wdgts.hud

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.BatteryManager
import android.provider.Telephony
import com.wdgts.core.Prefs
import com.wdgts.hud.engine.ArtStrip
import com.wdgts.hud.engine.Block
import com.wdgts.hud.engine.Badge
import com.wdgts.hud.engine.BadgeShape
import com.wdgts.hud.engine.Chip
import com.wdgts.hud.engine.CountRing
import com.wdgts.hud.engine.HudText
import com.wdgts.hud.engine.Icon
import com.wdgts.hud.engine.IconPill
import com.wdgts.hud.engine.MoonPhase
import com.wdgts.hud.engine.OutlinePill
import com.wdgts.hud.engine.ProgressMark
import com.wdgts.hud.engine.Token
import com.wdgts.hud.engine.DotRow
import com.wdgts.hud.engine.GlyphToken
import com.wdgts.hud.engine.HudGlyph
import com.wdgts.hud.engine.HudRules
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.Text
import com.wdgts.hud.engine.Timer
import com.wdgts.hud.engine.WeatherInfo
import com.wdgts.music.MusicState
import com.wdgts.panchang.Astro
import com.wdgts.panchang.Panchang
import com.wdgts.panchang.Paksha
import com.wdgts.panchang.numberFor
import com.wdgts.panchang.sunsetMs
import com.wdgts.widgets.GlyphMask
import com.wdgts.widgets.STREAKS
import com.wdgts.widgets.Streaks
import java.time.LocalDate
import java.time.ZoneId

/** Identifies an app whose monochrome icon the HUD should draw. */
data class AppIconKey(val pkg: String)

/**
 * What the device knows that Smartspacer does not: missed calls, the 20-20-20 look-away nudge, the tithi on the
 * first unlock of the day, the streaks still open late in the evening, and per-app notification counts.
 * Each source only decides what to say; ranking and placement happen in [HudComposer].
 */
object HudSources {
    /** [TapRef.targetId] for taps handled by this app (the action id is one of the constants below). */
    const val LOCAL = "local"
    const val CALLS = "CALLS"
    const val DISMISS_LOOK = "DISMISS_LOOK"
    const val DRIK = "DRIK"
    const val MUSIC_TOGGLE = "MUSIC_TOGGLE"
    const val SETTINGS = "SETTINGS"
    const val APP_PREFIX = "APP:"

    private fun tap(action: String) = TapRef(LOCAL, action)

    private val MESSAGING = listOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.instagram.android", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.facebook.orca", "com.google.android.apps.messaging",
        "com.discord", "com.slack", "com.google.android.gm", "com.snapchat.android",
    )

    /**
     * [SHAKA] NAME (+2) [→]: call me back. One caller is named, the others are a "+n". There is no "MISSED": the shaka
     * says it. When the notification chips fit on the same line they join it (and the arrow steps aside).
     */
    fun missedCalls(): Block? {
        val missed = HudSignals.missed
        if (missed.isEmpty()) return null
        val t = tap(CALLS)
        val line = ArrayList<Token>()
        line += GlyphToken(HudGlyph.SHAKA, t)
        line += Text(missed[0].uppercase(), t)
        if (missed.size > 1) line += OutlinePill("+${missed.size - 1}", t)
        return Block("missed", listOf(line + GlyphToken(HudGlyph.FOLLOWUP, t)), absorbs = "chips", absorbLine = line.toList())
    }

    /** [EYE] LOOK AWAY · 20 FT [→] / ⌊20S */
    fun lookAway(now: Long): Block? {
        val on = HudSignals.screenOnSince
        if (on <= 0 || now - maxOf(on, HudSignals.lookDismissedAt) < 20 * 60_000L) return null
        val t = tap(DISMISS_LOOK)
        return Block(
            "look",
            listOf(
                listOf(GlyphToken(HudGlyph.EYE, t), Text("LOOK AWAY · 20 FT", t), GlyphToken(HudGlyph.FOLLOWUP, t)),
                listOf(Timer("20S", t)),
            ),
        )
    }

    /** [MOON DISC] SP 5 · KARTIK [→], only in the first ten minutes after the first unlock of the day. */
    fun tithi(c: Context, now: Long, zone: ZoneId, today: LocalDate): Block? {
        if (Prefs.hudUnlockDay(c) != today.toEpochDay() || now - Prefs.hudUnlockAt(c) >= 10 * 60_000L) return null
        val d = runCatching {
            Panchang.compute(today, zone, Prefs.lat(c), Prefs.lon(c), Prefs.purnimanta(c))
        }.getOrNull() ?: return null
        val t = tap(DRIK)
        val text = "${if (d.paksha == Paksha.KRISHNA) "KP" else "SP"} ${d.numberFor(Prefs.shukla16(c))} · ${d.month.uppercase()}"
        return Block("tithi", listOf(listOf(MoonPhase(d.elongation.toFloat(), t), Text(text, t), GlyphToken(HudGlyph.FOLLOWUP, t))))
    }

    @Volatile private var moonDay = Long.MIN_VALUE
    @Volatile private var moonE = 0f

    /** Moon - Sun elongation for [today] (sunrise value, cached per day), for the moon-phase glyph. */
    fun moonElongation(c: Context, zone: ZoneId, today: LocalDate): Float? {
        if (today.toEpochDay() == moonDay) return moonE
        val d = runCatching { Panchang.compute(today, zone, Prefs.lat(c), Prefs.lon(c), Prefs.purnimanta(c)) }.getOrNull() ?: return null
        moonE = d.elongation.toFloat()
        moonDay = today.toEpochDay()
        return moonE
    }

    /** 3 STREAKS LEFT / ●●●○○○ ⌊2H 10M, shown from 22:00 until midnight while any streak is still open. */
    fun streaks(c: Context, now: Long, zone: ZoneId, today: LocalDate, hour: Int): Block? {
        if (hour < 22 || STREAKS.isEmpty()) return null
        val day = today.toEpochDay()
        val total = STREAKS.size
        val done = STREAKS.count { Streaks.state(c, it, day).doneToday }
        val left = total - done
        if (left <= 0) return null
        val t = tap(SETTINGS)
        val minutes = ((today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - now) / 60_000L).coerceAtLeast(1)
        val remaining = if (minutes >= 60) "${minutes / 60}H${if (minutes % 60 > 0) " ${minutes % 60}M" else ""}" else "${minutes}MIN"
        return Block(
            "streaks",
            listOf(
                listOf(Text("$left STREAK${if (left > 1) "S" else ""} LEFT", t)),
                listOf(DotRow(done, total, t), Timer(remaining, t)),
            ),
        )
    }

    fun defaultChipApps(): Set<String> = MESSAGING.toSet()

    /** Notification counts per app, shown as up to three differently shaped chips, busiest first. */
    fun chips(c: Context): Block? {
        val allowed = HudPrefs.chipApps(c) ?: MESSAGING.toSet()
        val counts = HashMap<String, Int>()
        for (pkg in allowed) HudSignals.counts[pkg]?.takeIf { it > 0 }?.let { counts[pkg] = it }
        val sms = HudSignals.smsCount(c)
        if (sms > 0) Telephony.Sms.getDefaultSmsPackage(c)?.takeIf { it in allowed }?.let { counts[it] = sms }
        if (counts.isEmpty()) return null
        val tokens = ArrayList<Token>()
        counts.entries.sortedByDescending { it.value }.take(3).forEachIndexed { rank, (pkg, n) ->
            val t = tap(APP_PREFIX + pkg)
            val count = if (n > 99) "99+" else n.toString()
            val key = AppIconKey(pkg)
            if (GlyphMask.forPackage(c, pkg) == null) {
                tokens += OutlinePill("${label(c, pkg)} $count", t)
                return@forEachIndexed
            }
            when (rank) {
                0 -> { tokens += IconPill(key, t); tokens += CountRing(count, t) }       // [icon] (3)
                1 -> tokens += Chip(key, count, t)                                       // ( icon 3 )
                else -> { tokens += Icon(key, t); tokens += Badge(BadgeShape.BURST, count, t) }   // icon (burst 3)
            }
        }
        return Block("chips", listOf(tokens))
    }

    private fun label(c: Context, pkg: String): String =
        runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg.substringAfterLast('.')).uppercase().take(4)

    /** Apps worth offering in Settings: the messaging list that is installed, plus whatever is notifying right now. */
    fun candidateApps(c: Context): List<Pair<String, String>> {
        val pkgs = LinkedHashSet<String>()
        pkgs += MESSAGING.filter { runCatching { c.packageManager.getApplicationInfo(it, 0) }.isSuccess }
        pkgs += HudSignals.counts.keys
        Telephony.Sms.getDefaultSmsPackage(c)?.let { pkgs += it }
        return pkgs.map { it to label(c, it) }.sortedBy { it.second }
    }

    /**
     * The now-playing block, with the player's own icon. Styles (see [HudPrefs.musicLayout]):
     *  1 plain      [icon] TITLE [→] / ARTIST
     *  2 by         [icon] TITLE (BY) ARTIST [rings]   (BY: a small knocked-out word in a filled circle)
     *  3 copyright  [icon] TITLE (c) [ARTIST]
     *  4 progress   [icon] TITLE [three rings, filled to the track position] ARTIST
     *  5 cover      [icon] TITLE [colour bar | cover | colour bar] ARTIST
     * 0 picks one per track so it does not flicker between updates. Styles 2-5 take a single line when it fits
     * (first with the button, then without) and otherwise two: the title line, then the artist with the same ornament.
     * The button at the end is the arrow, a pause pill or the cassette pill ([HudPrefs.musicControl]).
     * Titles are cleaned of "(Official Video)" and friends.
     */
    fun music(c: Context): Block? {
        if (!(MusicState.hasSession && MusicState.playing)) return null
        val title = HudText.cleanTitle(MusicState.title).uppercase()
        if (title.isBlank()) return null
        val artist = HudText.cleanArtist(MusicState.artist).uppercase()
        val pkg = MusicState.pkg
        val t = tap(APP_PREFIX + pkg)
        val lead: Token = if (GlyphMask.forPackage(c, pkg) != null) Icon(AppIconKey(pkg), t) else GlyphToken(HudGlyph.MUSIC_NOTE, t)
        val control: Token = when (HudPrefs.musicControl(c)) {
            HudPrefs.CONTROL_PAUSE -> GlyphToken(HudGlyph.PAUSE, tap(MUSIC_TOGGLE))
            HudPrefs.CONTROL_CASSETTE -> GlyphToken(HudGlyph.CASSETTE, tap(MUSIC_TOGGLE))
            else -> GlyphToken(HudGlyph.FOLLOWUP, t)
        }
        if (artist.isBlank()) return Block("music", listOf(listOf(lead, Text(title, t), control)))

        val art = MusicState.art
        var style = HudPrefs.musicLayout(c)
        if (style == 0) style = 2 + Math.floorMod((title + artist).hashCode(), 4)     // 2..5; plain is only for when it is chosen
        if (style == 5 && art == null) style = 2

        val head = listOf(lead, Text(title, t))
        // [orn] sits between title and artist; [name] is the artist as the style writes it
        val orn: Token
        val name: Token
        val tail: List<Token>
        when (style) {
            2 -> { orn = Badge(BadgeShape.CIRCLE, "BY", t); name = Text(artist, t); tail = listOf(GlyphToken(HudGlyph.RINGS, t)) }
            3 -> { orn = GlyphToken(HudGlyph.COPYRIGHT, t); name = Text("[$artist]", t); tail = emptyList() }
            4 -> { orn = ProgressMark(MusicState.progress(), t); name = Text(artist, t); tail = emptyList() }
            5 -> { val f = artFocus(); orn = ArtStrip(art, f.first, f.second, t); name = Text(artist, t); tail = emptyList() }
            else -> { orn = Text("·"); name = Text(artist, t); tail = emptyList() }
        }
        val oneLine = if (style == 1) emptyList() else head + orn + name + tail
        val second: List<Token> = if (style == 1) listOf(name)
            else if (style == 2) listOf(name) + tail          // BY only makes sense inline; under the title the rings follow the artist
            else listOf(orn, name)
        val compact = listOf(lead, Text(title, t), orn, name, control)
        val folds = if (oneLine.isEmpty()) emptyList() else listOf(oneLine + control, oneLine)
        return Block("music", listOf(head + control, second), compact = compact, folds = folds)
    }

    /** Where the subject of the cover is (0..1), from ML Kit's cut-out: the centre of its opaque pixels. 0.5 / 0.5 until the cut-out is ready. */
    @Volatile private var focusKey: Bitmap? = null
    @Volatile private var focusValue = 0.5f to 0.5f

    private fun artFocus(): Pair<Float, Float> {
        val cut = MusicState.cutout ?: return 0.5f to 0.5f
        if (cut.isRecycled) return 0.5f to 0.5f
        if (cut === focusKey) return focusValue
        val n = 48
        val small = Bitmap.createScaledBitmap(cut, n, n, true)
        var sx = 0f; var sy = 0f; var sw = 0f
        for (y in 0 until n) for (x in 0 until n) {
            val a = Color.alpha(small.getPixel(x, y)) / 255f
            if (a > 0.2f) { sx += x * a; sy += y * a; sw += a }
        }
        if (small !== cut) small.recycle()
        focusValue = if (sw > 0f) ((sx / sw + 0.5f) / n) to ((sy / sw + 0.5f) / n) else (0.5f to 0.5f)
        focusKey = cut
        return focusValue
    }

    /** True when [text] is the track that is playing (so Smartspacer's own now-playing target can step aside). */
    fun isPlayingTitle(text: String): Boolean {
        if (!(MusicState.hasSession && MusicState.playing)) return false
        val t = text.trim()
        return t.equals(MusicState.title.trim(), ignoreCase = true) || t.equals(HudText.cleanTitle(MusicState.title), ignoreCase = true)
    }

    /**
     * [USB-C] FULL IN ⌊40MIN, or TO 80% IN when the charging policy holds the battery at 80 %. The system's estimate
     * (computeChargeTimeRemaining) is used when it has one and the target is 100 %; otherwise the time is worked out from the
     * charge counter and the current. Without any estimate: [USB-C] CHARGING (42%), so a plugged-in phone always shows it.
     */
    fun charging(c: Context): Block? {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) return null
        val bm = c.getSystemService(BatteryManager::class.java)
        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).let { l -> if (l < 0) -1 else l * 100 / bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        val limited = chargeLimited(bm) && level in 0..79
        val ms = if (!limited) bm.computeChargeTimeRemaining().takeIf { it > 0 } ?: estimateChargeMs(bm, 100)
                 else estimateChargeMs(bm, 80)
        if (ms in 1..(24L * 3_600_000L)) {
            val minutes = (ms / 60_000L).coerceAtLeast(1)
            val remaining = if (minutes >= 60) "${minutes / 60}H ${"%02d".format(minutes % 60)}M" else "${minutes}MIN"
            return Block("charging", listOf(listOf(GlyphToken(HudGlyph.USB_C), Text(if (limited) "TO 80% IN" else "FULL IN"), Timer(remaining))))
        }
        if (level < 0) return Block("charging", listOf(listOf(GlyphToken(HudGlyph.USB_C), Text("CHARGING"))))
        val word = if (chargeLimited(bm) && level >= 80) "HELD AT 80%" else "CHARGING"
        return Block("charging", listOf(listOf(GlyphToken(HudGlyph.USB_C), Text(word), OutlinePill("$level%"))))
    }

    /** True when the system's charging policy is something other than the default (adaptive / long-life: it stops at about 80 %). */
    private fun chargeLimited(bm: BatteryManager): Boolean {
        val p = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY) }.getOrDefault(0)
        return p != 0 && p != Int.MIN_VALUE && p != BatteryManager.CHARGING_POLICY_DEFAULT
    }

    /** Milliseconds until [target] percent from the charge counter (µAh) and the current (µA); -1 when the device reports neither. */
    private fun estimateChargeMs(bm: BatteryManager, target: Int): Long {
        fun prop(id: Int) = runCatching { bm.getIntProperty(id) }.getOrDefault(Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val cur = prop(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { Math.abs(it.toLong()) } ?: return -1
        val counter = prop(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong() ?: return -1
        val pct = prop(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return -1
        if (cur <= 0 || counter <= 0 || pct <= 0) return -1
        val microAmps = if (cur < 20_000) cur * 1000 else cur           // some devices report mA
        val remaining = counter * 100 / pct * target / 100 - counter     // µAh still to go
        if (remaining <= 0) return -1
        return remaining * 3_600_000L / microAmps
    }

    /** What the battery reports, for working out why a charging time does not show. */
    fun chargingDump(c: Context): String {
        val bi = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = c.getSystemService(BatteryManager::class.java)
        fun prop(name: String, id: Int) = "$name=" + runCatching { bm.getIntProperty(id) }.getOrElse { "error ${it.javaClass.simpleName}" }
        return listOf(
            "plugged=${bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)} status=${bi?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)} level=${bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/${bi?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)}",
            "computeChargeTimeRemaining=${runCatching { bm.computeChargeTimeRemaining() }.getOrElse { "error ${it.javaClass.simpleName}" }} ms",
            prop("charging_policy", BatteryManager.BATTERY_PROPERTY_CHARGING_POLICY) + " (default=${BatteryManager.CHARGING_POLICY_DEFAULT})",
            prop("current_now(uA)", BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            prop("current_average(uA)", BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            prop("charge_counter(uAh)", BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            prop("capacity(%)", BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "own estimate to 100%=${estimateChargeMs(bm, 100)} ms, to 80%=${estimateChargeMs(bm, 80)} ms",
        ).joinToString("\n")
    }

    /** Open-Meteo weather from Weather.kt, used when Smartspacer has none. */
    fun weather(c: Context, now: Long, zone: ZoneId, today: LocalDate): WeatherInfo? {
        val (temp, word, _) = Prefs.weather(c) ?: return null
        val look = HudRules.weatherLook(word, isNight(c, now, zone, today))
        return WeatherInfo(temp, true, look?.word, look?.glyphs.orEmpty())
    }

    fun isNight(c: Context, now: Long, zone: ZoneId, today: LocalDate): Boolean {
        val lat = Prefs.lat(c)
        val lon = Prefs.lon(c)
        return now < Astro.sunriseMs(today, zone, lat, lon) || now >= Astro.sunsetMs(today, zone, lat, lon)
    }
}
