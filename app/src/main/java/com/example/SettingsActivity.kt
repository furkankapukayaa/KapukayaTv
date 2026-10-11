package com.example

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.db.entity.Category
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivitySettingsBinding
import com.example.ui.adapter.ManageCategoryAdapter
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefManager = PreferenceManager(this)
        channelRepository = ChannelRepository.getInstance(this)

        setupToolbar()
        setupBootStartup()
        setupM3uSection()
        setupCategoryManagement()
        setupAboutSection()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupBootStartup() {
        val isBootEnabled = prefManager.isBootOnStartupEnabled()
        binding.switchBootStartup.isChecked = isBootEnabled

        binding.layoutBootToggle.setOnClickListener {
            val newState = !binding.switchBootStartup.isChecked
            binding.switchBootStartup.isChecked = newState
            prefManager.setBootOnStartup(newState)
            val msg = if (newState) {
                "Cihaz açılışında otomatik başlatma etkinleştirildi"
            } else {
                "Cihaz açılışında başlatma kapatıldı"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.layoutBootToggle.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
            } else {
                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }
        }
    }

    private fun setupM3uSection() {
        binding.etM3uUrl.setText(prefManager.getM3uUrl())

        binding.btnSaveAndSyncM3u.setOnClickListener {
            val url = binding.etM3uUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Geçerli bir URL giriniz", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefManager.setM3uUrl(url)
            Toast.makeText(this, "Kanallar arka planda güncelleniyor…", Toast.LENGTH_SHORT).show()

            lifecycleScope.launch(Dispatchers.IO) {
                val channels = channelRepository.loadChannels(url)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "${channels.size} kanal başarıyla yüklendi!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun setupCategoryManagement() {
        categoryAdapter = ManageCategoryAdapter(
            onEditClick = { category ->
                showEditCategoryDialog(category)
            },
            onDeleteClick = { category ->
                showDeleteCategoryDialog(category)
            }
        )
        binding.rvManageCategories.layoutManager = LinearLayoutManager(this)
        binding.rvManageCategories.adapter = categoryAdapter

        binding.btnAddCategory.setOnClickListener {
            showAddCategoryDialog()
        }

        loadCategories()
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            val categories = channelRepository.getAllCategoriesList()
            categoryAdapter.setCategories(categories)
        }
    }

    private fun showAddCategoryDialog() {
        val container = android.widget.FrameLayout(this)
        val input = EditText(this).apply {
            hint = "Kategori Adı"
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_category_tab)
            setPadding(32, 20, 32, 20)
            textSize = 14f
        }
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 48
            rightMargin = 48
            topMargin = 24
            bottomMargin = 24
        }
        container.addView(input, params)

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Yeni Kategori Oluştur")
            .setView(container)
            .setPositiveButton("Ekle") { dialog, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        channelRepository.createCategory(name)
                        Toast.makeText(this@SettingsActivity, "Kategori oluşturuldu", Toast.LENGTH_SHORT).show()
                        loadCategories()
                    }
                } else {
                    Toast.makeText(this, "Kategori adı boş olamaz", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showEditCategoryDialog(category: Category) {
        val container = android.widget.FrameLayout(this)
        val input = EditText(this).apply {
            setText(category.name)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.white))
            setHintTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.text_muted))
            setBackgroundResource(R.drawable.bg_category_tab)
            setPadding(32, 20, 32, 20)
            textSize = 14f
        }
        val params = android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 48
            rightMargin = 48
            topMargin = 24
            bottomMargin = 24
        }
        container.addView(input, params)

        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle("Kategoriyi Düzenle")
            .setView(container)
            .setPositiveButton("Kaydet") { dialog, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        channelRepository.updateCategory(category.copy(name = newName))
                        Toast.makeText(this@SettingsActivity, "Kategori güncellendi", Toast.LENGTH_SHORT).show()
                        loadCategories()
                    }
                } else {
                    Toast.makeText(this, "Kategori adı boş olamaz", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
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
            .setNegativeButton("İptal") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun setupAboutSection() {
        // Cihaz Hakkında Butonu
        binding.btnAboutDevice.setOnClickListener {
            val brand = Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            val model = Build.MODEL
            val ipAddress = getDeviceIpAddress(this)

            val message = "Marka: $brand\nModel: $model\nIP Adresi: $ipAddress"

            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Cihaz Hakkında")
                .setMessage(message)
                .setPositiveButton("Tamam") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }

        // Uygulama Hakkında Butonu
        binding.btnAboutApp.setOnClickListener {
            val aboutText = """
                Kapukaya TV Premium
                Versiyon: 1.0.0
                
                kapukaya.dev tarafından özenle geliştirilmiştir.
                
                YASAL UYARI:
                Bu uygulama gelişmiş bir medya oynatıcıdır. İçerisinde hiçbir dahili kanal, yayın veya medya barındırmaz. Kullanıcılar kendi içeriklerini (M3U vb.) eklemekle yükümlüdür. Telif hakkı ihlallerinden ve eklenen içeriklerden tamamen son kullanıcı sorumludur.
            """.trimIndent()

            AlertDialog.Builder(this, R.style.DarkDialogTheme)
                .setTitle("Uygulama Hakkında")
                .setMessage(aboutText)
                .setPositiveButton("Tamam") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun getDeviceIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
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
