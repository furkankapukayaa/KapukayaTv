package com.example

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.InputType
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.backup.BackupManager
import com.example.data.db.entity.Category
import com.example.data.epg.EpgRepository
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivitySettingsBinding
import com.example.ui.adapter.ManageCategoryAdapter
import com.example.util.DeviceUtil
import com.example.util.PinDialog
import com.example.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefManager: PreferenceManager
    private lateinit var channelRepository: ChannelRepository
    private lateinit var categoryAdapter: ManageCategoryAdapter
    private lateinit var backupManager: BackupManager

    // Android'in dosya seçicisi (SAF) ile yedek kaydetme / açma
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val json = backupManager.export()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                Toast.makeText(this@SettingsActivity, "Yedek kaydedildi", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Yedek kaydedilemedi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val openBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IllegalStateException("Dosya okunamadı")
                val result = backupManager.restore(json)
                channelRepository.switchPlaylist(prefManager.getActivePlaylist().id)
                AlertDialog.Builder(this@SettingsActivity, R.style.DarkDialogTheme)
                    .setTitle("Yedek Yüklendi")
                    .setMessage("${result.favorites} favori, ${result.categories} kategori ve ${result.playlists} liste geri yüklendi.")
                    .setPositiveButton("Tamam") { _, _ -> recreate() }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Yedek yüklenemedi: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager(this)
        channelRepository = ChannelRepository.getInstance(this)
        backupManager = BackupManager(this)

        // Yalnızca telefonda: dar kenar boşluğu ve yan yana butonları alt alta dizme (TV etkilenmez)
        DeviceUtil.applyCompactPadding(binding.root)
        DeviceUtil.stackButtonRows(binding.root)

        setupToolbar()
        setupToggles()
        setupM3uSection()
        setupEpgSection()
        setupPlaylists()
        setupParental()
        setupMaintenance()
        setupBackup()
        setupCategoryManagement()
        setupAboutSection()

        binding.btnRemoteSetup.setOnClickListener {
            startActivity(Intent(this, RemoteSetupActivity::class.java))
        }

        binding.btnOpenMirror.setOnClickListener {
            startActivity(Intent(this, MirrorMenuActivity::class.java))
        }
        setupToggle(binding.layoutLaunchChoiceToggle, binding.switchLaunchChoice, prefManager.isLaunchChoiceEnabled()) { enabled ->
            prefManager.setLaunchChoiceEnabled(enabled)
        }
    }

    override fun onResume() {
        super.onResume()
        // Telefondan yapılan değişiklikleri yansıt
        binding.etM3uUrl.setText(prefManager.getM3uUrl())
        binding.etEpgUrl.setText(prefManager.getEpgUrl())
        updatePlaylistHeader()
        updatePlaylistStatus()
    }

    // ---- M3U listeleri (profiller) ----

    private fun setupPlaylists() {
        updatePlaylistHeader()

        binding.btnSwitchPlaylist.setOnClickListener {
            val playlists = prefManager.getPlaylists()
            val activeIndex = playlists.indexOfFirst { it.id == prefManager.getActivePlaylist().id }
            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Liste Seç")
                .setSingleChoiceItems(playlists.map { it.name }.toTypedArray(), activeIndex) { dialog, which ->
                    dialog.dismiss()
                    val selected = playlists[which]
                    if (selected.id != prefManager.getActivePlaylist().id) switchPlaylist(selected.id)
                }
                .setNegativeButton("İptal", null)
                .show()
        }

        binding.btnAddPlaylist.setOnClickListener {
            showInputDialog(
                "Yeni M3U Listesi",
                listOf(
                    InputField("Liste adı (ör. Yerel Kanallar)", "", InputType.TYPE_CLASS_TEXT),
                    InputField("https://…/liste.m3u", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                ),
                "Ekle ve Aç"
            ) { values ->
                val name = values[0].ifBlank { "Liste ${prefManager.getPlaylists().size + 1}" }
                val url = values[1].trim()
                if (!isHttpUrl(url)) {
                    Toast.makeText(this, "Geçerli bir URL giriniz (http:// veya https://)", Toast.LENGTH_SHORT).show()
                    return@showInputDialog
                }
                val playlist = prefManager.addPlaylist(name, url)
                switchPlaylist(playlist.id)
            }
        }

        binding.btnRenamePlaylist.setOnClickListener {
            val active = prefManager.getActivePlaylist()
            showInputDialog(
                "Listeyi Yeniden Adlandır",
                listOf(InputField("Liste adı", active.name, InputType.TYPE_CLASS_TEXT)),
                "Kaydet"
            ) { values ->
                val name = values[0].trim()
                if (name.isNotEmpty()) {
                    prefManager.updatePlaylist(active.copy(name = name))
                    updatePlaylistHeader()
                }
            }
        }

        binding.btnDeletePlaylist.setOnClickListener {
            val active = prefManager.getActivePlaylist()
            if (prefManager.getPlaylists().size <= 1) {
                Toast.makeText(this, "Son kalan liste silinemez", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Listeyi Sil")
                .setMessage("\"${active.name}\" listesi silinsin mi? Favorileriniz korunur.")
                .setPositiveButton("Sil") { _, _ ->
                    prefManager.removePlaylist(active.id)
                    switchPlaylist(prefManager.getActivePlaylist().id)
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    private fun updatePlaylistHeader() {
        val playlists = prefManager.getPlaylists()
        val active = prefManager.getActivePlaylist()
        binding.tvActivePlaylist.text = "Etkin liste: ${active.name}  (${playlists.size} kayıtlı liste)"
    }

    private fun switchPlaylist(playlistId: String) {
        binding.tvPlaylistStatus.text = "Liste değiştiriliyor…"
        lifecycleScope.launch {
            val result = channelRepository.switchPlaylist(playlistId)
            binding.etM3uUrl.setText(prefManager.getM3uUrl())
            updatePlaylistHeader()
            updatePlaylistStatus()
            val message = result.error?.let { "Liste indirilemedi: $it" } ?: "${result.channels.size} kanal yüklendi"
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private data class InputField(val hint: String, val initial: String, val inputType: Int)

    private fun showInputDialog(title: String, fields: List<InputField>, positive: String, onDone: (List<String>) -> Unit) {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val h = (24 * density).toInt()
            setPadding(h, (12 * density).toInt(), h, 0)
        }
        val inputs = fields.map { field ->
            EditText(this).apply {
                hint = field.hint
                setText(field.initial)
                inputType = field.inputType
                setSingleLine()
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
                setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_muted))
                setBackgroundResource(R.drawable.bg_category_tab)
                val pad = (12 * density).toInt()
                setPadding(pad, pad, pad, pad)
                textSize = 14f
            }.also { editText ->
                container.addView(editText, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() })
            }
        }
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positive) { _, _ -> onDone(inputs.map { it.text.toString() }) }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---- Ebeveyn kilidi ----

    private fun setupParental() {
        updateParentalStatus()

        binding.btnSetPin.setOnClickListener {
            requirePin {
                PinDialog.show(this, "Yeni PIN", "4 haneli yeni PIN girin") { first ->
                    if (first.length != 4) {
                        Toast.makeText(this, "PIN 4 haneli olmalı", Toast.LENGTH_SHORT).show()
                        return@show
                    }
                    PinDialog.show(this, "PIN Tekrar", "Aynı PIN'i tekrar girin") { second ->
                        if (first == second) {
                            prefManager.setPin(first)
                            Toast.makeText(this, "PIN kaydedildi. Şimdi kilitlenecek kategorileri seçin.", Toast.LENGTH_LONG).show()
                            updateParentalStatus()
                            showLockedItemsDialog()
                        } else {
                            Toast.makeText(this, "PIN'ler eşleşmedi", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        binding.btnLockedItems.setOnClickListener {
            if (!prefManager.hasPin()) {
                Toast.makeText(this, "Önce bir PIN belirleyin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            requirePin { showLockedItemsDialog() }
        }

        binding.btnRemovePin.setOnClickListener {
            if (!prefManager.hasPin()) return@setOnClickListener
            requirePin {
                prefManager.clearPin()
                channelRepository.lockParental()
                lifecycleScope.launch { channelRepository.refreshLockCache() }
                updateParentalStatus()
                Toast.makeText(this, "Ebeveyn kilidi kaldırıldı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** PIN tanımlıysa önce doğrulatır, değilse doğrudan devam eder. */
    private fun requirePin(onSuccess: () -> Unit) {
        if (!prefManager.hasPin()) {
            onSuccess()
            return
        }
        PinDialog.show(this, "Mevcut PIN", getString(R.string.parental_enter_pin)) { pin ->
            if (prefManager.checkPin(pin)) onSuccess()
            else Toast.makeText(this, R.string.parental_wrong_pin, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLockedItemsDialog() {
        lifecycleScope.launch {
            val groups = channelRepository.getGroups().map { "group:$it" to it }
            val categories = channelRepository.getAllCategoriesList().map { "custom:${it.id}" to "📁 ${it.name}" }
            val options = groups + categories
            if (options.isEmpty()) {
                Toast.makeText(this@SettingsActivity, "Kilitlenecek grup veya kategori yok", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val locked = prefManager.getLockedItems()
            val checked = options.map { it.first in locked }.toBooleanArray()
            AlertDialog.Builder(this@SettingsActivity, R.style.DarkDialogTheme)
                .setTitle("Kilitlenecek Gruplar ve Kategoriler")
                .setMultiChoiceItems(options.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton("Kaydet") { _, _ ->
                    prefManager.setLockedItems(options.filterIndexed { i, _ -> checked[i] }.map { it.first })
                    channelRepository.lockParental()
                    lifecycleScope.launch { channelRepository.refreshLockCache() }
                    updateParentalStatus()
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    private fun updateParentalStatus() {
        val count = prefManager.getLockedItems().size
        binding.tvParentalStatus.text = when {
            !prefManager.hasPin() -> "Kapalı"
            count == 0 -> "PIN tanımlı · kilitli kategori yok"
            else -> "Açık · $count grup/kategori kilitli"
        }
    }

    // ---- Kanal bakımı ----

    private fun setupMaintenance() {
        setupToggle(binding.layoutHideBrokenToggle, binding.switchHideBroken, prefManager.isHideBrokenEnabled()) { enabled ->
            prefManager.setHideBroken(enabled)
        }

        binding.btnHealthCheck.setOnClickListener {
            channelRepository.startHealthCheck(force = true)
        }

        binding.btnHiddenChannels.setOnClickListener { showHiddenChannelsDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                channelRepository.healthProgress.collect { progress ->
                    binding.btnHealthCheck.isEnabled = !progress.running
                    binding.tvHealthStatus.text = when {
                        progress.running -> "Kontrol ediliyor: ${progress.checked}/${progress.total} · ${progress.broken} kanal açılmıyor"
                        progress.total > 0 -> "Son kontrol tamamlandı · ${progress.broken} kanal açılmıyor"
                        else -> {
                            val last = prefManager.getLastHealthCheck()
                            val broken = channelRepository.brokenChannelIds().size
                            if (last > 0) {
                                "Son kontrol: ${DateFormat.format("dd.MM.yyyy HH:mm", last)} · $broken kanal açılmıyor"
                            } else {
                                "Henüz kontrol yapılmadı"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showHiddenChannelsDialog() {
        val hiddenIds = prefManager.getHiddenChannelIds()
        if (hiddenIds.isEmpty()) {
            Toast.makeText(this, "Gizlenen kanal yok. Kanal kartında OK'a basılı tutup \"Kanalı gizle\" seçebilirsiniz.", Toast.LENGTH_LONG).show()
            return
        }
        val byId = channelRepository.getAllChannelsUnfiltered().associateBy { it.id }
        val items = hiddenIds.toList()
        val names = items.map { id -> byId[id]?.name ?: id }.toTypedArray()
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Gösterilecek kanalları seçin")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Tekrar Göster") { _, _ ->
                val toShow = items.filterIndexed { i, _ -> checked[i] }
                channelRepository.unhideChannels(toShow)
                Toast.makeText(this, "${toShow.size} kanal tekrar gösterilecek", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Tümünü Göster") { _, _ ->
                channelRepository.unhideChannels(items)
                Toast.makeText(this, "Tüm gizlenen kanallar gösterilecek", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---- Yedekleme ----

    private fun setupBackup() {
        binding.btnExportBackup.setOnClickListener {
            try {
                createBackupLauncher.launch(BackupManager.suggestedFileName())
            } catch (e: ActivityNotFoundException) {
                showNoFilePicker()
            }
        }
        binding.btnImportBackup.setOnClickListener {
            try {
                openBackupLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            } catch (e: ActivityNotFoundException) {
                showNoFilePicker()
            }
        }
    }

    private fun showNoFilePicker() {
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Dosya Seçici Bulunamadı")
            .setMessage("Bu cihazda dosya seçici yok. Yedeği telefonunuza indirmek veya telefondan yüklemek için Telefondan Yönet ekranını kullanın.")
            .setPositiveButton("Telefondan Yönet") { _, _ ->
                startActivity(Intent(this, RemoteSetupActivity::class.java))
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    /** Kart tıklanınca anahtarı değiştiren ortak ayar satırı. */
    private fun setupToggle(
        container: View,
        switch: SwitchCompat,
        initial: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        switch.isChecked = initial
        container.setOnClickListener {
            val newState = !switch.isChecked
            switch.isChecked = newState
            onChanged(newState)
        }
        container.setOnFocusChangeListener { view, hasFocus ->
            val scale = if (hasFocus) 1.02f else 1.0f
            view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        }
    }

    private fun setupToggles() {
        setupToggle(binding.layoutBootToggle, binding.switchBootStartup, prefManager.isBootOnStartupEnabled()) { enabled ->
            prefManager.setBootOnStartup(enabled)
            val msg = if (enabled) {
                "Cihaz açılışında otomatik başlatma etkinleştirildi"
            } else {
                "Cihaz açılışında başlatma kapatıldı"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        setupToggle(
            binding.layoutResumeToggle,
            binding.switchResumeLastChannel,
            prefManager.isResumeLastChannelEnabled()
        ) { enabled ->
            prefManager.setResumeLastChannel(enabled)
        }

        setupToggle(
            binding.layoutMergeToggle,
            binding.switchMergeDuplicates,
            prefManager.isMergeDuplicatesEnabled()
        ) { enabled ->
            prefManager.setMergeDuplicates(enabled)
            // Birleştirme ayarı listenin yeniden işlenmesini gerektirir
            syncPlaylist(prefManager.getM3uUrl())
        }

        setupToggle(
            binding.layoutGroupTabsToggle,
            binding.switchGroupTabs,
            prefManager.isGroupTabsEnabled()
        ) { enabled ->
            prefManager.setGroupTabsEnabled(enabled)
        }
    }

    // ---- M3U listesi ----

    private fun setupM3uSection() {
        binding.etM3uUrl.setText(prefManager.getM3uUrl())
        updatePlaylistStatus()

        binding.btnSaveAndSyncM3u.setOnClickListener {
            val url = binding.etM3uUrl.text.toString().trim()
            if (!isHttpUrl(url)) {
                Toast.makeText(this, "Geçerli bir URL giriniz (http:// veya https://)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefManager.setM3uUrl(url)
            syncPlaylist(url)
        }

        binding.btnResetM3u.setOnClickListener {
            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Varsayılan Listeye Dön")
                .setMessage("M3U adresi varsayılan Türkiye listesiyle (iptv-org) değiştirilecek. Favorileriniz korunur.")
                .setPositiveButton("Devam") { _, _ ->
                    val url = PreferenceManager.DEFAULT_M3U_URL
                    binding.etM3uUrl.setText(url)
                    prefManager.setM3uUrl(url)
                    syncPlaylist(url)
                }
                .setNegativeButton("İptal", null)
                .show()
        }
    }

    private fun isHttpUrl(url: String) =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun syncPlaylist(url: String) {
        binding.tvPlaylistStatus.text = "Kanallar indiriliyor…"
        binding.btnSaveAndSyncM3u.isEnabled = false

        lifecycleScope.launch {
            val result = channelRepository.refresh(url)
            binding.btnSaveAndSyncM3u.isEnabled = true
            if (result.error == null) {
                Toast.makeText(
                    this@SettingsActivity,
                    "${result.channels.size} kanal başarıyla yüklendi!",
                    Toast.LENGTH_LONG
                ).show()
                // Liste kendi EPG adresini getirmiş olabilir
                if (binding.etEpgUrl.text.isBlank() && prefManager.getEffectiveEpgUrl() != null) {
                    loadEpg(force = false)
                }
            } else {
                AlertDialog.Builder(this@SettingsActivity, R.style.DarkDialogTheme)
                    .setTitle("Liste Yüklenemedi")
                    .setMessage(
                        "${result.error}\n\nAdresi kontrol edip tekrar deneyin. " +
                            "Önceki kayıtlı liste kullanılmaya devam edecek."
                    )
                    .setPositiveButton("Tamam", null)
                    .show()
            }
            updatePlaylistStatus()
        }
    }

    private fun updatePlaylistStatus() {
        val count = channelRepository.getAllChannels().size
        val last = prefManager.getLastRefreshTime()
        val lastText = if (last > 0) DateFormat.format("dd.MM.yyyy HH:mm", last).toString() else "henüz yok"
        val error = channelRepository.lastRefreshError?.let { "\nSon deneme başarısız: $it" } ?: ""
        binding.tvPlaylistStatus.text = "$count kanal · Son başarılı güncelleme: $lastText$error"
    }

    // ---- EPG (yayın akışı) ----

    private fun setupEpgSection() {
        binding.etEpgUrl.setText(prefManager.getEpgUrl())
        updateEpgStatus(null)

        binding.btnSaveEpg.setOnClickListener {
            val url = binding.etEpgUrl.text.toString().trim()
            if (url.isNotEmpty() && !isHttpUrl(url)) {
                Toast.makeText(this, "Geçerli bir URL giriniz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefManager.setEpgUrl(url)
            if (prefManager.getEffectiveEpgUrl() == null) {
                EpgRepository.clear(this)
                updateEpgStatus(null)
                Toast.makeText(this, "Yayın akışı kapatıldı", Toast.LENGTH_SHORT).show()
            } else {
                loadEpg(force = true)
            }
        }
    }

    private fun loadEpg(force: Boolean) {
        binding.tvEpgStatus.text = "Yayın akışı indiriliyor… (büyük dosyalarda biraz sürebilir)"
        binding.btnSaveEpg.isEnabled = false
        lifecycleScope.launch {
            val result = channelRepository.loadEpg(force)
            binding.btnSaveEpg.isEnabled = true
            updateEpgStatus(result)
        }
    }

    private fun updateEpgStatus(result: Result<Int>?) {
        val effective = prefManager.getEffectiveEpgUrl()
        binding.tvEpgStatus.text = when {
            result?.isFailure == true ->
                "Yayın akışı yüklenemedi: ${result.exceptionOrNull()?.message ?: "bilinmeyen hata"}"
            result?.isSuccess == true -> "Yayın akışı yüklendi · ${result.getOrDefault(0)} kanal eşleşti"
            effective == null -> "Yayın akışı kapalı"
            EpgRepository.isLoaded -> "Yayın akışı yüklü"
            prefManager.getEpgUrl().isBlank() -> "Listenin kendi EPG adresi kullanılıyor: $effective"
            else -> "Kaydedildi, bir sonraki açılışta yüklenecek"
        }
    }

    // ---- Kategoriler ----

    private fun setupCategoryManagement() {
        categoryAdapter = ManageCategoryAdapter(
            onEditClick = { category -> showEditCategoryDialog(category) },
            onDeleteClick = { category -> showDeleteCategoryDialog(category) }
        )
        binding.rvManageCategories.layoutManager = LinearLayoutManager(this)
        binding.rvManageCategories.adapter = categoryAdapter

        binding.btnAddCategory.setOnClickListener {
            showCategoryNameDialog("Yeni Kategori Oluştur", "Ekle", "") { name ->
                lifecycleScope.launch {
                    channelRepository.createCategory(name)
                    Toast.makeText(this@SettingsActivity, "Kategori oluşturuldu", Toast.LENGTH_SHORT).show()
                    loadCategories()
                }
            }
        }

        loadCategories()
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            categoryAdapter.setCategories(channelRepository.getAllCategoriesList())
        }
    }

    private fun showCategoryNameDialog(title: String, positive: String, initial: String, onSave: (String) -> Unit) {
        val density = resources.displayMetrics.density
        val container = FrameLayout(this)
        val input = EditText(this).apply {
            setText(initial)
            hint = getString(R.string.category_name_hint)
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setHintTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_category_tab)
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            textSize = 14f
            setSingleLine()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val h = (24 * density).toInt()
            val v = (12 * density).toInt()
            setMargins(h, v, h, v)
        }
        container.addView(input, params)

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(positive) { dialog, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    onSave(name)
                } else {
                    Toast.makeText(this, "Kategori adı boş olamaz", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun showEditCategoryDialog(category: Category) {
        showCategoryNameDialog("Kategoriyi Düzenle", "Kaydet", category.name) { newName ->
            lifecycleScope.launch {
                channelRepository.updateCategory(category.copy(name = newName))
                Toast.makeText(this@SettingsActivity, "Kategori güncellendi", Toast.LENGTH_SHORT).show()
                loadCategories()
            }
        }
    }

    private fun showDeleteCategoryDialog(category: Category) {
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Kategoriyi Sil")
            .setMessage("\"${category.name}\" kategorisini silmek istediğinize emin misiniz?")
            .setPositiveButton("Sil") { dialog, _ ->
                lifecycleScope.launch {
                    channelRepository.deleteCategory(category)
                    Toast.makeText(this@SettingsActivity, "Kategori silindi", Toast.LENGTH_SHORT).show()
                    loadCategories()
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---- Hakkında ----

    private fun setupAboutSection() {
        binding.btnAboutDevice.setOnClickListener {
            val brand = Build.MANUFACTURER.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
            val message = "Marka: $brand\nModel: ${Build.MODEL}\n" +
                "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                "IP Adresi: ${getDeviceIpAddress(this)}"

            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Cihaz Hakkında")
                .setMessage(message)
                .setPositiveButton("Tamam", null)
                .show()
        }

        binding.btnAboutApp.setOnClickListener {
            val aboutText = """
                Kapukaya TV
                Versiyon: ${BuildConfig.VERSION_NAME}

                kapukaya.dev tarafından özenle geliştirilmiştir.

                YASAL UYARI:
                Bu uygulama gelişmiş bir medya oynatıcıdır. İçerisinde hiçbir dahili kanal, yayın veya medya barındırmaz. Kullanıcılar kendi içeriklerini (M3U vb.) eklemekle yükümlüdür. Telif hakkı ihlallerinden ve eklenen içeriklerden tamamen son kullanıcı sorumludur.
            """.trimIndent()

            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Uygulama Hakkında")
                .setMessage(aboutText)
                .setPositiveButton("Tamam", null)
                .show()
        }

        binding.btnClearRecents.setOnClickListener {
            prefManager.clearRecents()
            Toast.makeText(this, "Son izlenenler temizlendi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDeviceIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager?.connectionInfo
            @Suppress("DEPRECATION")
            if (wifiInfo != null && wifiInfo.ipAddress != 0) {
                val ip = wifiInfo.ipAddress
                return String.format(
                    Locale.getDefault(),
                    "%d.%d.%d.%d",
                    ip and 0xff,
                    ip shr 8 and 0xff,
                    ip shr 16 and 0xff,
                    ip shr 24 and 0xff
                )
            }
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && !hostAddress.contains(":")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Bilinmiyor"
    }
}
