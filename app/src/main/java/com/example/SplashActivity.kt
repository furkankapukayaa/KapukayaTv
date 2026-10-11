package com.example

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.data.repository.ChannelRepository
import com.example.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Premium slow fade-in scale animation
        binding.ivSplashLogo.alpha = 0f
        binding.ivSplashLogo.scaleX = 0.85f
        binding.ivSplashLogo.scaleY = 0.85f
        binding.ivSplashLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1200)
            .setInterpolator(DecelerateInterpolator())
            .start()

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()

            try {
                ChannelRepository.getInstance(this@SplashActivity).loadChannels()
            } catch (e: Exception) {
                // Ignore, proceed to main
            }

            val elapsed = System.currentTimeMillis() - startTime
            val remaining = 1500L - elapsed
            if (remaining > 0) {
                delay(remaining)
            }

            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            finish()
        }
    }
}
