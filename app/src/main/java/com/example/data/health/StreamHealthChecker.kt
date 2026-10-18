package com.example.data.health

import com.example.data.model.Channel
import com.example.data.parser.M3uParser
import com.example.data.parser.StreamSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Kanalların yayın adreslerine kısa bir istek atarak çalışıp çalışmadığını kontrol eder.
 * Yayının tamamı indirilmez; yalnızca ilk birkaç KB okunur.
 */
object StreamHealthChecker {

    private const val PARALLEL_REQUESTS = 6
    private const val BYTES_TO_READ = 2048

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun check(channels: List<Channel>, onResult: (Channel, Boolean) -> Unit) = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(PARALLEL_REQUESTS)
        coroutineScope {
            channels.map { channel ->
                async {
                    semaphore.withPermit {
                        // Kaynaklardan biri açılıyorsa kanal çalışıyor sayılır
                        val ok = channel.allStreamUrls().take(3).any { isReachable(it) }
                        synchronized(this@StreamHealthChecker) { onResult(channel, ok) }
                    }
                }
            }.awaitAll()
        }
    }

    fun isReachable(rawUrl: String): Boolean {
        return try {
            val source = StreamSource.parse(rawUrl)
            if (!source.url.startsWith("http", ignoreCase = true)) return true // rtmp/udp vb. kontrol edilemez
            val builder = Request.Builder().url(source.url)
            builder.header("User-Agent", source.userAgent ?: M3uParser.USER_AGENT)
            source.headers.forEach { (key, value) ->
                if (!key.equals("User-Agent", ignoreCase = true)) builder.header(key, value)
            }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val buffer = ByteArray(BYTES_TO_READ)
                body.byteStream().read(buffer) > 0
            }
        } catch (e: Exception) {
            false
        }
    }
}
