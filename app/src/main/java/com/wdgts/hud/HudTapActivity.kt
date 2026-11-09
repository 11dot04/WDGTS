package com.wdgts.hud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource

/**
 * Invisible trampoline behind a widget tap on a Smartspacer line. A widget can only hold our own
 * PendingIntent, and the target's intent may not survive process death, so this looks the target up
 * again and fires it. Declared translucent, no history, excluded from recents (see README).
 */
class HudTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_TARGET)
        if (id == null) { finish(); return }
        HudSmartspacerSource.fire(this, TapRef(id, intent.getStringExtra(EXTRA_ACTION))) { finish() }
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_ACTION = "action"

        fun pending(c: Context, tap: TapRef, code: Int): PendingIntent {
            val i = Intent(c, HudTapActivity::class.java)
                .setData(Uri.parse("hud://tap/${Uri.encode(tap.targetId)}/${Uri.encode(tap.actionId ?: "")}"))
                .putExtra(EXTRA_TARGET, tap.targetId)
                .putExtra(EXTRA_ACTION, tap.actionId)
            return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
