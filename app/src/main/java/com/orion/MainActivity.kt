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
import com.orion.inference.DualNpuPipeline
import com.orion.inference.GemmaNpuEngine
import com.orion.inference.LiteRTLMManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val TAG = "Orion.MainActivity"

    private lateinit var binding: ActivityMainBinding
    private lateinit var liteRTLMManager: LiteRTLMManager

    private var selectedPackage = "com.ubercab"
    private var selectedMode = "gpu"
    private var pendingComparisonGoal: com.orion.core.ParsedGoal? = null

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val pending = pendingComparisonGoal
            if (pending != null) {
                pendingComparisonGoal = null
                val category = pending.toCategory()
                val installedApps = AppRegistry.installedFor(this, category)
                if (installedApps.isEmpty()) {
                    binding.textStatus.text = "No ${category.name.lowercase().replace('_', ' ')} apps installed"
                    binding.btnStart.isEnabled = true
                    return@registerForActivityResult
                }
                val session = com.orion.core.ComparisonSession(pending, installedApps)
                ScreenCaptureService.startComparison(
                    context = this,
                    resultCode = result.resultCode,
                    data = result.data!!,
                    session = session,
                ) { chosenApp ->
                    runOnUiThread {
                        selectedPackage = chosenApp.packageName
                        binding.editGoal.setText("Complete the booking")
                        binding.textStatus.text = "Booking with ${chosenApp.displayName}…"
                        requestScreenCapture()
                    }
                }
                packageManager.getLaunchIntentForPackage(session.currentApp?.packageName ?: "")
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                binding.btnStop.isEnabled = true
                binding.textStatus.text = "Comparing across ${installedApps.size} apps…"
            } else {
                // Single-app mode — existing logic unchanged
                val goal = binding.editGoal.text.toString().trim()
                ScreenCaptureService.startCapture(this, result.resultCode, result.data!!, goal, selectedPackage)
                packageManager.getLaunchIntentForPackage(selectedPackage)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                binding.btnStop.isEnabled = true
                binding.textStatus.text = getString(R.string.status_running)
            }
            binding.btnStart.isEnabled = false
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
        setupModeSelector()
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

    private fun setupModeSelector() {
        val modes = listOf(
            Triple("Gemma NPU", "npu_gemma", true),
            Triple("Gemma GPU", "gpu", false),
            Triple("NPU Pipeline", "npu_pipeline", false)
        )
        for ((label, mode, _) in modes) {
            val btn = Button(this).apply {
                text = label
                setOnClickListener {
                    if (selectedMode == mode) return@setOnClickListener
                    selectedMode = mode
                    binding.textInferenceMode.text = "Inference mode: $label"
                    switchInferenceMode()
                }
            }
            binding.layoutModeSelector.addView(btn)
        }
    }

    private fun switchInferenceMode() {
        binding.btnStart.isEnabled = false
        binding.textStatus.text = "Unloading current model…"
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ScreenCaptureService.activeEngine?.cleanup()
                ScreenCaptureService.activeEngine = null
                System.gc()
                kotlinx.coroutines.delay(500)
            }
            initializeModel()
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

        val parsed = parseGoal(goal)
        if (parsed != null) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                binding.textStatus.text = "Grant 'Draw over other apps', then tap Use Assistant again"
                return
            }
            pendingComparisonGoal = parsed
            requestScreenCaptureForComparison()
            return
        }

        // Single-app mode — existing flow unchanged
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

    private fun requestScreenCaptureForComparison() {
        binding.textStatus.text = "Requesting screen capture…"
        binding.btnStart.isEnabled = false
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        // screenCaptureLauncher callback branches on pendingComparisonGoal
    }

    private fun requestScreenCapture() {
        binding.textStatus.text = "Requesting screen capture permission…"
        binding.btnStart.isEnabled = false
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun onStopAssistant() {
        ScreenCaptureService.comparisonSession = null
        com.orion.ui.ComparisonOverlay.dismiss()
        pendingComparisonGoal = null
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
        val modelPath = findModelPath() ?: run {
            binding.textStatus.text = "Push model to ${getExternalFilesDir(null)?.absolutePath}"
            return
        }

        // Reuse if already ready on the same mode
        val existingEngine = ScreenCaptureService.activeEngine
        val engineMatchesMode = when (selectedMode) {
            "npu_gemma" -> existingEngine is GemmaNpuEngine
            "npu_pipeline" -> existingEngine is DualNpuPipeline
            else -> existingEngine is LiteRTLMManager
        }
        if (existingEngine != null && existingEngine.isReady() && engineMatchesMode) {
            binding.textBackendStatus.text = existingEngine.getDescription()
            binding.textStatus.text = getString(R.string.status_ready)
            binding.btnStart.isEnabled = true
            return
        }

        binding.textStatus.text = getString(R.string.status_loading)
        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            try {
                when (selectedMode) {
                    "npu_gemma" -> {
                        val modelPath = findModelByName(GemmaNpuEngine.MODEL_FILENAME)
                            ?: run { binding.textStatus.text = "Gemma NPU model not found"; return@launch }
                        val engine = GemmaNpuEngine.getInstance(this@MainActivity)
                        withContext(Dispatchers.IO) { engine.initialize(modelPath) }
                        if (!engine.isReady()) {
                            binding.textStatus.text = "Gemma NPU init failed — check logcat"
                            return@launch
                        }
                        ScreenCaptureService.activeEngine = engine
                    }
                    "npu_pipeline" -> {
                        val fastVlmPath = findModelByName(DualNpuPipeline.FASTVLM_MODEL)
                        val gemmaNpuPath = findModelByName(DualNpuPipeline.GEMMA_NPU_MODEL)
                        if (fastVlmPath == null || gemmaNpuPath == null) {
                            binding.textStatus.text = "NPU models not found in /data/local/tmp"
                            return@launch
                        }
                        val pipeline = DualNpuPipeline.getInstance(this@MainActivity)
                        withContext(Dispatchers.IO) { pipeline.initialize(fastVlmPath, gemmaNpuPath) }
                        if (!pipeline.isReady()) {
                            binding.textStatus.text = "NPU pipeline init failed — check logcat"
                            return@launch
                        }
                        ScreenCaptureService.activeEngine = pipeline
                    }
                    else -> { // "gpu"
                        withContext(Dispatchers.IO) { liteRTLMManager.initialize(modelPath) }
                        ScreenCaptureService.activeEngine = liteRTLMManager
                    }
                }
                val engine = ScreenCaptureService.activeEngine ?: return@launch
                val elapsed = System.currentTimeMillis() - startMs
                Log.i(TAG, "${engine.getDescription()} ready in ${elapsed}ms")
                binding.textBackendStatus.text = "${engine.getDescription()} — ${elapsed}ms"
                binding.textStatus.text = getString(R.string.status_ready)
                binding.btnStart.isEnabled = true
                ScreenCaptureService.triggerCaptureIfReady()
            } catch (e: Exception) {
                binding.textStatus.text = "Load failed: ${e.message}"
            }
        }
    }

    private fun findModelByName(name: String): String? {
        val dirs = listOfNotNull("/data/local/tmp", getExternalFilesDir(null)?.absolutePath)
        return dirs.map { java.io.File(it, name) }.firstOrNull { it.exists() }?.absolutePath
    }

    private fun findModelPath(): String? {
        val searchDirs = listOfNotNull(
            "/data/local/tmp",
            getExternalFilesDir(null)?.absolutePath
        )
        val modelNames = listOf(
            "gemma-4-E2B-it.litertlm",
            "gemma-4-E2B-it_qualcomm_sm8750.litertlm"
        )
        for (name in modelNames) {
            for (dir in searchDirs) {
                val f = java.io.File(dir, name)
                if (f.exists()) return f.absolutePath
            }
        }
        return null
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        ScreenCaptureService.onBookingChosen = null
        ScreenCaptureService.activeEngine?.cleanup()
        ScreenCaptureService.activeEngine = null
        liteRTLMManager.cleanup()
        super.onDestroy()
    }
}
