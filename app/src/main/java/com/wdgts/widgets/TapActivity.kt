package com.wdgts.widgets

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wdgts.core.Haptics
import com.wdgts.core.Prefs
import java.time.LocalDate

/** Invisible trampoline: log the streak, buzz, refresh the widget, open the app, vanish. */
class TapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val def = STREAKS.firstOrNull { it.id == intent.getStringExtra("streak") }
        if (def != null) {
            val today = LocalDate.now().toEpochDay()
            val wasDone = Prefs.streak(this, def.id, today, def.seed).doneToday
            Prefs.markDone(this, def.id, today, def.seed)
            if (wasDone) Haptics.tick(this) else Haptics.heavy(this)     // heavier for a newly completed streak
            StreaksWidget.refreshAll(this)
            val pkg = AppResolver.resolve(this, def)
            when {
                pkg.isNotEmpty() -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                def.url.isNotEmpty() -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(def.url)))
            }
        }
        finish()
    }
}
