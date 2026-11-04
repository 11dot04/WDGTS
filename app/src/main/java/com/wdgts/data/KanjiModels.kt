package com.wdgts.data

import org.json.JSONArray
import org.json.JSONObject

data class Example(val written: String, val reading: String, val gloss: String)

data class KanjiDay(
    val date: String,
    val level: Int,              // JLPT N-level the kanji was picked from (5 = N5)
    val kanji: String,
    val grade: Int,              // school grade, -1 if none
    val strokes: Int,
    val meanings: List<String>,
    val on: List<String>,
    val kun: List<String>,
    val examples: List<Example>,
) {
    fun toJson(): String = JSONObject().apply {
        put("date", date); put("level", level); put("kanji", kanji)
        put("grade", grade); put("strokes", strokes)
        put("meanings", JSONArray(meanings)); put("on", JSONArray(on)); put("kun", JSONArray(kun))
        put("examples", JSONArray(examples.map {
            JSONObject().put("w", it.written).put("r", it.reading).put("g", it.gloss)
        }))
    }.toString()

    companion object {
        fun fromJson(s: String): KanjiDay? = runCatching {
            val o = JSONObject(s)
            fun strs(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
            val ex = o.getJSONArray("examples").let { a ->
                List(a.length()) { i ->
                    a.getJSONObject(i).let { Example(it.getString("w"), it.getString("r"), it.getString("g")) }
                }
            }
            KanjiDay(
                o.getString("date"), o.getInt("level"), o.getString("kanji"),
                o.getInt("grade"), o.getInt("strokes"), strs("meanings"), strs("on"), strs("kun"), ex,
            )
        }.getOrNull()
    }
}
