package com.wdgts.data

import android.content.Context
import com.wdgts.core.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Which kanji: the same JLPT feeds your KWGT used (kotd.dperkins.org), so N5..N1 selection matches.
 * Everything else: kanjiapi.dev (KANJIDIC2 + JMdict as JSON). Stroke paths: KanjiVG (see KanjiVg.kt).
 */
object KanjiRepo {
    private val FALLBACK = "日一二三十人大中本年月水火山川前後学先".map { it.toString() }
    private val COMMON = setOf("news1", "ichi1", "spec1", "gai1")
    private val MINOR = setOf("news2", "ichi2", "spec2", "gai2")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Whatever we have for the current level (may be yesterday's if offline). Safe on the main thread. */
    fun cached(c: Context): KanjiDay? =
        Prefs.kanjiJson(c, Prefs.jlpt(c))?.let { KanjiDay.fromJson(it) }

    /** Blocking: refreshes the cache if it isn't from today. Call off the main thread. */
    fun ensure(c: Context): KanjiDay? {
        val level = Prefs.jlpt(c)
        val today = LocalDate.now().toString()
        val cur = cached(c)
        if (cur != null && cur.date == today && cur.examples.isNotEmpty()) return cur

        val kanji = kotd(level) ?: cur?.kanji
            ?: FALLBACK[(LocalDate.now().toEpochDay() % FALLBACK.size).toInt()]

        if (cur != null && cur.kanji == kanji && cur.examples.isNotEmpty()) {
            return cur.copy(date = today).also { Prefs.setKanjiJson(c, level, it.toJson()) }
        }

        val info = Http.get("https://kanjiapi.dev/v1/kanji/${enc(kanji)}")
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val words = Http.get("https://kanjiapi.dev/v1/words/${enc(kanji)}")?.let { examples(it, kanji) }
            ?: emptyList()
        if (info == null && words.isEmpty() && cur != null) return cur   // offline: keep showing the last one

        val day = KanjiDay(
            today, level, kanji,
            info?.optInt("grade", -1) ?: -1,
            info?.optInt("stroke_count", 0) ?: 0,
            info.strs("meanings"), info.strs("on_readings"), info.strs("kun_readings"),
            words,
        )
        Prefs.setKanjiJson(c, level, day.toJson())
        KanjiVg.load(c, kanji, allowNetwork = true)     // prefetch strokes so tap-to-animate is instant
        return day
    }

    private fun JSONObject?.strs(key: String): List<String> {
        val a = this?.optJSONArray(key) ?: return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    /** The kanji of the day for an N-level, from the same RSS feeds the KWGT version scraped. */
    private fun kotd(level: Int): String? {
        val xml = Http.get("https://kotd.dperkins.org/jlpt.n$level.xml") ?: return null
        return Regex("Kanji of the Day:\\s*([\\u4E00-\\u9FFF])").find(xml)?.groupValues?.get(1)
    }

    /** Ten most common words containing [kanji], ranked by JMdict priority tags. */
    private fun examples(json: String, kanji: String): List<Example> = runCatching {
        val arr = JSONArray(json)
        val out = ArrayList<Pair<Int, Example>>()
        for (i in 0 until arr.length()) {
            val w = arr.getJSONObject(i)
            val vs = w.getJSONArray("variants")
            var best: JSONObject? = null
            for (j in 0 until vs.length()) {
                val v = vs.getJSONObject(j)
                if (v.getString("written").contains(kanji)) { best = v; break }
            }
            if (best == null) continue
            var score = 0
            val pr = best.optJSONArray("priorities")
            if (pr != null) for (k in 0 until pr.length()) {
                val p = pr.getString(k)
                score += when {
                    p in COMMON -> 20
                    p in MINOR -> 8
                    p.startsWith("nf") -> maxOf(0, 12 - (p.drop(2).toIntOrNull() ?: 48) / 4)
                    else -> 1
                }
            }
            val ms = w.getJSONArray("meanings")
            if (ms.length() == 0) continue
            val gl = ms.getJSONObject(0).getJSONArray("glosses")
            val gloss = (0 until minOf(2, gl.length())).joinToString(", ") { gl.getString(it) }
            out += score to Example(best.getString("written"), best.getString("pronounced"), gloss.take(32))
        }
        out.sortedByDescending { it.first }.map { it.second }.distinctBy { it.written }.take(10)
    }.getOrDefault(emptyList())
}
