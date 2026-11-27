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
        is ScorePill -> m.pill(t.text) + 2 * Hud.OUTLINE_PAD
        is Bubble -> Hud.BUBBLE_PAD_L + m.pill(t.word) + Hud.BUBBLE_PAD_R
        is GlyphToken -> glyphSize(t.glyph).first
        is Chip -> Hud.CHIP_PAD_L + Hud.CHIP_ICON + Hud.CHIP_GAP + m.pill(t.text) + Hud.CHIP_PAD_R
        is DotRow -> t.total * Hud.DOT + (t.total - 1).coerceAtLeast(0) * Hud.DOT_GAP
        is ChipSegment -> maxOf(
            Hud.PILL_H * 1.2f,
            2.6f + (if (t.icon != null) Hud.CHIP_ICON + (if (t.text.isNotEmpty()) 1.4f else 0f) else 0f) + m.pill(t.text) + 2.6f,
        )
        is ChargePill -> 25.5f
        is DotGrid -> ((t.total + 1) / 2) * Hud.DOT * 0.95f + Hud.DOT * 0.5f
        is Tally -> tallyWidth(t.total)
        is IconPill -> Hud.ICONPILL_W
        is CountRing -> maxOf(Hud.PILL_H, m.pill(t.text) + 4.6f)
        is Badge -> if (t.shape == BadgeShape.CIRCLE) Hud.PILL_H else if (t.text.length <= 2) Hud.BADGE_D else Hud.BADGE_WIDE
        is Icon -> Hud.ICON_W
        is Timer -> Hud.TIMER_LEAD + m.body(t.text) * Hud.TIMER_TEXT_SCALE
        is Cursor -> Hud.CURSOR_W
        is Space -> m.bodyAdv
        is ArtStrip -> Hud.ART_W
        is MoonPhase -> Hud.MOON_D
        is ProgressMark -> Hud.PROGRESS_W
    }

    private fun gap(a: Token, b: Token): Float = when {
        a is Space || b is Space -> 0f
        b is Cursor -> 0.7f
        a is NumeralPill && b is MonthPill -> 0.4f
        a is Text && b is NumeralPill -> 2f
        a is Text && b is GlyphToken && b.glyph.isUnit -> 0.4f
        a is Text && b is Text -> m.bodyAdv
        a is ChipSegment && b is ChipSegment -> 0.8f
        a is Icon && b is Text -> 2f
        (a is OutlinePill || a is Chip) && (b is OutlinePill || b is Chip) -> 1.6f
        a is IconPill && (b is OutlinePill || b is CountRing) -> 0.8f
        a is GlyphToken && b is GlyphToken && a.glyph.isWeather && b.glyph.isWeather -> 0.8f
        a is DotRow || b is DotRow || a is DotGrid || b is DotGrid || a is Tally || b is Tally -> 3f
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
        val kept = lines.take(maxLines).map { it.toMutableList() }.toMutableList()
        val last = kept.last()
        val ell = m.body("…")
        while (last.isNotEmpty() && last.last().let { it.x + it.w + ell } > maxW) {
            last.removeAt(last.lastIndex)
        }
        if (last.isEmpty()) {            // nothing but the ellipsis would be left: show nothing rather than a lone "…"
            kept.removeAt(kept.lastIndex)
            return kept
        }
        val x = last.last().let { it.x + it.w }
        last += Placed(Text("…"), x, ell)
        return kept
    }
}

/**
 * The action pill at the end of a line: the follow-up pill, the plain north-east arrow, or the add pill. Events and music
 * always keep the follow-up pill. The add pill is the least obvious, so it only appears where adding makes sense (a
 * reminder, a task, a note, a booking). Otherwise the pill takes turns between follow-up and arrow, so a screen full of
 * targets does not show a column of identical pills. The choice is stable per line (it comes from the line's own text),
 * and never the same as the pill on the line before. All of them fire the same tap.
 */
object ActionPills {
    private val ADDABLE = Regex("""(?i)remind|task|note|to-?do|list|add|new |book|reserv|ticket|appointment|save|schedule""")

    fun pick(seed: String, previous: HudGlyph?, allowAdd: Boolean = false): HudGlyph {
        val cycle = if (allowAdd) listOf(HudGlyph.FOLLOWUP, HudGlyph.ADD, HudGlyph.ARROW_NE) else listOf(HudGlyph.FOLLOWUP, HudGlyph.ARROW_NE)
        var i = Math.floorMod(seed.hashCode(), cycle.size)
        if (cycle[i] == previous) i = (i + 1) % cycle.size
        return cycle[i]
    }

    /** Swaps the follow-up pill in [tokens] for this line's pick; returns the new tokens and the glyph used (or [previous] if the line has none). */
    fun apply(tokens: List<Token>, previous: HudGlyph?, block: Block? = null): Pair<List<Token>, HudGlyph?> {
        if (tokens.none { it is GlyphToken && it.glyph == HudGlyph.FOLLOWUP }) return tokens to previous
        if (block != null && (block.kind == "calendar" || block.id == "event" || block.id == "music")) return tokens to previous
        val seed = tokens.filterIsInstance<Text>().joinToString(" ") { it.text }
        val g = pick(seed, previous, ADDABLE.containsMatchIn(seed))
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
        val tail = blocks.filter { it.tail && segs(it).isNotEmpty() }
        if (tail.isNotEmpty() && slots > 0) {
            // calls and chips keep a line for themselves, but sit right after the content, where a last line reads strongest
            val main = plan(blocks.filter { !(it.tail && segs(it).isNotEmpty()) }, maxOf(0, slots - tail.size), ambientLimit)
            var at = main.maxOfOrNull { it.start + it.lines } ?: 0
            val out = ArrayList(main)
            for (b in tail) {
                if (at >= slots) break
                val n = segs(b).size
                val take = minOf(n, slots - at)
                out += Placement(b, at, take, compact = b.compact != null && take < n)
                at += take
            }
            return out.sortedBy { it.start }
        }
        val free = BooleanArray(slots) { true }
        val out = ArrayList<Placement>()
        val placed = HashSet<String>()

        // the fifth line is scarce: only urgent blocks may reach past the first [ambientLimit] body lines
        fun limit(b: Block) = if (b.urgent) slots else minOf(slots, ambientLimit)

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

    /** A block that [Block.absorbs] another takes its line when the two fit together on one; the absorbed block is dropped. */
    private fun absorb(blocks: List<Block>, maxW: Float): List<Block> {
        var out = blocks
        for (b in blocks) {
            val id = b.absorbs ?: continue
            val other = out.firstOrNull { it.id == id } ?: continue
            val theirs = other.segments.firstOrNull { it.isNotEmpty() } ?: continue
            val joined = b.absorbLine + Space + theirs
            if (!flow.fits(joined, maxW)) continue
            // the merged line sits where the absorbed block was: calls and chips both live at the bottom
            out = out.mapNotNull { x ->
                when (x.id) {
                    b.id -> null
                    id -> b.copy(segments = listOf(joined), compact = null, folds = emptyList(), absorbs = null)
                    else -> x
                }
            }
        }
        return out
    }

    /** Which blocks were folded into which line by [pair], so the caller still learns that each of them got a place. */
    private val partners = HashMap<String, List<String>>()

    /**
     * Two pairable blocks whose one-line forms fit side by side become one line (AQI and steps), so the lines come out
     * closer in length and a line is saved. One partner per line; blocks that are not pairable (music, scores, events)
     * never take a partner.
     */
    private fun pair(blocks: List<Block>, maxW: Float): List<Block> {
        val out = ArrayList<Block>()
        val used = HashSet<String>()
        for ((i, b) in blocks.withIndex()) {
            if (b.id in used) continue
            val mine = b.segments.filter { it.isNotEmpty() }
            if (!b.pairable || mine.size != 1) { out += b; continue }
            var cur = b
            // a partner from the same group first (weather with weather), then whatever else is pairable
            val order = (i + 1 until blocks.size).filter { blocks[it].group != null && blocks[it].group == b.group } +
                (i + 1 until blocks.size).filter { !(blocks[it].group != null && blocks[it].group == b.group) }
            for (j in order) {
                val o = blocks[j]
                if (o.id in used || !o.pairable) continue
                val theirs = o.segments.filter { it.isNotEmpty() }
                if (theirs.size != 1) continue
                val joined = mine[0] + Space + Space + theirs[0]
                if (!flow.fits(joined, maxW)) continue
                cur = b.copy(
                    id = b.id + "+" + o.id, segments = listOf(joined), compact = null, folds = emptyList(),
                    optional = b.optional && o.optional, urgent = b.urgent || o.urgent, tail = b.tail || o.tail, droppable = b.droppable && o.droppable,
                )
                partners[cur.id] = listOf(b.id, o.id)
                used += o.id
                break
            }
            out += cur
        }
        return out
    }

    fun pack(blocks: List<Block>, slots: Int, maxW: Float): List<Line> = packed(blocks, slots, maxW).first

    /** The lines, and the ids of the blocks that got a line (an absorbed block counts as placed with the one that absorbed it). */
    fun packed(blocks: List<Block>, slots: Int, maxW: Float): Pair<List<Line>, Set<String>> {
        val out = MutableList<Line>(slots) { emptyList() }
        val ids = HashSet<String>()
        var last: HudGlyph? = null
        for (pl in HudPlan.plan(pair(absorb(blocks, maxW).map { fold(it, maxW) }, maxW), slots)) {
            ids += pl.block.id
            partners[pl.block.id]?.let { ids += it }
            if (pl.compact) {
                val (toks, g) = ActionPills.apply(pl.block.compact.orEmpty(), last, pl.block)
                last = g
                out[pl.start] = flow.layout(toks, maxW, 1).firstOrNull() ?: emptyList()
                continue
            }
            val s = pl.block.segments.filter { it.isNotEmpty() }
            for (i in 0 until pl.lines) {
                val (toks, g) = ActionPills.apply(s[i], last, pl.block)
                last = g
                out[pl.start + i] = flow.layout(toks, maxW, 1).firstOrNull() ?: emptyList()
            }
        }
        return out to ids
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


/** Width of [total] tally marks in design units: 2.8 per bar, a slash on every fifth, 2.4 between groups. */
internal fun tallyWidth(total: Int): Float {
    if (total <= 0) return 0f
    val groups = (total + 4) / 5
    var w = 0f
    var left = total
    for (g in 0 until groups) {
        val bars = minOf(left, 5)
        left -= bars
        w += (minOf(bars, 4) * 2.8f) + (if (bars == 5) 0.6f else 0f) + (if (g < groups - 1) 2.4f else 0f)
    }
    return w
}
