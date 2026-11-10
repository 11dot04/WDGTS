package com.wdgts.hud.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure logic. The algorithms were cross-checked with a Python port; they have not been run in Gradle yet. */
class HudLogicTest {
    private val metrics = Metrics(bodyAdv = 5.5f, pillAdv = 5.02f)
    private val flow = FlowLayout(metrics) { 10f to Hud.PILL_H }
    private val maxW = Hud.WIDTH_UNITS - 2 * Hud.PAD_H

    private fun first(line: Line) = (line[0].token as Text).text

    @Test fun fingersSpellCountdowns() {
        assertEquals(listOf(HudGlyph.HAND_2, HudGlyph.HAND_5), HudRules.hands(7))
        assertEquals(listOf(HudGlyph.HAND_2, HudGlyph.HAND_5, HudGlyph.HAND_5), HudRules.hands(12))
        assertTrue(HudRules.hands(16).isEmpty())
        assertTrue(HudRules.hands(0).isEmpty())
    }

    @Test fun tempBarBuckets() {
        assertEquals(HudGlyph.TEMP_100_80, HudRules.tempGlyph(85f))
        assertEquals(HudGlyph.TEMP_70_60, HudRules.tempGlyph(79.9f))
        assertEquals(HudGlyph.TEMP_9_0, HudRules.tempGlyph(-5f))
        assertEquals(HudGlyph.TEMP_70_60, HudRules.tempGlyph(HudRules.toFahrenheit(21, true)))
    }

    @Test fun durationsAndCountdowns() {
        assertEquals(7, HudRules.exactMinutes("7MIN"))
        assertTrue(HudRules.isDuration("2H 30M"))
        assertEquals(7 to "", HudRules.countdown("in 7 min"))
        // "at 09:50" at 09:49 is one minute away; "9:50 PM" is not today's next hour at 09:49
        assertEquals(1, HudRules.clockCountdown("at 09:50", 9 * 60 + 49))
        assertNull(HudRules.clockCountdown("at 09:50", 8 * 60))
        assertNull(HudRules.clockCountdown("09:50", 10 * 60))
        assertEquals(10, HudRules.clockCountdown("9:50 PM", 21 * 60 + 40))
    }

    @Test fun weatherUsesTheNothingVocabulary() {
        assertEquals("CLOUDY", HudRules.weatherLook("PARTLY", night = false)?.word)
        assertEquals("SUNNY", HudRules.weatherLook("CLEAR", night = false)?.word)
        assertEquals(HudGlyph.MOON, HudRules.weatherLook("CLEAR", night = true)?.glyph)
        assertEquals("CLEAR", HudRules.weatherLook("CLEAR", night = true)?.word)
        assertEquals("RAIN", HudRules.weatherLook("RAINY", night = false)?.word)
        assertEquals("RAIN", HudRules.weatherLook("DRIZZLE", night = false)?.word)
        assertEquals("HAZE", HudRules.weatherLook("FOG", night = false)?.word)
        assertEquals("SNOW", HudRules.weatherLook("SNOWY", night = false)?.word)
        assertEquals("THUNDER", HudRules.weatherLook("STORM", night = false)?.word)
        assertNull(HudRules.weatherLook("—", night = false))
    }

    @Test fun greetingsAreRecognised() {
        assertTrue(HudRules.isGreeting("Good morning, Aryan"))
        assertTrue(!HudRules.isGreeting("Govardhan Puja"))
    }

    @Test fun weatherBlockLooksLikeTheReference() {
        val b = HudBlocks.weather(WeatherInfo(21, true, "CLEAR", HudGlyph.MOON))
        assertNull(b.pinLine)
        val t = b.segments.single()
        assertEquals(HudGlyph.TEMP_70_60, (t[0] as GlyphToken).glyph)
        assertEquals("21°", (t[1] as Text).text)
        assertTrue(t.last() is Bubble)
    }

    @Test fun sizingAddsAFifthLineOnlyWhenTheWidgetIsTallEnough() {
        assertEquals(4, HudSizing.of(1000, 380).lines)     // roughly 4x2
        assertEquals(5, HudSizing.of(1000, 600).lines)     // taller
        assertEquals(5, HudSizing.of(1000, 2000).lines)    // capped
        assertEquals(4, HudSizing.of(1000, 300).lines)     // too short for a fifth
    }

    @Test fun headerFitsOneLineAndReferenceRowsFit() {
        val header = listOf(Text("MON"), NumeralPill("9"), MonthPill("NOV"), Space, Text("12:27"), Cursor)
        val withChip = header + HudBlocks.temperatureChip(27, true)
        assertEquals(1, flow.layout(withChip, maxW, 2).size)

        val row3 = listOf(GlyphToken(HudGlyph.SPEAK), Text("WEEKLY DESIGN SYNC"), GlyphToken(HudGlyph.FOLLOWUP))
        val lines = flow.layout(row3, maxW, 1)
        assertEquals(1, lines.size)
        assertTrue(lines[0].last().let { it.x + it.w } <= maxW)
    }

    @Test fun overflowEndsInEllipsis() {
        val lines = flow.layout(listOf(Text("A VERY LONG EVENT TITLE THAT WILL NOT FIT ON ONE LINE AT ALL")), maxW, 1)
        assertEquals("…", (lines[0].last().token as Text).text)
    }

    @Test fun planUsesCompactLineAndKeepsAmbientOffTheFifthLine() {
        val packer = LinePacker(flow)
        val music = Block(
            "m", listOf(listOf(Text("LOVERBOY")), listOf(Text("A-WALL"))),
            compact = listOf(Text("LOVERBOY"), Text("—"), Text("A-WALL")),
        )
        // only one line left: the compact one-liner is used instead of dropping the artist
        val one = packer.pack(listOf(music), 1, maxW)
        assertEquals(3, one[0].size)

        // ambient blocks may not use body line 4 (the fifth line of the widget)
        val essential = Block("e", listOf(listOf(Text("A")), listOf(Text("B")), listOf(Text("C"))))
        val ambient = Block("a", listOf(listOf(Text("AMBIENT"))), optional = true)
        val out = packer.pack(listOf(essential, ambient), 4, maxW)
        assertEquals("C", first(out[2]))
        assertTrue(out[3].isEmpty())
        // essential blocks may
        val another = Block("x", listOf(listOf(Text("X"))))
        assertEquals("X", first(packer.pack(listOf(essential, another), 4, maxW)[3]))
    }

    @Test fun lineTapsFollowThePlan() {
        val tapW = TapRef("local", "SETTINGS")
        val tapC = TapRef("local", "CALLS")
        val a = Block("a", listOf(listOf(Text("A", tapW))))
        val b = Block("b", listOf(listOf(Text("TITLE", tapC)), listOf(Text("SUB", tapC))))
        assertEquals(listOf(tapW, tapC, tapC), HudPlan.lineTaps(listOf(a, b), 3))
    }
}
