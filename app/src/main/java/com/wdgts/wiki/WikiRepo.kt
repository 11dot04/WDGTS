package com.wdgts.wiki

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wdgts.core.Prefs
import com.wdgts.data.Http
import com.wdgts.music.Cutout
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Random

data class Story(
    val date: String, val title: String, val desc: String, val extract: String,
    val imageUrl: String?, val pageUrl: String,
) {
    val key: Long get() = title.hashCode().toLong()
    fun toJson(): String = JSONObject().put("date", date).put("title", title).put("desc", desc)
        .put("extract", extract).put("img", imageUrl ?: "").put("url", pageUrl).toString()

    companion object {
        fun fromJson(s: String): Story? = runCatching {
            val o = JSONObject(s)
            Story(o.getString("date"), o.getString("title"), o.optString("desc"), o.optString("extract"),
                o.optString("img").ifEmpty { null }, o.getString("url"))
        }.getOrNull()
    }
}

/**
 * One article per day from Wikipedia's featured articles (the ones the community rates as its best, so the
 * pick is usually worth reading), the same one all day; a tap on page one draws another. Everything comes from
 * the stable Action API: a category listing at a random spot, then intro text, short description and a lead image.
 */
object WikiRepo {
    private const val KEY = "wiki_json"
    private const val API = "https://en.wikipedia.org/w/api.php"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun cached(c: Context): Story? = Prefs.raw(c).getString(KEY, null)?.let { Story.fromJson(it) }

    private fun imageFile(c: Context) = File(c.filesDir, "wiki_img.jpg")
    private fun stagedFile(c: Context) = File(c.filesDir, "wiki_next.jpg")

    /** The lead image, decoded to at most ~1100 px on the long side. Safe on the main thread (small file). */
    fun image(c: Context, staged: Boolean = false): Bitmap? {
        val f = if (staged) stagedFile(c) else imageFile(c)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 1100) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun cutoutFile(c: Context, s: Story) = File(c.cacheDir, "cutout2_${s.key}.png")
    private fun noneFile(c: Context, s: Story) = File(c.cacheDir, "cutout_none_${s.key}")

    /** The subject lifted off the lead image, if one was found; same pixel size as [image]. */
    fun cutout(c: Context, s: Story): Bitmap? =
        cutoutFile(c, s).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun needsCutout(c: Context, s: Story) =
        s.imageUrl != null && !cutoutFile(c, s).exists() && !noneFile(c, s).exists()

    /** Slow (model download on first use): call off the main thread and outside a broadcast's budget. */
    fun buildCutout(c: Context, s: Story, staged: Boolean = false) {
        val art = image(c, staged) ?: return
        val fg = Cutout.compute(c, art, s.key)
        if (fg == null) runCatching { noneFile(c, s).createNewFile() }
    }

    /** A fetched article that is not on the widget yet: its image waits in a staging file until the cutout is ready. */
    class Next(val story: Story, val staged: Boolean)

    /**
     * Blocking: today's article, or a fresh random one when [reroll]. Nothing is published here; the caller builds
     * the cutout first (so the card never redraws its layout) and then calls [commit]. Null when offline.
     */
    fun fetch(c: Context, reroll: Boolean = false): Next? {
        val today = LocalDate.now().toString()
        val cur = cached(c)
        if (!reroll && cur != null && cur.date == today) return Next(cur, false)

        val rnd = if (reroll) Random() else Random(LocalDate.now().toEpochDay())
        val prefix = "" + ('A' + rnd.nextInt(26)) + ('a' + rnd.nextInt(26))
        val titles = (members(prefix).ifEmpty { members(null) }).shuffled(rnd).filter { it != cur?.title }
        var pick: Story? = null
        for (t in titles.take(8)) {
            val s = details(t, today) ?: continue
            if (s.extract.length < 250) continue
            if (s.imageUrl != null) { pick = s; break }
            if (pick == null) pick = s
        }
        val story = pick ?: return cur?.let { Next(it, false) }
        val bytes = story.imageUrl?.let { Http.getBytes(it, 12000) }
        if (bytes != null) stagedFile(c).writeBytes(bytes) else stagedFile(c).delete()
        return Next(if (bytes == null) story.copy(imageUrl = null) else story, bytes != null)
    }

    /** Puts a fetched article (and its staged image) on the widget. */
    fun commit(c: Context, n: Next) {
        if (n.staged) {
            val st = stagedFile(c)
            if (st.exists()) { imageFile(c).delete(); st.renameTo(imageFile(c)) }
        } else if (n.story.imageUrl == null) imageFile(c).delete()
        Prefs.raw(c).edit().putString(KEY, n.story.toJson()).apply()
    }

    private fun members(prefix: String?): List<String> {
        val url = "$API?action=query&format=json&list=categorymembers&cmtitle=${enc("Category:Featured articles")}" +
            "&cmnamespace=0&cmtype=page&cmlimit=200&cmsort=sortkey" +
            (if (prefix != null) "&cmstartsortkeyprefix=${enc(prefix)}" else "")
        val a = Http.get(url)?.let { runCatching { JSONObject(it).getJSONObject("query").getJSONArray("categorymembers") }.getOrNull() }
            ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it).getString("title") }
    }

    private fun details(title: String, today: String): Story? {
        val url = "$API?action=query&format=json&redirects=1&titles=${enc(title)}" +
            "&prop=extracts|pageimages|description|info&exintro=1&explaintext=1&exchars=1300" +
            "&piprop=thumbnail&pithumbsize=1000&inprop=url"
        val pages = Http.get(url)?.let { runCatching { JSONObject(it).getJSONObject("query").getJSONObject("pages") }.getOrNull() }
            ?: return null
        val p = pages.keys().asSequence().firstOrNull()?.let { pages.getJSONObject(it) } ?: return null
        val extract = clean(p.optString("extract"))
        if (extract.isEmpty()) return null
        return Story(
            today, p.optString("title", title), p.optString("description"), extract,
            p.optJSONObject("thumbnail")?.optString("source")?.ifEmpty { null },
            p.optString("fullurl", "https://en.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}"),
        )
    }

    /** One paragraph, no pronunciation or citation noise, ending on a full sentence. */
    private fun clean(raw: String): String {
        var t = raw.replace(Regex("\\[[0-9a-z ]{1,4}]"), "")
            .replace(Regex("\\s*\\([^()]*[/ˈəɪʊɛɔæ;][^()]*\\)"), "")
            .replace(Regex("\\s+"), " ").trim()
        if (t.length > 900) {
            val cut = t.lastIndexOf(". ", 900)
            t = if (cut > 300) t.substring(0, cut + 1) else t.substring(0, 900).trimEnd() + "…"
        }
        return t
    }
}
