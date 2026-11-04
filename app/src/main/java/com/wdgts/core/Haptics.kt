package com.wdgts.core

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/** Predefined system haptics (they follow the user's touch-feedback setting and device motor). */
object Haptics {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)   // touch-class usage, allowed from a widget tap
        .build()

    fun tick(c: Context) = play(c, VibrationEffect.EFFECT_TICK)
    fun click(c: Context) = play(c, VibrationEffect.EFFECT_CLICK)
    fun heavy(c: Context) = play(c, VibrationEffect.EFFECT_HEAVY_CLICK)

    private fun play(c: Context, effect: Int) {
        runCatching {
            c.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createPredefined(effect), attrs)
        }
    }
}
