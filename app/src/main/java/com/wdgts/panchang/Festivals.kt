package com.wdgts.panchang

/**
 * Tithi-at-sunrise rules keyed by AMANTA month (1 = Chaitra), paksha, day.
 * Approximate: real observance dates also depend on madhyahna / pradosh / nishita rules.
 */
object Festivals {
    private val TABLE: Map<Triple<Int, Boolean, Int>, String> = mapOf(
        Triple(1, false, 1) to "Gudi Padwa",
        Triple(1, false, 9) to "Ram Navami",
        Triple(1, false, 15) to "Hanuman Jayanti",
        Triple(2, false, 3) to "Akshaya Tritiya",
        Triple(4, false, 15) to "Guru Purnima",
        Triple(5, false, 5) to "Nag Panchami",
        Triple(5, false, 15) to "Raksha Bandhan",
        Triple(5, true, 8) to "Janmashtami",
        Triple(6, false, 4) to "Ganesh Chaturthi",
        Triple(7, false, 1) to "Navaratri",
        Triple(7, false, 10) to "Vijayadashami",
        Triple(7, true, 4) to "Karwa Chauth",
        Triple(7, true, 13) to "Dhanteras",
        Triple(7, true, 14) to "Naraka Chaturdashi",
        Triple(7, true, 15) to "Diwali",
        Triple(8, false, 2) to "Bhai Dooj",
        Triple(8, false, 6) to "Chhath Puja",
        Triple(11, true, 14) to "Maha Shivaratri",
        Triple(12, false, 15) to "Holika Dahan",
    )

    fun on(amantaMonth: Int, krishna: Boolean, day: Int): List<String> =
        listOfNotNull(TABLE[Triple(amantaMonth, krishna, day)])
}
