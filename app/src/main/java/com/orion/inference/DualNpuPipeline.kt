package com.orion.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.system.Os
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.orion.core.PerceptionResult
import com.orion.core.Plan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

private fun com.google.ai.edge.litertlm.Message.extractText(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

class DualNpuPipeline private constructor(private val context: Context) : InferenceEngine {

    private var fastVlmEngine: Engine? = null
    private var gemmaEngine: Engine? = null
    @Volatile private var initGeneration = 0
    @Volatile private var nativeLibsConfigured = false

    companion object {
        private const val TAG = "Orion.DualNPU"
        const val FASTVLM_MODEL = "FastVLM-0.5B.qualcomm.sm8750.litertlm"
        const val GEMMA_NPU_MODEL = "gemma-4-E2B-it_qualcomm_sm8750.litertlm"

        @Volatile private var instance: DualNpuPipeline? = null

        fun getInstance(context: Context): DualNpuPipeline =
            instance ?: synchronized(this) {
                instance ?: DualNpuPipeline(context.applicationContext).also { instance = it }
            }
    }

    override fun isReady(): Boolean = fastVlmEngine != null && gemmaEngine != null

    override fun getDescription(): String = "FastVLM+Gemma [NPU]"

    suspend fun initialize(fastVlmPath: String, gemmaNpuPath: String) = withContext(Dispatchers.IO) {
        cleanup()
        val generation = initGeneration
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        configureNativeRuntime(nativeLibDir)

        if (initGeneration != generation) return@withContext

        fastVlmEngine = tryCreateEngine(fastVlmPath, nativeLibDir, "FastVLM", generation) ?: return@withContext
        if (initGeneration != generation) { fastVlmEngine?.close(); fastVlmEngine = null; return@withContext }

        gemmaEngine = tryCreateEngine(gemmaNpuPath, nativeLibDir, "GemmaNPU", generation) ?: return@withContext
        if (initGeneration != generation) { gemmaEngine?.close(); gemmaEngine = null; return@withContext }

        Log.i(TAG, "DualNpuPipeline ready — FastVLM + Gemma on NPU")
    }

    private fun tryCreateEngine(modelPath: String, nativeLibDir: String, name: String, generation: Int): Engine? {
        return try {
            val cfg = EngineConfig(
                modelPath = modelPath,
                backend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                visionBackend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                maxNumImages = 1,
                cacheDir = context.cacheDir.path
            )
            val eng = Engine(cfg)
            eng.initialize()
            if (initGeneration != generation) { eng.close(); return null }
            Log.i(TAG, "$name engine ready")
            eng
        } catch (e: Exception) {
            Log.e(TAG, "$name engine failed: ${e.message}")
            null
        }
    }

    override suspend fun perceiveAndPlan(
        bitmap: Bitmap,
        goal: String,
        nodes: List<Pair<String, Rect>>,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        retryContext: String,
        previousAction: String
    ): Pair<PerceptionResult, Plan> {
        val fvEng = fastVlmEngine ?: return LiteRTLMManager.fallbackResult("fastvlm_not_ready")
        val gmEng = gemmaEngine ?: return LiteRTLMManager.fallbackResult("gemma_not_ready")

        // Step 1: FastVLM — describe the screen
        val fastVlmStart = System.currentTimeMillis()
        val screenDescription = try {
            describeScreen(bitmap, fvEng)
        } catch (e: Exception) {
            Log.e(TAG, "FastVLM failed: ${e.message}")
            return LiteRTLMManager.fallbackResult("fastvlm_error: ${e.message}")
        }
        val fastVlmMs = System.currentTimeMillis() - fastVlmStart
        Log.i(TAG, "FastVLM [NPU] ${fastVlmMs}ms → ${screenDescription.take(120)}")

        // Step 2: Gemma NPU — reason about action using text only
        val gemmaStart = System.currentTimeMillis()
        val result = try {
            val prompt = buildDualPrompt(screenDescription, goal, nodes, screenWidth, screenHeight, appPackage, retryContext, previousAction)
            val response = sendTextMessage(prompt, gmEng)
            LiteRTLMManager.parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Gemma NPU failed: ${e.message}")
            LiteRTLMManager.fallbackResult("gemma_error: ${e.message}")
        }
        val gemmaMs = System.currentTimeMillis() - gemmaStart
        Log.i(TAG, "Gemma [NPU] ${gemmaMs}ms | Total pipeline: ${fastVlmMs + gemmaMs}ms | ${result.second.summaryForUser}")

        return result
    }

    private suspend fun describeScreen(bitmap: Bitmap, eng: Engine): String {
        // Save bitmap to a temp file — NPU requires Content.ImageFile, not Content.ImageBytes
        val imageFile = java.io.File(context.cacheDir, "fastvlm_input_${System.currentTimeMillis()}.png")
        try {
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write temp image: ${e.message}")
            throw e
        }

        val conv = eng.createConversation(ConversationConfig())
        return try {
            val contents = Contents.of(
                Content.ImageFile(imageFile.absolutePath),
                Content.Text("Describe all interactive UI elements visible on this Android app screenshot. List every button, text field, search bar, card, label, and navigation element with their exact text content.")
            )
            val sb = StringBuilder()
            conv.sendMessageAsync(contents).collect { msg ->
                sb.append(msg.extractText())
            }
            sb.toString()
        } finally {
            try { conv.close() } catch (_: Exception) {}
            imageFile.delete()
        }
    }

    private suspend fun sendTextMessage(prompt: String, eng: Engine): String {
        val conv = eng.createConversation(ConversationConfig())
        return try {
            val sb = StringBuilder()
            conv.sendMessageAsync(prompt).collect { msg ->
                sb.append(msg.extractText())
            }
            sb.toString()
        } finally {
            try { conv.close() } catch (_: Exception) {}
        }
    }

    private fun buildDualPrompt(
        screenDescription: String,
        goal: String,
        nodes: List<Pair<String, Rect>>,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        retryContext: String,
        previousAction: String
    ): String {
        val nodeList = if (nodes.isNotEmpty()) {
            "\n\nClickable elements on screen:\n" +
            nodes.mapIndexed { i, (text, rect) ->
                val label = if (text.length > 60) text.take(60) + "…" else text
                "[${i+1}] \"$label\" at (${rect.centerX()}, ${rect.centerY()})"
            }.joinToString("\n")
        } else ""

        val ctx = buildString {
            if (screenWidth > 0) append("Screen: ${screenWidth}x${screenHeight}px. ")
            if (appPackage.isNotBlank()) append("App: $appPackage. ")
        }

        val retryPrefix = if (retryContext.isNotBlank()) "IMPORTANT - $retryContext\n\n" else ""
        val historyPrefix = if (previousAction.isNotBlank()) "Previous action: $previousAction — you are now on a NEW screen. Continue navigating toward the goal.\n\n" else ""

        return """${retryPrefix}${historyPrefix}${if (goal.isNotBlank()) "User goal: $goal\n\n" else ""}${ctx}Screen visual description: $screenDescription$nodeList

Reply ONLY with a single valid JSON object, no markdown:
{
  "keyboardVisible": <true if you see a QWERTY layout on the screen, false otherwise>,
  "action": {
    "type": "<tap_node|type_text|none>",
    "nodeIndex": <1-based index from the clickable elements list, or null>,
    "nodeText": "<exact text of the node, or null>",
    "text": "<text to type if type_text, otherwise null>"
  },
  "screenPhase": "<UNKNOWN|HOME|SEARCH_INPUT|FARE_ESTIMATE|CONFIRMATION>",
  "extractedData": {"price": "...", "eta": "...", "service": "..."},
  "confidence": 0.0,
  "summaryForUser": "<one sentence: what action is being taken and why>"
}
Emit exactly ONE action — never a list, never multiple. If no action is needed, set "action.type" to "none". nodeIndex must be a valid index from the clickable elements list above.

Action type rules:
- If "keyboardVisible" is true, action.type MUST be "type_text" or "none" — tap_node is forbidden.
- For type_text: set nodeIndex to the 1-based index of the focused input field in the clickable elements list, and set text to the string to type.
- Otherwise use tap_node for buttons, links, cards, and input placeholders."""
    }

    @Synchronized
    private fun configureNativeRuntime(nativeLibDir: String) {
        if (nativeLibsConfigured) return
        Os.setenv("LD_LIBRARY_PATH", nativeLibDir, true)
        Os.setenv("ADSP_LIBRARY_PATH", nativeLibDir, true)
        nativeLibsConfigured = true
    }

    override fun cleanup() {
        initGeneration++
        try { gemmaEngine?.close() } catch (_: Exception) {}
        try { fastVlmEngine?.close() } catch (_: Exception) {}
        gemmaEngine = null
        fastVlmEngine = null
    }
}
