package com.wdgts.panchang

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sun/Moon longitudes (Meeus, Astronomical Algorithms ch. 25 & 47, truncated) + sunrise.
 * Good to roughly a few arc-minutes of Moon longitude -> tithi ends within a few minutes.
 * VALIDATE against Drik Panchang for a few dates before trusting the minutes.
 */
object Astro {
    private const val RAD = PI / 180.0

    fun jd(ms: Long) = ms / 86_400_000.0 + 2_440_587.5
    fun fromJd(jd: Double): Long = ((jd - 2_440_587.5) * 86_400_000.0).roundToLong()

    private fun norm(d: Double): Double { var x = d % 360.0; if (x < 0) x += 360.0; return x }
    private fun wrap180(d: Double): Double = norm(d + 180.0) - 180.0
    private fun s(deg: Double) = sin(deg * RAD)

    /** Apparent tropical longitude of the Sun, degrees. */
    fun sunLongitude(jd: Double): Double {
        val t = (jd - 2_451_545.0) / 36_525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = 357.52911 + 35999.05029 * t - 0.0001537 * t * t
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * s(m) +
            (0.019993 - 0.000101 * t) * s(2 * m) + 0.000289 * s(3 * m)
        val omega = 125.04 - 1934.136 * t
        return norm(l0 + c - 0.00569 - 0.00478 * s(omega))
    }

    // columns: D, M, M', F, coefficient (1e-6 deg)
    private val TERMS = arrayOf(
        intArrayOf(0, 0, 1, 0, 6288774), intArrayOf(2, 0, -1, 0, 1274027), intArrayOf(2, 0, 0, 0, 658314),
        intArrayOf(0, 0, 2, 0, 213618), intArrayOf(0, 1, 0, 0, -185116), intArrayOf(0, 0, 0, 2, -114332),
        intArrayOf(2, 0, -2, 0, 58793), intArrayOf(2, -1, -1, 0, 57066), intArrayOf(2, 0, 1, 0, 53322),
        intArrayOf(2, -1, 0, 0, 45758), intArrayOf(0, 1, -1, 0, -40923), intArrayOf(1, 0, 0, 0, -34720),
        intArrayOf(0, 1, 1, 0, -30383), intArrayOf(2, 0, 0, -2, 15327), intArrayOf(0, 0, 1, 2, -12528),
        intArrayOf(0, 0, 1, -2, 10980), intArrayOf(4, 0, -1, 0, 10675), intArrayOf(0, 0, 3, 0, 10034),
        intArrayOf(4, 0, -2, 0, 8548), intArrayOf(2, 1, -1, 0, -7888), intArrayOf(2, 1, 0, 0, -6766),
        intArrayOf(1, 0, -1, 0, -5163), intArrayOf(1, 1, 0, 0, 4987), intArrayOf(2, -1, 1, 0, 4036),
        intArrayOf(2, 0, 2, 0, 3994), intArrayOf(4, 0, 0, 0, 3861), intArrayOf(2, 0, -3, 0, 3665),
        intArrayOf(0, 1, -2, 0, -2689), intArrayOf(2, 0, -1, 2, -2602), intArrayOf(2, -1, -2, 0, 2390),
        intArrayOf(1, 0, 1, 0, -2348), intArrayOf(2, -2, 0, 0, 2236), intArrayOf(0, 1, 2, 0, -2120),
        intArrayOf(0, 2, 0, 0, -2069), intArrayOf(2, -2, -1, 0, 2048), intArrayOf(2, 0, 1, -2, -1773),
        intArrayOf(2, 0, 0, 2, -1595), intArrayOf(4, -1, -1, 0, 1215), intArrayOf(0, 0, 2, 2, -1110),
        intArrayOf(3, 0, -1, 0, -892), intArrayOf(2, 1, 1, 0, -810), intArrayOf(4, -1, -2, 0, 759),
        intArrayOf(0, 2, -1, 0, -713), intArrayOf(2, 2, -1, 0, -700), intArrayOf(2, 1, -2, 0, 691),
        intArrayOf(2, -1, 0, -2, 596), intArrayOf(4, 0, 1, 0, 549), intArrayOf(0, 0, 4, 0, 537),
        intArrayOf(4, -1, 0, 0, 520), intArrayOf(1, 0, -2, 0, -487), intArrayOf(2, 1, 0, -2, -399),
        intArrayOf(0, 0, 2, -2, -381), intArrayOf(1, 1, 1, 0, 351), intArrayOf(3, 0, -2, 0, -340),
        intArrayOf(4, 0, -3, 0, 330), intArrayOf(2, -1, 2, 0, 327), intArrayOf(0, 2, 1, 0, -323),
        intArrayOf(1, 1, -1, 0, 299), intArrayOf(2, 0, 3, 0, 294),
    )

    /** Apparent tropical longitude of the Moon, degrees. */
    fun moonLongitude(jd: Double): Double {
        val t = (jd - 2_451_545.0) / 36_525.0
        val t2 = t * t; val t3 = t2 * t; val t4 = t3 * t
        val lp = norm(218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0)
        val d = norm(297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0)
        val m = norm(357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0)
        val mp = norm(134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0)
        val f = norm(93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0)
        val a1 = norm(119.75 + 131.849 * t)
        val a2 = norm(53.09 + 479264.290 * t)
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        var sum = 0.0
        for (r in TERMS) {
            var c = r[4].toDouble()
            when (abs(r[1])) { 1 -> c *= e; 2 -> c *= e * e }
            sum += c * s(r[0] * d + r[1] * m + r[2] * mp + r[3] * f)
        }
        sum += 3958 * s(a1) + 1962 * s(lp - f) + 318 * s(a2)
        val nut = -0.00478 * s(125.04 - 1934.136 * t)
        return norm(lp + sum / 1e6 + nut)
    }

    /** Lahiri (Chitrapaksha) ayanamsa, mean. */
    fun ayanamsa(jd: Double) = 23.85306 + 0.013969 * ((jd - 2_451_545.0) / 365.25)

    fun elongation(jd: Double) = norm(moonLongitude(jd) - sunLongitude(jd))

    /** JD at which Moon-Sun elongation equals [target] degrees, nearest [nearJd]. */
    fun solveElongation(target: Double, nearJd: Double): Double {
        var t = nearJd
        repeat(14) { t -= wrap180(elongation(t) - target % 360.0) / 12.19 }
        return t
    }

    private fun declEot(jd: Double): Pair<Double, Double> {
        val t = (jd - 2_451_545.0) / 36_525.0
        val lam = sunLongitude(jd)
        val eps = (23.439291 - 0.0130042 * t) * RAD
        val decl = asin(sin(eps) * s(lam)) / RAD
        val ra = norm(atan2(cos(eps) * s(lam), cos(lam * RAD)) / RAD)
        val l0 = norm(280.46646 + 36000.76983 * t)
        return decl to 4.0 * wrap180(l0 - 0.0057183 - ra)   // minutes
    }

    /** Sunrise (upper limb, standard refraction) for a local date, as epoch ms. */
    fun sunriseMs(date: LocalDate, zone: ZoneId, lat: Double, lon: Double): Long {
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val zHours = zone.rules.getOffset(Instant.ofEpochMilli(midnight + 43_200_000L)).totalSeconds / 3600.0
        var guess = midnight + 6 * 3_600_000L
        repeat(3) {
            val (decl, eot) = declEot(jd(guess))
            val cosH = (sin(-0.833 * RAD) - sin(lat * RAD) * sin(decl * RAD)) /
                (cos(lat * RAD) * cos(decl * RAD))
            if (cosH < -1.0 || cosH > 1.0) return guess      // polar day/night fallback
            val h = acos(cosH) / RAD
            val noonClock = 12.0 + zHours - lon / 15.0 - eot / 60.0
            guess = midnight + ((noonClock - h / 15.0) * 3_600_000.0).toLong()
        }
        return guess
    }
}
