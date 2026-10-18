package com.example

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.View
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.example.databinding.ActivityMirrorReceiverBinding
import com.example.mirror.MirrorDiscovery
import com.example.mirror.MirrorProtocol
import com.example.mirror.MirrorReceiver
import com.example.util.NetworkUtil

/**
 * TV tarafı: telefondan gelen ekran görüntüsünü ve sesi tam ekran oynatır.
 * Ekran açıkken ağda "Kapukaya TV - <cihaz adı>" olarak görünür.
 */
@OptIn(UnstableApi::class)
class MirrorReceiverActivity : AppCompatActivity(), MirrorReceiver.Listener {

    private lateinit var binding: ActivityMirrorReceiverBinding
    private lateinit var discovery: MirrorDiscovery
    private var receiver: MirrorReceiver? = null
    private val deviceName: String by lazy { resolveDeviceName() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMirrorReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        discovery = MirrorDiscovery(this)

        binding.tvMirrorDeviceName.text = "Bu cihaz: $deviceName"
        binding.mirrorAspectFrame.setAspectRatio(16f / 9f)

        binding.mirrorSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                receiver?.setSurface(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                receiver?.setSurface(null)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val newReceiver = MirrorReceiver(this)
        val holder = binding.mirrorSurface.holder
        if (holder.surface?.isValid == true) newReceiver.setSurface(holder.surface)
        receiver = newReceiver
        newReceiver.start()
        discovery.advertise("Kapukaya TV - $deviceName", MirrorProtocol.PORT)
        showWaiting(null)
    }

    override fun onStop() {
        super.onStop()
        discovery.stopAdvertising()
        receiver?.stop()
        receiver = null
    }

    private fun resolveDeviceName(): String {
        val name = try {
            Settings.Global.getString(contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return name?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "TV")
    }

    private fun showWaiting(status: String?) {
        binding.mirrorWaitingPanel.visibility = View.VISIBLE
        binding.tvMirrorConnectedBadge.visibility = View.GONE
        val ip = NetworkUtil.localIpAddress()
        binding.tvMirrorAddress.text = if (ip != null) {
            "Telefonda listede görünmezse elle girin: $ip"
        } else {
            "Cihaz bir ağa bağlı değil"
        }
        binding.tvMirrorStatus.text = status.orEmpty()
        binding.tvMirrorStatus.visibility = if (status.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---- MirrorReceiver.Listener (arka plan iş parçacığından çağrılır) ----

    override fun onWaiting(port: Int) {
        runOnUiThread {
            if (binding.mirrorWaitingPanel.visibility != View.VISIBLE) showWaiting(null)
        }
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread {
            binding.mirrorWaitingPanel.visibility = View.GONE
            binding.tvMirrorConnectedBadge.text = "📱 $deviceName yansıtıyor · Çıkmak için Geri"
            binding.tvMirrorConnectedBadge.visibility = View.VISIBLE
            binding.tvMirrorConnectedBadge.alpha = 1f
            binding.tvMirrorConnectedBadge.animate().alpha(0f).setStartDelay(4000).setDuration(600)
                .withEndAction { binding.tvMirrorConnectedBadge.visibility = View.GONE }
                .start()
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        runOnUiThread {
            if (height > 0) binding.mirrorAspectFrame.setAspectRatio(width.toFloat() / height)
        }
    }

    override fun onDisconnected(reason: String?) {
        runOnUiThread { showWaiting(reason) }
    }
}
