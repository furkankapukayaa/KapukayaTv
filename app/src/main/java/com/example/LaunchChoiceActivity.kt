package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.databinding.ActivityChoiceBinding
import com.example.databinding.ViewChoiceCardBinding
import com.example.util.DeviceUtil

/** Açılışta: "Televizyon İzle" veya "Ekran Yansıt". */
class LaunchChoiceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChoiceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvChoiceTitle.setText(R.string.choice_title)
        binding.tvChoiceFooter.setText(R.string.choice_footer)

        setupCard(binding.cardFirst, R.drawable.ic_tv, R.string.choice_watch_tv, R.string.choice_watch_tv_desc) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FROM_LAUNCH, true))
            finish()
        }
        setupCard(binding.cardSecond, R.drawable.ic_cast, R.string.choice_mirror, R.string.choice_mirror_desc) {
            startActivity(Intent(this, MirrorMenuActivity::class.java))
        }

        // Yalnızca telefonda: kartlar dikeyde alt alta (TV etkilenmez)
        DeviceUtil.applyCompactPadding(binding.root, verticalDp = 24)
        DeviceUtil.stackCards(binding.choiceCards, binding.choiceCards.getChildAt(1))

        binding.cardFirst.root.requestFocus()
    }

    companion object {
        fun isTvDevice(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        /** İki kartlı ekranlarda ortak kart kurulumu. */
        fun setupCard(card: ViewChoiceCardBinding, icon: Int, title: Int, subtitle: Int, onClick: () -> Unit) {
            card.cardIcon.setImageResource(icon)
            card.cardTitle.setText(title)
            card.cardSubtitle.setText(subtitle)
            card.root.setOnClickListener { onClick() }
            card.root.setOnFocusChangeListener { view: View, hasFocus: Boolean ->
                val scale = if (hasFocus) 1.04f else 1f
                view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
            }
        }
    }
}
