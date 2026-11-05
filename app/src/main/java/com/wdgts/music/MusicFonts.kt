package com.wdgts.music

import android.content.Context
import android.graphics.Typeface
import java.util.Random

/**
 * Per-track typography. Google's open variable families for Material 3 Expressive are Google Sans Flex
 * (wght 100-900, wdth 75-125, ROND 0-100, opsz 8-144, GRAD, slnt) and Roboto Flex (wght 100-1000,
 * wdth 25-151, opsz, GRAD, slnt, parametric axes). Each track seeds a font and a point in the axis space,
 * so the same song always looks the same and the next one looks different.
 * Fonts: assets/fonts/display.ttf (Google Sans Flex) and assets/fonts/roboto_flex.ttf. Missing -> system faces.
 */
object MusicFonts {
    class Pick(val tf: Typeface, val fvar: String?, val upper: Boolean)

    private val cache = HashMap<String, Typeface>()
    private fun asset(c: Context, name: String): Typeface? =
        cache[name] ?: runCatching { Typeface.createFromAsset(c.assets, "fonts/$name") }.getOrNull()?.also { cache[name] = it }

    private fun rnd(r: Random, lo: Int, hi: Int) = lo + r.nextInt(hi - lo + 1)

    fun pick(c: Context, seed: Long): Pick {
        val r = Random(seed)
        val options = ArrayList<Pick>()
        asset(c, "display.ttf")?.let {
            options += Pick(
                it, "'wght' ${rnd(r, 600, 900)}, 'wdth' ${rnd(r, 75, 125)}, 'ROND' ${rnd(r, 0, 100)}, " +
                    "'opsz' 144, 'slnt' ${-rnd(r, 0, 10)}", false,
            )
        }
        asset(c, "roboto_flex.ttf")?.let {
            options += Pick(
                it, "'wght' ${rnd(r, 500, 1000)}, 'wdth' ${rnd(r, 40, 151)}, 'opsz' 144, " +
                    "'GRAD' ${rnd(r, -40, 100)}, 'slnt' ${-rnd(r, 0, 10)}, " +
                    // the parametric axes: stroke weight, counter width, ascender/descender/cap/lowercase/figure heights
                    "'XOPQ' ${rnd(r, 60, 140)}, 'XTRA' ${rnd(r, 400, 560)}, 'YOPQ' ${rnd(r, 40, 110)}, " +
                    "'YTAS' ${rnd(r, 700, 850)}, 'YTDE' ${-rnd(r, 110, 260)}, 'YTFI' ${rnd(r, 600, 780)}, " +
                    "'YTLC' ${rnd(r, 430, 560)}, 'YTUC' ${rnd(r, 600, 750)}", true,
            )
        }
        options += Pick(Typeface.create(Typeface.SERIF, 700, false), null, false)
        options += Pick(Typeface.create(Typeface.SERIF, 600, true), null, false)
        options += Pick(Typeface.create(Typeface.create("sans-serif-condensed", Typeface.NORMAL), 800, false), null, true)
        return options[r.nextInt(options.size)]
    }
}
