package com.wdgts

import android.app.Activity
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
import com.wdgts.core.Prefs
import com.wdgts.widgets.refreshEverything
import java.time.LocalDate

/** Plain, dependency-free settings screen. Opens from the launcher icon. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun header(t: String) = col.addView(TextView(this).apply { text = t; textSize = 18f; setPadding(0, pad, 0, 0) })
        fun field(hint: String, value: String, number: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; setText(value)
            if (number) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            col.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

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
                    refreshEverything(this@SettingsActivity)
                }.onFailure { Toast.makeText(this@SettingsActivity, "Check the values: ${it.message}", Toast.LENGTH_LONG).show() }
                    .onSuccess { Toast.makeText(this@SettingsActivity, "Saved", Toast.LENGTH_SHORT).show() }
            }
        }
        col.addView(save)

        header("Music widgets")
        col.addView(TextView(this).apply { text = "They read the active media session, which needs notification access." })
        col.addView(Button(this).apply {
            text = "Grant notification access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }
}
