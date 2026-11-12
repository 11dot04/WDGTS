package com.wdgts

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.wdgts.core.Fonts
import com.wdgts.core.Prefs
import com.wdgts.hud.HudPrefs
import com.wdgts.hud.HudSources
import com.wdgts.hud.HudWidget
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource.Access
import com.wdgts.widgets.STREAKS
import com.wdgts.widgets.Streaks
import com.wdgts.widgets.refreshEverything
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Plain, dependency-free settings screen. Opens from the launcher icon. */
class SettingsActivity : Activity() {
    private class StreakRow(
        val def: com.wdgts.widgets.StreakDef,
        val count: EditText, val shields: EditText, val earn: EditText, val cap: EditText, val grace: EditText, val reset: Switch,
    )

    private lateinit var ssStatus: TextView
    private lateinit var ssButton: Button
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val today = LocalDate.now().toEpochDay()

        fun header(t: String) = col.addView(TextView(this).apply { text = t; textSize = 18f; setPadding(0, pad, 0, 0) })
        fun note(t: String) = col.addView(TextView(this).apply { text = t; textSize = 12f })
        fun field(hint: String, value: String, number: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; setText(value)
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            col.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        header("Fonts")
        val gsf = runCatching { assets.open("fonts/display.ttf").close(); true }.getOrDefault(false)
        val rf = runCatching { assets.open("fonts/roboto_flex.ttf").close(); true }.getOrDefault(false)
        val gm = runCatching { assets.list("fonts").orEmpty().any { it.contains("geist", true) && it.contains("mono", true) } }.getOrDefault(false)
        note("Google Sans Flex: ${if (gsf) "bundled" else "MISSING, using the system font"}\n" +
            "Roboto Flex: ${if (rf) "bundled" else "MISSING, music titles use other faces"}\n" +
            "Geist Mono (HUD): ${if (gm) "bundled" else "MISSING, the HUD uses the app's mono font"}")
        Fonts.display(this)
        note("Axes in use: " + (Fonts.fvar ?: "none (variable font not loaded)"))

        header("Panchang")
        val lat = field("Latitude", Prefs.lat(this).toString(), true)
        val lon = field("Longitude", Prefs.lon(this).toString(), true)
        val purni = Switch(this).apply { text = "Purnimanta month names"; isChecked = Prefs.purnimanta(this@SettingsActivity) }
        col.addView(purni)

        header("Countdown")
        val start = field("Start (yyyy-mm-dd)", LocalDate.ofEpochDay(Prefs.cdStart(this)).toString())
        val end = field("End (yyyy-mm-dd)", LocalDate.ofEpochDay(Prefs.cdEnd(this)).toString())
        val quote = field("Quote", Prefs.cdQuote(this))

        header("Team match (ESPN)")
        val team = field("ESPN team id (359 = Arsenal)", Prefs.teamId(this))
        val tag = field("Home tag (e.g. N5)", Prefs.homeTag(this))

        header("Streak rules")
        note("Per tile: count, shields held, shields earned per completed day, max shields, free missed days, and whether missed days reset it. With auto-reset off the count simply waits for you.")
        val rows = STREAKS.map { def ->
            val v = Streaks.state(this, def, today)
            val r = Streaks.rule(this, def)
            col.addView(TextView(this).apply { text = def.label; setPadding(0, pad / 2, 0, 0) })
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun num(hint: String, value: Int) = EditText(this).apply {
                this.hint = hint; setText(value.toString()); inputType = InputType.TYPE_CLASS_NUMBER
                line.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val cnt = num("count", v.count); val sh = num("shields", v.shields)
            val earn = num("earn/day", r.earn); val cap = num("max", r.cap); val grace = num("free", r.grace)
            col.addView(line)
            val sw = Switch(this).apply { text = "auto-reset on misses"; isChecked = r.autoReset }
            col.addView(sw)
            StreakRow(def, cnt, sh, earn, cap, grace, sw)
        }

        val save = Button(this).apply {
            text = "Save and refresh widgets"
            setOnClickListener {
                runCatching {
                    Prefs.setLocation(this@SettingsActivity, lat.text.toString().toDouble(), lon.text.toString().toDouble())
                    Prefs.setPurnimanta(this@SettingsActivity, purni.isChecked)
                    Prefs.setCountdown(
                        this@SettingsActivity,
                        LocalDate.parse(start.text.toString().trim()).toEpochDay(),
                        LocalDate.parse(end.text.toString().trim()).toEpochDay(),
                        quote.text.toString(),
                    )
                    Prefs.setTeamId(this@SettingsActivity, team.text.toString().trim())
                    Prefs.setHomeTag(this@SettingsActivity, tag.text.toString().trim())
                    for (r in rows) {
                        fun i(e: EditText) = e.text.toString().trim().toIntOrNull() ?: 0
                        Streaks.setRule(this@SettingsActivity, r.def, Streaks.Rule(i(r.earn), i(r.cap), i(r.grace), r.reset.isChecked))
                        Streaks.setCount(this@SettingsActivity, r.def, i(r.count), i(r.shields))
                    }
                    refreshEverything(this@SettingsActivity)
                }.onFailure { Toast.makeText(this@SettingsActivity, "Check the values: ${it.message}", Toast.LENGTH_LONG).show() }
                    .onSuccess { Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show() }
            }
        }
        col.addView(save)

        header("Permissions")
        note("Music and HUD read your media session, notifications (missed calls, counts) and screen state through notification access.")
        col.addView(Button(this).apply {
            text = "Grant notification access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        note("The HUD shows your next calendar event.")
        col.addView(Button(this).apply {
            text = "Grant calendar access"
            setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 7) }
        })

        header("Smartspacer")
        note("The HUD also shows your Smartspacer targets (calendar, flights, commute and so on) once this app has access.")
        ssStatus = TextView(this).apply { textSize = 12f }
        col.addView(ssStatus)
        ssButton = Button(this).apply {
            text = "Grant Smartspacer access"
            setOnClickListener { HudSmartspacerSource.requestAccess(this@SettingsActivity) }
        }
        col.addView(ssButton)
        col.addView(Button(this).apply {
            text = "Copy Smartspacer targets (for debugging)"
            setOnClickListener {
                val dump = HudSmartspacerSource.dump()
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Smartspacer targets", dump))
                android.widget.Toast.makeText(this@SettingsActivity, "Copied ${dump.length} characters", android.widget.Toast.LENGTH_SHORT).show()
            }
        })

        header("HUD")
        fun radios(title: String, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
            note(title)
            val group = android.widget.RadioGroup(this)
            labels.forEachIndexed { i, l ->
                group.addView(android.widget.RadioButton(this).apply { text = l; id = 7000 + i })
            }
            group.check(7000 + current)
            group.setOnCheckedChangeListener { _, id ->
                onPick(id - 7000)
                HudWidget().refreshAll(applicationContext)
            }
            col.addView(group)
        }
        radios(
            "Between the month and the time (the Nothing clock puts a time-zone burst there).",
            listOf("Nothing", "Sun or moon (day / night)", "Time zone, always", "Time zone, only away from home"),
            HudPrefs.badgeMode(this),
        ) { HudPrefs.setBadgeMode(this, it) }
        col.addView(android.widget.CheckBox(this).apply {
            text = "Sun/moon switches at sunrise and sunset (instead of 06:00 and 18:00)"
            isChecked = HudPrefs.dayNightBySun(this@SettingsActivity)
            setOnCheckedChangeListener { _, on ->
                HudPrefs.setDayNightBySun(this@SettingsActivity, on)
                HudWidget().refreshAll(applicationContext)
            }
        })
        radios(
            "Now playing style (one line when it fits, otherwise the artist goes under the title).",
            listOf(
                "Vary by track", "Title, then artist", "TITLE (BY) ARTIST + rings", "TITLE © [ARTIST]",
                "TITLE + progress rings + ARTIST", "TITLE + album-art strip + ARTIST",
            ),
            HudPrefs.musicLayout(this),
        ) { HudPrefs.setMusicLayout(this, it) }
        radios(
            "Now playing button.",
            listOf("Arrow (opens the player)", "Pause pill", "Cassette pill (pause)"),
            HudPrefs.musicControl(this),
        ) { HudPrefs.setMusicControl(this, it) }
        col.addView(Button(this).apply {
            text = "Apps that get a notification chip"
            setOnClickListener {
                val apps = HudSources.candidateApps(this@SettingsActivity)
                val chosen = (HudPrefs.chipApps(this@SettingsActivity) ?: HudSources.defaultChipApps()).toMutableSet()
                android.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Notification chips")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), BooleanArray(apps.size) { apps[it].first in chosen }) { _, i, on ->
                        if (on) chosen += apps[i].first else chosen -= apps[i].first
                    }
                    .setPositiveButton("Save") { _, _ ->
                        HudPrefs.setChipApps(this@SettingsActivity, chosen)
                        HudWidget().refreshAll(applicationContext)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        refreshSmartspacer()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HudSmartspacerSource.REQUEST_PERMISSION) {
            HudSmartspacerSource.restart(this)
            refreshSmartspacer()
        }
    }

    private fun refreshSmartspacer() {
        ui.launch {
            val access = HudSmartspacerSource.checkAccess(this@SettingsActivity)
            ssStatus.text = when (access) {
                Access.NOT_INSTALLED -> "Smartspacer: not installed"
                Access.NEEDS_PERMISSION -> "Smartspacer: installed, access not granted"
                Access.GRANTED -> "Smartspacer: connected (${HudSmartspacerSource.targets.size} targets right now)"
                Access.UNKNOWN -> "Smartspacer: could not reach it. Open Smartspacer once, then come back."
            }
            ssButton.isEnabled = access == Access.NEEDS_PERMISSION || access == Access.UNKNOWN
            if (access == Access.GRANTED) {
                // no-op if the session is already running
                HudSmartspacerSource.start(applicationContext) { HudWidget().refreshAll(applicationContext) }
            }
        }
    }
}
