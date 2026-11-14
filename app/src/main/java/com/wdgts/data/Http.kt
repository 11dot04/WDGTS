package com.wdgts.data

import java.net.HttpURLConnection
import java.net.URL

object Http {
    /** Returns the body, or null on any failure (offline, 4xx/5xx, timeout). */
    fun get(url: String, timeoutMs: Int = 8000): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "WDGTS/0.2 (personal home-screen widgets)")
        if (c.responseCode !in 200..299) return null
        c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    /** [code] is the HTTP status (-1 when the request never got one); [err] is the exception or the start of an error body. */
    class Res(val code: Int, val body: String?, val err: String?)

    /** Like [get] but says why it failed, for diagnostics. */
    fun probe(url: String, timeoutMs: Int = 8000): Res = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "WDGTS/0.2 (personal home-screen widgets)")
        c.setRequestProperty("Accept", "application/json")
        val code = c.responseCode
        if (code in 200..299) Res(code, c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }, null)
        else Res(code, null, c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(80)?.replace('\n', ' '))
    } catch (e: Exception) {
        Res(-1, null, e.javaClass.simpleName + ": " + (e.message ?: "").take(60))
    }

    fun getBytes(url: String, timeoutMs: Int = 8000): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "WDGTS/0.3 (personal home-screen widgets)")
        if (c.responseCode !in 200..299) return null
        c.inputStream.use { it.readBytes() }
    }.getOrNull()
}
