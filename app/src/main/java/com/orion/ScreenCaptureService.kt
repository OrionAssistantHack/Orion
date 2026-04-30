package com.orion

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.orion.automation.AccessibilityAutomationExecutor
import com.orion.core.ExecutionResult
import com.orion.core.Plan
import com.orion.core.PlanAction
import com.orion.inference.LiteRTLMManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var executor: AccessibilityAutomationExecutor? = null
    @Volatile private var currentGoal = ""
    @Volatile private var isLoopRunning = false
    @Volatile private var retryCount = 0
    @Volatile private var lastError: String? = null

    companion object {
        private const val CHANNEL_ID = "OrionCapture"
        private const val NOTIFICATION_ID = 1
        private const val MAX_RETRIES = 3
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_APP = "app"

        fun startCapture(context: Context, resultCode: Int, data: Intent, goal: String, app: String) {
            context.startForegroundService(
                Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_DATA, data)
                    putExtra(EXTRA_GOAL, goal)
                    putExtra(EXTRA_APP, app)
                }
            )
        }

        fun stopCapture(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        fun isFrameSecure(bitmap: Bitmap): Boolean {
            val xs = listOf(0, bitmap.width / 4, bitmap.width / 2, 3 * bitmap.width / 4, bitmap.width - 1)
            val ys = listOf(0, bitmap.height / 4, bitmap.height / 2, 3 * bitmap.height / 4, bitmap.height - 1)
            var nearBlack = 0
            for (x in xs) for (y in ys) {
                val p = bitmap.getPixel(x, y)
                if ((p shr 16 and 0xFF) < 30 && (p shr 8 and 0xFF) < 30 && (p and 0xFF) < 30) nearBlack++
            }
            return nearBlack >= 24
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        currentGoal = intent.getStringExtra(EXTRA_GOAL) ?: ""

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        setupImageReader()

        OrionAccessibilityService.instance?.onCaptureRequested = { nodes ->
            if (!isLoopRunning) runAgentCycle(nodes)
        }

        return START_NOT_STICKY
    }

    private fun setupImageReader() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        imageReader = ImageReader.newInstance(bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OrionCapture", bounds.width(), bounds.height(),
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun captureFrameToFile(): String? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val bmp = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888
            ).also { it.copyPixelsFromBuffer(plane.buffer) }
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()
            if (isFrameSecure(cropped)) {
                cropped.recycle()
                return null
            }
            val file = File(cacheDir, "orion_frame.png")
            FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
            cropped.recycle()
            file.absolutePath
        } finally {
            image.close()
        }
    }

    private fun runAgentCycle(nodes: List<AccessibilityNodeInfo>) {
        isLoopRunning = true
        serviceScope.launch {
            val screenshotPath = withContext(Dispatchers.IO) { captureFrameToFile() }
                ?: run { isLoopRunning = false; return@launch }

            val manager = LiteRTLMManager.getInstance(this@ScreenCaptureService)
            val accessService = OrionAccessibilityService.instance
                ?: run { isLoopRunning = false; return@launch }
            if (executor == null) executor = AccessibilityAutomationExecutor(accessService)

            val nodeList = nodes.mapIndexed { i, n ->
                "$i: ${n.text?.toString() ?: n.contentDescription?.toString() ?: "(no label)"}"
            }.joinToString("\n")
            val prompt = LiteRTLMManager.buildPrompt(currentGoal, nodeList, retryContext())

            val responseBuilder = StringBuilder()
            manager.sendAgentMessage(screenshotPath, prompt).collect { token ->
                responseBuilder.append(token)
            }
            val plan = LiteRTLMManager.parseResponse(responseBuilder.toString())
            withContext(Dispatchers.IO) { executePlan(plan, nodes) }
            isLoopRunning = false
        }
    }

    private fun retryContext(): String? = if (retryCount > 0) lastError else null

    private fun executePlan(plan: Plan, nodes: List<AccessibilityNodeInfo>) {
        for (action in plan.actions) {
            val result = executeAction(action, nodes)
            if (result.success) {
                retryCount = 0
                lastError = null
            } else {
                retryCount++
                lastError = result.errorCode
                if (retryCount >= MAX_RETRIES) { retryCount = 0; lastError = null }
            }
        }
    }

    private fun executeAction(action: PlanAction, nodes: List<AccessibilityNodeInfo>): ExecutionResult {
        val exec = executor ?: return ExecutionResult(false, errorCode = "NO_EXECUTOR")
        return when (action.type) {
            "tap_node" -> {
                val node = action.nodeIndex?.takeIf { it < nodes.size }?.let { nodes[it] }
                    ?: action.nodeText?.let { t -> nodes.firstOrNull { it.text?.toString() == t } }
                when {
                    node != null -> exec.tapNode(node)
                    action.x != null && action.y != null -> exec.dispatchTap(action.x, action.y)
                    else -> ExecutionResult(false, errorCode = "NODE_NOT_FOUND")
                }
            }
            "type_text" -> {
                val text = action.text ?: return ExecutionResult(false, errorCode = "NO_TEXT")
                val node = action.nodeIndex?.takeIf { it < nodes.size }?.let { nodes[it] }
                    ?: return ExecutionResult(false, errorCode = "NO_NODE_FOR_TEXT")
                exec.typeText(node, text)
            }
            else -> ExecutionResult(false, errorCode = "UNKNOWN_ACTION")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        OrionAccessibilityService.instance?.onCaptureRequested = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Orion is running")
            .setContentText("AI agent active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
}
