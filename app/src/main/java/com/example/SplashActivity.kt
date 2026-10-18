package com.example

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivitySplashBinding
import com.example.tv.TvHomeChannels
import com.example.util.PreferenceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvSplashVersion.text = "v${BuildConfig.VERSION_NAME}"
        playIntroAnimation()

        // TV ana ekranındaki bir kanal kartından mı açıldı?
        val directChannelId = TvHomeChannels.channelIdFromUri(intent?.data)

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            val repository = ChannelRepository.getInstance(this@SplashActivity)

            // Önbellek varsa anında yüklenir; güncel liste ana ekranda arka planda indirilir
            if (!repository.hasChannels) {
                binding.tvSplashStatus.setText(R.string.splash_status_loading)
            }
            try {
                repository.ensureLoaded()
            } catch (e: Exception) {
                // Hata olsa da ana ekrana geç; orada yeniden deneme seçeneği var
            }

            val remaining = MIN_DURATION_MS - (System.currentTimeMillis() - startTime)
            if (remaining > 0) delay(remaining)

            // Seçim ekranı: doğrudan kanal açılmıyorsa ve "son kanalı oynat" kapalıysa gösterilir
            val prefs = PreferenceManager(this@SplashActivity)
            val showChoice = directChannelId == null && prefs.isLaunchChoiceEnabled() &&
                !prefs.isResumeLastChannelEnabled()
            val target = if (showChoice) LaunchChoiceActivity::class.java else MainActivity::class.java

            val intent = Intent(this@SplashActivity, target).apply {
                putExtra(MainActivity.EXTRA_FROM_LAUNCH, true)
                directChannelId?.let { putExtra(MainActivity.EXTRA_PLAY_CHANNEL_ID, it) }
                // Uygulama zaten açıkken ana ekran kartından gelinirse eski ekranlar temizlenir
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }
    }

    private fun playIntroAnimation() {
        val interpolator = DecelerateInterpolator()

        binding.viewSplashGlow.alpha = 0f
        binding.viewSplashGlow.scaleX = 0.6f
        binding.viewSplashGlow.scaleY = 0.6f
        binding.viewSplashGlow.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(1100).setInterpolator(interpolator).start()

        binding.ivSplashLogo.alpha = 0f
        binding.ivSplashLogo.scaleX = 0.85f
        binding.ivSplashLogo.scaleY = 0.85f
        binding.ivSplashLogo.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(700).setInterpolator(interpolator).start()

        binding.splashWordmark.alpha = 0f
        binding.splashWordmark.translationY = 24f
        binding.splashWordmark.animate().alpha(1f).translationY(0f)
            .setStartDelay(200).setDuration(600).setInterpolator(interpolator).start()

        binding.splashDivider.scaleX = 0f
        binding.splashDivider.animate().scaleX(1f)
            .setStartDelay(450).setDuration(600).setInterpolator(interpolator).start()

        binding.tvSplashTagline.alpha = 0f
        binding.tvSplashTagline.animate().alpha(1f)
            .setStartDelay(600).setDuration(600).start()
    }

    companion object {
        private const val MIN_DURATION_MS = 1100L
    }
}
