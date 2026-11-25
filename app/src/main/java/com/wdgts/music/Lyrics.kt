package com.wdgts.music

import android.content.Context
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Settings of the music widgets that have no home in [Prefs]. Stored in the same preferences file. */
object MusicPrefs {
    fun lyrics(c: Context) = Prefs.raw(c).getBoolean("music_lyrics", true)
    fun setLyrics(c: Context, on: Boolean) = Prefs.raw(c).edit().putBoolean("music_lyrics", on).apply()

    /** Depth buttons: 0 = colours taken from the cover, 1 = frosted glass over the cover. */
    fun buttonStyle(c: Context) = Prefs.raw(c).getInt("music_btn_style", 0)
    fun setButtonStyle(c: Context, v: Int) = Prefs.raw(c).edit().putInt("music_btn_style", v).apply()

    /** Depth: leave out the title or the artist line when the cover already shows it in readable lettering. */
    fun hideDuplicates(c: Context) = Prefs.raw(c).getBoolean("music_hide_dup", true)
    fun setHideDuplicates(c: Context, on: Boolean) = Prefs.raw(c).edit().putBoolean("music_hide_dup", on).apply()
}

/**
 * Time-synced lyrics from LRCLIB (free, no key). One lookup per track, cached in files/lyrics; a track with no
 * synced lyrics is remembered for three days so it is not asked again on every play.
 */
object Lyrics {
    class Line(val t: Long, val s: String)

    /** The widget redraws a little late, so the line changes a little early. */
    private const val LEAD = 250L

    @Volatile private var lines: List<Line> = emptyList()
    @Volatile private var forKey = Long.MIN_VALUE
    @Volatile private var loading = Long.MIN_VALUE
    @Volatile private var retryAt = 0L

    fun ready(): Boolean = forKey == MusicState.key && lines.isNotEmpty()

    /** Every line of the current song, to size the text for the whole song at once. */
    fun all(): List<Line> = if (ready()) lines else emptyList()

    /** The line being sung at [posMs], or null between lines and when there are no lyrics. */
    fun current(posMs: Long): String? {
        if (!ready()) return null
        val l = lines
        val p = posMs + LEAD
        var lo = 0; var hi = l.size - 1; var ans = -1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            if (l[m].t <= p) { ans = m; lo = m + 1 } else hi = m - 1
        }
        return if (ans >= 0) l[ans].s.ifBlank { null } else null
    }

    /** Milliseconds until the next line starts, or -1 if there is none. */
    fun msToNext(posMs: Long): Long {
        if (!ready()) return -1
        val p = posMs + LEAD
        val n = lines.firstOrNull { it.t > p } ?: return -1
        return n.t - p
    }

    /** Starts the lookup for the current track if it has not been done (or failed for want of a connection). */
    fun ensure(c: Context) {
        if (!MusicPrefs.lyrics(c) || !MusicState.hasSession) return
        val key = MusicState.key
        val now = System.currentTimeMillis()
        if (loading == key) return
        if (forKey == key && (lines.isNotEmpty() || now < retryAt)) return
        loading = key
        val title = MusicState.title
        val artist = MusicState.artist
        val dur = MusicState.durationMs
        val app = c.applicationContext
        thread(name = "wdgts-lyrics") {
            val got = runCatching { load(app, key, title, artist, dur) }.getOrNull()
            if (MusicState.key == key) {
                lines = got ?: emptyList()
                forKey = key
                retryAt = if (got == null) System.currentTimeMillis() + 120_000L else 0L
            }
            if (loading == key) loading = Long.MIN_VALUE
            MusicWidgets.refreshAll(app)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** "Song - Remastered 2011", "Song (feat. X)" -> "Song". */
    private fun cleanTitle(t: String) = t.replace(Regex("\\s*[(\\[].*?[)\\]]"), "").substringBefore(" - ").trim().ifEmpty { t }
    private fun firstArtist(a: String) = a.split(Regex("\\s*(,|&|/| feat\\.? | ft\\.? | x )\\s*", RegexOption.IGNORE_CASE)).first().trim().ifEmpty { a }

    /** The parsed lines; empty when the track has no synced lyrics; null when the lookup could not be made. */
    private fun load(c: Context, key: Long, title: String, artist: String, durMs: Long): List<Line>? {
        val dir = File(c.filesDir, "lyrics").also { it.mkdirs() }
        val hit = File(dir, "$key.lrc")
        val none = File(dir, "$key.none")
        if (hit.exists()) return parse(hit.readText())
        if (none.exists() && System.currentTimeMillis() - none.lastModified() < 3L * 24 * 3600 * 1000) return emptyList()

        var synced: String? = null
        var transient = false
        val secs = (durMs / 1000).toInt()

        val exact = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            if (secs > 0) "&duration=$secs" else ""
        val r = Http.probe(exact, 10_000)
        if (r.code in 200..299 && r.body != null) {
            synced = runCatching { JSONObject(r.body).optString("syncedLyrics") }.getOrNull()?.ifBlank { null }
        } else if (r.code == -1 || r.code >= 500) transient = true

        if (synced == null && !transient) {
            val q = "https://lrclib.net/api/search?track_name=${enc(cleanTitle(title))}&artist_name=${enc(firstArtist(artist))}"
            val s = Http.probe(q, 10_000)
            if (s.code in 200..299 && s.body != null) {
                val arr = runCatching { JSONArray(s.body) }.getOrNull()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val txt = o.optString("syncedLyrics").ifBlank { null } ?: continue
                    val d = o.optDouble("duration", -1.0)
                    if (secs <= 0 || d < 0 || Math.abs(d - secs) <= 4.0) { synced = txt; break }
                }
            } else if (s.code == -1 || s.code >= 500) transient = true
        }

        if (synced != null) {
            runCatching { hit.writeText(synced) }
            return parse(synced)
        }
        if (transient) return null
        runCatching { none.writeText("") }
        return emptyList()
    }

    private val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")

    fun parse(txt: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in txt.lineSequence()) {
            val ms = stamp.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val text = raw.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val frac = m.groupValues[3]
                val f = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toInt()
                out += Line(m.groupValues[1].toLong() * 60_000L + m.groupValues[2].toLong() * 1000L + f, text)
            }
        }
        return out.sortedBy { it.t }
    }
}
