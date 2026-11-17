package com.wdgts

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.wdgts.hud.engine.smartspacer.HudSmartspacerSource
import com.wdgts.ui.WdgtsApp
import com.wdgts.ui.WdgtsTheme

/**
 * The app's own screen, opened from the launcher icon. The look lives in com.wdgts.ui (home poster and tile grid,
 * one screen per widget); every setting there writes straight to the same prefs the widgets read.
 */
class SettingsActivity : ComponentActivity() {
    /** Bumped when Smartspacer's permission dialog comes back, so the Access screen re-checks. */
    private var ssTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WdgtsTheme { WdgtsApp(ssTick) } }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HudSmartspacerSource.REQUEST_PERMISSION) {
            HudSmartspacerSource.restart(this)
            ssTick++
        }
    }
}
