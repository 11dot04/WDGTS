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
        is Chip -> Hud.CHIP_PAD_L + Hud.CHIP_ICON + Hud.CHIP_GAP + m.pill(t.text) + Hud.CHIP_PAD_R
        is DotRow -> t.total * Hud.DOT + (t.total - 1).coerceAtLeast(0) * Hud.DOT_GAP
        is IconPill -> Hud.ICONPILL_W
        is CountRing -> maxOf(Hud.PILL_H, m.pill(t.text) + 4.6f)
        is Badge -> if (t.shape == BadgeShape.CIRCLE) Hud.PILL_H else if (t.text.length <= 2) Hud.BADGE_D else Hud.BADGE_WIDE
        is Icon -> Hud.ICON_W
        is Timer -> Hud.TIMER_LEAD + m.body(t.text) * Hud.TIMER_TEXT_SCALE
        is Cursor -> Hud.CURSOR_W
        is Space -> m.bodyAdv
        is ArtStrip -> Hud.ART_W
        is ProgressMark -> Hud.PROGRESS_W
    }

    private fun gap(a: Token, b: Token): Float = when {
        a is Space || b is Space -> 0f
        b is Cursor -> 0.7f
        a is NumeralPill && b is MonthPill -> 0.4f
        a is Text && b is NumeralPill -> 2f
        a is Text && b is GlyphToken && b.glyph.isUnit -> 0.4f
        a is Text && b is Text -> m.bodyAdv
        a is Icon && b is Text -> 2f
        (a is OutlinePill || a is Chip) && (b is OutlinePill || b is Chip) -> 1.6f
        a is IconPill && (b is OutlinePill || b is CountRing) -> 0.8f
        a is GlyphToken && b is GlyphToken && a.glyph.isWeather && b.glyph.isWeather -> 0.8f
        a is DotRow || b is DotRow -> 3f
        a is GlyphToken && b is GlyphToken && a.glyph.isHand && b.glyph.isHand -> 3.5f
        else -> 2.6f
    }

    /** Cuts [word] to the longest prefix that leaves room for "…"; counts double-width characters as what they are. */
    private fun fit(word: String, maxW: Float): String {
        if (m.body(word) <= maxW) return word
        val room = maxW - m.body("…")
        var w = 0f
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val cw = if (Metrics.isWide(cp)) m.bodyWide else m.bodyAdv
            if (w + cw > room && i > 0) break
            w += cw
            i += Character.charCount(cp)
        }
        return word.substring(0, i) + "…"
    }

    /** True when all of [tokens] sit on one line of [maxW] without wrapping or cutting anything. */
    fun fits(tokens: List<Token>, maxW: Float): Boolean {
        var x = 0f
        var prev: Token? = null
        for (t in tokens) {
            val parts = if (t is Text) t.text.split(' ').filter { it.isNotEmpty() }.map { Text(it, t.tap) } else listOf(t)
            for (a in parts) {
                val w = width(a)
                val g = if (prev == null) 0f else gap(prev, a)
                if (a !is Cursor && x + g + w > maxW + 0.001f) return false
                x += g + w
                prev = a
            }
        }
        return true
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

/**
 * The action pill at the end of a line takes turns between the follow-up pill, the add pill and the plain north-east
 * arrow, so a screen full of targets does not show a column of identical pills. The choice is stable per line (it
 * comes from the line's own text), and never the same as the pill on the line before. All three fire the same tap.
 */
object ActionPills {
    private val CYCLE = listOf(HudGlyph.FOLLOWUP, HudGlyph.ADD, HudGlyph.ARROW_NE)

    fun pick(seed: String, previous: HudGlyph?): HudGlyph {
        var i = Math.floorMod(seed.hashCode(), CYCLE.size)
        if (CYCLE[i] == previous) i = (i + 1) % CYCLE.size
        return CYCLE[i]
    }

    /** Swaps the follow-up pill in [tokens] for this line's pick; returns the new tokens and the glyph used (or [previous] if the line has none). */
    fun apply(tokens: List<Token>, previous: HudGlyph?): Pair<List<Token>, HudGlyph?> {
        if (tokens.none { it is GlyphToken && it.glyph == HudGlyph.FOLLOWUP }) return tokens to previous
        val seed = tokens.filterIsInstance<Text>().joinToString(" ") { it.text }
        val g = pick(seed, previous)
        return tokens.map { if (it is GlyphToken && it.glyph == HudGlyph.FOLLOWUP) GlyphToken(g, it.tap) else it } to g
    }
}

/** A block assigned to dynamic lines [start, start + lines); [compact] = its one-line version was chosen. */
class Placement(val block: Block, val start: Int, val lines: Int, val compact: Boolean = false)

/**
 * Width-independent line planning, so tap zones can be wired in RemoteViews before any pixels exist.
 * Pinned blocks keep their line if they fit; the rest take the first free line in rank order. A block that
 * does not fit in full uses its compact one-liner if it has one, otherwise it is cut to the room left.
 * Optional (ambient) blocks only use the first [ambientLimit] lines.
 */
object HudPlan {
    private fun segs(b: Block) = b.segments.filter { it.isNotEmpty() }

    fun plan(blocks: List<Block>, slots: Int, ambientLimit: Int = Hud.AMBIENT_BODY_LINES): List<Placement> {
        val free = BooleanArray(slots) { true }
        val out = ArrayList<Placement>()
        val placed = HashSet<String>()

        fun limit(b: Block) = if (b.optional) minOf(slots, ambientLimit) else slots

        fun run(from: Int, lim: Int): Int {
            var n = 0
            while (from + n < lim && free[from + n]) n++
            return n
        }

        fun take(b: Block, at: Int, n: Int, compact: Boolean = false) {
            for (i in 0 until n) free[at + i] = false
            out += Placement(b, at, n, compact)
            placed += b.id
        }

        for (b in blocks) {
            val pin = b.pinLine ?: continue
            val at = pin.coerceIn(0, slots - 1)
            val n = segs(b).size
            if (n > 0 && run(at, limit(b)) >= n) take(b, at, n)
        }
        for (b in blocks) {
            if (b.id in placed) continue
            val lim = limit(b)
            val at = (0 until lim).firstOrNull { free[it] } ?: continue
            val n = segs(b).size
            if (n == 0) continue
            val r = run(at, lim)
            when {
                n <= r -> take(b, at, n)
                b.compact != null && r >= 1 -> take(b, at, 1, compact = true)
                else -> take(b, at, r)
            }
        }
        return out.sortedBy { it.start }
    }

    /** The tap for each dynamic line: the first tap found in that line's tokens. */
    fun lineTaps(blocks: List<Block>, slots: Int): List<TapRef?> {
        val out = MutableList<TapRef?>(slots) { null }
        for (pl in plan(blocks, slots)) {
            if (pl.compact) {
                out[pl.start] = pl.block.compact?.firstNotNullOfOrNull { it.tap }
                continue
            }
            val s = segs(pl.block)
            for (i in 0 until pl.lines) out[pl.start + i] = s[i].firstNotNullOfOrNull { it.tap }
        }
        return out
    }
}

/** Fills the dynamic lines (everything under the date line). */
class LinePacker(private val flow: FlowLayout) {
    /** A block whose one-line version fits becomes that one line, so it leaves a line free for the next block. */
    private fun fold(b: Block, maxW: Float): Block {
        val one = b.folds.firstOrNull { flow.fits(it, maxW) } ?: return b
        return b.copy(segments = listOf(one), compact = null, folds = emptyList())
    }

    fun pack(blocks: List<Block>, slots: Int, maxW: Float): List<Line> {
        val out = MutableList<Line>(slots) { emptyList() }
        var last: HudGlyph? = null
        for (pl in HudPlan.plan(blocks.map { fold(it, maxW) }, slots)) {
            if (pl.compact) {
                val (toks, g) = ActionPills.apply(pl.block.compact.orEmpty(), last)
                last = g
                out[pl.start] = flow.layout(toks, maxW, 1).firstOrNull() ?: emptyList()
                continue
            }
            val s = pl.block.segments.filter { it.isNotEmpty() }
            for (i in 0 until pl.lines) {
                val (toks, g) = ActionPills.apply(s[i], last)
                last = g
                out[pl.start + i] = flow.layout(toks, maxW, 1).firstOrNull() ?: emptyList()
            }
        }
        return out
    }
}


/** One horizontal tap span of a line, in pixels from the widget's left edge. */
class Span(val x0: Float, val x1: Float, val tap: TapRef?)

/** Splits laid-out lines into tap spans, so an arrow pill, a title and a chip can each be their own target. */
object HudHits {
    private class Seg(var x0: Float, var x1: Float, val tap: TapRef?)

    /**
     * Spans cover the whole line width. Each token owns the area up to the middle of the gap to its neighbour;
     * tokens without a tap take the line's first tap. Neighbours with the same tap are merged, and at most
     * [maxSpans] are returned (the tail is merged into the last).
     */
    fun spans(line: Line, unit: Float, padPx: Float, widthPx: Float, maxSpans: Int = 5): List<Span> {
        if (line.isEmpty()) return listOf(Span(0f, widthPx, null))
        val default = line.firstNotNullOfOrNull { it.token.tap }
        val segs = ArrayList<Seg>()
        for ((i, p) in line.withIndex()) {
            val tap = p.token.tap ?: default
            val left = if (i == 0) 0f else (line[i - 1].x + line[i - 1].w + p.x) / 2f * unit + padPx
            val right = if (i == line.lastIndex) widthPx else (p.x + p.w + line[i + 1].x) / 2f * unit + padPx
            val last = segs.lastOrNull()
            if (last != null && last.tap == tap) last.x1 = right else segs += Seg(left, right, tap)
        }
        segs.first().x0 = 0f
        segs.last().x1 = widthPx
        while (segs.size > maxSpans) {
            val tail = segs.removeAt(segs.lastIndex)
            segs.last().x1 = tail.x1
        }
        return segs.map { Span(it.x0, it.x1, it.tap) }
    }
}
