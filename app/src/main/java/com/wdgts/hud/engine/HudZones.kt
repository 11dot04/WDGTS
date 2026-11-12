package com.wdgts.hud.engine

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The short zone name for the time-zone burst: IST, EST, PST ... The JDK / ICU short names are unreliable
 * (Android answers "GMT+05:30" for Asia/Kolkata), so the common zones are spelled out here, with daylight-saving
 * variants. Anything unknown falls back to the platform's short name if it is a real abbreviation, then to the offset.
 */
object HudZones {
    private val T = HashMap<String, Pair<String, String>>()

    private fun put(std: String, dst: String, vararg ids: String) { for (i in ids) T[i] = std to dst }

    init {
        put("IST", "IST", "Asia/Kolkata", "Asia/Calcutta")
        put("PKT", "PKT", "Asia/Karachi")
        put("NPT", "NPT", "Asia/Kathmandu", "Asia/Katmandu")
        put("GST", "GST", "Asia/Dubai", "Asia/Muscat")
        put("AST", "AST", "Asia/Riyadh", "Asia/Kuwait", "Asia/Baghdad", "Asia/Qatar")
        put("IRST", "IRDT", "Asia/Tehran")
        put("SGT", "SGT", "Asia/Singapore")
        put("HKT", "HKT", "Asia/Hong_Kong")
        put("CST", "CST", "Asia/Shanghai", "Asia/Taipei", "Asia/Macau")
        put("JST", "JST", "Asia/Tokyo")
        put("KST", "KST", "Asia/Seoul")
        put("ICT", "ICT", "Asia/Bangkok", "Asia/Ho_Chi_Minh", "Asia/Saigon")
        put("WIB", "WIB", "Asia/Jakarta")
        put("PHT", "PHT", "Asia/Manila")
        put("MYT", "MYT", "Asia/Kuala_Lumpur")
        put("AEST", "AEDT", "Australia/Sydney", "Australia/Melbourne", "Australia/Canberra", "Australia/Hobart")
        put("AEST", "AEST", "Australia/Brisbane")
        put("ACST", "ACDT", "Australia/Adelaide")
        put("AWST", "AWST", "Australia/Perth")
        put("NZST", "NZDT", "Pacific/Auckland")
        put("GMT", "BST", "Europe/London")
        put("GMT", "IST", "Europe/Dublin")
        put("WET", "WEST", "Europe/Lisbon", "Atlantic/Canary")
        put("CET", "CEST", "Europe/Paris", "Europe/Berlin", "Europe/Rome", "Europe/Madrid", "Europe/Amsterdam",
            "Europe/Brussels", "Europe/Vienna", "Europe/Zurich", "Europe/Stockholm", "Europe/Oslo", "Europe/Copenhagen",
            "Europe/Prague", "Europe/Warsaw", "Europe/Budapest")
        put("EET", "EEST", "Europe/Athens", "Europe/Helsinki", "Europe/Kiev", "Europe/Kyiv", "Europe/Bucharest",
            "Europe/Sofia", "Africa/Cairo", "Asia/Beirut")
        put("MSK", "MSK", "Europe/Moscow")
        put("TRT", "TRT", "Europe/Istanbul")
        put("SAST", "SAST", "Africa/Johannesburg")
        put("WAT", "WAT", "Africa/Lagos")
        put("EAT", "EAT", "Africa/Nairobi", "Africa/Addis_Ababa")
        put("EST", "EDT", "America/New_York", "America/Toronto", "America/Detroit")
        put("CST", "CDT", "America/Chicago", "America/Winnipeg", "America/Mexico_City")
        put("MST", "MDT", "America/Denver", "America/Edmonton", "America/Boise")
        put("MST", "MST", "America/Phoenix")
        put("PST", "PDT", "America/Los_Angeles", "America/Vancouver", "America/Tijuana")
        put("AKST", "AKDT", "America/Anchorage")
        put("HST", "HST", "Pacific/Honolulu")
        put("BRT", "BRT", "America/Sao_Paulo")
        put("ART", "ART", "America/Argentina/Buenos_Aires", "America/Buenos_Aires")
        put("UTC", "UTC", "UTC", "Etc/UTC", "Etc/GMT", "GMT")
    }

    fun abbreviation(zone: ZoneId, at: Instant = Instant.now(), locale: Locale = Locale.ENGLISH): String {
        T[zone.id]?.let { (std, dst) -> return if (zone.rules.isDaylightSavings(at)) dst else std }
        val s = zone.getDisplayName(TextStyle.SHORT, locale).uppercase(locale)
        if (!s.startsWith("GMT") && !s.startsWith("UTC") && !s.startsWith("+") && !s.startsWith("-") && s.length in 2..5 && s.all { it.isLetter() }) return s
        return offset(zone, at)
    }

    /** +5:30, -8, +0 */
    fun offset(zone: ZoneId, at: Instant = Instant.now()): String {
        val secs = zone.rules.getOffset(at).totalSeconds
        val h = Math.abs(secs) / 3600
        val m = Math.abs(secs % 3600) / 60
        return (if (secs >= 0) "+" else "-") + h + (if (m > 0) ":" + "%02d".format(m) else "")
    }
}
