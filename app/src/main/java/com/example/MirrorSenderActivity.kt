package com.example

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.databinding.ActivityMirrorSenderBinding
import com.example.mirror.MirrorDiscovery
import com.example.mirror.MirrorProtocol
import com.example.mirror.MirrorSendState
import com.example.mirror.MirrorSenderService
import com.example.mirror.MirrorTarget
import com.example.util.DeviceUtil
import com.example.util.PreferenceManager
import kotlinx.coroutines.launch

/**
 * Telefon tarafı: ağdaki Kapukaya TV alıcılarını listeler, seçilen TV'ye ekranı ve sesi yansıtır.
 */
class MirrorSenderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMirrorSenderBinding
    private lateinit var discovery: MirrorDiscovery
    private val targets = LinkedHashMap<String, MirrorTarget>()
    private var pendingTarget: MirrorTarget? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Ses izni verilmedi: yalnızca görüntü yansıtılacak", Toast.LENGTH_LONG).show()
        }
        requestNotificationThenCapture()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> requestScreenCapture() }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val target = pendingTarget ?: return@registerForActivityResult
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MirrorSenderService.start(this, result.resultCode, data, target)
        } else {
            Toast.makeText(this, "Ekran yansıtma izni verilmedi", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMirrorSenderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        discovery = MirrorDiscovery(this)

        binding.btnSenderBack.setOnClickListener { finish() }
        binding.btnStopMirroring.setOnClickListener { MirrorSenderService.stop(this) }
        binding.btnManualIp.setOnClickListener { showManualIpDialog() }
        setupBatteryTip()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MirrorSenderService.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        targets.clear()
        renderTargets()
        discovery.discover(
            onFound = { target -> runOnUiThread { targets[target.name] = target; renderTargets() } },
            onLost = { name -> runOnUiThread { targets.remove(name); renderTargets() } }
        )
    }

    override fun onStop() {
        super.onStop()
        discovery.stopDiscovery()
    }

    private fun render(state: MirrorSendState) {
        when (state) {
            is MirrorSendState.Idle -> {
                binding.tvSenderStatus.setText(R.string.mirror_sender_idle)
                binding.tvSenderHint.setText(R.string.mirror_sender_idle_hint)
                binding.btnStopMirroring.visibility = View.GONE
            }
            is MirrorSendState.Connecting -> {
                binding.tvSenderStatus.text = "${state.target} cihazına bağlanılıyor…"
                binding.tvSenderHint.text = ""
                binding.btnStopMirroring.visibility = View.VISIBLE
            }
            is MirrorSendState.Streaming -> {
                val audio = if (state.withAudio) "ses açık" else "yalnızca görüntü"
                binding.tvSenderStatus.text = "${state.target} cihazına yansıtılıyor ($audio)"
                binding.tvSenderHint.setText(R.string.mirror_sender_streaming_hint)
                binding.btnStopMirroring.visibility = View.VISIBLE
            }
            is MirrorSendState.Error -> {
                binding.tvSenderStatus.text = "Yansıtma durdu"
                binding.tvSenderHint.text = state.message
                binding.btnStopMirroring.visibility = View.GONE
            }
        }
    }

    // ---- Pil yönetimi uyarısı (Huawei, Xiaomi, Oppo, Vivo…) ----

    private fun setupBatteryTip() {
        val prefs = PreferenceManager(this)
        val restricted = !isIgnoringBatteryOptimizations()
        val show = !prefs.isBatteryTipDismissed() && (DeviceUtil.hasAggressiveBatteryManager() || restricted)
        binding.batteryCard.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val brand = DeviceUtil.manufacturerName()
        binding.tvBatteryTip.text = if (DeviceUtil.hasAggressiveBatteryManager()) {
            getString(R.string.battery_tip_oem, brand)
        } else {
            getString(R.string.battery_tip_generic)
        }
        binding.btnBatterySettings.setOnClickListener { openBatterySettings() }
        binding.btnBatteryDismiss.setOnClickListener {
            prefs.setBatteryTipDismissed(true)
            binding.batteryCard.visibility = View.GONE
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** Üreticiye özel "otomatik başlatma / arka plan" ekranını, yoksa genel pil ayarlarını açar. */
    private fun openBatterySettings() {
        val oemScreens = listOf(
            // Huawei / Honor
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            // Xiaomi / Redmi / POCO
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            // Oppo / Realme / OnePlus (ColorOS)
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            // Vivo / iQOO
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        )
        for (component in oemScreens) {
            try {
                startActivity(Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Bu cihazda bu ekran yok, sıradakini dene
            }
        }
        val fallbacks = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) else null,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in fallbacks) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // Sıradakini dene
            }
        }
        Toast.makeText(this, "Pil ayarları açılamadı. Ayarlar > Pil bölümünden düzenleyin.", Toast.LENGTH_LONG).show()
    }

    private fun renderTargets() {
        binding.llTargets.removeAllViews()
        binding.tvSenderSearching.setText(
            if (targets.isEmpty()) R.string.mirror_searching else R.string.mirror_tap_to_cast
        )
        val density = resources.displayMetrics.density
        targets.values.forEach { target ->
            val button = Button(this).apply {
                text = "📺  ${target.name}"
                isAllCaps = false
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MirrorSenderActivity, R.color.white))
                setBackgroundResource(R.drawable.bg_choice_card)
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setOnClickListener { startMirroring(target) }
            }
            binding.llTargets.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * density).toInt() })
        }
    }

    private fun showManualIpDialog() {
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            hint = "192.168.1.25"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(ContextCompat.getColor(this@MirrorSenderActivity, R.color.white))
            setHintTextColor(ContextCompat.getColor(this@MirrorSenderActivity, R.color.text_muted))
        }
        val container = FrameLayout(this).apply {
            val h = (24 * density).toInt()
            setPadding(h, (8 * density).toInt(), h, 0)
            addView(input)
        }
        AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(R.string.mirror_manual_ip)
            .setMessage("TV'deki \"Bu cihazda izle\" ekranında yazan IP adresini girin.")
            .setView(container)
            .setPositiveButton("Bağlan") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isNotEmpty()) startMirroring(MirrorTarget(host, host, MirrorProtocol.PORT))
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---- İzin zinciri: ses (Android 10+) → bildirim (Android 13+) → ekran yakalama ----

    private fun startMirroring(target: MirrorTarget) {
        if (MirrorSenderService.state.value is MirrorSendState.Streaming ||
            MirrorSenderService.state.value is MirrorSendState.Connecting
        ) {
            MirrorSenderService.stop(this)
        }
        pendingTarget = target
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestNotificationThenCapture()
        }
    }

    private fun requestNotificationThenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            Toast.makeText(this, "Bu cihaz ekran yansıtmayı desteklemiyor", Toast.LENGTH_LONG).show()
        }
    }
}
