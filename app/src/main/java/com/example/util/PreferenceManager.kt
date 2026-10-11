package com.example.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "kapukaya_tv_prefs"
        private const val KEY_BOOT_ON_STARTUP = "key_boot_on_startup"
        private const val KEY_M3U_URL = "key_m3u_url"
        private const val KEY_FAVORITES_ORDER = "key_favorites_order_json"
        const val DEFAULT_M3U_URL = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    }

    fun isBootOnStartupEnabled(): Boolean {
        return prefs.getBoolean(KEY_BOOT_ON_STARTUP, false)
    }

    fun setBootOnStartup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT_ON_STARTUP, enabled).apply()
    }

    fun getM3uUrl(): String {
        return prefs.getString(KEY_M3U_URL, DEFAULT_M3U_URL) ?: DEFAULT_M3U_URL
    }

    fun setM3uUrl(url: String) {
        prefs.edit().putString(KEY_M3U_URL, url).apply()
    }

    fun getFavoritesList(): List<String> {
        val json = prefs.getString(KEY_FAVORITES_ORDER, null)
        if (!json.isNullOrBlank()) {
            try {
                val array = JSONArray(json)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    list.add(array.getString(i))
                }
                return list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return emptyList()
    }

    fun setFavoritesList(list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        prefs.edit().putString(KEY_FAVORITES_ORDER, array.toString()).apply()
    }

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

    fun isFavorite(channelId: String): Boolean {
        return getFavoritesList().contains(channelId)
    }

    fun moveFavoriteUp(channelId: String) {
        val list = getFavoritesList().toMutableList()
        val index = list.indexOf(channelId)
        if (index > 0) {
            val temp = list[index - 1]
            list[index - 1] = list[index]
            list[index] = temp
            setFavoritesList(list)
        }
    }

    fun moveFavoriteDown(channelId: String) {
        val list = getFavoritesList().toMutableList()
        val index = list.indexOf(channelId)
        if (index != -1 && index < list.size - 1) {
            val temp = list[index + 1]
            list[index + 1] = list[index]
            list[index] = temp
            setFavoritesList(list)
        }
    }
}
