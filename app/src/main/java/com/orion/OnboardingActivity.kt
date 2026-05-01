package com.orion

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.orion.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: SharedPreferences
    var currentStep = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("orion_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("onboarding_complete", false)) {
            startMain()
            return
        }
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showStep(0)
        binding.btnStep1.setOnClickListener { onStep1CtaClick() }
        binding.btnStep2.setOnClickListener { onStep2CtaClick() }
        binding.btnStep3.setOnClickListener { onStep3CtaClick() }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        when (currentStep) {
            0 -> if (isAccessibilityEnabled()) advance()
            2 -> if (Settings.canDrawOverlays(this)) complete()
        }
    }

    fun showStep(step: Int) {
        currentStep = step
        binding.stepContainer1.visibility = if (step == 0) View.VISIBLE else View.GONE
        binding.stepContainer2.visibility = if (step == 1) View.VISIBLE else View.GONE
        binding.stepContainer3.visibility = if (step == 2) View.VISIBLE else View.GONE
    }

    fun onStep1CtaClick() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun onStep2CtaClick() = advance()

    fun onStep3CtaClick() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))

    private fun advance() {
        val next = currentStep + 1
        when {
            next >= 3 -> complete()
            next == 2 && Settings.canDrawOverlays(this) -> complete()
            else -> showStep(next)
        }
    }

    private fun complete() {
        prefs.edit().putBoolean("onboarding_complete", true).apply()
        startMain()
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "$packageName/${OrionAccessibilityService::class.java.name}"
        return enabled.split(":").any { it.equals(target, ignoreCase = true) }
    }
}
