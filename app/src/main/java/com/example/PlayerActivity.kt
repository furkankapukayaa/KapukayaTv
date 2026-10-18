package com.example

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.data.parser.M3uParser
import com.example.data.parser.StreamSource
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivityPlayerBinding
import com.example.ui.adapter.TabType
import com.example.ui.adapter.ZappingChannelAdapter
import com.example.util.PreferenceManager
import com.example.util.TextUtil
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var channelRepository: ChannelRepository
    private lateinit var prefManager: PreferenceManager
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory

    private var currentChannel: Channel? = null
    private var previousChannel: Channel? = null
    private var channelList: List<Channel> = emptyList()

    // Yayın kaynağı / yeniden deneme durumu
    private var sourceUrls: List<String> = emptyList()
    private var sourceIndex = 0
    private var attemptCount = 0
    private var forceHls = false
    private var inFinalError = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var osdHideRunnable: Runnable? = null
    private var bufferTimeoutRunnable: Runnable? = null
    private var slowHintRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var zappingNumberRunnable: Runnable? = null
    private var zapRunnable: Runnable? = null
    private var sleepRunnable: Runnable? = null
    private var sleepWarningRunnable: Runnable? = null

    /** Yukarı/aşağı ile hızlı gezinirken henüz açılmamış hedef kanal */
    private var pendingZapChannel: Channel? = null

    private val typedNumberBuffer = StringBuilder()

    private lateinit var zappingAdapter: ZappingChannelAdapter
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkLost = false

    private var currentZappingListType = TabType.ALL
    private var currentZappingCategoryId = -1L
    private var currentZappingGroup: String? = null
    private var searchQuery = ""
    private var resizeModeIndex = 0
    private var sleepMinutes = 0
    private var sleepEndTime = 0L
    private var stoppedInBackground = false
    private var reportedWorkingId: String? = null

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!spokenMatches.isNullOrEmpty()) {
                binding.etPlayerSearch.setText(spokenMatches[0])
                binding.etPlayerSearch.requestFocus()
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        private const val OSD_TIMEOUT_MS = 6000L
        private const val SLOW_HINT_MS = 6000L
        private const val BUFFER_TIMEOUT_MS = 15000L
        private const val RETRY_DELAY_MS = 2500L
        private const val MAX_RETRIES_PER_SOURCE = 2
        private const val ZAPPING_DELAY_MS = 1500L
        private const val ZAP_DEBOUNCE_MS = 450L
        private val SLEEP_OPTIONS = intArrayOf(0, 15, 30, 60, 90, 120)
        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        )
        private val RESIZE_MODE_NAMES = arrayOf("Sığdır", "Tam Ekran", "Kırp (Zoom)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelRepository = ChannelRepository.getInstance(this)
        prefManager = PreferenceManager(this)

        // Büyük listeler Intent ile taşınmaz; sıra repository'de tutulur
        channelList = channelRepository.playbackQueue.ifEmpty { channelRepository.getAllChannels() }

        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        @Suppress("DEPRECATION")
        val passedChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        }
        val startChannel = channelList.firstOrNull { it.id == channelId }
            ?: channelRepository.findChannel(channelId)
            ?: passedChannel

        setupZappingMenu()
        setupZappingCategories()
        setupPlayerOptions()
        setupOsdButtons()
        setupNetworkMonitor()
        initializePlayer()

        if (startChannel != null) {
            playChannel(startChannel)
        } else {
            Toast.makeText(this, "Kanal bulunamadı", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Oynatıcı
    // ---------------------------------------------------------------------------------------

    private fun initializePlayer() {
        if (exoPlayer != null) return

        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(M3uParser.USER_AGENT)
            .setAllowCrossProtocolRedirects(true) // http -> https yönlendirmeleri için
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                binding.playerView.player = this
                playWhenReady = true

                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setMaxVideoSize(3840, 2160)
                    .setPreferredAudioLanguage("tr")
                    .build()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                if (!inFinalError) {
                                    binding.loadingLayout.visibility = View.VISIBLE
                                    binding.pbLoading.visibility = View.VISIBLE
                                    startBufferTimers()
                                }
                            }
                            Player.STATE_READY -> {
                                cancelBufferTimers()
                                binding.loadingLayout.visibility = View.GONE
                                attemptCount = 0
                                inFinalError = false
                                currentChannel?.let { ch ->
                                    if (reportedWorkingId != ch.id) {
                                        reportedWorkingId = ch.id
                                        channelRepository.reportPlaybackResult(ch.id, success = true)
                                    }
                                }
                            }
                            Player.STATE_ENDED -> {
                                // Canlı yayın beklenmedik şekilde bitti: yeniden bağlan
                                handleSourceFailure()
                            }
                            Player.STATE_IDLE -> Unit
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        when (error.errorCode) {
                            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                                exoPlayer?.seekToDefaultPosition()
                                exoPlayer?.prepare()
                            }
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                                // Uzantısız HLS adresleri için bir kez HLS olarak dene
                                if (!forceHls) {
                                    forceHls = true
                                    startCurrentSource()
                                } else {
                                    handleSourceFailure()
                                }
                            }
                            else -> handleSourceFailure()
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.height > 0 && pendingZapChannel == null) {
                            binding.osdView.tvOsdResolution.text = "${videoSize.height}p"
                            binding.osdView.tvOsdResolution.visibility = View.VISIBLE
                        }
                    }
                })
            }

        resizeModeIndex = prefManager.getResizeMode().coerceIn(0, RESIZE_MODES.size - 1)
        applyResizeMode(showToast = false)
    }

    private fun playChannel(channel: Channel) {
        cancelPendingZap()
        currentChannel?.let { if (it.id != channel.id) previousChannel = it }
        currentChannel = channel

        sourceUrls = channel.allStreamUrls()
        sourceIndex = 0
        attemptCount = 0
        forceHls = false
        inFinalError = false

        if (!channel.logoUrl.isNullOrBlank()) {
            binding.ivLoadingLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            binding.ivLoadingLogo.setImageResource(R.drawable.ic_tv)
        }
        showLoadingPanel("${channel.displayName()} açılıyor…")

        binding.osdView.tvOsdResolution.visibility = View.GONE
        updateOsdContent(channel)
        showOsd()

        zappingAdapter.setActiveChannel(channel.id)
        channelRepository.recordWatched(channel)

        startCurrentSource()
    }

    /** Geçerli kaynağı (ana veya yedek yayın) başlatır. */
    private fun startCurrentSource() {
        cancelRetry()
        cancelBufferTimers()
        val player = exoPlayer ?: return
        val raw = sourceUrls.getOrNull(sourceIndex) ?: return

        try {
            val source = StreamSource.parse(raw)
            httpDataSourceFactory.setUserAgent(source.userAgent ?: M3uParser.USER_AGENT)
            httpDataSourceFactory.setDefaultRequestProperties(
                source.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            )

            val itemBuilder = MediaItem.Builder().setUri(source.url)
            if (forceHls || source.url.contains(".m3u8", ignoreCase = true)) {
                itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            player.setMediaItem(itemBuilder.build())
            player.prepare()
            player.play()
            startBufferTimers()
        } catch (e: Exception) {
            handleSourceFailure()
        }
    }

    /**
     * Kaynak açılamadı: önce aynı kaynağı birkaç kez dener, sonra yedek yayına geçer,
     * hiçbiri açılmazsa sonsuz döngüye girmeden hata ekranını gösterir.
     */
    private fun handleSourceFailure() {
        cancelBufferTimers()
        if (inFinalError || isFinishing || isDestroyed) return

        attemptCount++
        when {
            attemptCount <= MAX_RETRIES_PER_SOURCE -> {
                showLoadingPanel(null, hint = "${getString(R.string.retrying)} ($attemptCount/$MAX_RETRIES_PER_SOURCE)")
                retryRunnable = Runnable { startCurrentSource() }
                mainHandler.postDelayed(retryRunnable!!, RETRY_DELAY_MS)
            }
            sourceIndex < sourceUrls.size - 1 -> {
                sourceIndex++
                attemptCount = 0
                forceHls = false
                showLoadingPanel(null, hint = "Yedek yayına geçiliyor (${sourceIndex + 1}/${sourceUrls.size})…")
                startCurrentSource()
            }
            else -> showFinalError()
        }
    }

    private fun showFinalError() {
        inFinalError = true
        currentChannel?.let { channelRepository.reportPlaybackResult(it.id, success = false) }
        cancelRetry()
        cancelBufferTimers()
        exoPlayer?.stop()
        binding.loadingLayout.visibility = View.VISIBLE
        binding.pbLoading.visibility = View.GONE
        binding.tvLoadingStatus.text = "${currentChannel?.name ?: "Kanal"} açılamadı"
        binding.tvErrorMessage.visibility = View.VISIBLE
        binding.tvRetryHint.visibility = View.VISIBLE
        binding.tvRetryHint.text = "OK: Tekrar dene  ·  ▲▼: Başka kanal  ·  0: Önceki kanal"
    }

    /** Ortadaki yükleme panelini gösterir. [status] null ise mevcut başlık korunur. */
    private fun showLoadingPanel(status: String?, hint: String? = null) {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.pbLoading.visibility = View.VISIBLE
        status?.let { binding.tvLoadingStatus.text = it }
        binding.tvErrorMessage.visibility = View.GONE
        if (hint != null) {
            binding.tvRetryHint.text = hint
            binding.tvRetryHint.visibility = View.VISIBLE
        } else {
            binding.tvRetryHint.visibility = View.GONE
        }
    }

    private fun startBufferTimers() {
        cancelBufferTimers()
        slowHintRunnable = Runnable {
            binding.tvRetryHint.text = "Bağlantı yavaş, bekleniyor…"
            binding.tvRetryHint.visibility = View.VISIBLE
        }
        bufferTimeoutRunnable = Runnable { handleSourceFailure() }
        mainHandler.postDelayed(slowHintRunnable!!, SLOW_HINT_MS)
        mainHandler.postDelayed(bufferTimeoutRunnable!!, BUFFER_TIMEOUT_MS)
    }

    private fun cancelBufferTimers() {
        bufferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        slowHintRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferTimeoutRunnable = null
        slowHintRunnable = null
    }

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    // ---------------------------------------------------------------------------------------
    // Kanal değiştirme
    // ---------------------------------------------------------------------------------------

    /** Listede [delta] kadar ilerler. Hızlı basışlarda yalnızca son seçilen kanal açılır. */
    private fun zapBy(delta: Int) {
        if (channelList.isEmpty()) return
        val base = pendingZapChannel ?: currentChannel
        val index = channelList.indexOfFirst { it.id == base?.id }
        val newIndex = if (index == -1) 0 else (index + delta).mod(channelList.size)
        val target = channelList[newIndex]

        pendingZapChannel = target
        binding.osdView.tvOsdResolution.visibility = View.GONE
        updateOsdContent(target)
        showOsd()

        zapRunnable?.let { mainHandler.removeCallbacks(it) }
        zapRunnable = Runnable {
            pendingZapChannel = null
            playChannel(target)
        }
        mainHandler.postDelayed(zapRunnable!!, ZAP_DEBOUNCE_MS)
    }

    private fun cancelPendingZap() {
        zapRunnable?.let { mainHandler.removeCallbacks(it) }
        zapRunnable = null
        pendingZapChannel = null
    }

    private fun switchToPreviousChannel() {
        val previous = previousChannel
        if (previous != null) {
            playChannel(previous)
        } else {
            Toast.makeText(this, "Önceki kanal yok", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleNumberInput(digit: Int) {
        typedNumberBuffer.append(digit)
        val number = typedNumberBuffer.toString().toIntOrNull()
        val preview = number?.let { findChannelByNumber(it) }
        binding.tvNumberInput.text = if (preview != null && typedNumberBuffer.toString() != "0") {
            "$typedNumberBuffer  ${preview.name}"
        } else {
            typedNumberBuffer.toString()
        }
        binding.tvNumberInput.visibility = View.VISIBLE

        zappingNumberRunnable?.let { mainHandler.removeCallbacks(it) }
        zappingNumberRunnable = Runnable {
            val typed = typedNumberBuffer.toString()
            typedNumberBuffer.clear()
            binding.tvNumberInput.visibility = View.GONE

            // Tek başına "0": önceki kanala dön
            if (typed == "0") {
                switchToPreviousChannel()
                return@Runnable
            }
            val targetNum = typed.toIntOrNull() ?: return@Runnable
            val targetChannel = findChannelByNumber(targetNum)
            if (targetChannel != null) {
                playChannel(targetChannel)
            } else {
                Toast.makeText(this, "$targetNum numaralı kanal bulunamadı", Toast.LENGTH_SHORT).show()
            }
        }
        mainHandler.postDelayed(zappingNumberRunnable!!, ZAPPING_DELAY_MS)
    }

    private fun findChannelByNumber(number: Int): Channel? =
        channelList.find { it.channelNumber == number } ?: channelRepository.findByNumber(number)

    // ---------------------------------------------------------------------------------------
    // Seçenekler paneli (arama, görüntü, ses, kalite, uyku)
    // ---------------------------------------------------------------------------------------

    private fun setupPlayerOptions() {
        binding.etPlayerSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                filterZappingChannels()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnPlayerVoiceSearch.setOnClickListener {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Kanal adı söyleyin")
                }
                voiceSearchLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Sesli arama desteklenmiyor", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAspectRatio.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
            prefManager.setResizeMode(resizeModeIndex)
            applyResizeMode(showToast = true)
        }

        binding.btnAudioTrack.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_AUDIO, "Ses Dili / Parçası", "ses parçası")
        }

        binding.btnVideoQuality.setOnClickListener {
            showTrackDialog(C.TRACK_TYPE_VIDEO, "Görüntü Kalitesi", "kalite seçeneği")
        }

        binding.btnSleepTimer.setOnClickListener {
            val index = SLEEP_OPTIONS.indexOf(sleepMinutes)
            setSleepTimer(SLEEP_OPTIONS[(index + 1) % SLEEP_OPTIONS.size])
        }

        binding.btnOpenGuide.setOnClickListener {
            hideZappingMenu()
            GuideActivity.start(this, currentChannel?.id)
        }

        binding.btnReloadStream.setOnClickListener {
            hideZappingMenu()
            currentChannel?.let { playChannel(it) }
        }
    }

    private fun applyResizeMode(showToast: Boolean) {
        binding.playerView.resizeMode = RESIZE_MODES[resizeModeIndex]
        val modeText = RESIZE_MODE_NAMES[resizeModeIndex]
        binding.btnAspectRatio.text = "📺 Görüntü: $modeText"
        if (showToast) Toast.makeText(this, "Görüntü Modu: $modeText", Toast.LENGTH_SHORT).show()
    }

    private fun showTrackDialog(trackType: Int, title: String, itemName: String) {
        val player = exoPlayer ?: return
        val trackCount = player.currentTracks.groups
            .filter { it.type == trackType }
            .sumOf { it.length }
        if (trackCount <= 1) {
            Toast.makeText(this, "Bu yayında seçilebilecek başka $itemName yok", Toast.LENGTH_SHORT).show()
            return
        }
        TrackSelectionDialogBuilder(this, title, player, trackType)
            .setTheme(R.style.DarkDialogTheme)
            .setShowDisableOption(false)
            .setAllowAdaptiveSelections(true)
            .build()
            .show()
    }

    private fun setSleepTimer(minutes: Int) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepWarningRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepMinutes = minutes

        if (minutes > 0) {
            val durationMs = minutes * 60_000L
            sleepEndTime = System.currentTimeMillis() + durationMs
            sleepRunnable = Runnable {
                exoPlayer?.pause()
                Toast.makeText(this, "Uyku zamanlayıcı: uygulama kapatılıyor", Toast.LENGTH_LONG).show()
                finishAffinity()
            }
            sleepWarningRunnable = Runnable {
                Toast.makeText(
                    this,
                    "1 dakika sonra kapanacak. Uzatmak için Seçenekler > Uyku Zamanlayıcı",
                    Toast.LENGTH_LONG
                ).show()
                showOsd()
            }
            mainHandler.postDelayed(sleepRunnable!!, durationMs)
            mainHandler.postDelayed(sleepWarningRunnable!!, durationMs - 60_000L)
            binding.btnSleepTimer.text = "⏾ Uyku Zamanlayıcı: $minutes dk"
            Toast.makeText(this, "$minutes dakika sonra kapanacak", Toast.LENGTH_SHORT).show()
        } else {
            sleepEndTime = 0L
            binding.btnSleepTimer.text = "⏾ Uyku Zamanlayıcı: Kapalı"
            Toast.makeText(this, "Uyku zamanlayıcı kapatıldı", Toast.LENGTH_SHORT).show()
        }
        updateSleepIndicator()
    }

    private fun updateSleepIndicator() {
        val remainingMs = sleepEndTime - System.currentTimeMillis()
        if (sleepMinutes > 0 && remainingMs > 0) {
            val remainingMin = ((remainingMs + 59_999) / 60_000).toInt()
            binding.osdView.tvOsdSleep.text = "⏾ $remainingMin dk"
            binding.osdView.tvOsdSleep.visibility = View.VISIBLE
        } else {
            binding.osdView.tvOsdSleep.visibility = View.GONE
        }
    }

    private fun showOptionsPanel() {
        binding.zappingCategoryContainer.visibility = View.GONE
        binding.playerOptionsContainer.visibility = View.VISIBLE
        hideOsd()
        binding.btnAspectRatio.requestFocus()
    }

    private fun hideOptionsPanel() {
        binding.playerOptionsContainer.visibility = View.GONE
        if (binding.zappingMenuContainer.visibility == View.VISIBLE) {
            focusActiveZappingItem()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Kanal listesi (sol panel)
    // ---------------------------------------------------------------------------------------

    private fun filterZappingChannels() {
        val filtered = if (searchQuery.isNotEmpty()) {
            // Arama, tüm kanallarda yapılır
            channelRepository.getAllChannels().filter {
                TextUtil.matchesQuery(searchQuery, it.name, TextUtil.displayGroups(it.groupTitle))
            }
        } else {
            channelList
        }
        zappingAdapter.setChannels(filtered, currentChannel?.id)
        if (searchQuery.isNotEmpty()) {
            binding.tvZappingTitle.text = "“$searchQuery” · ${filtered.size} sonuç"
            binding.zappingCategoryContainer.visibility = View.GONE
            binding.zappingMenuContainer.visibility = View.VISIBLE
        }
    }

    private fun setupZappingMenu() {
        zappingAdapter = ZappingChannelAdapter(
            onChannelSelected = { selectedChannel ->
                // Arama sonucundan seçildiyse, gezinme sırası arama sonuçları olur
                if (searchQuery.isNotEmpty()) {
                    channelList = zappingAdapter.channels.toList()
                    channelRepository.playbackQueue = channelList
                }
                hideZappingMenu()
                playChannel(selectedChannel)
            },
            onMoveUp = { channel ->
                channelRepository.moveFavoriteUp(channel.id)
                reloadCurrentZappingList()
            },
            onMoveDown = { channel ->
                channelRepository.moveFavoriteDown(channel.id)
                reloadCurrentZappingList()
            },
            onDelete = { channel ->
                channelRepository.toggleFavorite(channel.id)
                reloadCurrentZappingList()
            },
            onLongPress = { channel ->
                val newFav = channelRepository.toggleFavorite(channel.id)
                val msg = if (newFav) getString(R.string.channel_added_fav) else getString(R.string.channel_removed_fav)
                Toast.makeText(this, "${channel.name}: $msg", Toast.LENGTH_SHORT).show()
                if (channel.id == currentChannel?.id) {
                    currentChannel = currentChannel?.copy(isFavorite = newFav)
                    updateOsdFavoriteIcon(newFav)
                }
                reloadCurrentZappingList()
            }
        )
        binding.rvZappingChannels.layoutManager = LinearLayoutManager(this)
        binding.rvZappingChannels.adapter = zappingAdapter
        binding.rvZappingChannels.itemAnimator = null
        zappingAdapter.setChannels(channelList, currentChannel?.id)
    }

    private fun setupZappingCategories() {
        binding.llCategoryList.removeAllViews()

        binding.llCategoryList.addView(createCategoryButton("Tüm Kanallar") {
            selectZappingList(TabType.ALL, "Tüm Kanallar")
        })

        val btnFav = createCategoryButton("⭐ Favoriler") {
            selectZappingList(TabType.FAVORITES, "⭐ Favoriler")
        }
        btnFav.setOnLongClickListener {
            zappingAdapter.isEditMode = true
            binding.tvZappingTitle.text = "⭐ Favoriler (Düzenleme Modu)"
            loadZappingChannels(TabType.FAVORITES)
            showZappingChannels()
            Toast.makeText(this, "Favori Düzenleme Modu Açıldı", Toast.LENGTH_SHORT).show()
            true
        }
        binding.llCategoryList.addView(btnFav)

        binding.llCategoryList.addView(createCategoryButton("🕘 Son İzlenenler") {
            selectZappingList(TabType.RECENT, "🕘 Son İzlenenler")
        })

        lifecycleScope.launch {
            channelRepository.getAllCategoriesList().forEach { cat ->
                binding.llCategoryList.addView(createCategoryButton("📁 ${cat.name}") {
                    selectZappingList(TabType.CUSTOM, cat.name, categoryId = cat.id)
                })
            }
            if (prefManager.isGroupTabsEnabled()) {
                val groups = channelRepository.getGroups()
                if (groups.size > 1) {
                    groups.forEach { group ->
                        binding.llCategoryList.addView(createCategoryButton(group) {
                            selectZappingList(TabType.GROUP, group, group = group)
                        })
                    }
                }
            }
        }
    }

    private fun selectZappingList(type: TabType, title: String, categoryId: Long = -1L, group: String? = null) {
        zappingAdapter.isEditMode = false
        if (searchQuery.isNotEmpty()) binding.etPlayerSearch.setText("")
        binding.tvZappingTitle.text = title
        loadZappingChannels(type, categoryId, group)
        showZappingChannels()
    }

    private fun createCategoryButton(text: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val button = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.white))
            setBackgroundResource(R.drawable.bg_category_tab)
            textSize = 13f
            val pad = (8 * density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
            isFocusable = true
            isFocusableInTouchMode = false
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        button.layoutParams = params
        button.setOnClickListener { onClick() }
        return button
    }

    private fun loadZappingChannels(type: TabType, categoryId: Long = -1L, group: String? = null) {
        currentZappingListType = type
        currentZappingCategoryId = categoryId
        currentZappingGroup = group

        lifecycleScope.launch {
            val list = when (type) {
                TabType.FAVORITES -> channelRepository.getFavoriteChannels()
                TabType.RECENT -> channelRepository.getRecentChannels()
                TabType.ALL -> channelRepository.getAllChannels()
                TabType.CUSTOM -> channelRepository.getChannelsForCategory(categoryId)
                TabType.GROUP -> channelRepository.getChannelsForGroup(group.orEmpty())
            }
            channelList = list
            channelRepository.playbackQueue = list
            zappingAdapter.setChannels(channelList, currentChannel?.id)
        }
    }

    private fun reloadCurrentZappingList() {
        loadZappingChannels(currentZappingListType, currentZappingCategoryId, currentZappingGroup)
    }

    private fun showZappingChannels() {
        binding.zappingCategoryContainer.visibility = View.GONE
        binding.zappingMenuContainer.visibility = View.VISIBLE
        hideOsd()
        focusActiveZappingItem()
    }

    private fun focusActiveZappingItem() {
        val activeIndex = zappingAdapter.indexOf(currentChannel?.id).coerceAtLeast(0)
        binding.rvZappingChannels.scrollToPosition(activeIndex)
        binding.rvZappingChannels.post {
            val childView = binding.rvZappingChannels.findViewHolderForAdapterPosition(activeIndex)?.itemView
                ?: binding.rvZappingChannels.getChildAt(0)
            childView?.requestFocus() ?: binding.rvZappingChannels.requestFocus()
        }
    }

    private fun showZappingCategories() {
        binding.zappingMenuContainer.visibility = View.GONE
        binding.playerOptionsContainer.visibility = View.GONE
        binding.zappingCategoryContainer.visibility = View.VISIBLE

        binding.llCategoryList.post {
            binding.llCategoryList.getChildAt(0)?.requestFocus() ?: binding.zappingCategoryContainer.requestFocus()
        }
    }

    private fun hideZappingMenu() {
        binding.zappingMenuContainer.visibility = View.GONE
        binding.zappingCategoryContainer.visibility = View.GONE
        binding.playerOptionsContainer.visibility = View.GONE
    }

    private val isAnyPanelVisible: Boolean
        get() = binding.zappingMenuContainer.visibility == View.VISIBLE ||
            binding.zappingCategoryContainer.visibility == View.VISIBLE ||
            binding.playerOptionsContainer.visibility == View.VISIBLE

    // ---------------------------------------------------------------------------------------
    // OSD (alt bilgi çubuğu)
    // ---------------------------------------------------------------------------------------

    private fun setupOsdButtons() {
        binding.osdView.btnOsdFavorite.setOnClickListener {
            currentChannel?.let { channel ->
                val newFav = channelRepository.toggleFavorite(channel.id)
                currentChannel = channel.copy(isFavorite = newFav)
                updateOsdFavoriteIcon(newFav)
                val msg = if (newFav) getString(R.string.channel_added_fav) else getString(R.string.channel_removed_fav)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                resetOsdTimer()
            }
        }

        binding.osdView.btnOsdAddCategory.setOnClickListener {
            currentChannel?.let { channel -> showAddToCategoryDialog(channel) }
        }

        binding.osdView.btnOsdChannelList.setOnClickListener { showZappingChannels() }

        binding.osdView.btnOsdOptions.setOnClickListener { showOptionsPanel() }

        binding.playerView.setOnClickListener {
            if (binding.osdView.root.visibility == View.VISIBLE) hideOsd() else showOsd()
        }
    }

    private fun showAddToCategoryDialog(channel: Channel) {
        lifecycleScope.launch {
            val categories = channelRepository.getAllCategoriesList()
            if (categories.isEmpty()) {
                AlertDialog.Builder(this@PlayerActivity, R.style.DarkDialogTheme)
                    .setTitle("Kategori Bulunamadı")
                    .setMessage("Henüz oluşturulmuş bir kategori yok. Ayarlar menüsünden yeni bir kategori oluşturabilirsiniz.")
                    .setPositiveButton("Tamam", null)
                    .show()
                return@launch
            }

            val categoryNames = categories.map { it.name }.toTypedArray()
            AlertDialog.Builder(this@PlayerActivity, R.style.DarkDialogTheme)
                .setTitle("\"${channel.name}\" Kanalını Kategoriye Ekle")
                .setItems(categoryNames) { dialog, which ->
                    val selectedCategory = categories[which]
                    lifecycleScope.launch {
                        channelRepository.addChannelToCategory(selectedCategory.id, channel)
                        Toast.makeText(
                            this@PlayerActivity,
                            "${channel.name}, \"${selectedCategory.name}\" kategorisine eklendi",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    private fun updateOsdContent(channel: Channel) {
        binding.osdView.tvOsdChannelName.text = channel.displayName()
        binding.osdView.tvOsdCategory.text = TextUtil.displayGroups(channel.groupTitle)

        if (!channel.logoUrl.isNullOrBlank()) {
            binding.osdView.ivOsdLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            binding.osdView.ivOsdLogo.setImageResource(R.drawable.ic_tv)
        }

        val isFav = prefManager.isFavorite(channel.id)
        updateOsdFavoriteIcon(isFav)
        updateOsdEpg(channel)
    }

    private fun updateOsdEpg(channel: Channel) {
        val (now, next) = EpgRepository.nowAndNext(channel)
        if (now == null && next == null) {
            binding.osdView.osdEpgContainer.visibility = View.GONE
            return
        }
        binding.osdView.osdEpgContainer.visibility = View.VISIBLE
        if (now != null) {
            binding.osdView.tvOsdNow.text = "Şimdi  ${now.timeRange()}  ${now.title}"
            binding.osdView.pbOsdEpg.visibility = View.VISIBLE
            binding.osdView.pbOsdEpg.progress = now.progress()
        } else {
            binding.osdView.tvOsdNow.text = "Şu an yayın akışı bilgisi yok"
            binding.osdView.pbOsdEpg.visibility = View.GONE
        }
        if (next != null) {
            binding.osdView.tvOsdNext.visibility = View.VISIBLE
            binding.osdView.tvOsdNext.text = "Sonra  ${next.timeRange()}  ${next.title}"
        } else {
            binding.osdView.tvOsdNext.visibility = View.GONE
        }
    }

    private fun updateOsdFavoriteIcon(isFavorite: Boolean) {
        val favRes = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_border
        val tintColor = if (isFavorite) R.color.accent_gold else R.color.white
        binding.osdView.btnOsdFavorite.setImageResource(favRes)
        binding.osdView.btnOsdFavorite.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, tintColor))
    }

    private fun showOsd() {
        if (isInPipMode()) return
        currentChannel?.let { if (pendingZapChannel == null) updateOsdEpg(it) }
        updateSleepIndicator()
        binding.osdView.root.visibility = View.VISIBLE
        binding.osdView.root.animate().alpha(1f).setDuration(200).start()
        resetOsdTimer()
    }

    private fun hideOsd() {
        osdHideRunnable?.let { mainHandler.removeCallbacks(it) }
        if (binding.osdView.root.visibility != View.VISIBLE) return
        binding.osdView.root.animate().alpha(0f).setDuration(200).withEndAction {
            binding.osdView.root.visibility = View.GONE
        }.start()
    }

    private fun resetOsdTimer() {
        osdHideRunnable?.let { mainHandler.removeCallbacks(it) }
        osdHideRunnable = Runnable { hideOsd() }
        mainHandler.postDelayed(osdHideRunnable!!, OSD_TIMEOUT_MS)
    }

    private fun isViewInOsd(v: View): Boolean {
        var parent = v.parent
        while (parent != null) {
            if (parent === binding.osdView.root) return true
            parent = parent.parent
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Kumanda tuşları
    // ---------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val keyCode = event.keyCode
        val typing = currentFocus is EditText

        if (!typing) {
            val digit = when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit != -1) {
                handleNumberInput(digit)
                return true
            }
        }

        // Her durumda çalışan tuşlar
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                zapBy(1); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                zapBy(-1); return true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                switchToPreviousChannel(); return true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                GuideActivity.start(this, currentChannel?.id)
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (binding.osdView.root.visibility == View.VISIBLE) hideOsd() else showOsd()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (binding.playerOptionsContainer.visibility == View.VISIBLE) hideOptionsPanel() else showOptionsPanel()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                exoPlayer?.let { if (it.isPlaying) it.pause() else { it.seekToDefaultPosition(); it.play() } }
                return true
            }
        }

        if (isAnyPanelVisible) {
            if (handlePanelKey(keyCode, typing)) return true
            return super.dispatchKeyEvent(event)
        }

        // Panel kapalı: tam ekran izleme
        val osdVisible = binding.osdView.root.visibility == View.VISIBLE
        val focusInOsd = osdVisible && currentFocus?.let { isViewInOsd(it) } == true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                zapBy(1); return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                zapBy(-1); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (focusInOsd) {
                    resetOsdTimer()
                } else {
                    showZappingChannels(); return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (focusInOsd) {
                    resetOsdTimer()
                } else {
                    showOptionsPanel(); return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when {
                    inFinalError && !focusInOsd -> {
                        currentChannel?.let { playChannel(it) }
                        return true
                    }
                    !osdVisible -> {
                        showOsd(); return true
                    }
                    !focusInOsd -> {
                        // OSD açıkken OK: butonlara odaklan
                        binding.osdView.btnOsdFavorite.requestFocus()
                        resetOsdTimer()
                        return true
                    }
                    else -> resetOsdTimer()
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (osdVisible) {
                    hideOsd(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Sol/sağ paneller açıkken tuş yönetimi. true dönerse olay tüketilmiştir. */
    private fun handlePanelKey(keyCode: Int, typing: Boolean): Boolean {
        val optionsVisible = binding.playerOptionsContainer.visibility == View.VISIBLE
        val zappingVisible = binding.zappingMenuContainer.visibility == View.VISIBLE
        val categoriesVisible = binding.zappingCategoryContainer.visibility == View.VISIBLE

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (optionsVisible) {
                    // Arama kutusunda yazı varken imleç hareketine izin ver
                    if (typing && binding.etPlayerSearch.text.isNotEmpty()) return false
                    hideOptionsPanel()
                    return true
                }
                if (zappingVisible) {
                    showZappingCategories()
                    return true
                }
                if (categoriesVisible) return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (zappingVisible && !optionsVisible) {
                    binding.playerOptionsContainer.visibility = View.VISIBLE
                    binding.etPlayerSearch.requestFocus()
                    return true
                }
                if (categoriesVisible) {
                    showZappingChannels()
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (optionsVisible) {
                    hideOptionsPanel()
                    return true
                }
                hideZappingMenu()
                return true
            }
        }
        return false
    }

    /** Rehber ekranından kanal seçildiğinde (oynatıcı zaten açıkken) çağrılır. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
        channelList = channelRepository.playbackQueue.ifEmpty { channelRepository.getAllChannels() }
        zappingAdapter.setChannels(channelList, currentChannel?.id)
        val channel = channelList.firstOrNull { it.id == channelId } ?: channelRepository.findChannel(channelId) ?: return
        hideZappingMenu()
        playChannel(channel)
    }

    // ---------------------------------------------------------------------------------------
    // Yaşam döngüsü, PiP, ağ
    // ---------------------------------------------------------------------------------------

    private fun isInPipMode(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                // PiP desteklenmiyorsa yoksay
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.osdView.root.visibility = View.GONE
            hideZappingMenu()
            binding.loadingLayout.visibility = View.GONE
            binding.tvNetworkErrorBanner.visibility = View.GONE
            binding.tvNumberInput.visibility = View.GONE
        }
    }

    private fun setupNetworkMonitor() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                runOnUiThread {
                    networkLost = true
                    binding.tvNetworkErrorBanner.visibility = View.VISIBLE
                }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    binding.tvNetworkErrorBanner.visibility = View.GONE
                    // Bağlantı geri geldi: yayını otomatik yeniden başlat
                    if (networkLost) {
                        networkLost = false
                        val player = exoPlayer
                        if (inFinalError || player == null || !player.isPlaying) {
                            currentChannel?.let { playChannel(it) }
                        }
                    }
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            networkCallback = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (stoppedInBackground) {
            stoppedInBackground = false
            // Canlı yayına geri dön (arka planda kalan tampon eskimiştir)
            exoPlayer?.let { player ->
                if (player.playbackState == Player.STATE_IDLE && !inFinalError) {
                    startCurrentSource()
                } else {
                    player.seekToDefaultPosition()
                    player.play()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPipMode()) {
            exoPlayer?.pause()
            stoppedInBackground = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelBufferTimers()
        cancelRetry()
        cancelPendingZap()
        osdHideRunnable?.let { mainHandler.removeCallbacks(it) }
        zappingNumberRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepWarningRunnable?.let { mainHandler.removeCallbacks(it) }
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Yoksay
            }
        }
        exoPlayer?.release()
        exoPlayer = null
    }
}
