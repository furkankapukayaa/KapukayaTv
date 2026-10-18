package com.example.data.epg

import android.content.Context
import android.util.Log
import android.util.Xml
import com.example.data.model.Channel
import com.example.data.parser.M3uParser
import com.example.util.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?
) {
    fun progress(now: Long = System.currentTimeMillis()): Int {
        val total = (stop - start).coerceAtLeast(1)
        return (((now - start).toDouble() / total) * 100).toInt().coerceIn(0, 100)
    }

    fun timeRange(): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${fmt.format(start)} – ${fmt.format(stop)}"
    }
}

/**
 * XMLTV (EPG) yayın akışı desteği. Dosya (düz veya .gz) indirilir, önbelleğe alınır ve
 * yalnızca listedeki kanallar için "şimdi - 24 saat sonrası" aralığındaki programlar belleğe yüklenir.
 */
object EpgRepository {
    private const val TAG = "EpgRepository"
    private const val CACHE_FILE = "epg_cache.xml"
    private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var programmes: Map<String, List<Programme>> = emptyMap()

    @Volatile
    var loadedUrl: String? = null
        private set

    val isLoaded: Boolean get() = programmes.isNotEmpty()

    /** Kanalın şu anki ve bir sonraki programı. */
    fun nowAndNext(channel: Channel, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = findProgrammes(channel) ?: return null to null
        val index = list.indexOfFirst { now >= it.start && now < it.stop }
        if (index == -1) {
            return null to list.firstOrNull { it.start > now }
        }
        return list[index] to list.getOrNull(index + 1)
    }

    /** Kanalın bitmemiş programları (rehber ekranı için). */
    fun upcomingProgrammes(channel: Channel, now: Long = System.currentTimeMillis()): List<Programme> =
        findProgrammes(channel)?.filter { it.stop > now }.orEmpty()

    fun hasProgrammes(channel: Channel): Boolean = findProgrammes(channel) != null

    private fun findProgrammes(channel: Channel): List<Programme>? {
        if (programmes.isEmpty()) return null
        channel.tvgId?.let { id ->
            programmes[idKey(id)]?.let { return it }
        }
        return programmes[nameKey(channel.name)]
    }

    /**
     * EPG'yi yükler. [force] false ise önbellek 6 saatten yeniyse indirme yapılmaz.
     * Başarılıysa eşleşen kanal sayısını döndürür.
     */
    suspend fun load(context: Context, url: String, channels: List<Channel>, force: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val cache = File(context.cacheDir, CACHE_FILE)
                val urlFile = File(context.cacheDir, "$CACHE_FILE.url")
                val fresh = cache.exists() && cache.length() > 0 &&
                    urlFile.exists() && urlFile.readText() == url &&
                    System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

                if (!force && fresh && loadedUrl == url && isLoaded) {
                    return@withContext Result.success(channels.count { findProgrammes(it) != null })
                }

                if (force || !fresh) {
                    download(url, cache)
                    urlFile.writeText(url)
                }

                val parsed = openMaybeGzip(cache).use { parse(it, channels) }
                programmes = parsed
                loadedUrl = url
                Result.success(channels.count { findProgrammes(it) != null })
            } catch (e: Exception) {
                Log.e(TAG, "EPG yüklenemedi", e)
                Result.failure(e)
            }
        }

    fun clear(context: Context) {
        programmes = emptyMap()
        loadedUrl = null
        File(context.cacheDir, CACHE_FILE).delete()
        File(context.cacheDir, "$CACHE_FILE.url").delete()
    }

    private fun download(url: String, target: File) {
        val request = Request.Builder().url(url).header("User-Agent", M3uParser.USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Boş yanıt")
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream())
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input) else input
    }

    private fun parse(input: InputStream, channels: List<Channel>): Map<String, List<Programme>> {
        // Listedeki kanallar için aranan anahtarlar
        val wantedIds = channels.mapNotNull { it.tvgId?.let(::idKey) }.toSet()
        val wantedNames = channels.map { nameKey(it.name) }.toSet()

        val now = System.currentTimeMillis()
        val windowStart = now - 2 * 60 * 60 * 1000L
        val windowEnd = now + 24 * 60 * 60 * 1000L

        // XMLTV kanal id -> eşleşen anahtarlar (id ve/veya isim)
        val channelKeys = HashMap<String, MutableSet<String>>()
        val result = HashMap<String, MutableList<Programme>>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var currentChannelId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var progTitle: String? = null
        var progDesc: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        currentChannelId = parser.getAttributeValue(null, "id")
                        currentChannelId?.let { id ->
                            val key = idKey(id)
                            if (key in wantedIds) channelKeys.getOrPut(id) { mutableSetOf() }.add(key)
                        }
                    }
                    "display-name" -> {
                        val id = currentChannelId
                        if (id != null && progChannel == null) {
                            val key = nameKey(parser.nextText())
                            if (key in wantedNames) channelKeys.getOrPut(id) { mutableSetOf() }.add(key)
                        }
                    }
                    "programme" -> {
                        currentChannelId = null
                        val channelId = parser.getAttributeValue(null, "channel")
                        val start = parseTime(parser.getAttributeValue(null, "start"))
                        val stop = parseTime(parser.getAttributeValue(null, "stop"))
                        if (channelId != null && start > 0 && stop > start &&
                            stop > windowStart && start < windowEnd &&
                            (channelKeys.containsKey(channelId) || idKey(channelId) in wantedIds)
                        ) {
                            progChannel = channelId
                            progStart = start
                            progStop = stop
                            progTitle = null
                            progDesc = null
                        } else {
                            progChannel = null
                        }
                    }
                    "title" -> if (progChannel != null && progTitle == null) progTitle = parser.nextText().trim()
                    "desc" -> if (progChannel != null && progDesc == null) progDesc = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                val channelId = progChannel
                val title = progTitle
                if (channelId != null && !title.isNullOrEmpty()) {
                    val programme = Programme(progStart, progStop, title, progDesc?.takeIf { it.isNotEmpty() })
                    val keys = channelKeys[channelId] ?: setOf(idKey(channelId))
                    keys.forEach { key -> result.getOrPut(key) { mutableListOf() }.add(programme) }
                }
                progChannel = null
            }
            event = parser.next()
        }

        return result.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
    }

    private fun parseTime(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val trimmed = value.trim()
        return try {
            if (trimmed.length > 14 && trimmed.contains(' ')) {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(trimmed)?.time ?: 0L
            } else {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(trimmed.take(14))?.time ?: 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /** iptv-org kimlikleri "TRT1.tr@HD" biçiminde olabilir; "@" sonrası atılır. */
    private fun idKey(id: String) = "id:" + id.substringBefore('@').trim().lowercase(Locale.ROOT)

    private fun nameKey(name: String) = "name:" + TextUtil.compact(
        name.replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .replace(Regex("""(?i)\b(hd|fhd|uhd|sd|4k)\b"""), "")
    )
}
