package com.wdgts.widgets

import android.app.Activity
import android.os.Bundle
import com.wdgts.core.Prefs
import java.time.LocalDate

/** Invisible trampoline: log the streak, refresh the widget, open the app, vanish. */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val def = STREAKS.firstOrNull { it.id == intent.getStringExtra("streak") }
        if (def != null) {
            Prefs.markDone(this, def.id, LocalDate.now().toEpochDay(), def.seed)
            StreaksWidget.refreshAll(this)
            if (def.pkg.isNotEmpty()) {
                packageManager.getLaunchIntentForPackage(def.pkg)?.let { startActivity(it) }
            }
        }
        finish()
    }
}
