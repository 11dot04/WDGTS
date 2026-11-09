package com.wdgts.hud.engine

/** A token positioned on a line. x and w are in design units, relative to the content edge. */
data class Placed(val token: Token, val x: Float, val w: Float)
typealias Line = List<Placed>

/** Pure Kotlin (no Android types) so it can be unit tested on the JVM. */
class FlowLayout(
    private val m: Metrics,
    private val glyphSize: (HudGlyph) -> Pair<Float, Float>,
) {
    fun width(t: Token): Float = when (t) {
        is Text -> m.body(t.text)
        is NumeralPill -> Hud.NUMERAL_W
        is MonthPill -> Hud.MONTH_W
        is OutlinePill -> m.pill(t.text) + 2 * Hud.OUTLINE_PAD
        is Bubble -> Hud.BUBBLE_PAD_L + m.pill(t.word) + Hud.BUBBLE_PAD_R
        is GlyphToken -> glyphSize(t.glyph).first
        is Icon -> Hud.ICON_W
        is Timer -> Hud.TIMER_LEAD + m.body(t.text)
        is Cursor -> Hud.CURSOR_W
        is Space -> m.bodyAdv
    }

    private fun gap(a: Token, b: Token): Float = when {
        a is Space || b is Space -> 0f
        b is Cursor -> 0.7f
        a is NumeralPill && b is MonthPill -> 0.4f
        a is Text && b is NumeralPill -> 2f
        a is Text && b is GlyphToken && b.glyph.isUnit -> 0.4f
        a is Text && b is Text -> m.bodyAdv
        a is Icon && b is Text -> 2f
        a is OutlinePill && b is OutlinePill -> 1.6f
        a is GlyphToken && b is GlyphToken && a.glyph.isHand && b.glyph.isHand -> 3.5f
        else -> 2.6f
    }

    private fun fit(word: String, maxW: Float): String {
        if (m.body(word) <= maxW) return word
        val n = ((maxW - m.body("…")) / m.bodyAdv).toInt().coerceAtLeast(1)
        return word.take(n) + "…"
    }

    private fun atoms(tokens: List<Token>, maxW: Float): List<Token> = tokens.flatMap { t ->
        if (t is Text) {
            t.text.split(' ').filter { it.isNotEmpty() }.map { Text(fit(it, maxW), t.tap) }
        } else {
            listOf(t)
        }
    }

    /** Wraps [tokens] into at most [maxLines] lines no wider than [maxW]; overflow ends in "…". */
    fun layout(tokens: List<Token>, maxW: Float, maxLines: Int): List<Line> {
        if (maxLines <= 0) return emptyList()
        val lines = ArrayList<List<Placed>>()
        var cur = ArrayList<Placed>()
        var x = 0f
        var prev: Token? = null
        for (a in atoms(tokens, maxW)) {
            val w = width(a)
            val g = if (prev == null || cur.isEmpty()) 0f else gap(prev, a)
            val wrap = a !is Cursor && cur.isNotEmpty() && x + g + w > maxW
            if (wrap) {
                lines += cur
                cur = ArrayList()
                x = 0f
                prev = null
                if (a is Space) continue
                cur += Placed(a, 0f, w)
                x = w
                prev = a
            } else {
                cur += Placed(a, x + g, w)
                x += g + w
                prev = a
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return truncate(lines, maxLines, maxW)
    }

    private fun truncate(lines: List<List<Placed>>, maxLines: Int, maxW: Float): List<Line> {
        if (lines.size <= maxLines) return lines
        val kept = lines.take(maxLines).map { it.toMutableList() }
        val last = kept.last()
        val ell = m.body("…")
        while (last.isNotEmpty() && last.last().let { it.x + it.w + ell } > maxW) {
            last.removeAt(last.lastIndex)
        }
        val x = last.lastOrNull()?.let { it.x + it.w } ?: 0f
        last += Placed(Text("…"), x, ell)
        return kept
    }
}

/** A block assigned to dynamic lines [start, start + lines). */
class Placement(val block: Block, val start: Int, val lines: Int)

/**
 * Width-independent line planning, so tap zones can be wired in RemoteViews before any pixels exist.
 * Pinned blocks keep their line if they fit; the rest take the first free line in rank order and
 * are cut to the room left.
 */
object HudPlan {
    private fun segs(b: Block) = b.segments.filter { it.isNotEmpty() }

    fun plan(blocks: List<Block>, slots: Int): List<Placement> {
        val free = BooleanArray(slots) { true }
        val out = ArrayList<Placement>()
        val placed = HashSet<String>()

        fun run(from: Int): Int {
            var n = 0
            while (from + n < slots && free[from + n]) n++
            return n
        }

        fun take(b: Block, at: Int, n: Int) {
            for (i in 0 until n) free[at + i] = false
            out += Placement(b, at, n)
            placed += b.id
        }

        for (b in blocks) {
            val pin = b.pinLine ?: continue
            val at = pin.coerceIn(0, slots - 1)
            val n = segs(b).size
            if (n > 0 && run(at) >= n) take(b, at, n)
        }
        for (b in blocks) {
            if (b.id in placed) continue
            val at = (0 until slots).firstOrNull { free[it] } ?: break
            val n = segs(b).size
            if (n == 0) continue
            take(b, at, minOf(n, run(at)))
        }
        return out.sortedBy { it.start }
    }

    /** The tap for each dynamic line: the first tap found in that line's tokens. */
    fun lineTaps(blocks: List<Block>, slots: Int): List<TapRef?> {
        val out = MutableList<TapRef?>(slots) { null }
        for (pl in plan(blocks, slots)) {
            val s = segs(pl.block)
            for (i in 0 until pl.lines) out[pl.start + i] = s[i].firstNotNullOfOrNull { it.tap }
        }
        return out
    }
}

/** Fills the dynamic lines (everything under the date line). */
class LinePacker(private val flow: FlowLayout) {
    fun pack(blocks: List<Block>, slots: Int, maxW: Float): List<Line> {
        val out = MutableList<Line>(slots) { emptyList() }
        for (pl in HudPlan.plan(blocks, slots)) {
            val s = pl.block.segments.filter { it.isNotEmpty() }
            for (i in 0 until pl.lines) {
                out[pl.start + i] = flow.layout(s[i], maxW, 1).firstOrNull() ?: emptyList()
            }
        }
        return out
    }
}
