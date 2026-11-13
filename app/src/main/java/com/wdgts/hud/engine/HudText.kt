package com.wdgts.hud.engine

/** Text clean-up for what other apps send: song titles full of "(Official Music Video)" and friends. Pure. */
object HudText {
    private val GROUP = Regex("""[\(\[\{]([^\)\]\}]*)[\)\]\}]""")
    private val NOISE = Regex(
        """(?i)\b(official|video|audio|lyrics?|visuali[sz]er|remaster(?:ed)?|hd|hq|4k|8k|slowed|reverb|sped\s*up|nightcore|""" +
            """bass\s*boosted|karaoke|full\s+song|explicit|feat\.?|ft\.?|featuring|prod\.?|bonus\s+track)\b|\b(?:19|20)\d\d\b""",
    )
    private val FEAT_TAIL = Regex("""(?i)\s+(?:feat\.?|ft\.?|featuring)\s+.*$""")
    private val TAIL = Regex("""(?i)\s*[-–—|•:]\s*(?:official\b.*|lyrics?\b.*|audio\b.*|video\b.*|full\s+song\b.*)$""")
    private val SPACES = Regex("""\s{2,}""")

    /** "Song (Official Music Video) [Slowed + Reverb] ft. X" -> "Song". Brackets with real information are kept. */
    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        s = GROUP.replace(s) { m -> if (NOISE.containsMatchIn(m.groupValues[1])) "" else m.value }
        s = FEAT_TAIL.replace(s, "")
        s = TAIL.replace(s, "")
        s = SPACES.replace(s, " ").trim().trim('-', '–', '—', '|', '•', ' ')
        return s.ifEmpty { raw.trim() }
    }

    /** "Mehdi Hassan - Topic" -> "Mehdi Hassan"; strips VEVO and "Official". */
    fun cleanArtist(raw: String): String {
        val s = raw.trim()
            .replace(Regex("""(?i)\s*[-–—]\s*topic$"""), "")
            .replace(Regex("""(?i)\s*vevo$"""), "")
            .replace(Regex("""(?i)\s+official$"""), "")
            .replace(Regex("""(?i)\s*(?:[•·|]|\s[-–—]\s)?\s*(?:recommended|made)\s+for\s+you\s*$"""), "")   // Spotify's "Artist • Recommended for you"
            .trim()
        return s.ifEmpty { raw.trim() }
    }
}
