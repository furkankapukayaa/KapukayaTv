package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.example.data.epg.EpgRepository
import com.example.data.model.Channel
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivityGuideBinding
import com.example.ui.adapter.GuideChannelAdapter
import com.example.ui.adapter.GuideProgrammeAdapter
import com.example.util.DeviceUtil
import kotlinx.coroutines.launch

/**
 * Tam ekran yayın akışı rehberi. Solda kanallar, sağda odaklanılan kanalın günlük akışı.
 * Kumandadaki Rehber (Guide) tuşu veya ana ekrandaki takvim ikonuyla açılır.
 */
class GuideActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SELECTED_CHANNEL_ID = "extra_selected_channel_id"
        private const val FOCUS_DEBOUNCE_MS = 150L

        fun start(context: Context, selectedChannelId: String? = null) {
            context.startActivity(
                Intent(context, GuideActivity::class.java).putExtra(EXTRA_SELECTED_CHANNEL_ID, selectedChannelId)
            )
        }
    }

    private lateinit var binding: ActivityGuideBinding
    private lateinit var repository: ChannelRepository
    private lateinit var channelAdapter: GuideChannelAdapter
    private lateinit var programmeAdapter: GuideProgrammeAdapter

    private var channels: List<Channel> = emptyList()
    private var selectedChannel: Channel? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSelection: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = ChannelRepository.getInstance(this)
        applyPhoneLayout()

        binding.btnGuideBack.setOnClickListener { finish() }
        binding.btnGuideSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        channelAdapter = GuideChannelAdapter(
            onFocused = { channel -> scheduleSelection(channel) },
            onClick = { channel -> playChannel(channel) }
        )
        binding.rvGuideChannels.layoutManager = LinearLayoutManager(this)
        binding.rvGuideChannels.adapter = channelAdapter
        binding.rvGuideChannels.itemAnimator = null

        programmeAdapter = GuideProgrammeAdapter(onClick = programmeClick@{ programme ->
            val now = System.currentTimeMillis()
            val channel = selectedChannel ?: return@programmeClick
            if (now >= programme.start) {
                playChannel(channel)
            } else {
                val time = android.text.format.DateFormat.format("HH:mm", programme.start)
                Toast.makeText(this, "\"${programme.title}\" $time'da başlayacak", Toast.LENGTH_SHORT).show()
            }
        })
        binding.rvGuideProgrammes.layoutManager = LinearLayoutManager(this)
        binding.rvGuideProgrammes.adapter = programmeAdapter
        binding.rvGuideProgrammes.itemAnimator = null

        loadGuide()
    }

    /** Dikey telefonda iki panel alt alta dizilir. TV'de değişiklik yapılmaz. */
    private fun applyPhoneLayout() {
        DeviceUtil.applyCompactPadding(binding.root)
        if (!DeviceUtil.isPhonePortrait(this)) return
        val content = binding.guideContent
        content.orientation = LinearLayout.VERTICAL
        binding.rvGuideChannels.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f)
        binding.rvGuideChannels.setPadding(0, 0, 0, 0)
        val programmesPanel = content.getChildAt(1)
        programmesPanel.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.58f).apply {
            topMargin = DeviceUtil.dp(this@GuideActivity, 10)
        }
    }

    private fun loadGuide() {
        if (EpgRepository.isLoaded) {
            showChannels()
            return
        }
        binding.pbGuideLoading.visibility = View.VISIBLE
        binding.guideContent.visibility = View.GONE
        lifecycleScope.launch {
            repository.ensureLoaded()
            repository.loadEpg()
            binding.pbGuideLoading.visibility = View.GONE
            showChannels()
        }
    }

    private fun showChannels() {
        channels = repository.getAllChannels().filter { EpgRepository.hasProgrammes(it) }
        if (channels.isEmpty()) {
            binding.guideContent.visibility = View.GONE
            binding.guideEmptyLayout.visibility = View.VISIBLE
            binding.btnGuideSettings.requestFocus()
            return
        }
        binding.guideEmptyLayout.visibility = View.GONE
        binding.guideContent.visibility = View.VISIBLE
        channelAdapter.setChannels(channels)

        val requestedId = intent.getStringExtra(EXTRA_SELECTED_CHANNEL_ID)
        val startIndex = channels.indexOfFirst { it.id == requestedId }.coerceAtLeast(0)
        selectChannel(channels[startIndex])
        binding.rvGuideChannels.scrollToPosition(startIndex)
        binding.rvGuideChannels.post {
            binding.rvGuideChannels.findViewHolderForAdapterPosition(startIndex)?.itemView?.requestFocus()
        }
    }

    /** Hızlı gezinirken her kanal için listeyi yeniden çizmemek için kısa gecikme. */
    private fun scheduleSelection(channel: Channel) {
        pendingSelection?.let { handler.removeCallbacks(it) }
        pendingSelection = Runnable { selectChannel(channel) }
        handler.postDelayed(pendingSelection!!, FOCUS_DEBOUNCE_MS)
    }

    private fun selectChannel(channel: Channel) {
        if (selectedChannel?.id == channel.id) return
        selectedChannel = channel
        binding.tvGuideSelectedName.text = channel.displayName()
        if (!channel.logoUrl.isNullOrBlank()) {
            binding.ivGuideSelectedLogo.load(channel.logoUrl) {
                placeholder(R.drawable.ic_tv)
                error(R.drawable.ic_tv)
            }
        } else {
            binding.ivGuideSelectedLogo.setImageResource(R.drawable.ic_tv)
        }
        programmeAdapter.setProgrammes(EpgRepository.upcomingProgrammes(channel))
        binding.rvGuideProgrammes.scrollToPosition(0)
    }

    private fun playChannel(channel: Channel) {
        repository.playbackQueue = channels
        // Oynatıcı zaten açıksa onNewIntent ile kanal değişir
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingSelection?.let { handler.removeCallbacks(it) }
    }
}
