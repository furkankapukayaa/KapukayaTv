package com.example

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityRemoteSetupBinding
import com.example.remote.RemoteConfigServer
import com.example.util.DeviceUtil
import com.example.util.NetworkUtil
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Telefonla QR kod okutularak M3U listesi, EPG adresi ve yedekleme işlemlerinin yapıldığı ekran.
 * Sunucu yalnızca bu ekran açıkken çalışır.
 */
class RemoteSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRemoteSetupBinding
    private var server: RemoteConfigServer? = null
    private val events = ArrayDeque<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        DeviceUtil.applyCompactPadding(binding.root)
        binding.btnRemoteBack.setOnClickListener { finish() }
        binding.btnRemoteBack.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        startServer()
    }

    override fun onStop() {
        super.onStop()
        server?.stop()
        server = null
    }

    private fun startServer() {
        val ip = NetworkUtil.localIpAddress()
        if (ip == null) {
            showError("Cihaz bir ağa bağlı değil. TV'yi Wi-Fi veya kabloyla internete bağlayın.")
            return
        }

        val newServer = RemoteConfigServer(this) { message -> runOnUiThread { addEvent(message) } }
        val port = newServer.start()
        if (port == -1) {
            showError("Yerel sunucu başlatılamadı. Uygulamayı yeniden açmayı deneyin.")
            return
        }
        server = newServer

        val url = "http://$ip:$port/?t=${newServer.token}"
        binding.tvRemoteUrl.text = url
        binding.ivQrCode.setImageBitmap(createQrBitmap(url, 640))
        binding.ivQrCode.visibility = View.VISIBLE
        binding.tvRemoteError.visibility = View.GONE
        addEvent("Hazır. Telefonunuzla QR kodu okutun.")
    }

    private fun showError(message: String) {
        binding.ivQrCode.visibility = View.INVISIBLE
        binding.tvRemoteUrl.text = ""
        binding.tvRemoteError.text = message
        binding.tvRemoteError.visibility = View.VISIBLE
    }

    private fun addEvent(message: String) {
        val time = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis())
        events.addFirst("$time  $message")
        while (events.size > 6) events.removeLast()
        binding.tvRemoteEvents.text = events.joinToString("\n")
    }

    private fun createQrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
