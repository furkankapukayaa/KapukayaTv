package com.example

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityChoiceBinding
import com.example.util.DeviceUtil

/**
 * Ekran yansıtma menüsü:
 *  - "Bu cihazda izle"  → TV alıcı olur (MirrorReceiverActivity)
 *  - "Bu cihazdan yansıt" → telefon gönderici olur (MirrorSenderActivity)
 *  - Alttaki buton: cihazın yerleşik yansıtması (Chromecast / Miracast) için yönlendirme
 */
class MirrorMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChoiceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val isTv = LaunchChoiceActivity.isTvDevice(this)
        binding.tvChoiceTitle.setText(R.string.mirror_menu_title)
        binding.tvChoiceFooter.setText(R.string.mirror_menu_footer)

        LaunchChoiceActivity.setupCard(
            binding.cardFirst, R.drawable.ic_tv, R.string.mirror_receive, R.string.mirror_receive_desc
        ) {
            startActivity(Intent(this, MirrorReceiverActivity::class.java))
        }
        LaunchChoiceActivity.setupCard(
            binding.cardSecond, R.drawable.ic_phone_android, R.string.mirror_send, R.string.mirror_send_desc
        ) {
            startActivity(Intent(this, MirrorSenderActivity::class.java))
        }

        DeviceUtil.applyCompactPadding(binding.root, verticalDp = 24)
        DeviceUtil.stackCards(binding.choiceCards, binding.choiceCards.getChildAt(1))

        binding.btnChoiceExtra.visibility = View.VISIBLE
        binding.btnChoiceExtra.setText(R.string.mirror_builtin)
        binding.btnChoiceExtra.setOnClickListener { showBuiltInCastHelp(isTv) }

        // TV'de alıcı, telefonda gönderici seçeneği öne çıkar
        if (isTv) binding.cardFirst.root.requestFocus() else binding.cardSecond.root.requestFocus()
    }

    private fun showBuiltInCastHelp(isTv: Boolean) {
        val builder = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(R.string.mirror_builtin)
            .setMessage(if (isTv) R.string.mirror_builtin_tv_help else R.string.mirror_builtin_phone_help)
            .setNegativeButton("Kapat", null)
        if (!isTv) {
            // Telefonun sistem "Yayınla" ayarlarını açmayı dene
            builder.setPositiveButton("Yayınla Ayarlarını Aç") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                    } catch (ignored: Exception) {
                        // Ayar ekranı yok
                    }
                }
            }
        }
        builder.show()
    }
}
