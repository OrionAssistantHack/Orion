package com.orion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.orion.core.PlanAction
import com.orion.inference.InferenceEngine
import com.orion.inference.LiteRTLMManager
import com.orion.inference.logGemmaToQwen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

private fun buildComparisonGoal(goal: com.orion.core.ParsedGoal): String = when (goal) {
    is com.orion.core.ParsedGoal.RideRequest ->
        "Navigate to the fare estimate screen for destination: ${goal.destination}. " +
        "STOP at the fare estimate screen — do NOT tap Book, Request, Confirm, or any booking button."
    is com.orion.core.ParsedGoal.FoodOrder ->
        "Search for restaurant '${goal.restaurant}'" +
        (goal.item?.let { " and find item '$it'" } ?: "") +
        ". Navigate to the order summary page. " +
        "STOP before placing the order — do NOT tap Place Order, Checkout, or any submit button."
}

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "Orion.ScreenCapture"
        private const val CHANNEL_ID = "orion_capture"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_APP = "app"

        var pendingGoal: String = ""
        var targetApp: String = ""
        var onPlanResult: ((String, com.orion.core.Plan) -> Unit)? = null
        var activeEngine: InferenceEngine? = null

        @Volatile var comparisonSession: com.orion.core.ComparisonSession? = null
        var onBookingChosen: ((com.orion.core.KnownApp) -> Unit)? = null

        fun startComparison(
            context: Context,
            resultCode: Int,
            data: Intent,
            session: com.orion.core.ComparisonSession,
            onBook: (com.orion.core.KnownApp) -> Unit,
        ) {
            comparisonSession = session
            onBookingChosen = onBook
            targetApp = session.currentApp?.packageName ?: return
            pendingGoal = buildComparisonGoal(session.parsedGoal)
            context.startForegroundService(
                Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_PROJECTION_DATA, data)
                    putExtra(EXTRA_GOAL, pendingGoal)
                    putExtra(EXTRA_APP, targetApp)
                }
            )
        }

        private const val MAX_TAP_RETRIES = 3
        private const val MAX_UNCHANGED_FINGERPRINT_RETRIES = 3
        private const val MIN_NODES_THRESHOLD = 5
        private const val MAX_THIN_NODE_RETRIES = 2
        private const val POST_ACTION_DELAY_MS = 2500L

        private var instance: WeakReference<ScreenCaptureService>? = null

        fun startCapture(context: Context, resultCode: Int, data: Intent, goal: String, app: String) {
            pendingGoal = goal
            targetApp = app
            context.startForegroundService(
                Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_PROJECTION_DATA, data)
                    putExtra(EXTRA_GOAL, goal)
                    putExtra(EXTRA_APP, app)
                }
            )
        }

        fun stopCapture(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun triggerCapture() {
            instance?.get()?.captureHandler?.post { instance?.get()?.captureFrame() }
        }

        fun resetGoalState() {
            instance?.get()?.apply {
                retryCount = 0
                retryContext = ""
                lastSuccessfulAction = ""
                lastNodeFingerprint = ""
                thinNodeRetryCount = 0
                postActionCooldownUntil = 0L
            }
        }

        fun triggerCaptureIfReady() {
            val svc = instance?.get() ?: return
            val eng = activeEngine ?: return
            if (targetApp.isNotBlank()
                && OrionAccessibilityService.lastAppPackage == targetApp
                && eng.isReady()
            ) {
                Log.i(TAG, "triggerCaptureIfReady — firing for $targetApp")
                svc.captureHandler.post { svc.captureFrame() }
            }
        }
    }

    @Volatile private var retryCount = 0
    @Volatile private var retryContext = ""
    @Volatile private var lastSuccessfulAction = ""
    @Volatile private var lastNodeFingerprint: String = ""
    @Volatile private var unchangedFingerprintCount: Int = 0
    @Volatile private var thinNodeRetryCount: Int = 0
    @Volatile private var postActionCooldownUntil: Long = 0L

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureThread: HandlerThread
    internal lateinit var captureHandler: Handler

    private val inferenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceActive = AtomicBoolean(false)
    private var frameCounter = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("CaptureThread").also { it.start() }
        captureHandler = Handler(captureThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

        pendingGoal = intent?.getStringExtra(EXTRA_GOAL) ?: pendingGoal
        targetApp = intent?.getStringExtra(EXTRA_APP) ?: targetApp

        if (resultCode == Activity.RESULT_OK && projectionData != null) {
            val mpManager = getSystemService(MediaProjectionManager::class.java)
            val mp = mpManager.getMediaProjection(resultCode, projectionData)
            if (mp == null) {
                Log.e(TAG, "getMediaProjection returned null — token invalid or already consumed")
                stopSelf()
            } else {
                mediaProjection = mp
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.i(TAG, "MediaProjection stopped")
                        stopSelf()
                    }
                }, captureHandler)
                startCapture(mp)
                instance = WeakReference(this)
            }
        } else {
            Log.e(TAG, "onStartCommand missing token — resultCode=$resultCode")
        }

        return START_NOT_STICKY
    }

    private fun startCapture(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        Log.i(TAG, "Creating VirtualDisplay: ${width}x${height}@${density}dpi")
        virtualDisplay = projection.createVirtualDisplay(
            "OrionCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        if (virtualDisplay == null) {
            Log.e(TAG, "createVirtualDisplay returned null — capture will not work")
            return
        }
        Log.i(TAG, "Capture started: ${width}x${height}@${density}dpi")
        triggerCaptureIfReady()
    }

    @Suppress("DEPRECATION")
    internal fun captureFrame() {
        if (inferenceActive.get()) {
            imageReader?.acquireLatestImage()?.close()
            Log.d(TAG, "Inference in flight — dropping frame")
            return
        }
        val cooldownRemaining = postActionCooldownUntil - System.currentTimeMillis()
        if (cooldownRemaining > 0) {
            imageReader?.acquireLatestImage()?.close()
            Log.d(TAG, "Post-action cooldown — waiting ${cooldownRemaining}ms before next capture")
            captureHandler.postDelayed({ captureFrame() }, cooldownRemaining)
            return
        }
        frameCounter++
        val frameNum = frameCounter
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val bitmapForInference = bitmap.copy(Bitmap.Config.ARGB_8888, false)

            // FLAG_SECURE detection — two-stage: cheap center pre-filter, then 5×5 grid
            val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            val flagSecure = if (Color.red(centerPixel) >= 30 || Color.green(centerPixel) >= 30 || Color.blue(centerPixel) >= 30) {
                false
            } else {
                val fractions = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
                var blackCount = 0
                for (fx in fractions) for (fy in fractions) {
                    val px = bitmap.getPixel((bitmap.width * fx).toInt(), (bitmap.height * fy).toInt())
                    if (Color.red(px) < 30 && Color.green(px) < 30 && Color.blue(px) < 30) blackCount++
                }
                blackCount >= 24
            }
            if (flagSecure) {
                Log.w(TAG, "FLAG_SECURE likely active — grid check: 24+/25 sample points near-black")
            }

            val outDir = getExternalFilesDir(null) ?: filesDir
            val file = File(outDir, "frame_${System.currentTimeMillis()}.jpg")
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                Log.i(TAG, "Frame #$frameNum saved → ${file.absolutePath}")
            } finally {
                bitmap.recycle()
            }

            if (targetApp.isNotBlank() && OrionAccessibilityService.lastAppPackage != targetApp) {
                bitmapForInference.recycle()
                Log.d(TAG, "Skipping inference — lastApp=${OrionAccessibilityService.lastAppPackage}, target=$targetApp")
                return
            }

            val lm = activeEngine ?: LiteRTLMManager.getInstance(this)
            if (!flagSecure && lm.isReady()) {
                if (inferenceActive.compareAndSet(false, true)) {
                    val nodes: List<Pair<String, Rect>> = buildList {
                        val root = OrionAccessibilityService.instance?.rootInActiveWindow
                        if (root != null) {
                            collectClickableNodes(root, this)
                            root.recycle()
                        } else {
                            Log.w(TAG, "rootInActiveWindow is null — no node list")
                        }
                    }

                    if (nodes.isEmpty()) {
                        bitmapForInference.recycle()
                        inferenceActive.set(false)
                        Log.w(TAG, "Node list empty — skipping inference, retrying in 800ms")
                        captureHandler.postDelayed({ captureFrame() }, 800L)
                        return
                    }

                    if (nodes.size < MIN_NODES_THRESHOLD && thinNodeRetryCount < MAX_THIN_NODE_RETRIES) {
                        thinNodeRetryCount++
                        bitmapForInference.recycle()
                        inferenceActive.set(false)
                        Log.w(TAG, "Too few nodes (${nodes.size}/$MIN_NODES_THRESHOLD) — screen may still be loading, retry $thinNodeRetryCount/$MAX_THIN_NODE_RETRIES in 800ms")
                        captureHandler.postDelayed({ captureFrame() }, 800L)
                        return
                    }
                    thinNodeRetryCount = 0

                    val rootPkg = OrionAccessibilityService.instance?.rootInActiveWindow?.also { it.recycle() }?.packageName?.toString()
                    if (targetApp.isNotBlank() && rootPkg != null && rootPkg != targetApp) {
                        bitmapForInference.recycle()
                        inferenceActive.set(false)
                        Log.d(TAG, "Root window is '$rootPkg', not '$targetApp' — window still transitioning, retrying in 400ms")
                        captureHandler.postDelayed({ captureFrame() }, 400L)
                        return
                    }

                    val fingerprint = nodes.joinToString("|") { it.first }
                    if (fingerprint == lastNodeFingerprint && lastSuccessfulAction.isNotBlank() && retryCount == 0) {
                        unchangedFingerprintCount++
                        if (unchangedFingerprintCount <= MAX_UNCHANGED_FINGERPRINT_RETRIES) {
                            bitmapForInference.recycle()
                            inferenceActive.set(false)
                            Log.d(TAG, "Node list unchanged after action — window still transitioning, retrying in 400ms ($unchangedFingerprintCount/$MAX_UNCHANGED_FINGERPRINT_RETRIES)")
                            captureHandler.postDelayed({ captureFrame() }, 400L)
                            return
                        }
                        Log.w(TAG, "Node list still unchanged after $MAX_UNCHANGED_FINGERPRINT_RETRIES retries — running inference on the new screenshot anyway (a11y tree may not reflect on-screen changes such as a keyboard overlay)")
                    }
                    lastNodeFingerprint = fingerprint
                    unchangedFingerprintCount = 0

                    Log.d(TAG, "Collected ${nodes.size} clickable nodes")

                    Log.i(TAG, "=== AGENT CYCLE #$frameNum | goal='$pendingGoal' | backend=${lm.getDescription()} ===")
                    Log.i(TAG, "Nodes (${nodes.size}):\n" + nodes.mapIndexed { i, (text, _) -> "  [${i+1}] $text" }.joinToString("\n"))

                    val screenW = bitmapForInference.width
                    val screenH = bitmapForInference.height
                    inferenceScope.launch {
                        try {
                            val inferenceStartMs = System.currentTimeMillis()
                            val (perception, plan) = lm.perceiveAndPlan(bitmapForInference, pendingGoal, nodes, screenW, screenH, targetApp, retryContext, if (retryCount == 0) lastSuccessfulAction else "")
                            val elapsedMs = System.currentTimeMillis() - inferenceStartMs
                            Log.i(TAG, "Frame #$frameNum [${lm.getDescription()}] ${elapsedMs}ms | phase=${perception.screenPhase} conf=%.2f | ${plan.summaryForUser}".format(perception.confidence))
                            // Log.i(TAG, "Raw response: ${perception.rawDescription.take(500)}")
                            logGemmaToQwen(perception.rawDescription, TAG)
                            Log.i(TAG, "Plan: ${plan.summaryForUser} | actions=${plan.actions.size}: ${plan.actions.joinToString { "${it.type}(${it.nodeText ?: it.nodeIndex})" }}")
                            appendInferenceLog(frameNum, elapsedMs, pendingGoal, targetApp, perception.rawDescription, plan.summaryForUser, plan.actions)

                            // Comparison mode: when fare estimate screen reached with price, advance to next app.
                            val session = comparisonSession
                            if (session != null && !session.isComplete) {
                                val price = perception.extractedData["price"]
                                    ?.takeIf { it.isNotBlank() && it != "null" }
                                if (perception.screenPhase == com.orion.core.ScreenPhase.FARE_ESTIMATE && price != null) {
                                    val eta = perception.extractedData["eta"]
                                        ?.replace(Regex("[^0-9]"), "")
                                        ?.toIntOrNull()
                                    val currentPkg = session.currentApp?.packageName
                                    if (currentPkg != null) {
                                        session.collectedFares[currentPkg] =
                                            com.orion.core.FareData(price, eta, perception.confidence)
                                        Log.i(TAG, "Comparison: fare collected for $currentPkg — price=$price eta=$eta")
                                    }
                                    session.advance()

                                    if (session.isComplete) {
                                        Log.i(TAG, "Comparison: all fares collected — showing overlay")
                                        val capturedSession = session
                                        comparisonSession = null
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            com.orion.ui.ComparisonOverlay.show(
                                                this@ScreenCaptureService,
                                                capturedSession
                                            ) { chosenApp ->
                                                onBookingChosen?.invoke(chosenApp)
                                            }
                                        }
                                    } else {
                                        val nextApp = session.currentApp
                                        if (nextApp != null) {
                                            Log.i(TAG, "Comparison: advancing to ${nextApp.packageName}")
                                            targetApp = nextApp.packageName
                                            pendingGoal = buildComparisonGoal(session.parsedGoal)
                                            resetGoalState()
                                            val intent = packageManager.getLaunchIntentForPackage(nextApp.packageName)
                                                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            if (intent != null) startActivity(intent)
                                            else Log.w(TAG, "Comparison: ${nextApp.packageName} not installed — skipping")
                                        }
                                    }
                                    return@launch  // skip action execution — fare collected, session advanced
                                }
                            }

                            val actionExecuted = if (plan.actions.isNotEmpty()) {
                                val action = plan.actions[0]
                                val nodeIdx = action.nodeIndex
                                val target = when {
                                    nodeIdx != null && nodeIdx in nodes.indices ->
                                        nodes[nodeIdx]
                                    action.nodeText != null -> {
                                        val t = action.nodeText
                                        nodes.firstOrNull { it.first == t }
                                            ?: nodes.firstOrNull { it.first.equals(t, ignoreCase = true) }
                                            ?: nodes.firstOrNull { it.first.startsWith(t, ignoreCase = true) }
                                            ?: nodes.firstOrNull { it.first.contains(t, ignoreCase = true) }
                                    }
                                    else -> null
                                }
                                if (target != null) {
                                    if (action.type == "type_text" && action.text != null) {
                                        val exec = OrionAccessibilityService.instance?.executor
                                        val success = exec?.typeText(target.first, action.text) ?: false
                                        if (success) {
                                            Log.i(TAG, "type_text into '${target.first}' text='${action.text}'")
                                            lastSuccessfulAction = "Typed '${action.text}' into '${target.first}'"
                                            retryContext = ""
                                            postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
                                        } else {
                                            Log.w(TAG, "type_text failed for '${target.first}' — keyboard likely not visible, switching to tap_node")
                                            retryContext = "CORRECTION: type_text on '${target.first}' failed — the keyboard was likely not visible, please confirm and accordingly, switch to tap_node."
                                        }
                                        success
                                    } else {
                                        Log.i(TAG, "Auto-tapping '${target.first}' via ACTION_CLICK [nodeIdx=$nodeIdx]")
                                        val result = OrionAccessibilityService.instance?.executor?.tapNode(target.first)
                                        val tapped = result?.success == true
                                        if (tapped) {
                                            lastSuccessfulAction = "Tapped '${target.first}'"
                                            retryContext = ""
                                            postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
                                        } else {
                                            Log.w(TAG, "ACTION_CLICK failed for '${target.first}' (${result?.errorCode}) — falling back to coordinates")
                                            val cx = target.second.centerX().toFloat()
                                            val cy = target.second.centerY().toFloat()
                                            OrionAccessibilityService.instance?.executor?.dispatchTap(cx, cy)
                                            lastSuccessfulAction = "Tapped '${target.first}'"
                                            retryContext = ""
                                            postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
                                        }
                                        true
                                    }
                                } else {
                                    retryContext = "CORRECTION: Previous attempt selected nodeIndex=${nodeIdx?.plus(1)} " +
                                        "nodeText=\"${action.nodeText}\" but neither was found in the accessibility tree. " +
                                        "The exact available nodes are listed above. Pick the nodeIndex that best matches the goal."
                                    Log.w(TAG, "Tap resolution failed — nodeIdx=$nodeIdx nodeText=${action.nodeText}")
                                    val nodeListStr = nodes.mapIndexed { i, (text, _) -> "[${i+1}] \"$text\"" }.joinToString(", ")
                                    Log.w(TAG, "Resolution failed — available: $nodeListStr")
                                    false
                                }
                            } else {
                                Log.w(TAG, "Model returned empty actions — raw: ${perception.rawDescription.take(200)}")
                                false
                            }

                            if (!actionExecuted) {
                                if (retryCount < MAX_TAP_RETRIES) {
                                    retryCount++
                                    Log.w(TAG, "No action executed — scheduling retry $retryCount/$MAX_TAP_RETRIES")
                                    captureHandler.postDelayed({ captureFrame() }, 600L)
                                } else {
                                    Log.e(TAG, "No action executed after $MAX_TAP_RETRIES retries — giving up")
                                    retryCount = 0
                                    retryContext = ""
                                }
                            } else {
                                retryCount = 0
                                retryContext = ""
                            }

                            onPlanResult?.let { cb ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    cb(perception.rawDescription, plan)
                                }
                            }
                        } finally {
                            bitmapForInference.recycle()
                            inferenceActive.set(false)
                        }
                    }
                } else {
                    bitmapForInference.recycle()
                    Log.d(TAG, "Inference in flight — skipping frame")
                }
            } else {
                bitmapForInference.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureFrame failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectClickableNodes(node: AccessibilityNodeInfo, result: MutableList<Pair<String, Rect>>) {
        if (node.isClickable) {
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: subtreeText(node).takeIf { it.isNotBlank() }
            if (!text.isNullOrBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                result.add(text to rect)
                return
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodes(child, result)
            child.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun subtreeText(node: AccessibilityNodeInfo): String {
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                ?: subtreeText(child)
            if (t.isNotBlank()) parts.add(t)
            child.recycle()
        }
        return parts.joinToString(" ")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Orion Capture Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    private fun appendInferenceLog(
        frameNum: Int,
        elapsedMs: Long,
        goal: String,
        app: String,
        rawResponse: String,
        summary: String,
        actions: List<PlanAction>,
    ) {
        try {
            val actionsArr = JSONArray()
            actions.forEach { a ->
                actionsArr.put(JSONObject().apply {
                    put("type", a.type)
                    a.nodeIndex?.let { put("nodeIndex", it) }
                    a.nodeText?.let { put("nodeText", it) }
                    a.x?.let { put("x", it) }
                    a.y?.let { put("y", it) }
                })
            }
            val entry = JSONObject().apply {
                put("frame", frameNum)
                put("timestamp_ms", System.currentTimeMillis())
                put("elapsed_ms", elapsedMs)
                put("goal", goal)
                put("app", app)
                put("summary", summary)
                put("actions", actionsArr)
                put("raw_response", rawResponse)
            }
            val logFile = File(getExternalFilesDir(null) ?: filesDir, "orion_inference.jsonl")
            FileWriter(logFile, true).use { it.write(entry.toString() + "\n") }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write inference log: ${e.message}")
        }
    }

    override fun onDestroy() {
        instance = null
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        captureThread.quitSafely()
        inferenceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
