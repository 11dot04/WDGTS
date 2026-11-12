package com.wdgts.hud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import com.wdgts.SettingsActivity
import com.wdgts.core.Haptics
import com.wdgts.hud.engine.TapRef
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.music.MusicState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything a tap can do. Every tap goes through [HudTapActivity], which calls [run]: one tick, then the action. */
object HudActions {
    fun run(ctx: Context, tap: TapRef?, done: () -> Unit) {
        Haptics.tick(ctx)
        if (tap == null || tap.targetId == HudSources.LOCAL) {
            local(ctx, tap?.actionId ?: HudSources.SETTINGS)
            done()
        } else {
            HudSmartspacerSource.fire(ctx, tap) { done() }
        }
    }

    private fun start(ctx: Context, i: Intent) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun local(ctx: Context, action: String) {
        when {
            action == HudSources.CALLS -> start(ctx, Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE))
            action == HudSources.DISMISS_LOOK -> {
                HudSignals.lookDismissedAt = System.currentTimeMillis()
                HudWidget().refreshAll(ctx.applicationContext)
            }
            action == HudSources.MUSIC_TOGGLE -> MusicState.controller?.transportControls?.let {
                if (MusicState.playing) it.pause() else it.play()
            }
            action == HudSources.DRIK -> start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.drikpanchang.com/panchang/day-panchang.html?date=" +
                    LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US)))))
            action.startsWith(HudSources.APP_PREFIX) -> {
                val pkg = action.removePrefix(HudSources.APP_PREFIX)
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) start(ctx, launch) else start(ctx, Intent(ctx, SettingsActivity::class.java))
            }
            else -> start(ctx, Intent(ctx, SettingsActivity::class.java))
        }
    }
}
