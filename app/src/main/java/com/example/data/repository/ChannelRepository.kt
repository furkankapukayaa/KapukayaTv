package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.entity.Category
import com.example.data.db.entity.CategoryChannelCrossRef
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.data.parser.DownloadResult
import com.example.data.parser.M3uParser
import com.example.util.PreferenceManager
import com.example.util.TextUtil
import com.example.data.health.StreamHealthChecker
import com.example.tv.TvHomeChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Arka plan yayın kontrolünün ilerlemesi. */
data class HealthCheckProgress(
    val running: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val broken: Int = 0
)

/** Liste yenileme sonucu. */
data class RefreshResult(
    val channels: List<Channel>,
    /** Ağdan indirilemediyse hata mesajı (bu durumda önbellekteki liste kullanılır). */
    val error: String? = null,
    val fromCache: Boolean = false
)

class ChannelRepository(private val context: Context) {

    private val prefManager = PreferenceManager(context)
    private val db = AppDatabase.getInstance(context)
    private val loadMutex = Mutex()

    /** Uygulama ömrü boyunca süren arka plan işleri (yayın kontrolü, TV ana ekranı). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null

    private val _healthProgress = MutableStateFlow(HealthCheckProgress())
    val healthProgress: StateFlow<HealthCheckProgress> = _healthProgress

    /** Her M3U listesinin kendi önbellek dosyası olur. */
    private fun cacheFile(): File {
        val playlistId = prefManager.getActivePlaylist().id
        val file = File(context.filesDir, "playlist_cache_$playlistId.m3u")
        // 1.1.0 öncesi tek önbellek dosyasını varsayılan listeye taşı
        val legacy = File(context.filesDir, PLAYLIST_CACHE_FILE)
        if (!file.exists() && legacy.exists() && playlistId == "default") {
            legacy.renameTo(file)
        }
        return file
    }

    companion object {
        private const val TAG = "ChannelRepository"
        private const val PLAYLIST_CACHE_FILE = "playlist_cache.m3u"

        @Volatile
        private var INSTANCE: ChannelRepository? = null

        fun getInstance(context: Context): ChannelRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChannelRepository(context.applicationContext).also { INSTANCE = it }
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
            "tgrt" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d4/TGRT_Haber_logo.svg/512px-TGRT_Haber_logo.svg.png",
            "bloomberg" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/30/Bloomberg_HT_logo.svg/512px-Bloomberg_HT_logo.svg.png",
            "bein" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b8/BeIN_Sports_Haber_logo.png/512px-BeIN_Sports_Haber_logo.png",
            "cnn" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a7/CNN_T%C3%BCrk_logo.svg/512px-CNN_T%C3%BCrk_logo.svg.png",
            "ntv" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/30/NTV_logo_Turkey.svg/512px-NTV_logo_Turkey.svg.png",
            "haber global" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/Haber_Global_logo.svg/512px-Haber_Global_logo.svg.png",
            "a haber" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/69/A_Haber_logo.svg/512px-A_Haber_logo.svg.png",
            "tv100" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/90/TV100_logo.svg/512px-TV100_logo.svg.png",
            "sozcu" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/14/S%C3%B6zc%C3%BC_TV_logo.svg/512px-S%C3%B6zc%C3%BC_TV_logo.svg.png",
            "halk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/bd/Halk_TV_logo.png/512px-Halk_TV_logo.png",
            "24" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/24_TV_logo.png/512px-24_TV_logo.png",
            "ulke" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f0/%C3%9Clke_TV_logo.svg/512px-%C3%9Clke_TV_logo.svg.png",
            "cartoon network" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/80/Cartoon_Network_2010_logo.svg/512px-Cartoon_Network_2010_logo.svg.png",
            "trt cocuk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f3/TRT_%C3%87ocuk_logo_2021.svg/512px-TRT_%C3%87ocuk_logo_2021.svg.png",
            "minika go" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3b/Minika_Go_logo.png/512px-Minika_Go_logo.png",
            "minika cocuk" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/36/Minika_%C3%87ocuk_logo.png/512px-Minika_%C3%87ocuk_logo.png",
            "trt belgesel" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/TRT_Belgesel_logo_2021.svg/512px-TRT_Belgesel_logo_2021.svg.png",
            "dmax" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/c8/DMAX_Logo.svg/512px-DMAX_Logo.svg.png",
            "national geographic" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/6a/National_Geographic_Channel_logo.svg/512px-National_Geographic_Channel_logo.svg.png",
            "tlc" to "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/TLC_logo.svg/512px-TLC_logo.svg.png",
            "discovery" to "https://upload.wikimedia.org/wikipedia/commons/thumb/1/12/Discovery_Channel_logo_2019.svg/512px-Discovery_Channel_logo_2019.svg.png"
        )

        // Logo eşleştirirken kanal adının sonunda kabul edilen ekler ("show" + "tv" = "showtv")
        private val LOGO_NAME_SUFFIXES = setOf("", "tv", "hd", "fhd", "tvhd", "turk", "turkiye", "tvturkiye")

        private val BRACKETS_REGEX = Regex("""\s*(\(.*?\)|\[.*?])\s*""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
        private val QUALITY_TOKEN_REGEX = Regex("""(?i)\b(4k|uhd|fhd|hd|sd)\b""")
    }

    @Volatile
    private var allChannels: List<Channel> = emptyList()

    /** Bu oturumda ağdan yenileme yapıldı mı? */
    @Volatile
    var refreshedThisSession = false
        private set

    /** Son yenileme denemesinin hatası (başarılıysa null). */
    @Volatile
    var lastRefreshError: String? = null
        private set

    /** Oynatıcıda yukarı/aşağı ile gezilecek kanal sırası (Intent ile büyük liste taşımamak için). */
    @Volatile
    var playbackQueue: List<Channel> = emptyList()

    val hasChannels: Boolean get() = allChannels.isNotEmpty()

    // ---- Ebeveyn kilidi ----

    /** PIN girildikten sonra uygulama kapanana kadar kilitli içerik görünür. */
    @Volatile
    var parentalUnlocked = false
        private set

    @Volatile
    private var lockedCategoryChannelIds: Set<String> = emptySet()

    fun isParentalLockActive(): Boolean =
        prefManager.hasPin() && prefManager.getLockedItems().isNotEmpty() && !parentalUnlocked

    fun unlockParental(pin: String): Boolean {
        val ok = prefManager.checkPin(pin)
        if (ok) parentalUnlocked = true
        return ok
    }

    fun lockParental() {
        parentalUnlocked = false
    }

    fun isGroupLocked(group: String): Boolean =
        isParentalLockActive() && "group:$group" in prefManager.getLockedItems()

    fun isCategoryLocked(categoryId: Long): Boolean =
        isParentalLockActive() && "custom:$categoryId" in prefManager.getLockedItems()

    /** Kilitli özel kategorilerdeki kanal kimliklerini yeniden okur. */
    suspend fun refreshLockCache() {
        withContext(Dispatchers.IO) { reloadLockedCategoryChannels() }
    }

    private suspend fun reloadLockedCategoryChannels() {
        val categoryIds = prefManager.getLockedItems()
            .filter { it.startsWith("custom:") }
            .mapNotNull { it.removePrefix("custom:").toLongOrNull() }
        lockedCategoryChannelIds = categoryIds.flatMap { id ->
            try {
                db.categoryChannelCrossRefDao().getChannelsForCategory(id).map { it.id }
            } catch (e: Exception) {
                emptyList<String>()
            }
        }.toSet()
    }

    // ---- Görünürlük (gizlenen, kilitli, çalışmayan kanallar) ----

    private inner class VisibilityFilter {
        private val hidden = prefManager.getHiddenChannelIds()
        private val lockActive = isParentalLockActive()
        private val lockedGroups = if (lockActive) {
            prefManager.getLockedItems().filter { it.startsWith("group:") }.map { it.removePrefix("group:") }.toSet()
        } else emptySet()
        private val broken = if (prefManager.isHideBrokenEnabled()) prefManager.getBrokenChannels().keys else emptySet()

        fun isVisible(ch: Channel): Boolean {
            if (ch.id in hidden || ch.id in broken) return false
            if (lockActive) {
                if (ch.id in lockedCategoryChannelIds) return false
                if (lockedGroups.isNotEmpty() && TextUtil.splitGroups(ch.groupTitle).any { it in lockedGroups }) return false
            }
            return true
        }
    }

    /** Kullanıcının görebileceği tüm kanallar (gizlenen/kilitli olanlar hariç). */
    fun getAllChannels(): List<Channel> {
        val favs = prefManager.getFavoritesList().toSet()
        val filter = VisibilityFilter()
        return allChannels.filter { filter.isVisible(it) }.map { it.copy(isFavorite = favs.contains(it.id)) }
    }

    /** Filtresiz tam liste (ayarlardaki gizlenen kanallar listesi gibi yerler için). */
    fun getAllChannelsUnfiltered(): List<Channel> = allChannels

    fun hideChannel(channelId: String) {
        prefManager.hideChannel(channelId)
        prefManager.removeFavorite(channelId)
        syncTvHomeAsync()
    }

    fun unhideChannels(channelIds: Collection<String>) {
        prefManager.setHiddenChannelIds(prefManager.getHiddenChannelIds() - channelIds.toSet())
    }

    // ---- Yayın durumu ----

    fun isBroken(channelId: String): Boolean = channelId in prefManager.getBrokenChannels()

    fun brokenChannelIds(): Set<String> = prefManager.getBrokenChannels().keys

    /** Oynatıcıdan gelen gerçek sonuç: açıldıysa çalışıyor, hiçbir kaynak açılmadıysa çalışmıyor. */
    fun reportPlaybackResult(channelId: String, success: Boolean) {
        if (success) prefManager.markWorking(channelId) else prefManager.markBroken(channelId)
    }

    /**
     * Tüm kanalları arka planda kontrol eder. [force] false ise son kontrol 24 saatten yeniyse
     * veya liste çok büyükse (300+ kanal) çalışmaz.
     */
    fun startHealthCheck(force: Boolean) {
        if (healthJob?.isActive == true) return
        val channels = allChannels
        if (channels.isEmpty()) return
        if (!force) {
            val recent = System.currentTimeMillis() - prefManager.getLastHealthCheck() < 24 * 60 * 60 * 1000L
            if (recent || channels.size > 300) return
        }
        healthJob = appScope.launch {
            _healthProgress.value = HealthCheckProgress(running = true, total = channels.size)
            val brokenIds = mutableSetOf<String>()
            val workingIds = mutableSetOf<String>()
            StreamHealthChecker.check(channels) { channel, ok ->
                if (ok) workingIds.add(channel.id) else brokenIds.add(channel.id)
                _healthProgress.value = HealthCheckProgress(
                    running = true,
                    checked = brokenIds.size + workingIds.size,
                    total = channels.size,
                    broken = brokenIds.size
                )
            }
            val now = System.currentTimeMillis()
            val map = prefManager.getBrokenChannels().toMutableMap()
            workingIds.forEach { map.remove(it) }
            brokenIds.forEach { map[it] = now }
            prefManager.setBrokenChannels(map)
            prefManager.setLastHealthCheck(now)
            _healthProgress.value = HealthCheckProgress(
                running = false,
                checked = channels.size,
                total = channels.size,
                broken = brokenIds.size
            )
        }
    }

    // ---- Android TV ana ekranı ----

    fun syncTvHomeAsync() {
        appScope.launch { TvHomeChannels.sync(context, this@ChannelRepository) }
    }

    // ---- M3U listeleri ----

    /** Etkin listeyi değiştirir: önce o listenin önbelleği yüklenir, ardından ağdan yenilenir. */
    suspend fun switchPlaylist(playlistId: String): RefreshResult = withContext(Dispatchers.IO) {
        prefManager.setActivePlaylist(playlistId)
        loadMutex.withLock { allChannels = emptyList() }
        refreshedThisSession = false
        loadFromCache()
        refresh()
    }

    fun findChannel(id: String?): Channel? {
        if (id == null) return null
        return getAllChannels().firstOrNull { it.id == id }
    }

    fun findByNumber(number: Int): Channel? = getAllChannels().firstOrNull { it.channelNumber == number }

    fun getFavoriteChannels(): List<Channel> {
        val favIds = prefManager.getFavoritesList()
        val channelMap = getAllChannels().associateBy { it.id }

        // Favoriler sekmesinde kullanıcının özel sıralamasına göre 1..N numaralandır
        return favIds.mapNotNull { channelMap[it] }
            .mapIndexed { index, channel -> channel.copy(isFavorite = true, channelNumber = index + 1) }
    }

    fun getRecentChannels(): List<Channel> {
        val channelMap = getAllChannels().associateBy { it.id }
        return prefManager.getRecentChannelIds().mapNotNull { channelMap[it] }
    }

    fun getChannelsForGroup(group: String): List<Channel> =
        getAllChannels().filter { group in TextUtil.splitGroups(it.groupTitle) }

    /** Gizlenen kanallar hariç listede geçen gruplar. */
    private fun visibleGroupSource(): List<Channel> {
        val hidden = prefManager.getHiddenChannelIds()
        return allChannels.filter { it.id !in hidden }
    }

    /** Listede geçen gruplar, kanal sayısına göre çoktan aza. */
    fun getGroups(): List<String> {
        val counts = LinkedHashMap<String, Int>()
        visibleGroupSource().forEach { ch ->
            TextUtil.splitGroups(ch.groupTitle).forEach { g -> counts[g] = (counts[g] ?: 0) + 1 }
        }
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase(TextUtil.TR_LOCALE) })
            .map { it.key }
    }

    fun recordWatched(channel: Channel) {
        prefManager.addRecentChannel(channel.id)
    }

    /**
     * Uygulama açılışında çağrılır: önce cihazdaki önbellekten anında yükler.
     * Önbellek yoksa ağdan indirir. Dönüş: kanal listesi.
     */
    suspend fun ensureLoaded(): List<Channel> = withContext(Dispatchers.IO) {
        if (allChannels.isEmpty()) {
            loadFromCache()
        }
        if (allChannels.isEmpty()) {
            refresh()
        }
        refreshLockCache()
        getAllChannels()
    }

    private suspend fun loadFromCache() {
        loadMutex.withLock {
            val cacheFile = cacheFile()
            if (allChannels.isNotEmpty() || !cacheFile.exists()) return
            try {
                val parsed = M3uParser.parse(cacheFile.readText())
                allChannels = processChannels(parsed.channels)
                Log.d(TAG, "Önbellekten ${allChannels.size} kanal yüklendi")
            } catch (e: Exception) {
                Log.e(TAG, "Önbellek okunamadı", e)
            }
        }
    }

    /** Listeyi ağdan yeniden indirir. Başarısız olursa önbellekteki liste korunur. */
    suspend fun refresh(m3uUrl: String = prefManager.getM3uUrl()): RefreshResult =
        withContext(Dispatchers.IO) {
            when (val download = M3uParser.download(m3uUrl)) {
                is DownloadResult.Success -> {
                    val parsed = M3uParser.parse(download.body)
                    if (parsed.channels.isEmpty()) {
                        lastRefreshError = "Listede oynatılabilir kanal bulunamadı"
                        if (allChannels.isEmpty()) loadFromCache()
                        return@withContext RefreshResult(getAllChannels(), "Listede oynatılabilir kanal bulunamadı", fromCache = true)
                    }
                    val processed = processChannels(parsed.channels)
                    loadMutex.withLock { allChannels = processed }
                    refreshedThisSession = true
                    lastRefreshError = null

                    try {
                        cacheFile().writeText(download.body)
                    } catch (e: Exception) {
                        Log.e(TAG, "Önbellek yazılamadı", e)
                    }
                    prefManager.setPlaylistEpgUrl(parsed.epgUrl)
                    prefManager.setLastRefreshTime(System.currentTimeMillis())

                    // Room DB önbelleğine kaydet (kategori ilişkileri için)
                    try {
                        db.channelDao().insertChannels(processed)
                    } catch (e: Exception) {
                        Log.e(TAG, "Kanallar veritabanına yazılamadı", e)
                    }
                    refreshLockCache()
                    syncTvHomeAsync()
                    startHealthCheck(force = false)
                    RefreshResult(getAllChannels())
                }

                is DownloadResult.Failure -> {
                    lastRefreshError = download.message
                    if (allChannels.isEmpty()) loadFromCache()
                    RefreshResult(getAllChannels(), download.message, fromCache = true)
                }
            }
        }

    /** Ayarlanmış EPG adresi varsa yayın akışını yükler. */
    suspend fun loadEpg(force: Boolean = false): Result<Int>? {
        val url = prefManager.getEffectiveEpgUrl() ?: return null
        return EpgRepository.load(context, url, allChannels, force)
    }

    /**
     * Ham kanal listesini işler: isim temizleme, kalite etiketi, logo tamamlama,
     * (isteğe bağlı) aynı kanalın kopyalarını birleştirme, sıralama ve numaralandırma.
     */
    private suspend fun processChannels(raw: List<Channel>): List<Channel> {
        val knownIds = HashSet<String>().apply {
            addAll(prefManager.getFavoritesList())
            addAll(prefManager.getRecentChannelIds())
            try {
                addAll(db.categoryChannelCrossRefDao().getAllCategorizedChannelIds())
            } catch (e: Exception) {
                Log.e(TAG, "Kategori kimlikleri okunamadı", e)
            }
        }

        val cleaned = raw.map { ch ->
            val cleanName = cleanName(ch.name)
            ch.copy(
                name = cleanName,
                quality = detectQuality(ch.name),
                logoUrl = ch.logoUrl ?: findKnownLogo(cleanName)
            )
        }

        val merged = if (prefManager.isMergeDuplicatesEnabled()) {
            cleaned.groupBy { dedupKey(it.name) }.values.map { group -> mergeGroup(group, knownIds) }
        } else {
            cleaned
        }

        // Kimlikler benzersiz olmalı (aynı tvg-id farklı kanallarda kullanılmış olabilir)
        val usedIds = HashSet<String>()
        val unique = merged.map { ch ->
            var id = ch.id
            var n = 2
            while (!usedIds.add(id)) {
                id = "${ch.id}#${n++}"
            }
            if (id == ch.id) ch else ch.copy(id = id)
        }

        // Türkçe alfabeye uygun A'dan Z'ye sıralama ve 1..N numaralandırma
        val favs = prefManager.getFavoritesList().toSet()
        return unique
            .sortedBy { it.name.lowercase(TextUtil.TR_LOCALE) }
            .mapIndexed { i, ch -> ch.copy(channelNumber = i + 1, isFavorite = favs.contains(ch.id)) }
    }

    private fun mergeGroup(group: List<Channel>, knownIds: Set<String>): Channel {
        if (group.size == 1) return group[0]
        val sorted = group.sortedByDescending { qualityScore(it.quality) }
        val best = sorted.first()
        // Favori / kategori kaydı olan kimliği koru ki kullanıcı verisi kaybolmasın
        val id = group.firstOrNull { it.id in knownIds }?.id ?: best.id
        val groups = group.flatMap { TextUtil.splitGroups(it.groupTitle) }.distinct()
        val alternates = sorted.drop(1).flatMap { it.allStreamUrls() }
            .filter { it != best.streamUrl }
            .distinct()
        return best.copy(
            id = id,
            logoUrl = best.logoUrl ?: group.firstNotNullOfOrNull { it.logoUrl },
            tvgId = best.tvgId ?: group.firstNotNullOfOrNull { it.tvgId },
            groupTitle = groups.joinToString(";"),
            alternateUrls = alternates.joinToString("\n").ifEmpty { null },
            orderIndex = group.minOf { it.orderIndex }
        )
    }

    /** "TRT 1 (1080p) [Geo-blocked]" -> "TRT 1" */
    private fun cleanName(name: String): String =
        name.replace(BRACKETS_REGEX, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { name.trim() }

    private fun dedupKey(name: String): String =
        TextUtil.compact(name.replace(QUALITY_TOKEN_REGEX, ""))

    private fun detectQuality(rawName: String): String? {
        RESOLUTION_REGEX.find(rawName)?.groupValues?.get(1)?.toIntOrNull()?.let { height ->
            return when {
                height >= 2160 -> "4K"
                height >= 1080 -> "FHD"
                height >= 720 -> "HD"
                else -> "SD"
            }
        }
        return when (QUALITY_TOKEN_REGEX.findAll(rawName).lastOrNull()?.value?.uppercase()) {
            "4K", "UHD" -> "4K"
            "FHD" -> "FHD"
            "HD" -> "HD"
            "SD" -> "SD"
            else -> null
        }
    }

    private fun qualityScore(quality: String?): Int = when (quality) {
        "4K" -> 4
        "FHD" -> 3
        "HD" -> 2
        null -> 1 // Bilinmeyen kalite: SD'den önce denenir
        else -> 0
    }

    private fun findKnownLogo(name: String): String? {
        val normName = TextUtil.compact(name.replace(QUALITY_TOKEN_REGEX, ""))
        // En uzun anahtar önce: "trt haber" "trt 1"den önce denenir
        return KNOWN_CHANNEL_LOGOS.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { (key, _) ->
                val k = TextUtil.compact(key)
                normName.startsWith(k) && normName.removePrefix(k) in LOGO_NAME_SUFFIXES
            }?.value
    }

    fun toggleFavorite(channelId: String): Boolean {
        val result = prefManager.toggleFavorite(channelId)
        syncTvHomeAsync()
        return result
    }

    fun moveFavoriteUp(channelId: String) {
        prefManager.moveFavoriteUp(channelId)
    }

    fun moveFavoriteDown(channelId: String) {
        prefManager.moveFavoriteDown(channelId)
    }

    fun moveFavorite(channelId: String, delta: Int): Int {
        val result = prefManager.moveFavorite(channelId, delta)
        syncTvHomeAsync()
        return result
    }

    // Room DB Kategori işlemleri
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
        prefManager.setLockedItems(prefManager.getLockedItems() - "custom:${category.id}")
        refreshLockCache()
    }

    suspend fun addChannelToCategory(categoryId: Long, channel: Channel) = withContext(Dispatchers.IO) {
        db.channelDao().insertChannel(channel)
        db.categoryChannelCrossRefDao().insertCrossRef(
            CategoryChannelCrossRef(categoryId = categoryId, channelId = channel.id)
        )
        refreshLockCache()
    }

    suspend fun removeChannelFromCategory(categoryId: Long, channelId: String) = withContext(Dispatchers.IO) {
        db.categoryChannelCrossRefDao().removeChannelFromCategory(categoryId, channelId)
        refreshLockCache()
    }

    /** Kategorideki kanallar, filtre uygulanmadan (yedekleme için). */
    suspend fun getChannelsForCategoryRaw(categoryId: Long): List<Channel> = withContext(Dispatchers.IO) {
        db.categoryChannelCrossRefDao().getChannelsForCategory(categoryId)
    }

    suspend fun getChannelsForCategory(categoryId: Long): List<Channel> = withContext(Dispatchers.IO) {
        val stored = db.categoryChannelCrossRefDao().getChannelsForCategory(categoryId)
        // Güncel listedeki halini tercih et (yeni yayın adresi / logo için)
        val current = getAllChannels().associateBy { it.id }
        val favs = prefManager.getFavoritesList().toSet()
        val hidden = prefManager.getHiddenChannelIds()
        val known = allChannels.map { it.id }.toSet()
        // Güncel listede olup görünmeyen (gizli/kilitli) kanallar atlanır
        stored.filter { it.id !in hidden && (it.id in current || it.id !in known) }
            .map { current[it.id] ?: it.copy(isFavorite = favs.contains(it.id)) }
            .sortedBy { it.name.lowercase(TextUtil.TR_LOCALE) }
            .mapIndexed { i, ch -> ch.copy(channelNumber = i + 1) }
    }
}
