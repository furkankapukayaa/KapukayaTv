package com.example.data.backup

import android.content.Context
import com.example.BuildConfig
import com.example.data.model.Channel
import com.example.data.repository.ChannelRepository
import com.example.util.Playlist
import com.example.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Favoriler, kategoriler, M3U listeleri ve ayarların JSON olarak yedeklenmesi / geri yüklenmesi.
 * Aynı dosya hem cihazdan (dosya seçici) hem de telefondan yönetim sayfasından kullanılır.
 */
class BackupManager(private val context: Context) {

    private val prefs = PreferenceManager(context)
    private val repository = ChannelRepository.getInstance(context)

    data class RestoreResult(val categories: Int, val favorites: Int, val playlists: Int)

    suspend fun export(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", "KapukayaTv")
        root.put("format", FORMAT_VERSION)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        root.put("createdAt", System.currentTimeMillis())

        val playlists = JSONArray()
        prefs.getPlaylists().forEach { p ->
            playlists.put(JSONObject().put("id", p.id).put("name", p.name).put("url", p.url))
        }
        root.put("playlists", playlists)
        root.put("activePlaylistId", prefs.getActivePlaylist().id)
        root.put("epgUrl", prefs.getEpgUrl())

        root.put("favorites", JSONArray(prefs.getFavoritesList()))
        root.put("recents", JSONArray(prefs.getRecentChannelIds()))
        root.put("hiddenChannels", JSONArray(prefs.getHiddenChannelIds().toList()))

        root.put("parental", JSONObject().apply {
            put("pinHash", prefs.getPinHash() ?: JSONObject.NULL)
            put("lockedItems", JSONArray(prefs.getLockedItems().toList()))
        })

        root.put("settings", JSONObject().apply {
            put("bootOnStartup", prefs.isBootOnStartupEnabled())
            put("resumeLastChannel", prefs.isResumeLastChannelEnabled())
            put("mergeDuplicates", prefs.isMergeDuplicatesEnabled())
            put("groupTabs", prefs.isGroupTabsEnabled())
            put("hideBroken", prefs.isHideBrokenEnabled())
            put("resizeMode", prefs.getResizeMode())
        })

        val categories = JSONArray()
        repository.getAllCategoriesList().forEach { category ->
            val channels = JSONArray()
            repository.getChannelsForCategoryRaw(category.id).forEach { ch -> channels.put(channelToJson(ch)) }
            categories.put(JSONObject().put("name", category.name).put("channels", channels))
        }
        root.put("categories", categories)

        root.toString(2)
    }

    /** Yedeği geri yükler. Mevcut kategoriler silinmez; aynı adlı kategoriye kanallar eklenir. */
    suspend fun restore(json: String): RestoreResult = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        require(root.optString("app") == "KapukayaTv") { "Bu dosya bir Kapukaya TV yedeği değil" }

        var playlistCount = 0
        root.optJSONArray("playlists")?.let { array ->
            val list = (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Playlist(o.getString("id"), o.getString("name"), o.getString("url"))
            }
            if (list.isNotEmpty()) {
                prefs.savePlaylists(list)
                playlistCount = list.size
                root.optString("activePlaylistId").takeIf { id -> list.any { it.id == id } }
                    ?.let { prefs.setActivePlaylist(it) }
            }
        }
        if (root.has("epgUrl")) prefs.setEpgUrl(root.optString("epgUrl"))

        val favorites = root.optJSONArray("favorites").toStringList()
        prefs.setFavoritesList(favorites)
        prefs.setRecentChannelIds(root.optJSONArray("recents").toStringList())
        prefs.setHiddenChannelIds(root.optJSONArray("hiddenChannels").toStringList())

        root.optJSONObject("parental")?.let { parental ->
            val hash = if (parental.isNull("pinHash")) null else parental.optString("pinHash")
            prefs.setPinHash(hash?.takeIf { it.isNotBlank() })
            prefs.setLockedItems(parental.optJSONArray("lockedItems").toStringList())
        }

        root.optJSONObject("settings")?.let { s ->
            prefs.setBootOnStartup(s.optBoolean("bootOnStartup", prefs.isBootOnStartupEnabled()))
            prefs.setResumeLastChannel(s.optBoolean("resumeLastChannel", prefs.isResumeLastChannelEnabled()))
            prefs.setMergeDuplicates(s.optBoolean("mergeDuplicates", prefs.isMergeDuplicatesEnabled()))
            prefs.setGroupTabsEnabled(s.optBoolean("groupTabs", prefs.isGroupTabsEnabled()))
            prefs.setHideBroken(s.optBoolean("hideBroken", prefs.isHideBrokenEnabled()))
            prefs.setResizeMode(s.optInt("resizeMode", prefs.getResizeMode()))
        }

        // Kategoriler: eski kategori kimlikleri yeni cihazda farklı olacağından kilitli
        // özel kategoriler isimle eşleştirilir.
        val oldLocked = prefs.getLockedItems()
        val existing = repository.getAllCategoriesList().associateBy { it.name }
        var categoryCount = 0
        root.optJSONArray("categories")?.let { array ->
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val name = o.getString("name")
                val categoryId = existing[name]?.id ?: repository.createCategory(name)
                val channels = o.optJSONArray("channels") ?: JSONArray()
                for (j in 0 until channels.length()) {
                    jsonToChannel(channels.getJSONObject(j))?.let { repository.addChannelToCategory(categoryId, it) }
                }
                categoryCount++
            }
        }
        // Yedekteki "custom:<eskiId>" kilitleri bu cihazda geçersiz; yalnızca grup kilitleri korunur
        prefs.setLockedItems(oldLocked.filter { it.startsWith("group:") })
        repository.refreshLockCache()

        RestoreResult(categoryCount, favorites.size, playlistCount)
    }

    private fun channelToJson(ch: Channel) = JSONObject().apply {
        put("id", ch.id)
        put("name", ch.name)
        put("streamUrl", ch.streamUrl)
        put("logoUrl", ch.logoUrl ?: JSONObject.NULL)
        put("groupTitle", ch.groupTitle ?: JSONObject.NULL)
        put("tvgId", ch.tvgId ?: JSONObject.NULL)
    }

    private fun jsonToChannel(o: JSONObject): Channel? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val stream = o.optString("streamUrl").takeIf { it.isNotBlank() } ?: return null
        return Channel(
            id = id,
            name = o.optString("name", id),
            streamUrl = stream,
            logoUrl = o.optNullableString("logoUrl"),
            groupTitle = o.optNullableString("groupTitle"),
            tvgId = o.optNullableString("tvgId")
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    companion object {
        private const val FORMAT_VERSION = 1

        fun suggestedFileName(): String {
            val date = android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
            return "kapukayatv-yedek-$date.json"
        }
    }
}
