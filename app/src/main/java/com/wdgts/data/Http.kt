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

    fun getBytes(url: String, timeoutMs: Int = 8000): ByteArray? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "WDGTS/0.3 (personal home-screen widgets)")
        if (c.responseCode !in 200..299) return null
        c.inputStream.use { it.readBytes() }
    }.getOrNull()
}
