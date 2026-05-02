package com.orion

import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.orion.core.ParsedGoal
import com.orion.databinding.ActivityMainBinding
import com.orion.inference.LiteRTLMManager
import com.orion.ui.ComparisonOverlay
import com.orion.ui.OrionPipOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PresetCard(
    val emoji: String,
    val title: String,
    val subtitle: String,
    val packageName: String?,   // null = comparison, "" = open anything, else specific app
    val goalTemplate: String,
    val needsDestination: Boolean = false,
    val promptTitle: String = "Where to?",
    val promptHint: String = "e.g. SFO Airport",
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var liteRTLMManager: LiteRTLMManager

    var selectedCategory = "rides"
        private set
    private var selectedPackage = "com.ubercab"
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
                    showIdleState()
                    setStatusPill("ready")
                    return@registerForActivityResult
                }
                val session = com.orion.core.ComparisonSession(pending, installedApps)
                ScreenCaptureService.onSessionDismissed = { runOnUiThread { showIdleState(); setStatusPill("ready") } }
                ScreenCaptureService.startComparison(
                    context = this,
                    resultCode = result.resultCode,
                    data = result.data!!,
                    session = session,
                ) { chosenApp ->
                    runOnUiThread {
                        OrionPipOverlay.dismiss()
                        pendingComparisonGoal = null
                        selectedPackage = chosenApp.packageName
                        binding.editGoal.setText("Complete the booking")
                        requestScreenCapture()
                    }
                }
                packageManager.getLaunchIntentForPackage(session.currentApp?.packageName ?: "")
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
                OrionPipOverlay.show(this)
                setStatusPill("running")
                showRunningState(pending.rawGoal)
            } else {
                // "Open anything" mode (selectedPackage blank): don't launch any app; agent attaches to whatever the user opens.
                val goal = binding.editGoal.text.toString().trim()
                ScreenCaptureService.startCapture(this, result.resultCode, result.data!!, goal, selectedPackage)
                if (selectedPackage.isNotBlank()) {
                    packageManager.getLaunchIntentForPackage(selectedPackage)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let { startActivity(it) }
                }
                OrionPipOverlay.show(this)
                setStatusPill("running")
                showRunningState(goal)
            }
        } else {
            showIdleState()
            setStatusPill("ready")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestScreenCapture() else setStatusPill("ready")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        setupCategoryTabs()
        buildPresetCards()
        binding.btnSend.setOnClickListener { onSendGoal() }
        binding.btnStop.setOnClickListener { onStopAssistant() }
        promptAccessibilityIfNeeded()
        initializeModel()
    }

    private fun setupCategoryTabs() {
        val tabs = listOf("rides" to "Rides", "food" to "Food", "anything" to "Anything")
        binding.layoutCategoryTabs.removeAllViews()
        for ((key, label) in tabs) {
            val btn = Button(this).apply {
                text = label
                tag = key
                textSize = 12f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
                setOnClickListener { selectCategory(key) }
            }
            binding.layoutCategoryTabs.addView(btn)
            updateTabStyle(btn, key == selectedCategory)
        }
    }

    internal fun selectCategory(key: String) {
        selectedCategory = key
        selectedPackage = when (key) {
            "anything" -> ""
            "food" -> "com.dd.doordash"
            else -> "com.ubercab"
        }
        for (i in 0 until binding.layoutCategoryTabs.childCount) {
            val btn = binding.layoutCategoryTabs.getChildAt(i) as? Button ?: continue
            val tabKey = btn.tag as? String ?: continue
            updateTabStyle(btn, tabKey == key)
        }
        buildPresetCards()
    }

    private fun updateTabStyle(btn: Button, active: Boolean) {
        if (active) {
            btn.setBackgroundResource(R.drawable.bg_gradient_brand)
            btn.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            btn.setBackgroundResource(R.drawable.bg_tab_inactive)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    fun presetCardsFor(category: String): List<PresetCard> = when (category) {
        "rides" -> listOf(
            PresetCard("🚗", "Find cheapest ride", "Compares Uber, Lyft & Waymo",
                packageName = null,
                goalTemplate = "find cheapest ride to [destination]",
                needsDestination = true),
            PresetCard("🚕", "Book an Uber", "Open Uber and complete booking",
                packageName = "com.ubercab",
                goalTemplate = "Book a ride to [destination]",
                needsDestination = true),
            PresetCard("🟣", "Book a Lyft", "Open Lyft and complete booking",
                packageName = "me.lyft.android",
                goalTemplate = "Book a ride to [destination]",
                needsDestination = true),
        )
        "food" -> listOf(
            PresetCard("🍕", "Order on DoorDash", "Open DoorDash and complete order",
                packageName = "com.dd.doordash",
                goalTemplate = "Order food from DoorDash"),
            PresetCard("🛵", "Order on Uber Eats", "Open Uber Eats and complete order",
                packageName = "com.ubercab.eats",
                goalTemplate = "Order food from Uber Eats"),
            PresetCard("🥡", "Order on Grubhub", "Open Grubhub and complete order",
                packageName = "com.grubhub.android",
                goalTemplate = "Order food from Grubhub"),
        )
        "anything" -> listOf(
            PresetCard("🌐", "Custom goal", "Describe what you need help with",
                packageName = "",
                goalTemplate = "[goal]",
                needsDestination = true,
                promptTitle = "What do you need help with?",
                promptHint = "e.g. Book a flight to NYC"),
            PresetCard("🛒", "Shopping", "Navigate any shopping app",
                packageName = "",
                goalTemplate = "Help me find and buy [goal]",
                needsDestination = true,
                promptTitle = "What do you want to buy?",
                promptHint = "e.g. running shoes size 10"),
            PresetCard("✈️", "Travel", "Book flights, hotels & more",
                packageName = "",
                goalTemplate = "Help me book a trip to [goal]",
                needsDestination = true,
                promptTitle = "Where are you going?",
                promptHint = "e.g. Paris next weekend"),
            PresetCard("🏦", "Banking", "Navigate any banking app",
                packageName = "",
                goalTemplate = "Help me [goal]",
                needsDestination = true,
                promptTitle = "What do you need help with?",
                promptHint = "e.g. transfer \$200 to savings"),
        )
        else -> emptyList()
    }

    private fun buildPresetCards() {
        binding.layoutCards.removeAllViews()
        val dp = resources.displayMetrics.density
        val cards = presetCardsFor(selectedCategory).filter { card ->
            card.packageName == null ||          // comparison mode
            card.packageName.isEmpty() ||        // open anything
            packageManager.getLaunchIntentForPackage(card.packageName) != null
        }
        for (card in cards) {
            val cardView = MaterialCardView(this).apply {
                radius = 12f * dp
                cardElevation = 2f * dp
                strokeWidth = dp.toInt()
                strokeColor = ContextCompat.getColor(this@MainActivity, R.color.brand_gradient_start)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
                setOnClickListener { onCardTapped(card) }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16 * dp).toInt()
                setPadding(p, p, p, p)
            }
            inner.addView(android.widget.TextView(this).apply {
                text = "${card.emoji} ${card.title}"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            })
            inner.addView(android.widget.TextView(this).apply {
                text = card.subtitle
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            })
            cardView.addView(inner)
            binding.layoutCards.addView(cardView)
        }
    }

    private fun onCardTapped(card: PresetCard) {
        if (card.needsDestination) {
            promptDestination(card)
        } else {
            selectedPackage = card.packageName ?: return
            binding.editGoal.setText(card.goalTemplate)
            onSendGoal()
        }
    }

    private fun promptDestination(card: PresetCard) {
        val editText = EditText(this).apply {
            hint = card.promptHint
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(card.promptTitle)
            .setView(editText)
            .setPositiveButton("Go") { _, _ ->
                val input = editText.text.toString().trim()
                if (input.isEmpty()) return@setPositiveButton
                val goal = card.goalTemplate
                    .replace("[destination]", input)
                    .replace("[goal]", input)
                if (card.packageName != null) selectedPackage = card.packageName
                binding.editGoal.setText(goal)
                onSendGoal()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onSendGoal() {
        val goal = binding.editGoal.text.toString().trim()
        if (goal.isEmpty()) return

        val parsed = parseGoal(goal)
        if (parsed != null) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                return
            }
            if (parsed is ParsedGoal.RideRequest && parsed.singleApp != null) {
                selectedPackage = parsed.singleApp.packageName
                // fall through to single-app path below
            } else {
                pendingComparisonGoal = parsed
                requestScreenCaptureForComparison()
                return
            }
        }

        // "Open anything" mode: selectedPackage is blank; skip the launch-intent check.
        if (selectedPackage.isNotBlank() && packageManager.getLaunchIntentForPackage(selectedPackage) == null) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    fun setStatusPill(state: String) {
        val (text, bgRes, colorRes) = when (state) {
            "ready" -> Triple(
                getString(R.string.status_ready),
                R.drawable.bg_status_pill_ready,
                R.color.status_ready_text)
            "running" -> Triple(
                getString(R.string.status_running),
                R.drawable.bg_status_pill_running,
                R.color.status_running_text)
            else -> Triple(
                getString(R.string.status_loading),
                R.drawable.bg_status_pill_loading,
                R.color.status_loading_text)
        }
        binding.textStatusPill.text = text
        binding.textStatusPill.setBackgroundResource(bgRes)
        binding.textStatusPill.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun showRunningState(goal: String) {
        binding.textGoalRecap.text = goal
        binding.scrollCards.visibility = View.GONE
        binding.scrollRunning.visibility = View.VISIBLE
        binding.btnSend.isEnabled = false
        for (i in 0 until binding.layoutCategoryTabs.childCount) {
            binding.layoutCategoryTabs.getChildAt(i).isEnabled = false
        }
    }

    private fun showIdleState() {
        binding.scrollCards.visibility = View.VISIBLE
        binding.scrollRunning.visibility = View.GONE
        binding.btnSend.isEnabled = true
        for (i in 0 until binding.layoutCategoryTabs.childCount) {
            binding.layoutCategoryTabs.getChildAt(i).isEnabled = true
        }
    }

    private fun onStopAssistant() {
        ScreenCaptureService.comparisonSession = null
        ScreenCaptureService.onSessionDismissed = null
        ComparisonOverlay.dismiss()
        OrionPipOverlay.dismiss()
        pendingComparisonGoal = null
        ScreenCaptureService.stopCapture(this)
        showIdleState()
        setStatusPill("ready")
    }

    private fun requestScreenCaptureForComparison() {
        setStatusPill("running")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun requestScreenCapture() {
        setStatusPill("running")
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
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
        val modelPath = findModelPath() ?: run { setStatusPill("loading"); return }
        val existing = ScreenCaptureService.activeEngine
        if (existing != null && existing.isReady()) { setStatusPill("ready"); return }
        setStatusPill("loading")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { liteRTLMManager.initialize(modelPath) }
                ScreenCaptureService.activeEngine = liteRTLMManager
                setStatusPill("ready")
                ScreenCaptureService.triggerCaptureIfReady()
            } catch (e: Exception) {
                setStatusPill("loading")
            }
        }
    }

    private fun findModelPath(): String? {
        val searchDirs = listOfNotNull("/data/local/tmp", getExternalFilesDir(null)?.absolutePath)
        val modelNames = listOf(
            "gemma-4-E4B-it.litertlm",
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
        ScreenCaptureService.onBookingChosen = null
        ScreenCaptureService.onSessionDismissed = null
        OrionPipOverlay.dismiss()
        ScreenCaptureService.activeEngine?.cleanup()
        ScreenCaptureService.activeEngine = null
        liteRTLMManager.cleanup()
        super.onDestroy()
    }
}
