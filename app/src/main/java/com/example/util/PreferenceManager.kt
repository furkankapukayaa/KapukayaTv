package com.example.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Kayıtlı M3U listesi (profil). */
data class Playlist(val id: String, val name: String, val url: String)

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "kapukaya_tv_prefs"
        private const val KEY_BOOT_ON_STARTUP = "key_boot_on_startup"
        private const val KEY_M3U_URL = "key_m3u_url"
        private const val KEY_FAVORITES_ORDER = "key_favorites_order_json"
        private const val KEY_RECENTS = "key_recent_channels_json"
        private const val KEY_RESUME_LAST_CHANNEL = "key_resume_last_channel"
        private const val KEY_RESIZE_MODE = "key_resize_mode"
        private const val KEY_EPG_URL = "key_epg_url"
        private const val KEY_MERGE_DUPLICATES = "key_merge_duplicates"
        private const val KEY_SHOW_GROUP_TABS = "key_show_group_tabs"
        private const val KEY_LAST_REFRESH = "key_last_refresh_time"
        private const val KEY_PLAYLIST_EPG_URL = "key_playlist_epg_url"
        private const val KEY_PLAYLISTS = "key_playlists_json"
        private const val KEY_ACTIVE_PLAYLIST = "key_active_playlist_id"
        private const val KEY_HIDDEN_CHANNELS = "key_hidden_channels_json"
        private const val KEY_PIN_HASH = "key_parental_pin_hash"
        private const val KEY_LOCKED_ITEMS = "key_parental_locked_items_json"
        private const val KEY_BROKEN_CHANNELS = "key_broken_channels_json"
        private const val KEY_HIDE_BROKEN = "key_hide_broken_channels"
        private const val KEY_LAST_HEALTH_CHECK = "key_last_health_check"
        private const val KEY_TV_CHANNEL_ID = "key_tv_home_channel_id"
        private const val KEY_TV_PROGRAM_IDS = "key_tv_home_program_ids_json"
        private const val MAX_RECENTS = 20
        const val DEFAULT_M3U_URL = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    }

    fun isBootOnStartupEnabled(): Boolean = prefs.getBoolean(KEY_BOOT_ON_STARTUP, false)

    fun setBootOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT_ON_STARTUP, enabled).apply()
    }

    fun isResumeLastChannelEnabled(): Boolean = prefs.getBoolean(KEY_RESUME_LAST_CHANNEL, false)

    fun setResumeLastChannel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_LAST_CHANNEL, enabled).apply()
    }

    fun isMergeDuplicatesEnabled(): Boolean = prefs.getBoolean(KEY_MERGE_DUPLICATES, true)

    fun setMergeDuplicates(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MERGE_DUPLICATES, enabled).apply()
    }

    fun isGroupTabsEnabled(): Boolean = prefs.getBoolean(KEY_SHOW_GROUP_TABS, true)

    fun setGroupTabsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_GROUP_TABS, enabled).apply()
    }

    // ---- M3U listeleri (profiller) ----

    fun getPlaylists(): List<Playlist> {
        val json = prefs.getString(KEY_PLAYLISTS, null)
        val list = mutableListOf<Playlist>()
        if (!json.isNullOrBlank()) {
            try {
                val array = JSONArray(json)
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    list.add(Playlist(o.getString("id"), o.getString("name"), o.getString("url")))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (list.isEmpty()) {
            // Eski sürümden geçiş: tek M3U adresi "Varsayılan Liste" olur
            val legacyUrl = prefs.getString(KEY_M3U_URL, DEFAULT_M3U_URL) ?: DEFAULT_M3U_URL
            list.add(Playlist("default", "Varsayılan Liste", legacyUrl))
            savePlaylists(list)
        }
        return list
    }

    fun savePlaylists(list: List<Playlist>) {
        val array = JSONArray()
        list.forEach { p ->
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("url", p.url))
        }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    fun getActivePlaylist(): Playlist {
        val list = getPlaylists()
        val activeId = prefs.getString(KEY_ACTIVE_PLAYLIST, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActivePlaylist(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PLAYLIST, id).apply()
    }

    fun addPlaylist(name: String, url: String): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), name, url)
        savePlaylists(getPlaylists() + playlist)
        return playlist
    }

    fun updatePlaylist(playlist: Playlist) {
        savePlaylists(getPlaylists().map { if (it.id == playlist.id) playlist else it })
    }

    /** Son kalan liste silinemez. Silinen liste etkinse ilk liste etkin olur. */
    fun removePlaylist(id: String): Boolean {
        val list = getPlaylists()
        if (list.size <= 1) return false
        val activeId = getActivePlaylist().id
        val remaining = list.filter { it.id != id }
        savePlaylists(remaining)
        if (activeId == id) setActivePlaylist(remaining.first().id)
        return true
    }

    /** Etkin listenin adresi. */
    fun getM3uUrl(): String = getActivePlaylist().url

    /** Etkin listenin adresini değiştirir. */
    fun setM3uUrl(url: String) {
        updatePlaylist(getActivePlaylist().copy(url = url))
    }

    // ---- Gizlenen kanallar ----

    fun getHiddenChannelIds(): Set<String> = readList(KEY_HIDDEN_CHANNELS).toSet()

    fun setHiddenChannelIds(ids: Collection<String>) = writeList(KEY_HIDDEN_CHANNELS, ids.toList())

    fun hideChannel(channelId: String) = setHiddenChannelIds(getHiddenChannelIds() + channelId)

    fun unhideChannel(channelId: String) = setHiddenChannelIds(getHiddenChannelIds() - channelId)

    // ---- Ebeveyn kilidi ----

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hash(pin)).apply()
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
        setLockedItems(emptySet())
    }

    fun checkPin(pin: String): Boolean = hasPin() && prefs.getString(KEY_PIN_HASH, null) == hash(pin)

    /** Kilitli öğeler: "group:Haber" veya "custom:3" biçiminde. */
    fun getLockedItems(): Set<String> = readList(KEY_LOCKED_ITEMS).toSet()

    fun setLockedItems(items: Collection<String>) = writeList(KEY_LOCKED_ITEMS, items.toList())

    fun getPinHash(): String? = prefs.getString(KEY_PIN_HASH, null)

    fun setPinHash(value: String?) {
        prefs.edit().putString(KEY_PIN_HASH, value).apply()
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(("kapukaya:" + pin).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- Yayın durumu (çalışmayan kanallar) ----

    /** Çalışmadığı tespit edilen kanallar: kimlik -> tespit zamanı. */
    fun getBrokenChannels(): Map<String, Long> {
        val json = prefs.getString(KEY_BROKEN_CHANNELS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(json)
            o.keys().asSequence().associateWith { o.getLong(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setBrokenChannels(map: Map<String, Long>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(KEY_BROKEN_CHANNELS, o.toString()).apply()
    }

    fun markBroken(channelId: String) = setBrokenChannels(getBrokenChannels() + (channelId to System.currentTimeMillis()))

    fun markWorking(channelId: String) {
        val map = getBrokenChannels()
        if (channelId in map) setBrokenChannels(map - channelId)
    }

    fun isHideBrokenEnabled(): Boolean = prefs.getBoolean(KEY_HIDE_BROKEN, false)

    fun setHideBroken(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_BROKEN, enabled).apply()
    }

    fun getLastHealthCheck(): Long = prefs.getLong(KEY_LAST_HEALTH_CHECK, 0L)

    fun setLastHealthCheck(time: Long) {
        prefs.edit().putLong(KEY_LAST_HEALTH_CHECK, time).apply()
    }

    // ---- Android TV ana ekran kanalı ----

    fun getTvHomeChannelId(): Long = prefs.getLong(KEY_TV_CHANNEL_ID, -1L)

    fun setTvHomeChannelId(id: Long) {
        prefs.edit().putLong(KEY_TV_CHANNEL_ID, id).apply()
    }

    fun getTvHomeProgramIds(): List<Long> = readList(KEY_TV_PROGRAM_IDS).mapNotNull { it.toLongOrNull() }

    fun setTvHomeProgramIds(ids: List<Long>) = writeList(KEY_TV_PROGRAM_IDS, ids.map { it.toString() })

    /** Kullanıcının elle girdiği EPG adresi (boşsa listedeki x-tvg-url kullanılır). */
    fun getEpgUrl(): String = prefs.getString(KEY_EPG_URL, "") ?: ""

    fun setEpgUrl(url: String) {
        prefs.edit().putString(KEY_EPG_URL, url).apply()
    }

    fun getPlaylistEpgUrl(): String? = prefs.getString(KEY_PLAYLIST_EPG_URL, null)

    fun setPlaylistEpgUrl(url: String?) {
        prefs.edit().putString(KEY_PLAYLIST_EPG_URL, url).apply()
    }

    /** Kullanılacak EPG adresi: önce kullanıcının girdiği, yoksa listenin kendi adresi. */
    fun getEffectiveEpgUrl(): String? =
        getEpgUrl().takeIf { it.isNotBlank() } ?: getPlaylistEpgUrl()?.takeIf { it.isNotBlank() }

    fun getLastRefreshTime(): Long = prefs.getLong(KEY_LAST_REFRESH, 0L)

    fun setLastRefreshTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_REFRESH, time).apply()
    }

    fun getResizeMode(): Int = prefs.getInt(KEY_RESIZE_MODE, 0)

    fun setResizeMode(mode: Int) {
        prefs.edit().putInt(KEY_RESIZE_MODE, mode).apply()
    }

    // ---- Son izlenenler ----

    fun getRecentChannelIds(): List<String> = readList(KEY_RECENTS)

    fun addRecentChannel(channelId: String) {
        val list = getRecentChannelIds().toMutableList()
        list.remove(channelId)
        list.add(0, channelId)
        writeList(KEY_RECENTS, list.take(MAX_RECENTS))
    }

    fun setRecentChannelIds(ids: List<String>) = writeList(KEY_RECENTS, ids.take(MAX_RECENTS))

    fun clearRecents() {
        writeList(KEY_RECENTS, emptyList())
    }

    fun getLastChannelId(): String? = getRecentChannelIds().firstOrNull()

    // ---- Favoriler ----

    fun getFavoritesList(): List<String> = readList(KEY_FAVORITES_ORDER)

    fun setFavoritesList(list: List<String>) = writeList(KEY_FAVORITES_ORDER, list)

    fun addFavorite(channelId: String) {
        val list = getFavoritesList().toMutableList()
        if (!list.contains(channelId)) {
            list.add(channelId)
            setFavoritesList(list)
        }
    }

    fun removeFavorite(channelId: String) {
        val list = getFavoritesList().toMutableList()
        list.remove(channelId)
        setFavoritesList(list)
    }

    fun toggleFavorite(channelId: String): Boolean {
        val isFav = isFavorite(channelId)
        if (isFav) {
            removeFavorite(channelId)
        } else {
            addFavorite(channelId)
        }
        return !isFav
    }

    fun isFavorite(channelId: String): Boolean = getFavoritesList().contains(channelId)

    fun moveFavoriteUp(channelId: String) = moveFavorite(channelId, -1)

    fun moveFavoriteDown(channelId: String) = moveFavorite(channelId, 1)

    /** Favoriyi [delta] kadar kaydırır (negatif = yukarı). Yeni konumu döndürür. */
    fun moveFavorite(channelId: String, delta: Int): Int {
        val list = getFavoritesList().toMutableList()
        val index = list.indexOf(channelId)
        if (index == -1) return -1
        val target = (index + delta).coerceIn(0, list.size - 1)
        if (target != index) {
            list.removeAt(index)
            list.add(target, channelId)
            setFavoritesList(list)
        }
        return target
    }

    private fun readList(key: String): List<String> {
        val json = prefs.getString(key, null)
        if (!json.isNullOrBlank()) {
            try {
                val array = JSONArray(json)
                return List(array.length()) { array.getString(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return emptyList()
    }

    private fun writeList(key: String, list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
