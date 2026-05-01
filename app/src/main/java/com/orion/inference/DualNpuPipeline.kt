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
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.orion.core.PerceptionResult
import com.orion.core.Plan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        val conv = eng.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2)))
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            val imageBytes = stream.toByteArray()
            suspendCancellableCoroutine { cont ->
                val sb = StringBuilder()
                conv.sendMessageAsync(
                    Contents.of(listOf(
                        Content.ImageBytes(imageBytes),
                        Content.Text("Describe all interactive UI elements visible on this Android app screenshot. List every button, text field, search bar, card, label, and navigation element with their exact text content."),
                    )),
                    object : MessageCallback {
                        override fun onMessage(msg: com.google.ai.edge.litertlm.Message) { sb.append(msg.toString()) }
                        override fun onDone() { cont.resume(sb.toString()) }
                        override fun onError(t: Throwable) { cont.resumeWithException(t) }
                    },
                    emptyMap(),
                )
                cont.invokeOnCancellation { conv.cancelProcess() }
            }
        } finally {
            try { conv.close() } catch (_: Exception) {}
        }
    }

    private suspend fun sendTextMessage(prompt: String, eng: Engine): String {
        val conv = eng.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2)))
        return try {
            suspendCancellableCoroutine { cont ->
                val sb = StringBuilder()
                conv.sendMessageAsync(
                    Contents.of(Content.Text(prompt)),
                    object : MessageCallback {
                        override fun onMessage(msg: com.google.ai.edge.litertlm.Message) { sb.append(msg.toString()) }
                        override fun onDone() { cont.resume(sb.toString()) }
                        override fun onError(t: Throwable) { cont.resumeWithException(t) }
                    },
                    emptyMap(),
                )
                cont.invokeOnCancellation { conv.cancelProcess() }
            }
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
  "screenPhase": "<UNKNOWN|HOME|SEARCH_INPUT|FARE_ESTIMATE|CONFIRMATION>",
  "extractedData": {"price": "...", "eta": "...", "service": "..."},
  "confidence": 0.0,
  "summaryForUser": "<one sentence: what action is being taken and why>",
  "actions": [
    {"type": "tap_node", "nodeIndex": <1-based>, "nodeText": "<exact text>"}
    OR
    {"type": "type_text", "nodeIndex": <1-based index of the input field>, "nodeText": "<exact text of field>", "text": "<text to type>"}
  ]
}
Use empty actions array if no action is needed. nodeIndex must be a valid index from the clickable elements list above.

Action type rules:
- Use tap_node for: buttons, links, cards, navigation elements, and search placeholders — anything that may open a new screen or focus an input when tapped.
- Use type_text ONLY when a keyboard is already visible AND a text field is actively focused and ready to receive input."""
    }

    @Synchronized
    private fun configureNativeRuntime(nativeLibDir: String) {
        if (nativeLibsConfigured) return
        Os.setenv("LD_LIBRARY_PATH", nativeLibDir, true)
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeLibDir;/system/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/dsp", true)
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
