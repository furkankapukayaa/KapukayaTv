package com.example.remote

import android.content.Context
import android.util.Log
import com.example.data.backup.BackupManager
import com.example.data.repository.ChannelRepository
import com.example.util.PreferenceManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * "Telefondan Yönet" için küçük bir yerel web sunucusu.
 * Yalnızca ekran açıkken çalışır ve her istekte QR kodla paylaşılan rastgele anahtarı (token) ister.
 *
 * Uç noktalar:
 *  GET  /               → yönetim sayfası (HTML)
 *  GET  /api/state      → listeler, EPG adresi, kanal sayısı
 *  POST /api/playlist   → {"name","url"} yeni liste ekler ve etkinleştirir
 *  POST /api/activate   → {"id"} kayıtlı listeye geçer
 *  POST /api/epg        → {"url"} EPG adresini kaydeder
 *  GET  /api/backup     → yedek dosyasını indirir
 *  POST /api/restore    → yedek JSON'unu geri yükler
 */
class RemoteConfigServer(
    private val context: Context,
    private val onEvent: (String) -> Unit
) {
    private val prefs = PreferenceManager(context)
    private val repository = ChannelRepository.getInstance(context)
    private val backupManager = BackupManager(context)

    val token: String = generateToken()

    @Volatile
    private var serverSocket: ServerSocket? = null

    var port: Int = -1
        private set

    /** Sunucuyu başlatır; başarılıysa dinlenen portu döndürür. */
    fun start(): Int {
        if (serverSocket != null) return port
        for (candidate in PORT_START until PORT_START + 10) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate))
                serverSocket = socket
                port = candidate
                break
            } catch (e: Exception) {
                // Port kullanımda, bir sonrakini dene
            }
        }
        val socket = serverSocket ?: return -1

        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    Thread({ handle(client) }, "remote-client").apply { isDaemon = true }.start()
                } catch (e: SocketException) {
                    break // stop() çağrıldı
                } catch (e: Exception) {
                    Log.w(TAG, "Bağlantı kabul edilemedi", e)
                }
            }
        }, "remote-server").apply { isDaemon = true }.start()
        return port
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Yoksay
        }
        serverSocket = null
    }

    // ---------------------------------------------------------------------------------------

    private class Request(val method: String, val path: String, val query: Map<String, String>, val body: String)

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 15_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                val request = readRequest(input) ?: return

                if (request.query["t"] != token) {
                    respond(output, 403, "text/plain; charset=utf-8", "Geçersiz bağlantı. TV ekranındaki QR kodu yeniden okutun.")
                    return
                }

                when {
                    request.method == "GET" && request.path == "/" ->
                        respond(output, 200, "text/html; charset=utf-8", RemotePage.html())

                    request.method == "GET" && request.path == "/api/state" ->
                        respondJson(output, stateJson())

                    request.method == "POST" && request.path == "/api/playlist" -> {
                        val o = JSONObject(request.body)
                        val url = o.optString("url").trim()
                        val name = o.optString("name").trim().ifEmpty { "Liste" }
                        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                            respondJson(output, result(false, "Geçerli bir adres girin (http:// veya https://)"))
                            return
                        }
                        val playlist = prefs.addPlaylist(name, url)
                        onEvent("Yeni liste eklendi: $name — kanallar indiriliyor…")
                        val refresh = runBlocking { repository.switchPlaylist(playlist.id) }
                        val message = refresh.error?.let { "Liste eklendi ama indirilemedi: $it" }
                            ?: "$name etkinleştirildi · ${refresh.channels.size} kanal yüklendi"
                        onEvent(message)
                        respondJson(output, result(refresh.error == null, message))
                    }

                    request.method == "POST" && request.path == "/api/activate" -> {
                        val id = JSONObject(request.body).optString("id")
                        val playlist = prefs.getPlaylists().firstOrNull { it.id == id }
                        if (playlist == null) {
                            respondJson(output, result(false, "Liste bulunamadı"))
                            return
                        }
                        val refresh = runBlocking { repository.switchPlaylist(playlist.id) }
                        val message = refresh.error?.let { "Listeye geçildi ama indirilemedi: $it" }
                            ?: "${playlist.name} etkinleştirildi · ${refresh.channels.size} kanal"
                        onEvent(message)
                        respondJson(output, result(refresh.error == null, message))
                    }

                    request.method == "POST" && request.path == "/api/epg" -> {
                        val url = JSONObject(request.body).optString("url").trim()
                        prefs.setEpgUrl(url)
                        val message = if (url.isEmpty()) {
                            "EPG adresi temizlendi"
                        } else {
                            val loaded = runBlocking { repository.loadEpg(force = true) }
                            if (loaded?.isSuccess == true) {
                                "Yayın akışı yüklendi · ${loaded.getOrDefault(0)} kanal eşleşti"
                            } else {
                                "EPG kaydedildi ama yüklenemedi: ${loaded?.exceptionOrNull()?.message ?: "bilinmeyen hata"}"
                            }
                        }
                        onEvent(message)
                        respondJson(output, result(true, message))
                    }

                    request.method == "GET" && request.path == "/api/backup" -> {
                        val json = runBlocking { backupManager.export() }
                        onEvent("Yedek telefona indirildi")
                        respond(
                            output, 200, "application/json; charset=utf-8", json,
                            extraHeaders = "Content-Disposition: attachment; filename=\"${BackupManager.suggestedFileName()}\"\r\n"
                        )
                    }

                    request.method == "POST" && request.path == "/api/restore" -> {
                        val message = try {
                            val r = runBlocking { backupManager.restore(request.body) }
                            runBlocking { repository.switchPlaylist(prefs.getActivePlaylist().id) }
                            "Yedek yüklendi · ${r.favorites} favori, ${r.categories} kategori, ${r.playlists} liste"
                        } catch (e: Exception) {
                            "Yedek yüklenemedi: ${e.message}"
                        }
                        onEvent(message)
                        respondJson(output, result(!message.startsWith("Yedek yüklenemedi"), message))
                    }

                    else -> respond(output, 404, "text/plain; charset=utf-8", "Bulunamadı")
                }
            } catch (e: Exception) {
                Log.w(TAG, "İstek işlenemedi", e)
            }
        }
    }

    private fun stateJson(): JSONObject {
        val active = prefs.getActivePlaylist()
        val playlists = JSONArray()
        prefs.getPlaylists().forEach { p ->
            playlists.put(JSONObject().put("id", p.id).put("name", p.name).put("url", p.url).put("active", p.id == active.id))
        }
        return JSONObject()
            .put("playlists", playlists)
            .put("epgUrl", prefs.getEpgUrl())
            .put("channelCount", repository.getAllChannels().size)
    }

    private fun result(ok: Boolean, message: String) = JSONObject().put("ok", ok).put("message", message)

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val target = parts[1]

        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > MAX_BODY_BYTES) return null

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n == -1) break
            read += n
        }

        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")
            .split('&')
            .filter { it.contains('=') }
            .associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        return Request(method, path, query, String(body, 0, read, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
            if (buffer.size() > 8192) return null
        }
        return buffer.toString("ISO-8859-1")
    }

    private fun respondJson(output: OutputStream, json: JSONObject) =
        respond(output, 200, "application/json; charset=utf-8", json.toString())

    private fun respond(output: OutputStream, code: Int, contentType: String, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    companion object {
        private const val TAG = "RemoteConfigServer"
        private const val PORT_START = 8765
        private const val MAX_BODY_BYTES = 5 * 1024 * 1024

        private fun generateToken(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }
    }
}
