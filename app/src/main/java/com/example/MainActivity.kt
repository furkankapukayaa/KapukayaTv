package com.example

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.databinding.ActivityMainBinding
import com.example.ui.adapter.CategoryTabAdapter
import com.example.ui.adapter.ChannelAdapter
import com.example.ui.adapter.TabType
import com.example.ui.main.MainEvent
import com.example.ui.main.MainUiState
import com.example.ui.main.MainViewModel
import com.example.util.DeviceUtil
import com.example.util.PinDialog
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** Splash'tan gelindiğinde true: "açılışta son kanalı oynat" ayarı uygulanır. */
        const val EXTRA_FROM_LAUNCH = "extra_from_launch"
        /** TV ana ekranından gelen kanal bağlantısı. */
        const val EXTRA_PLAY_CHANNEL_ID = "extra_play_channel_id"
        private const val NUMBER_INPUT_DELAY_MS = 1500L
        private const val EPG_TICK_MS = 60_000L
        private const val BACK_EXIT_WINDOW_MS = 2000L
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryTabAdapter: CategoryTabAdapter
    private lateinit var gridLayoutManager: GridLayoutManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val typedNumberBuffer = StringBuilder()
    private var numberInputRunnable: Runnable? = null
    private var lastBackPressTime = 0L
    private var lastHandledFocusToken = 0L
    private var lastEpgVersion = 0L
    private var renderedTabs: List<com.example.ui.adapter.CategoryTabItem> = emptyList()

    /** Favori taşıma modundaki kanal */
    private var movingChannelId: String? = null

    private val state: MainUiState get() = viewModel.state.value

    private val epgTicker = object : Runnable {
        override fun run() {
            if (EpgRepository.isLoaded) channelAdapter.refreshDynamicContent()
            mainHandler.postDelayed(this, EPG_TICK_MS)
        }
    }

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!spokenMatches.isNullOrEmpty()) {
                // TextWatcher araması otomatik tetikler
                binding.etSearchQuery.setText(spokenMatches[0])
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyPhoneLandscapeTweaks()
        setupTopBar()
        setupCategoryTabs()
        setupChannelsRecycler()
        setupEmptyState()
        setupBackHandling()
        observeViewModel()

        val firstLaunch = savedInstanceState == null
        viewModel.start(
            autoResume = firstLaunch && intent.getBooleanExtra(EXTRA_FROM_LAUNCH, false),
            playChannelId = if (firstLaunch) intent.getStringExtra(EXTRA_PLAY_CHANNEL_ID) else null
        )
    }

    // ---------------------------------------------------------------------------------------
    // Durum gözlemi
    // ---------------------------------------------------------------------------------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch {
                    viewModel.eventFlow.collect { event ->
                        when (event) {
                            is MainEvent.Message -> Toast.makeText(this@MainActivity, event.text, Toast.LENGTH_LONG).show()
                            is MainEvent.OpenPlayer -> openPlayer(event.channel, event.queue)
                        }
                    }
                }
                launch {
                    var wasRunning = false
                    viewModel.healthProgress.collect { progress ->
                        if (wasRunning && !progress.running) viewModel.refreshBrokenState()
                        wasRunning = progress.running
                    }
                }
            }
        }
    }

    private fun render(ui: MainUiState) {
        // Sekmeler yalnızca değiştiğinde yeniden çizilir (odak kaybolmasın)
        if (ui.tabs != renderedTabs) {
            renderedTabs = ui.tabs
            categoryTabAdapter.setItems(ui.tabs)
        }
        if (categoryTabAdapter.selectedId() != ui.activeTab.id) categoryTabAdapter.selectById(ui.activeTab.id)

        channelAdapter.brokenIds = ui.brokenIds
        if (ui.epgVersion != lastEpgVersion) {
            lastEpgVersion = ui.epgVersion
            channelAdapter.refreshDynamicContent()
        }

        val showLoading = ui.isLoading
        binding.mainLoadingLayout.visibility = if (showLoading) View.VISIBLE else View.GONE

        channelAdapter.submitList(ui.channels) {
            updateEmptyState(ui)
            updateSectionInfo(ui)
            if (ui.focusPosition >= 0 && ui.focusToken != lastHandledFocusToken) {
                lastHandledFocusToken = ui.focusToken
                focusCard(ui.focusPosition)
            }
        }

        val last = ui.lastChannel
        if (last != null) {
            binding.btnContinue.visibility = View.VISIBLE
            binding.btnContinue.text = last.name
        } else {
            binding.btnContinue.visibility = View.GONE
        }
        binding.tvOfflineBadge.visibility =
            if (ui.lastError != null && ui.hasAnyChannels) View.VISIBLE else View.GONE
    }

    private fun updateSectionInfo(ui: MainUiState) {
        val title = if (ui.searchQuery.isNotEmpty()) "“${ui.searchQuery}” araması" else ui.activeTab.name
        val updated = if (ui.lastRefreshTime > 0) {
            " · Güncelleme " + DateFormat.format("HH:mm", ui.lastRefreshTime)
        } else ""
        val playlist = if (ui.playlistName.isNotEmpty()) "${ui.playlistName} · " else ""
        binding.tvSectionInfo.text = "$playlist$title · ${ui.channels.size} kanal$updated"
    }

    private fun updateEmptyState(ui: MainUiState) {
        if (!ui.initialLoadDone || ui.isLoading) {
            binding.mainEmptyLayout.visibility = View.GONE
            binding.rvChannels.visibility = if (ui.isLoading) View.GONE else View.VISIBLE
            return
        }
        if (ui.channels.isNotEmpty()) {
            binding.mainEmptyLayout.visibility = View.GONE
            binding.rvChannels.visibility = View.VISIBLE
            return
        }
        binding.rvChannels.visibility = View.GONE
        binding.mainEmptyLayout.visibility = View.VISIBLE

        val isSearching = ui.searchQuery.isNotEmpty()
        val noChannelsAtAll = !ui.hasAnyChannels
        val (title, message) = when {
            noChannelsAtAll -> getString(R.string.empty_no_channels) to
                (ui.lastError?.let { "$it\n" } ?: "") + getString(R.string.empty_no_channels_desc)
            isSearching -> getString(R.string.empty_search) to getString(R.string.empty_search_desc)
            ui.activeTab.type == TabType.FAVORITES -> getString(R.string.empty_no_favorites) to getString(R.string.empty_no_favorites_desc)
            ui.activeTab.type == TabType.RECENT -> getString(R.string.empty_no_recents) to getString(R.string.empty_no_recents_desc)
            ui.activeTab.type == TabType.CUSTOM -> getString(R.string.empty_category) to getString(R.string.empty_category_desc)
            else -> getString(R.string.empty_search) to ""
        }
        binding.tvEmptyTitle.text = title
        binding.tvEmptyMessage.text = message
        binding.emptyActions.visibility = if (noChannelsAtAll) View.VISIBLE else View.GONE
        if (noChannelsAtAll && currentFocus == null) binding.btnEmptyRetry.requestFocus()
    }

    // ---------------------------------------------------------------------------------------
    // Kurulum
    // ---------------------------------------------------------------------------------------

    private fun setupTopBar() {
        binding.btnVoiceSearch.setOnClickListener { launchVoiceSearch() }
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnGuide.setOnClickListener { GuideActivity.start(this) }
        binding.btnContinue.setOnClickListener { viewModel.openLastChannel() }

        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun launchVoiceSearch() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Kanal adı söyleyin")
            }
            voiceSearchLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Sesli arama bu cihazda desteklenmiyor", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupCategoryTabs() {
        categoryTabAdapter = CategoryTabAdapter { selectedTab ->
            endMoveMode()
            if (viewModel.isTabLocked(selectedTab)) {
                // Kilitli sekme: PIN doğruysa açılır, değilse önceki sekme seçili kalır
                categoryTabAdapter.selectById(state.activeTab.id)
                PinDialog.show(this, "🔒 ${selectedTab.name.removePrefix("🔒 ")}", getString(R.string.parental_enter_pin)) { pin ->
                    if (viewModel.unlock(pin)) {
                        viewModel.selectTab(selectedTab)
                    } else {
                        Toast.makeText(this, R.string.parental_wrong_pin, Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                viewModel.selectTab(selectedTab)
            }
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCategories.adapter = categoryTabAdapter
    }

    private fun setupChannelsRecycler() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                if (movingChannelId == null) openPlayer(channel, channelAdapter.currentList)
            },
            onFavoriteToggle = { channel -> viewModel.toggleFavorite(channel) },
            onChannelLongClick = { channel -> showChannelActions(channel) }
        )

        gridLayoutManager = GridLayoutManager(this, calculateSpanCount())
        binding.rvChannels.layoutManager = gridLayoutManager
        binding.rvChannels.adapter = channelAdapter
        binding.rvChannels.itemAnimator = null // TV'de odak kaymasını önler
    }

    private fun setupEmptyState() {
        binding.btnEmptyRetry.setOnClickListener { viewModel.refresh(silent = false) }
        binding.btnEmptySettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.btnEmptyRemote.setOnClickListener { startActivity(Intent(this, RemoteSetupActivity::class.java)) }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    movingChannelId != null -> endMoveMode()
                    state.searchQuery.isNotEmpty() -> {
                        binding.etSearchQuery.setText("")
                        focusCard(0)
                    }
                    System.currentTimeMillis() - lastBackPressTime < BACK_EXIT_WINDOW_MS -> finish()
                    else -> {
                        lastBackPressTime = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    /**
     * Yatay telefonda (dar ekran) üst barın taşmaması için saat ve kumanda ipucu gizlenir,
     * arama kutusu daraltılır. TV'de ve dikey telefonda (layout-port) hiçbir şey değişmez.
     */
    private fun applyPhoneLandscapeTweaks() {
        if (DeviceUtil.isTv(this) || DeviceUtil.isPhonePortrait(this)) return
        if (resources.configuration.screenWidthDp >= 900) return
        binding.clockColumn.visibility = View.GONE
        binding.tvRemoteHint.visibility = View.GONE
        binding.etSearchQuery.layoutParams = binding.etSearchQuery.layoutParams.apply {
            width = DeviceUtil.dp(this@MainActivity, 150)
        }
        binding.btnContinue.maxWidth = DeviceUtil.dp(this, 130)
        val h = DeviceUtil.dp(this, 24)
        binding.mainRootLayout.setPadding(h, DeviceUtil.dp(this, 16), h, DeviceUtil.dp(this, 12))
    }

    /** Ekran genişliğine göre sütun sayısı (TV'de 4-5, telefonda 2). */
    private fun calculateSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return ((widthDp - 96) / 200).coerceIn(2, 6)
    }

    private fun focusCard(position: Int) {
        if (channelAdapter.itemCount == 0) return
        val target = position.coerceIn(0, channelAdapter.itemCount - 1)
        binding.rvChannels.scrollToPosition(target)
        binding.rvChannels.post {
            binding.rvChannels.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Kanal işlemleri
    // ---------------------------------------------------------------------------------------

    private fun openPlayer(channel: Channel, queue: List<Channel>) {
        viewModel.setPlaybackQueue(queue)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(PlayerActivity.EXTRA_CHANNEL, channel)
        }
        startActivity(intent)
    }

    /** OK tuşuna basılı tutunca (veya Menü tuşuyla) açılan işlem menüsü. */
    private fun showChannelActions(channel: Channel) {
        val ui = state
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "▶  İzle" to { openPlayer(channel, channelAdapter.currentList) }
        actions += if (channel.isFavorite) {
            "★  Favorilerden çıkar" to { viewModel.toggleFavorite(channel) }
        } else {
            "☆  Favorilere ekle" to { viewModel.toggleFavorite(channel) }
        }
        actions += "📁  Kategoriye ekle" to { showAddToCategoryDialog(channel) }
        if (ui.activeTab.type == TabType.CUSTOM && ui.searchQuery.isEmpty()) {
            actions += "✕  Bu kategoriden çıkar" to { viewModel.removeFromCurrentCategory(channel) }
        }
        if (ui.activeTab.type == TabType.FAVORITES && ui.searchQuery.isEmpty() && channel.isFavorite) {
            actions += "↕  Sırasını değiştir" to { startMoveMode(channel) }
        }
        if (EpgRepository.hasProgrammes(channel)) {
            actions += "🗓  Yayın akışı" to { GuideActivity.start(this, channel.id) }
        }
        actions += "🚫  Kanalı gizle" to { viewModel.hideChannel(channel) }

        val alternates = channel.allStreamUrls().size - 1
        val title = buildString {
            append(channel.name)
            if (alternates > 0) append("  ($alternates yedek yayın)")
            if (channel.id in ui.brokenIds) append("  · ⚠ yayın açılmıyor")
        }

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                actions[which].second.invoke()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddToCategoryDialog(channel: Channel) {
        lifecycleScope.launch {
            val categories = viewModel.categories()
            if (categories.isEmpty()) {
                AlertDialog.Builder(this@MainActivity, R.style.DarkDialogTheme)
                    .setTitle("Kategori Bulunamadı")
                    .setMessage("Henüz oluşturulmuş bir kategori yok. Ayarlar menüsünden yeni bir kategori oluşturabilirsiniz.")
                    .setPositiveButton("Ayarlara Git") { _, _ ->
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    .setNegativeButton("Tamam", null)
                    .show()
                return@launch
            }

            AlertDialog.Builder(this@MainActivity, R.style.DarkDialogTheme)
                .setTitle("\"${channel.name}\" Kanalını Kategoriye Ekle")
                .setItems(categories.map { it.name }.toTypedArray()) { dialog, which ->
                    val selected = categories[which]
                    viewModel.addToCategory(selected.id, selected.name, channel)
                    dialog.dismiss()
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    // ---- Favori taşıma modu ----

    private fun startMoveMode(channel: Channel) {
        movingChannelId = channel.id
        channelAdapter.movingChannelId = channel.id
        Toast.makeText(this, R.string.move_mode_hint, Toast.LENGTH_LONG).show()
        focusCard(channelAdapter.currentList.indexOfFirst { it.id == channel.id })
    }

    private fun endMoveMode() {
        if (movingChannelId == null) return
        movingChannelId = null
        channelAdapter.movingChannelId = null
    }

    private fun handleMoveKey(keyCode: Int): Boolean {
        val id = movingChannelId ?: return false
        val span = gridLayoutManager.spanCount
        val delta = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -span
            KeyEvent.KEYCODE_DPAD_DOWN -> span
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                endMoveMode()
                return true
            }
            else -> return false
        }
        viewModel.moveFavorite(id, delta)
        return true
    }

    // ---- Rakam tuşlarıyla kanala git ----

    private fun handleNumberInput(digit: Int) {
        typedNumberBuffer.append(digit)
        val preview = typedNumberBuffer.toString().toIntOrNull()?.let { viewModel.findByNumber(it) }
        binding.tvMainNumberInput.text = if (preview != null) "$typedNumberBuffer  ${preview.name}" else typedNumberBuffer.toString()
        binding.tvMainNumberInput.visibility = View.VISIBLE

        numberInputRunnable?.let { mainHandler.removeCallbacks(it) }
        numberInputRunnable = Runnable {
            val target = typedNumberBuffer.toString().toIntOrNull()
            typedNumberBuffer.clear()
            binding.tvMainNumberInput.visibility = View.GONE
            if (target != null) viewModel.openByNumber(target)
        }
        mainHandler.postDelayed(numberInputRunnable!!, NUMBER_INPUT_DELAY_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (movingChannelId != null && handleMoveKey(event.keyCode)) return true

            if (currentFocus !is EditText) {
                val digit = when (event.keyCode) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                if (digit != -1) {
                    handleNumberInput(digit)
                    return true
                }
            }

            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    val holder = currentFocus?.let { binding.rvChannels.findContainingViewHolder(it) }
                    val position = holder?.bindingAdapterPosition ?: -1
                    if (position in 0 until channelAdapter.itemCount) {
                        showChannelActions(channelAdapter.currentList[position])
                        return true
                    }
                }
                KeyEvent.KEYCODE_SEARCH -> {
                    binding.etSearchQuery.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_GUIDE -> {
                    GuideActivity.start(this)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        mainHandler.postDelayed(epgTicker, EPG_TICK_MS)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(epgTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        numberInputRunnable?.let { mainHandler.removeCallbacks(it) }
    }
}
