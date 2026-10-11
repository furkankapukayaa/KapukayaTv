package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.entity.Category
import com.example.data.db.entity.CategoryChannelCrossRef
import com.example.data.model.Channel
import com.example.data.parser.M3uParser
import com.example.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Locale

class ChannelRepository(private val context: Context) {

    private val prefManager = PreferenceManager(context)
    private val db = AppDatabase.getInstance(context)

    companion object {
        @Volatile
        private var INSTANCE: ChannelRepository? = null

        fun getInstance(context: Context): ChannelRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = ChannelRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        // Bilinen Popüler Türk Kanallarının Garantili Yüksek Çözünürlüklü Resmi Logoları
        val KNOWN_CHANNEL_LOGOS = mapOf(
            "trt 1" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/82/TRT_1_logo_2021.svg/512px-TRT_1_logo_2021.svg.png",
            "kanal 7" to "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4b/Kanal_7_logo.svg/512px-Kanal_7_logo.svg.png",
            "star" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f6/Star_TV_logo.svg/512px-Star_TV_logo.svg.png",
            "show" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cc/Show_TV_logo_2020.png/512px-Show_TV_logo_2020.png",
            "atv" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1b/ATV_Turkey_logo.svg/512px-ATV_Turkey_logo.svg.png",
            "kanal d" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/Kanal_D_logo.svg/512px-Kanal_D_logo.svg.png",
            "now" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c2/NOW_Turkey_logo.svg/512px-NOW_Turkey_logo.svg.png",
            "fox" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c2/NOW_Turkey_logo.svg/512px-NOW_Turkey_logo.svg.png",
            "tv8" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/23/TV8_logo.svg/512px-TV8_logo.svg.png",
            "360" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a1/360_logo.svg/512px-360_logo.svg.png",
            "teve2" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3c/Teve2_logo.svg/512px-Teve2_logo.svg.png",
            "a2" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/A2_logo.svg/512px-A2_logo.svg.png",
            "beyaz" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a4/Beyaz_TV_logo.png/512px-Beyaz_TV_logo.png",
            "trt haber" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b3/TRT_Haber_logo_2021.svg/512px-TRT_Haber_logo_2021.svg.png",
            "haberturk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/28/Habert%C3%BCrk_TV_logo.svg/512px-Habert%C3%BCrk_TV_logo.svg.png",
            "habertürk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/28/Habert%C3%BCrk_TV_logo.svg/512px-Habert%C3%BCrk_TV_logo.svg.png",
            "tgrt" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d4/TGRT_Haber_logo.svg/512px-TGRT_Haber_logo.svg.png",
            "bloomberg" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/30/Bloomberg_HT_logo.svg/512px-Bloomberg_HT_logo.svg.png",
            "bein" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b8/BeIN_Sports_Haber_logo.png/512px-BeIN_Sports_Haber_logo.png",
            "cnn" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a7/CNN_T%C3%BCrk_logo.svg/512px-CNN_T%C3%BCrk_logo.svg.png",
            "ntv" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/30/NTV_logo_Turkey.svg/512px-NTV_logo_Turkey.svg.png",
            "global" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/Haber_Global_logo.svg/512px-Haber_Global_logo.svg.png",
            "a haber" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/69/A_Haber_logo.svg/512px-A_Haber_logo.svg.png",
            "tv100" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/90/TV100_logo.svg/512px-TV100_logo.svg.png",
            "sozcu" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/14/S%C3%B6zc%C3%BC_TV_logo.svg/512px-S%C3%B6zc%C3%BC_TV_logo.svg.png",
            "sözcü" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/14/S%C3%B6zc%C3%BC_TV_logo.svg/512px-S%C3%B6zc%C3%BC_TV_logo.svg.png",
            "halk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bd/Halk_TV_logo.png/512px-Halk_TV_logo.png",
            "24" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/24_TV_logo.png/512px-24_TV_logo.png",
            "ulke" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f0/%C3%9Clke_TV_logo.svg/512px-%C3%9Clke_TV_logo.svg.png",
            "ülke" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f0/%C3%9Clke_TV_logo.svg/512px-%C3%9Clke_TV_logo.svg.png",
            "cartoon" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/80/Cartoon_Network_2010_logo.svg/512px-Cartoon_Network_2010_logo.svg.png",
            "trt cocuk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/TRT_%C3%87ocuk_logo_2021.svg/512px-TRT_%C3%87ocuk_logo_2021.svg.png",
            "trt çocuk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/TRT_%C3%87ocuk_logo_2021.svg/512px-TRT_%C3%87ocuk_logo_2021.svg.png",
            "minika go" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3b/Minika_Go_logo.png/512px-Minika_Go_logo.png",
            "minika cocuk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/Minika_%C3%87ocuk_logo.png/512px-Minika_%C3%87ocuk_logo.png",
            "minika çocuk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/Minika_%C3%87ocuk_logo.png/512px-Minika_%C3%87ocuk_logo.png",
            "trt belgesel" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/TRT_Belgesel_logo_2021.svg/512px-TRT_Belgesel_logo_2021.svg.png",
            "dmax" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c8/DMAX_Logo.svg/512px-DMAX_Logo.svg.png",
            "nat geo" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6a/National_Geographic_Channel_logo.svg/512px-National_Geographic_Channel_logo.svg.png",
            "tlc" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/TLC_logo.svg/512px-TLC_logo.svg.png",
            "discovery" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/12/Discovery_Channel_logo_2019.svg/512px-Discovery_Channel_logo_2019.svg.png"
        )
    }

    private var allChannels: List<Channel> = emptyList()

    private fun normalize(str: String): String {
        return str.lowercase(Locale.ROOT)
            .replace('ç', 'c')
            .replace('ğ', 'g')
            .replace('ı', 'i')
            .replace('ö', 'o')
            .replace('ş', 's')
            .replace('ü', 'u')
            .replace("[^a-z0-9]".toRegex(), "")
    }

    fun getAllChannels(): List<Channel> {
        val favs = prefManager.getFavoritesList().toSet()
        return allChannels.map { it.copy(isFavorite = favs.contains(it.id)) }
    }

    fun getFavoriteChannels(): List<Channel> {
        val favIds = prefManager.getFavoritesList()
        val channelMap = getAllChannels().associateBy { it.id }

        val result = mutableListOf<Channel>()
        favIds.forEach { id ->
            channelMap[id]?.let { ch ->
                result.add(ch.copy(isFavorite = true))
            }
        }

        // Favoriler sekmesinde kullanıcının özel sıralamasına göre 1..N numaralandır
        return result.mapIndexed { index, channel ->
            channel.copy(channelNumber = index + 1)
        }
    }

    suspend fun loadChannels(m3uUrl: String = prefManager.getM3uUrl()): List<Channel> =
        withContext(Dispatchers.IO) {
            val favs = prefManager.getFavoritesList().toSet()
            val fetched = M3uParser.fetchAndParse(m3uUrl)

            val trLocale = Locale.forLanguageTag("tr-TR")

            // Logoları doğrula: M3U logosu yoksa veya bilinen kanallardan biriyse kaliteli resmi logoyu atar
            val processedFetched = fetched.map { ch ->
                var logo = ch.logoUrl
                if (logo.isNullOrBlank()) {
                    val normName = normalize(ch.name)
                    val matchedKey = KNOWN_CHANNEL_LOGOS.keys.find { key -> normName.contains(normalize(key)) }
                    if (matchedKey != null) {
                        logo = KNOWN_CHANNEL_LOGOS[matchedKey]
                    }
                }
                ch.copy(
                    logoUrl = logo,
                    isFavorite = favs.contains(ch.id)
                )
            }

            // Türkçe alfabeye uygun A'dan Z'ye sıralama
            val sortedAll = processedFetched.sortedBy { it.name.lowercase(trLocale) }

            // Tüm kanallara 1, 2, 3... N şeklinde sıralı kanal numarası ver
            allChannels = sortedAll.mapIndexed { i, channel ->
                channel.copy(channelNumber = i + 1)
            }

            // Room DB önbelleğine kaydet
            try {
                db.channelDao().insertChannels(allChannels)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            getAllChannels()
        }

    fun toggleFavorite(channelId: String): Boolean {
        val newFavState = prefManager.toggleFavorite(channelId)
        return newFavState
    }

    fun moveFavoriteUp(channelId: String) {
        prefManager.moveFavoriteUp(channelId)
    }

    fun moveFavoriteDown(channelId: String) {
        prefManager.moveFavoriteDown(channelId)
    }

    // Room DB Kategori işlemleri
    fun getAllCategoriesFlow(): Flow<List<Category>> = db.categoryDao().getAllCategories()

    suspend fun getAllCategoriesList(): List<Category> = withContext(Dispatchers.IO) {
        db.categoryDao().getAllCategoriesList()
    }

    suspend fun createCategory(name: String): Long = withContext(Dispatchers.IO) {
        db.categoryDao().insertCategory(Category(name = name))
    }

    suspend fun updateCategory(category: Category) = withContext(Dispatchers.IO) {
        db.categoryDao().updateCategory(category)
    }

    suspend fun deleteCategory(category: Category) = withContext(Dispatchers.IO) {
        db.categoryDao().deleteCategoryCrossRefs(category.id)
        db.categoryDao().deleteCategory(category)
    }

    suspend fun addChannelToCategory(categoryId: Long, channel: Channel) = withContext(Dispatchers.IO) {
        db.channelDao().insertChannel(channel)
        db.categoryChannelCrossRefDao().insertCrossRef(
            CategoryChannelCrossRef(categoryId = categoryId, channelId = channel.id)
        )
    }

    suspend fun getChannelsForCategory(categoryId: Long): List<Channel> = withContext(Dispatchers.IO) {
        val channels = db.categoryChannelCrossRefDao().getChannelsForCategory(categoryId)
        val trLocale = Locale.forLanguageTag("tr-TR")
        channels.sortedBy { it.name.lowercase(trLocale) }.mapIndexed { i, ch ->
            ch.copy(channelNumber = i + 1)
        }
    }
}
