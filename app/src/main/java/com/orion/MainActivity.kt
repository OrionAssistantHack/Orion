package com.orion

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.orion.databinding.ActivityMainBinding
import com.orion.inference.LiteRTLMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val TAG = "Orion.MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var liteRTLMManager: LiteRTLMManager

    private var selectedPackage = "com.ubercab"

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val goal = binding.editGoal.text.toString().trim()
            ScreenCaptureService.startCapture(this, result.resultCode, result.data!!, goal, selectedPackage)
            val launchIntent = packageManager.getLaunchIntentForPackage(selectedPackage)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) startActivity(launchIntent)
            binding.btnStop.isEnabled = true
            binding.textStatus.text = getString(R.string.status_running)
        } else {
            binding.btnStart.isEnabled = true
            binding.textStatus.text = "Screen capture permission denied"
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestScreenCapture()
        else binding.textStatus.text = "Notification permission denied"
    }

    private val appOptions =
            listOf(
                    Triple("Uber", "com.ubercab", "Uber"),
                    Triple("Lyft", "me.lyft.android", "Lyft"),
                    Triple("AI Gallery", "com.google.aiedge.gallery", "AI Gallery")
            )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        liteRTLMManager = LiteRTLMManager.getInstance(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = maxOf(imeBottom, navBottom))
            insets
        }

        setupAppSelector()
        setupButtons()
        promptAccessibilityIfNeeded()
        initializeModel()
    }

    private fun setupAppSelector() {
        for ((label, pkg, _) in appOptions) {
            val btn =
                    Button(this).apply {
                        text = label
                        setOnClickListener {
                            selectedPackage = pkg
                            binding.textTargetApp.text =
                                    getString(R.string.label_target_app) + label
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
        if (packageManager.getLaunchIntentForPackage(selectedPackage) == null) {
            binding.textStatus.text = "App not found: $selectedPackage"
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        binding.textStatus.text = "Requesting screen capture permission…"
        binding.btnStart.isEnabled = false
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun onStopAssistant() {
        ScreenCaptureService.stopCapture(this)
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        binding.textStatus.text = getString(R.string.status_stopped)
    }

    private fun promptAccessibilityIfNeeded() {
        val enabled =
                Settings.Secure.getString(
                        contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                        ?: ""
        if (!enabled.contains("$packageName/${OrionAccessibilityService::class.java.name}")) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun initializeModel() {
        // Already initialized (e.g. after rotation) — just restore UI state
        if (liteRTLMManager.getActiveBackend() != "None") {
            binding.textBackendStatus.text = liteRTLMManager.getActiveBackend()
            binding.textStatus.text = getString(R.string.status_ready)
            binding.btnStart.isEnabled = true
            return
        }
        val modelPath = findModelPath()
        if (modelPath == null) {
            binding.textStatus.text =
                    "Push model.litertlm to ${getExternalFilesDir(null)?.absolutePath}"
            return
        }
        binding.textStatus.text = getString(R.string.status_loading)
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) { liteRTLMManager.initialize(modelPath) }
                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "Model loaded on ${liteRTLMManager.getActiveBackend()} in ${elapsed}ms")
                binding.textBackendStatus.text =
                        "${liteRTLMManager.getActiveBackend()} — ${elapsed}ms"
                binding.textStatus.text = getString(R.string.status_ready)
                binding.btnStart.isEnabled = true
            } catch (e: Exception) {
                binding.textStatus.text = "Model load failed: ${e.message}"
            }
        }
    }

    private fun findModelPath(): String? {
        val candidates =
                listOfNotNull(
                        getExternalFilesDir(null)?.absolutePath?.let {
                            "$it/gemma-4-E2B-it_qualcomm_sm8750.litertlm"
                        },
                        "/data/local/tmp/gemma-4-E2B-it_qualcomm_sm8750.litertlm"
                )
        return candidates.firstOrNull { java.io.File(it).exists() }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
        liteRTLMManager.cleanup()
    }
}
