package com.example.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Channel
import com.example.data.repository.ChannelRepository
import com.example.ui.adapter.CategoryTabItem
import com.example.ui.adapter.TabType
import com.example.util.PreferenceManager
import com.example.util.TextUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel as EventChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Ana ekranın tüm durumu. Activity yalnızca bu durumu çizer. */
data class MainUiState(
    val tabs: List<CategoryTabItem> = emptyList(),
    val activeTab: CategoryTabItem = MainViewModel.ALL_TAB,
    val channels: List<Channel> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val initialLoadDone: Boolean = false,
    val hasAnyChannels: Boolean = false,
    val lastError: String? = null,
    val lastChannel: Channel? = null,
    val brokenIds: Set<String> = emptySet(),
    val lastRefreshTime: Long = 0L,
    val playlistName: String = "",
    /** EPG yüklendikçe artar; kartlardaki "şimdi" bilgisi yenilenir. */
    val epgVersion: Long = 0L,
    /** Liste çizildikten sonra odaklanılacak konum (-1: dokunma). [focusToken] her istekte artar. */
    val focusPosition: Int = -1,
    val focusToken: Long = 0L
)

sealed class MainEvent {
    data class Message(val text: String) : MainEvent()
    data class OpenPlayer(val channel: Channel, val queue: List<Channel>) : MainEvent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val TAB_FAVORITES = "favorites"
        const val TAB_RECENT = "recent"
        const val TAB_ALL = "all"
        val ALL_TAB = CategoryTabItem(TAB_ALL, "Tüm Kanallar", TabType.ALL)
        val FAVORITES_TAB = CategoryTabItem(TAB_FAVORITES, "⭐ Favoriler", TabType.FAVORITES)
        val RECENT_TAB = CategoryTabItem(TAB_RECENT, "🕘 Son İzlenenler", TabType.RECENT)
    }

    private val repository = ChannelRepository.getInstance(application)
    private val prefs = PreferenceManager(application)

    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val events = EventChannel<MainEvent>(EventChannel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var filterJob: Job? = null
    private var started = false

    val healthProgress = repository.healthProgress

    /**
     * İlk açılış. [autoResume]: "son kanalı oynat" ayarı uygulanır.
     * [playChannelId]: TV ana ekranından gelen kanal bağlantısı.
     */
    fun start(autoResume: Boolean, playChannelId: String?) {
        if (started) return
        started = true
        _state.update { it.copy(isLoading = !repository.hasChannels) }

        viewModelScope.launch {
            repository.ensureLoaded()
            val startTab = if (repository.getFavoriteChannels().isNotEmpty()) FAVORITES_TAB else ALL_TAB
            _state.update { it.copy(isLoading = false, initialLoadDone = true, activeTab = startTab) }
            reloadTabs()
            applyFilter(focusPosition = 0)
            updateHeader()

            val directChannel = repository.findChannel(playChannelId)
            if (directChannel != null) {
                events.send(MainEvent.OpenPlayer(directChannel, repository.getAllChannels()))
            } else if (autoResume && prefs.isResumeLastChannelEnabled()) {
                repository.findChannel(prefs.getLastChannelId())?.let { channel ->
                    events.send(MainEvent.OpenPlayer(channel, repository.getAllChannels()))
                }
            }

            if (!repository.refreshedThisSession) {
                refresh(silent = true)
            } else {
                loadEpg()
                repository.syncTvHomeAsync()
            }
        }
    }

    /** Ekrana geri dönüldüğünde (ayarlar, oynatıcı) güncel veriyi yükler. */
    fun onResume() {
        if (!_state.value.initialLoadDone) return
        viewModelScope.launch {
            reloadTabs()
            applyFilter()
            updateHeader()
        }
    }

    fun refresh(silent: Boolean) {
        viewModelScope.launch {
            if (!silent) {
                events.send(MainEvent.Message("Kanal listesi yenileniyor…"))
                if (!repository.hasChannels) _state.update { it.copy(isLoading = true) }
            }
            val result = repository.refresh()
            _state.update { it.copy(isLoading = false) }
            reloadTabs()
            applyFilter(focusPosition = if (!silent && _state.value.channels.isEmpty()) 0 else -1)
            updateHeader()
            if (!silent) {
                val msg = if (result.error == null) {
                    "${result.channels.size} kanal yüklendi"
                } else {
                    "Liste güncellenemedi: ${result.error}"
                }
                events.send(MainEvent.Message(msg))
            }
            loadEpg()
        }
    }

    private suspend fun loadEpg() {
        val result = repository.loadEpg() ?: return
        if (result.isSuccess) {
            // Kartlardaki "şimdi" bilgisini tazelemek için listeyi yeniden yayınla
            _state.update { it.copy(epgVersion = it.epgVersion + 1) }
            repository.syncTvHomeAsync()
        }
    }

    fun selectTab(tab: CategoryTabItem) {
        _state.update { it.copy(activeTab = tab) }
        applyFilter()
    }

    fun setSearchQuery(query: String) {
        if (query == _state.value.searchQuery) return
        _state.update { it.copy(searchQuery = query) }
        applyFilter()
    }

    // ---- Ebeveyn kilidi ----

    fun isTabLocked(tab: CategoryTabItem): Boolean = when (tab.type) {
        TabType.GROUP -> repository.isGroupLocked(tab.groupName.orEmpty())
        TabType.CUSTOM -> repository.isCategoryLocked(tab.customCategoryId)
        else -> false
    }

    fun unlock(pin: String): Boolean {
        val ok = repository.unlockParental(pin)
        if (ok) {
            viewModelScope.launch {
                reloadTabs()
                applyFilter()
            }
        }
        return ok
    }

    // ---- Kanal işlemleri ----

    fun toggleFavorite(channel: Channel) {
        val newFav = repository.toggleFavorite(channel.id)
        val position = _state.value.channels.indexOfFirst { it.id == channel.id }
        applyFilter(focusPosition = position)
        viewModelScope.launch {
            events.send(MainEvent.Message(if (newFav) "Kanal favorilere eklendi" else "Kanal favorilerden çıkarıldı"))
        }
    }

    fun hideChannel(channel: Channel) {
        val position = _state.value.channels.indexOfFirst { it.id == channel.id }
        repository.hideChannel(channel.id)
        applyFilter(focusPosition = position)
        viewModelScope.launch {
            events.send(MainEvent.Message("${channel.name} gizlendi. Ayarlar > Gizlenen Kanallar'dan geri alabilirsiniz."))
        }
    }

    fun removeFromCurrentCategory(channel: Channel) {
        val tab = _state.value.activeTab
        if (tab.type != TabType.CUSTOM) return
        viewModelScope.launch {
            repository.removeChannelFromCategory(tab.customCategoryId, channel.id)
            events.send(MainEvent.Message("${channel.name} kategoriden çıkarıldı"))
            applyFilter(focusPosition = 0)
        }
    }

    fun addToCategory(categoryId: Long, categoryName: String, channel: Channel) {
        viewModelScope.launch {
            repository.addChannelToCategory(categoryId, channel)
            events.send(MainEvent.Message("${channel.name}, \"$categoryName\" kategorisine eklendi"))
        }
    }

    /** Favori taşıma modunda kanalı [delta] kadar kaydırır. */
    fun moveFavorite(channelId: String, delta: Int) {
        val newPosition = repository.moveFavorite(channelId, delta)
        if (newPosition >= 0) applyFilter(focusPosition = newPosition)
    }

    fun findByNumber(number: Int): Channel? = repository.findByNumber(number)

    fun openByNumber(number: Int) {
        viewModelScope.launch {
            val channel = repository.findByNumber(number)
            if (channel != null) {
                events.send(MainEvent.OpenPlayer(channel, repository.getAllChannels()))
            } else {
                events.send(MainEvent.Message("$number numaralı kanal bulunamadı"))
            }
        }
    }

    fun openLastChannel() {
        viewModelScope.launch {
            repository.findChannel(prefs.getLastChannelId())?.let {
                events.send(MainEvent.OpenPlayer(it, repository.getAllChannels()))
            }
        }
    }

    fun setPlaybackQueue(queue: List<Channel>) {
        repository.playbackQueue = queue.ifEmpty { repository.getAllChannels() }
    }

    suspend fun categories() = repository.getAllCategoriesList()

    // ---- İç işler ----

    private suspend fun reloadTabs() {
        val tabs = mutableListOf(FAVORITES_TAB, RECENT_TAB, ALL_TAB)
        repository.getAllCategoriesList().forEach { cat ->
            val locked = repository.isCategoryLocked(cat.id)
            tabs.add(
                CategoryTabItem(
                    id = "custom_${cat.id}",
                    name = (if (locked) "🔒 " else "📁 ") + cat.name,
                    type = TabType.CUSTOM,
                    customCategoryId = cat.id
                )
            )
        }
        if (prefs.isGroupTabsEnabled()) {
            val groups = repository.getGroups()
            if (groups.size > 1) {
                groups.forEach { g ->
                    val name = if (repository.isGroupLocked(g)) "🔒 $g" else g
                    tabs.add(CategoryTabItem("group_$g", name, TabType.GROUP, groupName = g))
                }
            }
        }

        val current = _state.value.activeTab
        val active = tabs.firstOrNull { it.id == current.id }?.takeUnless { isTabLocked(it) } ?: ALL_TAB
        _state.update { it.copy(tabs = tabs, activeTab = active) }
    }

    private fun updateHeader() {
        _state.update {
            it.copy(
                hasAnyChannels = repository.hasChannels,
                lastError = repository.lastRefreshError,
                lastChannel = repository.findChannel(prefs.getLastChannelId()),
                brokenIds = repository.brokenChannelIds(),
                lastRefreshTime = prefs.getLastRefreshTime(),
                playlistName = prefs.getActivePlaylist().name
            )
        }
    }

    /** Durum kartlarının "yayın yok" işaretini yeniler (yayın kontrolü bitince). */
    fun refreshBrokenState() {
        _state.update { it.copy(brokenIds = repository.brokenChannelIds()) }
        if (prefs.isHideBrokenEnabled()) applyFilter()
    }

    private fun applyFilter(focusPosition: Int = -1) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            val snapshot = _state.value
            val isSearching = snapshot.searchQuery.isNotEmpty()
            // Arama tüm kanallarda yapılır; sekme seçimi aramasız görünümü belirler
            val baseList = if (isSearching) {
                repository.getAllChannels()
            } else {
                val tab = snapshot.activeTab
                when (tab.type) {
                    TabType.FAVORITES -> repository.getFavoriteChannels()
                    TabType.RECENT -> repository.getRecentChannels()
                    TabType.ALL -> repository.getAllChannels()
                    TabType.CUSTOM -> repository.getChannelsForCategory(tab.customCategoryId)
                    TabType.GROUP -> repository.getChannelsForGroup(tab.groupName.orEmpty())
                }
            }
            val filtered = if (isSearching) {
                baseList.filter {
                    TextUtil.matchesQuery(snapshot.searchQuery, it.name, TextUtil.displayGroups(it.groupTitle))
                }
            } else {
                baseList
            }
            _state.update {
                it.copy(
                    channels = filtered,
                    focusPosition = focusPosition,
                    focusToken = if (focusPosition >= 0) it.focusToken + 1 else it.focusToken
                )
            }
        }
    }
}
