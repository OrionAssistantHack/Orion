package com.orion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import java.io.FileWriter
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

private fun buildComparisonGoal(goal: com.orion.core.ParsedGoal): String = when (goal) {
    is com.orion.core.ParsedGoal.RideRequest -> {
        val stopCondition = if (goal.preference == com.orion.core.Preference.FASTEST)
            "prices and ETAs" else "prices"
        "Navigate to the fare estimate screen for destination: ${goal.destination}. " +
        "If a promotional overlay or bottom sheet appears (e.g. 'Pay with Bilt points', 'Pay with points', rewards offers), tap its X or Close button to dismiss it first. " +
        "STOP at the fare estimate screen once you can see ride options with $stopCondition — do NOT tap Book, Request, Confirm, or any booking button."
    }
    is com.orion.core.ParsedGoal.FoodOrder -> {
        val stopCondition = if (goal.preference == com.orion.core.Preference.FASTEST)
            "prices and delivery times" else "prices"
        "Search for restaurant '${goal.restaurant}'" +
        (goal.item?.let { " and find item '$it'" } ?: "") +
        ". Navigate to the order summary page. " +
        "If a promotional overlay appears, dismiss it first. " +
        "STOP before placing the order — do NOT tap Place Order, Checkout, or any submit button. Ensure $stopCondition are visible."
    }
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

        @Volatile var pendingGoal: String = ""
        @Volatile var targetApp: String = ""
        @Volatile var onPlanResult: ((String, com.orion.core.Plan) -> Unit)? = null
        @Volatile var activeEngine: InferenceEngine? = null
        @Volatile var pipeline: com.orion.inference.InferencePipeline? = null

        @Volatile var comparisonSession: com.orion.core.ComparisonSession? = null
        @Volatile var onBookingChosen: ((com.orion.core.KnownApp) -> Unit)? = null
        @Volatile var onSessionDismissed: (() -> Unit)? = null

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
        private const val CONSECUTIVE_NONE_THRESHOLD = 3
        private const val MAX_UNCHANGED_FINGERPRINT_RETRIES = 3
        private const val MIN_NODES_THRESHOLD = 5
        private const val MAX_THIN_NODE_RETRIES = 2
        private const val POST_ACTION_DELAY_MS = 2500L
        private const val MAX_FRAME_FILES = 10

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
            instance?.get()?.captureHandler?.post { instance?.get()?.runAgentCycle() }
        }

        fun resetGoalState() {
            instance?.get()?.apply {
                retryCount = 0
                retryContext = ""
                lastSuccessfulAction = ""
                lastNodeFingerprint = ""
                thinNodeRetryCount = 0
                postActionCooldownUntil = 0L
                pendingAutoSelectText = null
                consecutiveNoneCount = 0
                hideNextCycleText = null
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
                svc.captureHandler.post { svc.runAgentCycle() }
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
    @Volatile private var pendingAutoSelectText: String? = null
    @Volatile private var consecutiveNoneCount: Int = 0
    // Set when press_home fires (to the node text we just tapped). The next inference cycle
    // hides any matching node from the list before sending it to the model, so the model
    // physically cannot re-pick it. One-shot — cleared after that single cycle.
    @Volatile private var hideNextCycleText: String? = null

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

        pruneFrameFiles(getExternalFilesDir(null) ?: filesDir, keep = 0)

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
    internal fun runAgentCycle() {
        if (inferenceActive.get()) {
            imageReader?.acquireLatestImage()?.close()
            Log.d(TAG, "Inference in flight — dropping frame")
            return
        }
        val cooldownRemaining = postActionCooldownUntil - System.currentTimeMillis()
        if (cooldownRemaining > 0) {
            imageReader?.acquireLatestImage()?.close()
            Log.d(TAG, "Post-action cooldown — waiting ${cooldownRemaining}ms before next capture")
            captureHandler.postDelayed({ runAgentCycle() }, cooldownRemaining)
            return
        }
        frameCounter++
        val frameNum = frameCounter
        val image = imageReader?.acquireLatestImage() ?: return
        try {
            // FLAG_SECURE detection — two-stage: cheap center pre-filter, then 5×5 grid
            val imgPlane = image.planes[0]
            val imgBuffer = imgPlane.buffer
            val imgRowStride = imgPlane.rowStride
            val imgW = image.width
            val imgH = image.height

            fun samplePixel(x: Int, y: Int): Int {
                val idx = y * imgRowStride + x * 4
                val r = imgBuffer.get(idx).toInt() and 0xFF
                val g = imgBuffer.get(idx + 1).toInt() and 0xFF
                val b = imgBuffer.get(idx + 2).toInt() and 0xFF
                return android.graphics.Color.rgb(r, g, b)
            }

            val centerPixel = samplePixel(imgW / 2, imgH / 2)
            val flagSecure = if (android.graphics.Color.red(centerPixel) >= 30 ||
                android.graphics.Color.green(centerPixel) >= 30 ||
                android.graphics.Color.blue(centerPixel) >= 30) {
                false
            } else {
                val fractions = floatArrayOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f)
                var blackCount = 0
                for (fx in fractions) for (fy in fractions) {
                    val px = samplePixel((imgW * fx).toInt(), (imgH * fy).toInt())
                    if (android.graphics.Color.red(px) < 30 &&
                        android.graphics.Color.green(px) < 30 &&
                        android.graphics.Color.blue(px) < 30) blackCount++
                }
                blackCount >= 24
            }
            if (flagSecure) {
                Log.w(TAG, "FLAG_SECURE likely active — grid check: 24+/25 sample points near-black")
            }

            if (targetApp.isNotBlank() && OrionAccessibilityService.lastAppPackage != targetApp) {
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
                        inferenceActive.set(false)
                        Log.w(TAG, "Node list empty — skipping inference, retrying in 800ms")
                        captureHandler.postDelayed({ runAgentCycle() }, 800L)
                        return
                    }

                    if (nodes.size < MIN_NODES_THRESHOLD && thinNodeRetryCount < MAX_THIN_NODE_RETRIES) {
                        thinNodeRetryCount++
                        inferenceActive.set(false)
                        Log.w(TAG, "Too few nodes (${nodes.size}/$MIN_NODES_THRESHOLD) — screen may still be loading, retry $thinNodeRetryCount/$MAX_THIN_NODE_RETRIES in 800ms")
                        captureHandler.postDelayed({ runAgentCycle() }, 800L)
                        return
                    }
                    thinNodeRetryCount = 0

                    val rootPkg = OrionAccessibilityService.instance?.rootInActiveWindow?.also { it.recycle() }?.packageName?.toString()
                    if (targetApp.isNotBlank() && rootPkg != null && rootPkg != targetApp) {
                        inferenceActive.set(false)
                        Log.d(TAG, "Root window is '$rootPkg', not '$targetApp' — window still transitioning, retrying in 400ms")
                        captureHandler.postDelayed({ runAgentCycle() }, 400L)
                        return
                    }

                    val fingerprint = nodes.joinToString("|") { it.first }
                    if (fingerprint == lastNodeFingerprint && lastSuccessfulAction.isNotBlank() && retryCount == 0) {
                        unchangedFingerprintCount++
                        if (unchangedFingerprintCount <= MAX_UNCHANGED_FINGERPRINT_RETRIES) {
                            inferenceActive.set(false)
                            Log.d(TAG, "Node list unchanged after action — window still transitioning, retrying in 400ms ($unchangedFingerprintCount/$MAX_UNCHANGED_FINGERPRINT_RETRIES)")
                            captureHandler.postDelayed({ runAgentCycle() }, 400L)
                            return
                        }
                        Log.w(TAG, "Node list still unchanged after $MAX_UNCHANGED_FINGERPRINT_RETRIES retries — running inference on the new screenshot anyway (a11y tree may not reflect on-screen changes such as a keyboard overlay)")
                    }
                    lastNodeFingerprint = fingerprint
                    unchangedFingerprintCount = 0

                    Log.d(TAG, "Collected ${nodes.size} clickable nodes")

                    Log.i(TAG, "=== AGENT CYCLE #$frameNum | goal='$pendingGoal' | backend=${lm.getDescription()} ===")
                    Log.i(TAG, "Nodes (${nodes.size}):\n" + nodes.mapIndexed { i, (text, _) -> "  [${i+1}] $text" }.joinToString("\n"))

                    // Auto-select first matching search result after a successful type — skip model entirely.
                    val typed = pendingAutoSelectText
                    if (typed != null) {
                        val firstWord = typed.split(Regex("\\s+")).firstOrNull { it.length >= 3 } ?: typed
                        val match = nodes.firstOrNull { (text, _) ->
                            !text.equals(typed, ignoreCase = true) && text.contains(firstWord, ignoreCase = true)
                        }
                        if (match != null) {
                            Log.i(TAG, "Auto-selecting first matching result for typed '$typed' (matched on '$firstWord'): '${match.first}'")
                            val cx = match.second.centerX().toFloat()
                            val cy = match.second.centerY().toFloat()
                            OrionAccessibilityService.instance?.executor?.dispatchTap(cx, cy)
                            lastSuccessfulAction = "Auto-tapped '${match.first}' (matched typed text)"
                            pendingAutoSelectText = null
                            retryContext = ""
                            postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
                            inferenceActive.set(false)
                            return
                        } else {
                            Log.w(TAG, "Auto-select: no node matching '$firstWord' found — falling through to model inference")
                            pendingAutoSelectText = null
                        }
                    }

                    inferenceScope.launch {
                        try {
                            // Hide-next-cycle hard filter: when press_home fired last cycle we stashed
                            // the just-tapped node text. Remove any matching node from the list the
                            // model sees AND resolves indices against — the prompt is unchanged. The
                            // constraint is one-shot: cleared after this single cycle so the option
                            // returns on the cycle after.
                            val hide = hideNextCycleText
                            hideNextCycleText = null
                            val rawNodes = nodes
                            val nodes: List<Pair<String, Rect>> = if (hide != null) {
                                rawNodes.filterNot { (text, _) -> text.contains(hide, ignoreCase = true) }
                                    .also { filtered ->
                                        val removed = rawNodes.size - filtered.size
                                        if (removed > 0) Log.i(TAG, "Hide-next-cycle: removed $removed node(s) matching '$hide' (${rawNodes.size}→${filtered.size})")
                                    }
                            } else rawNodes
                            val inferenceStartMs = System.currentTimeMillis()
                            val activePipeline = pipeline ?: com.orion.inference.InferencePipeline(listOf(com.orion.inference.VisionStep(lm)))
                            val cycleContext = com.orion.inference.CycleContext(
                                image = image,
                                nodes = nodes,
                                goal = pendingGoal,
                                screenW = imgW,
                                screenH = imgH,
                                appPackage = targetApp,
                                retryContext = retryContext,
                                previousAction = if (retryCount == 0) lastSuccessfulAction else "",
                                frameNum = frameNum,
                            )
                            val (perception, plan) = activePipeline.run(cycleContext)
                            val elapsedMs = System.currentTimeMillis() - inferenceStartMs
                            Log.i(TAG, "Frame #$frameNum [${lm.getDescription()}] ${elapsedMs}ms | phase=${perception.screenPhase} conf=%.2f | ${plan.summaryForUser}".format(perception.confidence))
                            // Log.i(TAG, "Raw response: ${perception.rawDescription.take(500)}")
                            logGemmaToQwen(perception.rawDescription, TAG)
                            Log.i(TAG, "Plan: ${plan.summaryForUser} | actions=${plan.actions.size}: ${plan.actions.joinToString { "${it.type}(${it.nodeText ?: it.nodeIndex})" }}")
                            appendInferenceLog(frameNum, elapsedMs, pendingGoal, targetApp, perception.rawDescription, plan.summaryForUser, plan.actions)

                            if (handleComparisonSession(perception, plan)) {
                                return@launch
                            }

                            val session = comparisonSession
                            if (plan.goalReached && session == null) {
                                consecutiveNoneCount++
                                Log.i(TAG, "Goal-reached signal $consecutiveNoneCount/$CONSECUTIVE_NONE_THRESHOLD")
                                if (consecutiveNoneCount >= CONSECUTIVE_NONE_THRESHOLD) {
                                    Log.i(TAG, "Goal confirmed — stopping service")
                                    onPlanResult?.let { cb ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            cb(perception.rawDescription, plan)
                                        }
                                    }
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { stopSelf() }
                                } else {
                                    captureHandler.postDelayed({ runAgentCycle() }, POST_ACTION_DELAY_MS)
                                }
                                return@launch
                            }
                            consecutiveNoneCount = 0

                            val firstAction = plan.actions.firstOrNull()
                            val actionType = firstAction?.type
                            // Recovery-action contract (decided by the model from the screenshot):
                            //   press_home → model judged we are in the WRONG app/screen (launcher,
                            //                a different app, an undismissable system dialog).
                            //                We escape and relaunch targetApp.
                            //   swipe      → model judged we are in the CORRECT app but the element
                            //                it needs is off-screen; scroll and re-perceive.
                            // The discrimination lives in the prompt rules; lastAppPackage cannot be
                            // used here because launcher events are filtered by the a11y config.
                            val actionExecuted = dispatchAction(firstAction, actionType, nodes, imgW, imgH, perception.rawDescription)

                            handleRetry(actionExecuted, actionType)

                            onPlanResult?.let { cb ->
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    cb(perception.rawDescription, plan)
                                }
                            }
                        } finally {
                            inferenceActive.set(false)
                        }
                    }
                } else {
                    Log.d(TAG, "Inference in flight — skipping frame")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runAgentCycle failed: ${e.message}")
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
            .setSmallIcon(R.drawable.ic_notification)
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

    private fun pruneFrameFiles(dir: File, keep: Int) {
        val frames = dir.listFiles { f -> f.name.startsWith("frame_") && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name } ?: return
        frames.dropLast(keep).forEach { it.delete() }
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

    private fun handleRetry(actionExecuted: Boolean, actionType: String?) {
        if (!actionExecuted) {
            if (retryCount < MAX_TAP_RETRIES) {
                retryCount++
                Log.w(TAG, "No action executed — scheduling retry $retryCount/$MAX_TAP_RETRIES")
                captureHandler.postDelayed({ runAgentCycle() }, 600L)
            } else {
                Log.e(TAG, "No action executed after $MAX_TAP_RETRIES retries — giving up")
                retryCount = 0
                retryContext = ""
            }
        } else {
            when (actionType) {
                "press_home" -> {
                    // Extract the node text we just tapped (e.g. "Tapped 'Messages'" → "Messages")
                    // and stash it on hideNextCycleText so the next inference cycle removes
                    // that node from the list the model sees. We do NOT touch the prompt —
                    // the constraint is enforced purely by hiding the node.
                    hideNextCycleText = Regex("[Tt]apped '(.+?)'").find(lastSuccessfulAction)?.groupValues?.get(1)
                    retryCount /= 2
                    lastSuccessfulAction = ""
                    retryContext = ""
                }
                "swipe" -> {
                    // Per design: leave retryCount and lastSuccessfulAction untouched —
                    // swipe is exploratory and the meaningful prior action is still relevant.
                }
                else -> {
                    retryCount = 0
                    retryContext = ""
                }
            }
        }
    }

    private fun handleComparisonSession(
        perception: com.orion.core.PerceptionResult,
        plan: com.orion.core.Plan
    ): Boolean {
        val session = comparisonSession
        if (session != null && !session.isComplete) {
            val price = perception.extractedData["price"]
                ?.takeIf { p -> p.isNotBlank() && p != "null" && p.any { it.isDigit() } }
            if (price != null) {
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
                            capturedSession,
                            onBook = { chosenApp -> onBookingChosen?.invoke(chosenApp) },
                            onDismiss = { onSessionDismissed?.invoke() }
                        )
                        stopSelf()
                    }
                } else {
                    val nextApp = session.currentApp
                    if (nextApp != null) {
                        Log.i(TAG, "Comparison: advancing to ${nextApp.packageName}")
                        targetApp = nextApp.packageName
                        pendingGoal = buildComparisonGoal(session.parsedGoal)
                        resetGoalState()
                        val intent = this@ScreenCaptureService.packageManager.getLaunchIntentForPackage(nextApp.packageName)
                            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (intent != null) {
                            this@ScreenCaptureService.startActivity(intent)
                        } else {
                            Log.w(TAG, "Comparison: ${nextApp.packageName} not installed — skipping")
                            session.advance()
                            if (session.isComplete) {
                                val capturedSession2 = session
                                comparisonSession = null
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    com.orion.ui.ComparisonOverlay.show(
                                        this@ScreenCaptureService,
                                        capturedSession2,
                                        onBook = { chosenApp -> onBookingChosen?.invoke(chosenApp) },
                                        onDismiss = { onSessionDismissed?.invoke() }
                                    )
                                    stopSelf()
                                }
                            } else {
                                val skippedTo = session.currentApp
                                if (skippedTo != null) {
                                    targetApp = skippedTo.packageName
                                    pendingGoal = buildComparisonGoal(session.parsedGoal)
                                    resetGoalState()
                                    val nextIntent = this@ScreenCaptureService.packageManager.getLaunchIntentForPackage(skippedTo.packageName)
                                        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    if (nextIntent != null) this@ScreenCaptureService.startActivity(nextIntent)
                                    else Log.w(TAG, "Comparison: ${skippedTo.packageName} also not installed — session may stall")
                                }
                            }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    private fun dispatchAction(
        firstAction: com.orion.core.PlanAction?,
        actionType: String?,
        nodes: List<Pair<String, android.graphics.Rect>>,
        screenW: Int,
        screenH: Int,
        rawDescription: String
    ): Boolean = when {
        firstAction == null -> {
            Log.w(TAG, "Model returned empty actions — raw: ${rawDescription.take(200)}")
            false
        }
        actionType == "need_image" -> {
            Log.w(TAG, "need_image reached dispatcher — VisionStep should have handled this")
            false
        }
        actionType == "swipe" -> {
            val direction = firstAction.direction ?: "up"
            val ok = OrionAccessibilityService.instance?.executor?.swipe(direction, screenW, screenH)?.success == true
            Log.i(TAG, "swipe direction=$direction → $ok")
            if (ok) postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
            ok
        }
        actionType == "press_home" -> {
            val homeOk = OrionAccessibilityService.instance?.executor?.pressHome()?.success == true
            Log.i(TAG, "press_home → $homeOk")
            if (homeOk && targetApp.isNotBlank()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetApp)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launchIntent != null) {
                    Log.i(TAG, "press_home: relaunching $targetApp")
                    startActivity(launchIntent)
                } else {
                    Log.w(TAG, "press_home: no launch intent for $targetApp")
                }
            }
            if (homeOk) postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
            homeOk
        }
        else -> {
            val action = firstAction
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
                        pendingAutoSelectText = action.text
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
            } else if (action.type == "type_text" && action.text != null) {
                val exec = OrionAccessibilityService.instance?.executor
                val success = exec?.typeTextFocused(action.text) ?: false
                if (success) {
                    Log.i(TAG, "type_text via focused node text='${action.text}'")
                    lastSuccessfulAction = "Typed '${action.text}' into focused field"
                    retryContext = ""
                    postActionCooldownUntil = System.currentTimeMillis() + POST_ACTION_DELAY_MS
                    pendingAutoSelectText = action.text
                } else {
                    Log.w(TAG, "type_text focused fallback failed — no input-focused node")
                    retryContext = "CORRECTION: Previous attempt selected nodeIndex=${nodeIdx?.plus(1)} " +
                        "nodeText=\"${action.nodeText}\" but neither was found in the accessibility tree. " +
                        "The exact available nodes are listed above. Pick the nodeIndex that best matches the goal."
                }
                success
            } else {
                retryContext = "CORRECTION: Previous attempt selected nodeIndex=${nodeIdx?.plus(1)} " +
                    "nodeText=\"${action.nodeText}\" but neither was found in the accessibility tree. " +
                    "The exact available nodes are listed above. Pick the nodeIndex that best matches the goal."
                Log.w(TAG, "Tap resolution failed — nodeIdx=$nodeIdx nodeText=${action.nodeText}")
                val nodeListStr = nodes.mapIndexed { i, (text, _) -> "[${i+1}] \"$text\"" }.joinToString(", ")
                Log.w(TAG, "Resolution failed — available: $nodeListStr")
                false
            }
        }
    }
}
