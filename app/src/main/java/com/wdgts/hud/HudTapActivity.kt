package com.wdgts.hud

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wdgts.hud.engine.TapRef

/**
 * Invisible trampoline behind every widget tap. A widget can only hold our own PendingIntent, the target's own
 * intent may not survive process death, and this is where the haptic tick happens. Declared translucent, no
 * history, excluded from recents (see the manifest).
 */
class HudTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_TARGET)
        val tap = if (id == null) null else TapRef(id, intent.getStringExtra(EXTRA_ACTION))
        HudActions.run(this, tap) { finish() }
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_ACTION = "action"

        /** [tap] null = open settings. */
        fun pending(c: Context, tap: TapRef?, code: Int): PendingIntent {
            val i = Intent(c, HudTapActivity::class.java)
            if (tap != null) {
                i.setData(Uri.parse("hud://tap/${Uri.encode(tap.targetId)}/${Uri.encode(tap.actionId ?: "")}"))
                    .putExtra(EXTRA_TARGET, tap.targetId)
                    .putExtra(EXTRA_ACTION, tap.actionId)
            } else {
                i.setData(Uri.parse("hud://tap/none"))
            }
            return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
