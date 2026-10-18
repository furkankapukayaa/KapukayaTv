package com.example.data.parser

import android.util.Log
import com.example.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.StringReader
import java.util.concurrent.TimeUnit

/** İndirme sonucu: başarılıysa ham M3U içeriği, değilse kullanıcıya gösterilecek hata. */
sealed class DownloadResult {
    data class Success(val body: String) : DownloadResult()
    data class Failure(val message: String) : DownloadResult()
}

data class ParsedPlaylist(
    val channels: List<Channel>,
    /** #EXTM3U satırındaki x-tvg-url / url-tvg (EPG adresi), varsa. */
    val epgUrl: String?
)

object M3uParser {
    private const val TAG = "M3uParser"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11; Android TV) KapukayaTv/1.1"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val ATTR_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    suspend fun download(url: String): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "M3U fetch failed: HTTP ${response.code}")
                    return@withContext DownloadResult.Failure("Sunucu hata döndürdü (HTTP ${response.code})")
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    DownloadResult.Failure("Liste boş geldi")
                } else {
                    DownloadResult.Success(body)
                }
            }
        } catch (e: IllegalArgumentException) {
            DownloadResult.Failure("Geçersiz adres")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching M3U playlist", e)
            DownloadResult.Failure("Bağlantı kurulamadı (${e.javaClass.simpleName})")
        }
    }

    fun parse(content: String): ParsedPlaylist {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(StringReader(content))
        var epgUrl: String? = null

        var attrs: Map<String, String> = emptyMap()
        var currentName: String? = null
        var currentGroup: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var order = 0

        var line: String? = reader.readLine()
        while (line != null) {
            val trimmed = line.trim().removePrefix("﻿")
            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val header = parseAttributes(trimmed)
                    epgUrl = (header["x-tvg-url"] ?: header["url-tvg"])
                        ?.split(',')
                        ?.firstOrNull { it.isNotBlank() }
                        ?.trim()
                }

                trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
                    attrs = parseAttributes(trimmed)
                    currentName = extractTitle(trimmed) ?: attrs["tvg-name"]
                    currentGroup = attrs["group-title"]
                    attrs["http-user-agent"]?.let { userAgent = it }
                    attrs["http-referrer"]?.let { referrer = it }
                }

                trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (currentGroup.isNullOrBlank()) {
                        currentGroup = trimmed.substringAfter(':').trim()
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = trimmed.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> userAgent = value
                        "http-referrer", "http-referer" -> referrer = value
                    }
                }

                trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                    val channelName = currentName?.takeIf { it.isNotBlank() } ?: "Kanal ${order + 1}"
                    val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
                    val channelId = tvgId ?: (channelName + "_" + trimmed.hashCode())

                    channels.add(
                        Channel(
                            id = channelId,
                            name = channelName,
                            logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                            streamUrl = StreamSource.withHeaders(trimmed, userAgent, referrer),
                            groupTitle = currentGroup?.takeIf { it.isNotBlank() } ?: "Genel",
                            tvgId = tvgId,
                            isFavorite = false,
                            orderIndex = order++
                        )
                    )

                    attrs = emptyMap()
                    currentName = null
                    currentGroup = null
                    userAgent = null
                    referrer = null
                }
            }
            line = reader.readLine()
        }

        return ParsedPlaylist(channels, epgUrl)
    }

    private fun parseAttributes(line: String): Map<String, String> =
        ATTR_REGEX.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Başlık, son tırnak işaretinden sonraki ilk virgülden sonra gelir. */
    private fun extractTitle(line: String): String? {
        val lastQuote = line.lastIndexOf('"')
        val commaIndex = if (lastQuote != -1) line.indexOf(',', lastQuote) else line.indexOf(',')
        if (commaIndex == -1 || commaIndex >= line.length - 1) return null
        return line.substring(commaIndex + 1).trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Yayın adresleri, Kodi tarzı "url|User-Agent=..&Referer=.." biçiminde
 * HTTP başlıklarını taşıyabilir. Bu sınıf iki yöne de dönüşüm yapar.
 */
data class StreamSource(val url: String, val headers: Map<String, String>) {

    val userAgent: String? get() = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value

    companion object {
        fun parse(raw: String): StreamSource {
            val pipe = raw.indexOf('|')
            if (pipe == -1) return StreamSource(raw.trim(), emptyMap())
            val url = raw.substring(0, pipe).trim()
            val headers = raw.substring(pipe + 1)
                .split('&')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null
                    else part.substring(0, eq).trim() to java.net.URLDecoder.decode(part.substring(eq + 1).trim(), "UTF-8")
                }
                .toMap()
            return StreamSource(url, headers)
        }

        fun withHeaders(rawUrl: String, userAgent: String?, referrer: String?): String {
            if (userAgent.isNullOrBlank() && referrer.isNullOrBlank()) return rawUrl
            val existing = parse(rawUrl)
            val headers = LinkedHashMap(existing.headers)
            if (!userAgent.isNullOrBlank() && headers.keys.none { it.equals("User-Agent", true) }) {
                headers["User-Agent"] = userAgent
            }
            if (!referrer.isNullOrBlank() && headers.keys.none { it.equals("Referer", true) }) {
                headers["Referer"] = referrer
            }
            return existing.url + "|" + headers.entries.joinToString("&") { (k, v) ->
                k + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
        }
    }
}
