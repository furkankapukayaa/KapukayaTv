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
import java.util.regex.Pattern

object M3uParser {
    private const val TAG = "M3uParser"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val TVG_ID_REGEX = Pattern.compile("tvg-id=\"([^\"]*)\"")
    private val TVG_LOGO_REGEX = Pattern.compile("tvg-logo=\"([^\"]*)\"")
    private val GROUP_TITLE_REGEX = Pattern.compile("group-title=\"([^\"]*)\"")
    private val TVG_NAME_REGEX = Pattern.compile("tvg-name=\"([^\"]*)\"")

    suspend fun fetchAndParse(url: String): List<Channel> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android TV; KapukayaTv/1.0)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "M3U fetch failed: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val bodyString = response.body?.string() ?: return@withContext emptyList()
            parseM3u(bodyString)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching M3U playlist", e)
            emptyList()
        }
    }

    fun parseM3u(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(StringReader(content))

        var line: String? = reader.readLine()
        var currentTvgId: String? = null
        var currentLogoUrl: String? = null
        var currentGroupTitle: String? = null
        var currentName: String? = null
        var order = 0

        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                // Parse tvg-id
                val idMatcher = TVG_ID_REGEX.matcher(trimmed)
                currentTvgId = if (idMatcher.find()) idMatcher.group(1) else null

                // Parse tvg-logo
                val logoMatcher = TVG_LOGO_REGEX.matcher(trimmed)
                currentLogoUrl = if (logoMatcher.find()) logoMatcher.group(1) else null

                // Parse group-title
                val groupMatcher = GROUP_TITLE_REGEX.matcher(trimmed)
                currentGroupTitle = if (groupMatcher.find()) groupMatcher.group(1) else null

                // Parse name from after the last comma
                val commaIndex = trimmed.lastIndexOf(',')
                currentName = if (commaIndex != -1 && commaIndex < trimmed.length - 1) {
                    trimmed.substring(commaIndex + 1).trim()
                } else {
                    val nameMatcher = TVG_NAME_REGEX.matcher(trimmed)
                    if (nameMatcher.find()) nameMatcher.group(1) else "Kanal ${order + 1}"
                }
            } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                // This is the stream URL
                val streamUrl = trimmed
                val channelName = currentName ?: "Kanal ${order + 1}"
                val channelId = currentTvgId?.takeIf { it.isNotBlank() }
                    ?: (channelName + "_" + streamUrl.hashCode())

                channels.add(
                    Channel(
                        id = channelId,
                        name = channelName,
                        logoUrl = currentLogoUrl?.takeIf { it.isNotBlank() },
                        streamUrl = streamUrl,
                        groupTitle = currentGroupTitle?.takeIf { it.isNotBlank() } ?: "Genel",
                        tvgId = currentTvgId,
                        isFavorite = false,
                        orderIndex = order++
                    )
                )

                // Reset temporary fields
                currentTvgId = null
                currentLogoUrl = null
                currentGroupTitle = null
                currentName = null
            }
            line = reader.readLine()
        }

        return channels
    }
}
