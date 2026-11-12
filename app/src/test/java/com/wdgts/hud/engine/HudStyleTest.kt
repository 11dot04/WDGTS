package com.wdgts.hud.engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.*
class HudStyleTest {
    private val metrics = Metrics(bodyAdv = 5.5f, pillAdv = 5.02f, bodyWide = 9.0f, pillWide = 8.2f)
    private val flow = FlowLayout(metrics) { 10f to Hud.PILL_H }
    private val maxW = Hud.WIDTH_UNITS - 2 * Hud.PAD_H
    @Test fun wideCharactersAreMeasuredWide() {
        assertEquals(7 * 9.0f, metrics.body("雲を抜け風下へ"))
        assertEquals(2 * 9.0f + 3 * 5.5f, metrics.body("雲を ab"))
        assertEquals(5.5f * 3, metrics.body("ABC"))
    }
    @Test fun japaneseTitleDoesNotOverlapNeighbours() {
        val toks = listOf(Text("雲を抜け風下へ"), Text("-"), Text("私と"), Text("BEYOND"), Text("THE"), Text("SKY"), Text("EXTRA"))
        val line = flow.layout(toks, maxW, 1)[0]
        for (i in 1 until line.size) assertTrue(line[i].x >= line[i - 1].x + line[i - 1].w)
        assertTrue(line.last().let { it.x + it.w } <= maxW + 0.01f)
    }
    @Test fun longJapaneseWordIsCutByWidthNotCharCount() {
        val long = "あ".repeat(40)
        val l = flow.layout(listOf(Text(long)), maxW, 1)[0]
        val t = (l[0].token as Text).text
        assertTrue(t.endsWith("…"))
        assertTrue(metrics.body(t) <= maxW)
        assertTrue(t.length < 40)
    }
    @Test fun foldIsUsedOnlyWhenItFits() {
        val packer = LinePacker(flow)
        val two = listOf(listOf<Token>(Text("HALO"), Text("FAN")), listOf<Token>(Text("TAP TO OPEN")))
        val short = listOf<Token>(Text("HALO FAN"), GlyphToken(HudGlyph.STAR4), OutlinePill("ON"), GlyphToken(HudGlyph.FOLLOWUP))
        val long = listOf<Token>(Text("A".repeat(20)), Text("B".repeat(20)))
        val b = Block("t", two, folds = listOf(long, short))
        val out = packer.pack(listOf(b), 3, maxW)
        assertEquals(5, out[0].size)           // the short fold won (HALO, FAN, star, ON, arrow), the too-long one was skipped
        assertTrue(out[1].isEmpty())
        val b2 = Block("t", two, folds = listOf(long))
        val out2 = packer.pack(listOf(b2), 3, maxW)
        assertTrue(out2[1].isNotEmpty())       // nothing fits: two lines stay
    }
    @Test fun zoneNames() {
        val at = Instant.parse("2026-11-12T10:00:00Z")
        assertEquals("IST", HudZones.abbreviation(ZoneId.of("Asia/Kolkata"), at))
        assertEquals("EST", HudZones.abbreviation(ZoneId.of("America/New_York"), at))
        assertEquals("EDT", HudZones.abbreviation(ZoneId.of("America/New_York"), Instant.parse("2026-07-12T10:00:00Z")))
        assertEquals("+5:45", HudZones.offset(ZoneId.of("Asia/Kathmandu"), at))
        assertEquals("NPT", HudZones.abbreviation(ZoneId.of("Asia/Kathmandu"), at))
        assertTrue(HudZones.abbreviation(ZoneId.of("Asia/Thimphu"), at).isNotEmpty())
    }
    @Test fun badgeTextFits() { assertTrue(HudZones.abbreviation(ZoneId.of("Asia/Kolkata")).length <= 3) }
}
