package com.example

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.model.Channel
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivityMainBinding
import com.example.ui.adapter.CategoryTabAdapter
import com.example.ui.adapter.CategoryTabItem
import com.example.ui.adapter.ChannelAdapter
import com.example.ui.adapter.TabType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ChannelRepository

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var categoryTabAdapter: CategoryTabAdapter

    private var activeTab = CategoryTabItem("favorites", "⭐ Favoriler", TabType.FAVORITES)
    private var searchQuery: String = ""

    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!spokenMatches.isNullOrEmpty()) {
                val recognizedText = spokenMatches[0]
                binding.etSearchQuery.setText(recognizedText)
                searchQuery = recognizedText
                applyFilter()
                Toast.makeText(this, "Arama: $recognizedText", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ChannelRepository.getInstance(this)

        setupTopBar()
        setupCategoryTabs()
        setupChannelsRecycler()
        loadInitialChannels()
    }

    private fun setupTopBar() {
        binding.btnVoiceSearch.setOnClickListener {
            launchVoiceSearch()
        }

        binding.btnSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun launchVoiceSearch() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
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
            activeTab = selectedTab
            applyFilter()
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvCategories.adapter = categoryTabAdapter

        loadCategoryTabs()
    }

    private fun loadCategoryTabs() {
        lifecycleScope.launch {
            val tabs = mutableListOf(
                CategoryTabItem("favorites", "⭐ Favoriler", TabType.FAVORITES),
                CategoryTabItem("all", "Tüm Kanallar", TabType.ALL)
            )
            val userCategories = repository.getAllCategoriesList()
            for (cat in userCategories) {
                tabs.add(
                    CategoryTabItem(
                        id = "custom_${cat.id}",
                        name = cat.name,
                        type = TabType.CUSTOM,
                        customCategoryId = cat.id
                    )
                )
            }
            categoryTabAdapter.setItems(tabs)
            
            // Eğer favoriler boşsa başlangıçta "Tüm Kanallar" sekmesini seç
            val favs = repository.getFavoriteChannels()
            if (favs.isEmpty() && activeTab.type == TabType.FAVORITES) {
                activeTab = tabs[1]
                categoryTabAdapter.setSelectedPosition(1)
            }
            applyFilter()
        }
    }

    private fun setupChannelsRecycler() {
        channelAdapter = ChannelAdapter(
            onChannelClick = { channel ->
                val currentList = channelAdapter.currentList
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_CHANNEL, channel)
                    putExtra(PlayerActivity.EXTRA_CHANNEL_LIST, ArrayList(currentList))
                }
                startActivity(intent)
            },
            onFavoriteToggle = { channel ->
                val newFav = repository.toggleFavorite(channel.id)
                channel.isFavorite = newFav
                applyFilter()
                val msg = if (newFav) getString(R.string.channel_added_fav) else getString(R.string.channel_removed_fav)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            },
            onAddCategoryClick = { channel ->
                showAddToCategoryDialog(channel)
            },
            onMoveUpClick = { channel ->
                repository.moveFavoriteUp(channel.id)
                applyFilter()
            },
            onMoveDownClick = { channel ->
                repository.moveFavoriteDown(channel.id)
                applyFilter()
            }
        )

        val spanCount = calculateSpanCount()
        binding.rvChannels.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvChannels.adapter = channelAdapter
    }

    private fun showAddToCategoryDialog(channel: Channel) {
        lifecycleScope.launch {
            val categories = repository.getAllCategoriesList()
            if (categories.isEmpty()) {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity, R.style.DarkDialogTheme)
                    .setTitle("Kategori Bulunamadı")
                    .setMessage("Henüz oluşturulmuş bir kategori yok. Ayarlar menüsünden yeni bir kategori oluşturabilirsiniz.")
                    .setPositiveButton("Tamam") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
                return@launch
            }

            val categoryNames = categories.map { it.name }.toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity, R.style.DarkDialogTheme)
                .setTitle("\"${channel.name}\" Kanalını Kategoriye Ekle")
                .setItems(categoryNames) { dialog, which ->
                    val selectedCategory = categories[which]
                    lifecycleScope.launch {
                        repository.addChannelToCategory(selectedCategory.id, channel)
                        Toast.makeText(
                            this@MainActivity,
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

    private fun calculateSpanCount(): Int {
        val orientation = resources.configuration.orientation
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) 4 else 2
    }

    private fun loadInitialChannels() {
        applyFilter()

        lifecycleScope.launch(Dispatchers.IO) {
            repository.loadChannels()
            withContext(Dispatchers.Main) {
                applyFilter()
                if (channelAdapter.itemCount > 0) {
                    binding.rvChannels.post {
                        binding.rvChannels.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun applyFilter() {
        lifecycleScope.launch {
            channelAdapter.isFavoritesTab = (activeTab.type == TabType.FAVORITES)

            val baseList = when (activeTab.type) {
                TabType.FAVORITES -> repository.getFavoriteChannels()
                TabType.ALL -> repository.getAllChannels()
                TabType.CUSTOM -> repository.getChannelsForCategory(activeTab.customCategoryId)
            }

            val filtered = if (searchQuery.isNotEmpty()) {
                baseList.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                    (it.groupTitle?.contains(searchQuery, ignoreCase = true) == true)
                }
            } else {
                baseList
            }

            channelAdapter.submitList(filtered) {
                if (filtered.isEmpty()) {
                    binding.mainEmptyLayout.visibility = View.VISIBLE
                    binding.rvChannels.visibility = View.GONE
                } else {
                    binding.mainEmptyLayout.visibility = View.GONE
                    binding.rvChannels.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadCategoryTabs()
        applyFilter()
    }
}
