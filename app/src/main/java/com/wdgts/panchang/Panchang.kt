package com.wdgts.panchang

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

enum class Paksha { SHUKLA, KRISHNA }

data class DayPanchang(
    val date: LocalDate,
    val sunriseMs: Long,
    val tithi: Int,              // 1..30 across the lunar month
    val paksha: Paksha,
    val pakshaDay: Int,          // 1..15
    val tithiName: String,
    val tithiEndMs: Long,
    val month: String,           // display name, already purnimanta/amanta-adjusted
    val adhika: Boolean,
    val elongation: Double,      // Moon - Sun at sunrise, for the phase disc
    val festivals: List<String>,
)

object Panchang {
    private val NAMES = listOf(
        "Pratipada", "Dwitiya", "Tritiya", "Chaturthi", "Panchami", "Shashthi", "Saptami",
        "Ashtami", "Navami", "Dashami", "Ekadashi", "Dwadashi", "Trayodashi", "Chaturdashi",
    )
    private val MONTHS = listOf(
        "Chaitra", "Vaishakha", "Jyeshtha", "Ashadha", "Shravana", "Bhadrapada",
        "Ashwin", "Kartika", "Margashirsha", "Pausha", "Magha", "Phalguna",
    )
    private const val MEAN_RATE = 12.19   // deg/day, average Moon-Sun elongation rate

    fun compute(date: LocalDate, zone: ZoneId, lat: Double, lon: Double, purnimanta: Boolean): DayPanchang {
        val riseMs = Astro.sunriseMs(date, zone, lat, lon)
        val jdR = Astro.jd(riseMs)
        val e = Astro.elongation(jdR)

        // the day's tithi is the one running at sunrise
        val tithi = floor(e / 12.0).toInt() + 1
        val endJd = Astro.solveElongation(tithi * 12.0, jdR + (tithi * 12.0 - e) / MEAN_RATE)
        val paksha = if (tithi <= 15) Paksha.SHUKLA else Paksha.KRISHNA
        val pd = if (tithi <= 15) tithi else tithi - 15
        val name = if (pd == 15) (if (paksha == Paksha.SHUKLA) "Purnima" else "Amavasya") else NAMES[pd - 1]

        // lunar month: named after the Sun's sidereal rashi at the new moon that began it
        val newMoon = Astro.solveElongation(0.0, jdR - e / MEAN_RATE)
        val nextNew = Astro.solveElongation(0.0, newMoon + 29.53)
        val r = rashi(newMoon)
        val amanta = r % 12 + 1                 // 1 = Chaitra .. 12 = Phalguna
        val adhika = r == rashi(nextNew)        // no sankranti inside the month
        val krishna = paksha == Paksha.KRISHNA
        val monthNo = if (purnimanta && krishna) amanta % 12 + 1 else amanta
        // approximation: adhika label is not shown on the purnimanta krishna fortnight
        val showAdhika = adhika && !(purnimanta && krishna)
        val month = (if (showAdhika) "Adhika " else "") + MONTHS[monthNo - 1]

        return DayPanchang(
            date, riseMs, tithi, paksha, pd, name, Astro.fromJd(endJd), month, showAdhika, e,
            Festivals.on(amanta, krishna, pd),
        )
    }

    private fun rashi(jd: Double): Int {
        val sidereal = (Astro.sunLongitude(jd) - Astro.ayanamsa(jd) + 720.0) % 360.0
        return (sidereal / 30.0).toInt() + 1    // 1 = Mesha .. 12 = Meena
    }
}
