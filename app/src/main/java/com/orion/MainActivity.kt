package com.orion

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.orion.databinding.ActivityMainBinding
import com.orion.inference.LiteRTLMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var liteRTLMManager: LiteRTLMManager

    private var selectedPackage = "com.ubercab"
    private val captureRequestCode = 2001

    private val appOptions = listOf(
        Triple("Uber", "com.ubercab", "Uber"),
        Triple("Lyft", "me.lyft.android", "Lyft"),
        Triple("AI Gallery", "com.google.aiedge.gallery", "AI Gallery")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        liteRTLMManager = LiteRTLMManager.getInstance(this)

        setupAppSelector()
        setupButtons()
        promptAccessibilityIfNeeded()
        initializeModel()
    }

    private fun setupAppSelector() {
        for ((label, pkg, _) in appOptions) {
            val btn = Button(this).apply {
                text = label
                setOnClickListener {
                    selectedPackage = pkg
                    binding.textTargetApp.text = getString(R.string.label_target_app) + label
                }
            }
            binding.layoutAppSelector.addView(btn)
        }
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { onStartAssistant() }
        binding.btnStop.setOnClickListener { onStopAssistant() }
    }

    private fun onStartAssistant() {
        val goal = binding.editGoal.text.toString().trim()
        if (goal.isEmpty()) {
            binding.textStatus.text = "Please enter a goal first"
            return
        }
        packageManager.getLaunchIntentForPackage(selectedPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(this)
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequestCode && resultCode == RESULT_OK && data != null) {
            val goal = binding.editGoal.text.toString().trim()
            ScreenCaptureService.startCapture(this, resultCode, data, goal, selectedPackage)
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
            binding.textStatus.text = getString(R.string.status_running)
        }
    }

    private fun onStopAssistant() {
        ScreenCaptureService.stopCapture(this)
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        binding.textStatus.text = getString(R.string.status_stopped)
    }

    private fun promptAccessibilityIfNeeded() {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        if (!enabled.contains("$packageName/${OrionAccessibilityService::class.java.name}")) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun initializeModel() {
        val modelPath = "${getExternalFilesDir(null)?.absolutePath}/model.litertlm"
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            binding.textStatus.text = "Push model.litertlm to ${getExternalFilesDir(null)?.absolutePath}"
            return
        }
        binding.textStatus.text = getString(R.string.status_loading)
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) { liteRTLMManager.initialize(modelPath) }
                liteRTLMManager.startConversation()
                val elapsed = System.currentTimeMillis() - startMs
                binding.textBackendStatus.text = "${liteRTLMManager.getActiveBackend()} — ${elapsed}ms"
                binding.textStatus.text = getString(R.string.status_ready)
                binding.btnStart.isEnabled = true
            } catch (e: Exception) {
                binding.textStatus.text = "Model load failed: ${e.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        liteRTLMManager.cleanup()
    }
}
