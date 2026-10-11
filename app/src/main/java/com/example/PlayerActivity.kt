package com.example

import android.app.PictureInPictureParams
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.data.model.Channel
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivityPlayerBinding
import com.example.ui.adapter.TabType
import com.example.ui.adapter.ZappingChannelAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var channelRepository: ChannelRepository

    private var currentChannel: Channel? = null
    private var channelList: ArrayList<Channel> = arrayListOf()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var osdHideRunnable: Runnable? = null
    private var bufferTimeoutRunnable: Runnable? = null
    private var zappingNumberRunnable: Runnable? = null

    private val typedNumberBuffer = StringBuilder()
    private var retryCount = 0

    private lateinit var zappingAdapter: ZappingChannelAdapter
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var currentZappingListType = TabType.ALL
    private var currentZappingCategoryId = -1L
    private var searchQuery = ""
    @androidx.media3.common.util.UnstableApi
    private var currentResizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

    private val voiceSearchLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            if (!spokenMatches.isNullOrEmpty()) {
                val recognizedText = spokenMatches[0]
                binding.etPlayerSearch.setText(recognizedText)
                binding.etPlayerSearch.requestFocus()
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL = "extra_channel"
        const val EXTRA_CHANNEL_LIST = "extra_channel_list"
        private const val OSD_TIMEOUT_MS = 6000L
        private const val BUFFER_TIMEOUT_MS = 5000L
        private const val ZAPPING_DELAY_MS = 1500L
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelRepository = ChannelRepository.getInstance(this)

        @Suppress("DEPRECATION")
        currentChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_CHANNEL, Channel::class.java)
        } else {
            intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        }

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val passedList = intent.getSerializableExtra(EXTRA_CHANNEL_LIST) as? ArrayList<Channel>
        if (passedList != null) {
            channelList = passedList
        }

        setupZappingMenu()
        setupZappingCategories()
        setupPlayerOptions()
        setupPlayerOptions()
        setupOsdButtons()
        setupNetworkMonitor()
        initializePlayer()

        currentChannel?.let {
            playChannel(it)
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun setupPlayerOptions() {
        binding.etPlayerSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                filterZappingChannels()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.btnPlayerVoiceSearch.setOnClickListener {
            try {
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Kanal adı söyleyin")
                }
                voiceSearchLauncher.launch(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Sesli arama desteklenmiyor", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAspectRatio.setOnClickListener {
            toggleAspectRatio()
        }
    }

    @androidx.media3.common.util.UnstableApi
    private fun toggleAspectRatio() {
        currentResizeMode = when (currentResizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = currentResizeMode

        val modeText = when (currentResizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Sığdır"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Tam Ekran"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Kırp (Zoom)"
            else -> "Sığdır"
        }
        binding.btnAspectRatio.text = "📺 Görüntü: $modeText"
        Toast.makeText(this, "Görüntü Modu: $modeText", Toast.LENGTH_SHORT).show()
    }

    private fun filterZappingChannels() {
        val filtered = if (searchQuery.isNotEmpty()) {
            channelList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                (it.groupTitle?.contains(searchQuery, ignoreCase = true) == true)
            }
        } else {
            channelList
        }
        zappingAdapter.setChannels(filtered, currentChannel?.id)
    }

    private fun setupZappingMenu() {
        zappingAdapter = ZappingChannelAdapter(
            onChannelSelected = { selectedChannel ->
                hideZappingMenu()
                playChannel(selectedChannel)
            },
            onMoveUp = { channel ->
                channelRepository.moveFavoriteUp(channel.id)
                loadZappingChannels(currentZappingListType, currentZappingCategoryId)
            },
            onMoveDown = { channel ->
                channelRepository.moveFavoriteDown(channel.id)
                loadZappingChannels(currentZappingListType, currentZappingCategoryId)
            },
            onDelete = { channel ->
                channelRepository.toggleFavorite(channel.id)
                loadZappingChannels(currentZappingListType, currentZappingCategoryId)
            }
        )
        binding.rvZappingChannels.layoutManager = LinearLayoutManager(this)
        binding.rvZappingChannels.adapter = zappingAdapter
        zappingAdapter.setChannels(channelList, currentChannel?.id)
    }

    private fun setupZappingCategories() {
        binding.llCategoryList.removeAllViews()

        val btnAll = createCategoryButton("Tüm Kanallar") {
            zappingAdapter.isEditMode = false
            binding.tvZappingTitle.text = "Tüm Kanallar"
            loadZappingChannels(TabType.ALL)
            showZappingChannels()
        }
        binding.llCategoryList.addView(btnAll)

        val btnFav = createCategoryButton("⭐ Favoriler") {
            zappingAdapter.isEditMode = false
            binding.tvZappingTitle.text = "⭐ Favoriler"
            loadZappingChannels(TabType.FAVORITES)
            showZappingChannels()
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

        lifecycleScope.launch {
            val customCategories = channelRepository.getAllCategoriesList()
            for (cat in customCategories) {
                val btnCustom = createCategoryButton("📁 ${cat.name}") {
                    zappingAdapter.isEditMode = false
                    binding.tvZappingTitle.text = cat.name
                    loadZappingChannels(TabType.CUSTOM, cat.id)
                    showZappingChannels()
                }
                binding.llCategoryList.addView(btnCustom)
            }
        }
    }

    private fun createCategoryButton(text: String, onClick: () -> Unit): Button {
        val button = Button(this).apply {
            this.text = text
            this.isAllCaps = false
            this.setTextColor(androidx.core.content.ContextCompat.getColor(this@PlayerActivity, R.color.white))
            this.setBackgroundResource(R.drawable.bg_category_tab)
            this.textSize = 13f
            this.setPadding(16, 16, 16, 16)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, 16)
        }
        button.layoutParams = params
        button.setOnClickListener { onClick() }
        return button
    }

    private fun loadZappingChannels(type: TabType, categoryId: Long = -1L) {
        currentZappingListType = type
        currentZappingCategoryId = categoryId

        lifecycleScope.launch(Dispatchers.IO) {
            val list = when (type) {
                TabType.FAVORITES -> channelRepository.getFavoriteChannels()
                TabType.ALL -> channelRepository.getAllChannels()
                TabType.CUSTOM -> channelRepository.getChannelsForCategory(categoryId)
                else -> channelRepository.getAllChannels()
            }
            channelList = ArrayList(list)
            withContext(Dispatchers.Main) {
                zappingAdapter.setChannels(channelList, currentChannel?.id)
            }
        }
    }

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
            currentChannel?.let { channel ->
                showAddToCategoryDialog(channel)
            }
        }

        binding.playerView.setOnClickListener {
            if (binding.osdView.root.visibility == View.VISIBLE) {
                hideOsd()
            } else {
                showOsd()
            }
        }
    }

    private fun showAddToCategoryDialog(channel: Channel) {
        lifecycleScope.launch {
            val categories = channelRepository.getAllCategoriesList()
            if (categories.isEmpty()) {
                AlertDialog.Builder(this@PlayerActivity, R.style.DarkDialogTheme)
                    .setTitle("Kategori Bulunamadı")
                    .setMessage("Henüz oluşturulmuş bir kategori yok. Ayarlar menüsünden yeni bir kategori oluşturabilirsiniz.")
                    .setPositiveButton("Tamam") { dialog, _ ->
                        dialog.dismiss()
                    }
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
                .setNegativeButton("İptal") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .build().apply {
                    binding.playerView.player = this
                    playWhenReady = true

                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .setMaxVideoBitrate(Int.MAX_VALUE)
                        .setMaxVideoSize(3840, 2160)
                        .build()

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> {
                                    binding.loadingLayout.visibility = View.VISIBLE
                                    startBufferTimeoutTimer()
                                }
                                Player.STATE_READY -> {
                                    cancelBufferTimeoutTimer()
                                    binding.loadingLayout.visibility = View.GONE
                                    binding.tvErrorMessage.visibility = View.GONE
                                    binding.tvRetryHint.visibility = View.GONE
                                    retryCount = 0
                                }
                                Player.STATE_ENDED -> {
                                    retryPlayback()
                                }
                                Player.STATE_IDLE -> {
                                    // Idle
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            showStreamErrorAndRetry()
                        }
                    })
                }
        }
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        retryCount = 0
        cancelBufferTimeoutTimer()

        if (!channel.logoUrl.isNullOrBlank()) {
            binding.ivLoadingLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            binding.ivLoadingLogo.setImageResource(R.drawable.ic_tv)
        }
        val displayTitle = if (channel.channelNumber > 0) "${channel.channelNumber}. ${channel.name}" else channel.name
        binding.tvLoadingStatus.text = "$displayTitle Açılıyor…"
        binding.tvErrorMessage.visibility = View.GONE
        binding.tvRetryHint.visibility = View.GONE
        binding.loadingLayout.visibility = View.VISIBLE

        updateOsdContent(channel)
        showOsd()

        zappingAdapter.setChannels(channelList, channel.id)

        try {
            val mediaItem = MediaItem.fromUri(channel.streamUrl)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
            startBufferTimeoutTimer()
        } catch (e: Exception) {
            showStreamErrorAndRetry()
        }
    }

    private fun startBufferTimeoutTimer() {
        cancelBufferTimeoutTimer()
        bufferTimeoutRunnable = Runnable {
            binding.tvErrorMessage.visibility = View.VISIBLE
            binding.tvRetryHint.visibility = View.VISIBLE
            retryPlayback()
        }
        mainHandler.postDelayed(bufferTimeoutRunnable!!, BUFFER_TIMEOUT_MS)
    }

    private fun cancelBufferTimeoutTimer() {
        bufferTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bufferTimeoutRunnable = null
    }

    private fun showStreamErrorAndRetry() {
        binding.loadingLayout.visibility = View.VISIBLE
        binding.tvErrorMessage.visibility = View.VISIBLE
        binding.tvRetryHint.visibility = View.VISIBLE
        retryPlayback()
    }

    private fun retryPlayback() {
        retryCount++
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                currentChannel?.let { channel ->
                    exoPlayer?.setMediaItem(MediaItem.fromUri(channel.streamUrl))
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                }
            }
        }, 2500)
    }

    private fun updateOsdContent(channel: Channel) {
        val displayName = if (channel.channelNumber > 0) "${channel.channelNumber}. ${channel.name}" else channel.name
        binding.osdView.tvOsdChannelName.text = displayName
        binding.osdView.tvOsdCategory.text = channel.groupTitle ?: "Genel"

        if (!channel.logoUrl.isNullOrBlank()) {
            binding.osdView.ivOsdLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            binding.osdView.ivOsdLogo.setImageResource(R.drawable.ic_tv)
        }

        updateOsdFavoriteIcon(channel.isFavorite)
    }

    private fun updateOsdFavoriteIcon(isFavorite: Boolean) {
        val favRes = if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_border
        val tintColor = if (isFavorite) R.color.accent_gold else R.color.white
        binding.osdView.btnOsdFavorite.setImageResource(favRes)
        binding.osdView.btnOsdFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(this, tintColor)
        )
    }

    private fun showOsd() {
        binding.osdView.root.visibility = View.VISIBLE
        binding.osdView.root.animate().alpha(1f).setDuration(200).start()
        binding.osdView.btnOsdFavorite.requestFocus()
        resetOsdTimer()
    }

    private fun hideOsd() {
        binding.osdView.root.animate().alpha(0f).setDuration(200).withEndAction {
            binding.osdView.root.visibility = View.GONE
        }.start()
        osdHideRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    private fun resetOsdTimer() {
        osdHideRunnable?.let { mainHandler.removeCallbacks(it) }
        osdHideRunnable = Runnable { hideOsd() }
        mainHandler.postDelayed(osdHideRunnable!!, OSD_TIMEOUT_MS)
    }

    private fun showZappingChannels() {
        binding.zappingCategoryContainer.visibility = View.GONE
        binding.zappingMenuContainer.visibility = View.VISIBLE
        binding.zappingMenuContainer.animate().translationX(0f).setDuration(150).start()

        val activeIndex = channelList.indexOfFirst { it.id == currentChannel?.id }.coerceAtLeast(0)
        binding.rvZappingChannels.scrollToPosition(activeIndex)

        binding.rvZappingChannels.post {
            val childView = binding.rvZappingChannels.findViewHolderForAdapterPosition(activeIndex)?.itemView
                ?: binding.rvZappingChannels.layoutManager?.findViewByPosition(activeIndex)
                ?: binding.rvZappingChannels.getChildAt(0)

            childView?.requestFocus() ?: binding.rvZappingChannels.requestFocus()
        }
    }

    private fun showZappingCategories() {
        binding.zappingMenuContainer.visibility = View.GONE
        binding.zappingCategoryContainer.visibility = View.VISIBLE
        binding.zappingCategoryContainer.animate().translationX(0f).setDuration(150).start()

        binding.llCategoryList.post {
            val firstBtn = binding.llCategoryList.getChildAt(0)
            firstBtn?.requestFocus() ?: binding.zappingCategoryContainer.requestFocus()
        }
    }

    private fun hideZappingMenu() {
        binding.zappingMenuContainer.visibility = View.GONE
        binding.zappingCategoryContainer.visibility = View.GONE
        binding.playerOptionsContainer.visibility = View.GONE
    }

    private fun isViewInZappingMenu(v: View): Boolean {
        var parent = v.parent
        while (parent != null) {
            if (parent === binding.zappingMenuContainer || parent === binding.rvZappingChannels) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun isViewInOsd(v: View): Boolean {
        var parent = v.parent
        while (parent != null) {
            if (parent === binding.osdView.root || parent === binding.osdView.osdContainer) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    // Key handler for remote control (DPad and Digit keys 0-9)
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode

            // Check for digit keys (0-9)
            val digit = when (keyCode) {
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
                KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
                KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
                KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
                KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
                KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
                else -> -1
            }

            if (digit != -1) {
                handleNumberInput(digit)
                return true
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (binding.zappingMenuContainer.visibility != View.VISIBLE && binding.zappingCategoryContainer.visibility != View.VISIBLE) {
                        if (binding.osdView.root.visibility == View.VISIBLE) {
                            resetOsdTimer()
                        } else {
                            showOsd()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.zappingMenuContainer.visibility == View.VISIBLE) {
                        val focused = currentFocus
                        if (focused == null || !isViewInZappingMenu(focused)) {
                            val activeIndex = channelList.indexOfFirst { it.id == currentChannel?.id }.coerceAtLeast(0)
                            val childView = binding.rvZappingChannels.findViewHolderForAdapterPosition(activeIndex)?.itemView
                                ?: binding.rvZappingChannels.getChildAt(0)
                            childView?.requestFocus()
                        }
                    } else if (binding.zappingCategoryContainer.visibility == View.VISIBLE) {
                        // let default focus handle up
                    } else if (binding.osdView.root.visibility == View.GONE) {
                        showOsd()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.zappingMenuContainer.visibility == View.VISIBLE || binding.zappingCategoryContainer.visibility == View.VISIBLE) {
                        // handle list navigation natively
                    } else if (binding.osdView.root.visibility == View.VISIBLE) {
                        hideOsd()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (binding.playerOptionsContainer.visibility == View.VISIBLE) {
                        binding.playerOptionsContainer.visibility = View.GONE
                        binding.zappingMenuContainer.requestFocus()
                        return true
                    } else if (binding.osdView.root.visibility == View.VISIBLE) {
                        resetOsdTimer()
                    } else if (binding.zappingMenuContainer.visibility == View.VISIBLE) {
                        showZappingCategories()
                        return true
                    } else if (binding.zappingCategoryContainer.visibility == View.GONE) {
                        showZappingChannels()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (binding.zappingMenuContainer.visibility == View.VISIBLE && binding.playerOptionsContainer.visibility == View.GONE) {
                        binding.playerOptionsContainer.visibility = View.VISIBLE
                        binding.etPlayerSearch.requestFocus()
                        return true
                    } else if (binding.osdView.root.visibility == View.VISIBLE) {
                        resetOsdTimer()
                    } else if (binding.zappingCategoryContainer.visibility == View.VISIBLE) {
                        showZappingChannels()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (binding.playerOptionsContainer.visibility == View.VISIBLE) {
                        binding.playerOptionsContainer.visibility = View.GONE
                        binding.zappingMenuContainer.requestFocus()
                        return true
                    }
                    if (binding.zappingCategoryContainer.visibility == View.VISIBLE || binding.zappingMenuContainer.visibility == View.VISIBLE) {
                        hideZappingMenu()
                        return true
                    }
                    if (binding.osdView.root.visibility == View.VISIBLE) {
                        hideOsd()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleNumberInput(digit: Int) {
        typedNumberBuffer.append(digit)
        binding.tvNumberInput.text = typedNumberBuffer.toString()
        binding.tvNumberInput.visibility = View.VISIBLE

        zappingNumberRunnable?.let { mainHandler.removeCallbacks(it) }
        zappingNumberRunnable = Runnable {
            val targetNum = typedNumberBuffer.toString().toIntOrNull()
            typedNumberBuffer.clear()
            binding.tvNumberInput.visibility = View.GONE

            if (targetNum != null) {
                val targetChannel = channelList.find { it.channelNumber == targetNum }
                    ?: channelList.find { it.orderIndex == targetNum }
                    ?: channelRepository.getAllChannels().find { it.channelNumber == targetNum }

                if (targetChannel != null) {
                    playChannel(targetChannel)
                } else {
                    Toast.makeText(this, "$targetNum numaralı kanal bulunamadı", Toast.LENGTH_SHORT).show()
                }
            }
        }
        mainHandler.postDelayed(zappingNumberRunnable!!, ZAPPING_DELAY_MS)
    }

    // Picture-in-Picture (PiP)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val aspectRatio = Rational(16, 9)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                // Ignore if PiP fails
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.osdView.root.visibility = View.GONE
            binding.zappingMenuContainer.visibility = View.GONE
            binding.zappingCategoryContainer.visibility = View.GONE
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
                    binding.tvNetworkErrorBanner.visibility = View.VISIBLE
                }
            }

            override fun onAvailable(network: Network) {
                runOnUiThread {
                    binding.tvNetworkErrorBanner.visibility = View.GONE
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelBufferTimeoutTimer()
        osdHideRunnable?.let { mainHandler.removeCallbacks(it) }
        zappingNumberRunnable?.let { mainHandler.removeCallbacks(it) }
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore
            }
        }
        exoPlayer?.release()
        exoPlayer = null
    }
}
