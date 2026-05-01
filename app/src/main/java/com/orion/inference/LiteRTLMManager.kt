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
import com.orion.core.PlanAction
import com.orion.core.ScreenPhase
import com.orion.core.TapTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Orion.LiteRTLMManager"

private fun buildPrompt(
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

    return """${retryPrefix}${historyPrefix}${if (goal.isNotBlank()) "User goal: $goal\n\n" else ""}${ctx}Analyze this Android app screenshot. What single element should be tapped to make progress?$nodeList

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
- Use tap_node for: buttons, links, cards, navigation elements, and search placeholders (e.g. "Where to?", "Search here") — anything that may open a new screen or focus an input when tapped.
- Use type_text ONLY when: a keyboard is already visible on screen AND a text field is actively focused and ready to receive input (e.g. a blinking cursor is visible). Do NOT use type_text on a placeholder that requires a tap to open first."""
}

class LiteRTLMManager private constructor(private val context: Context) : InferenceEngine {

    private var engine: Engine? = null
    @Volatile private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var activeBackend: String = "None"
    @Volatile private var nativeLibsConfigured = false
    @Volatile private var initGeneration = 0
    private var lastModelPath: String? = null

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        lastModelPath = modelPath
        cleanup()
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val backends = listOf(
            BackendFactory("NPU") { Backend.NPU(nativeLibraryDir = nativeLibDir) },
            BackendFactory("GPU") { Backend.GPU() },
            BackendFactory("CPU") { Backend.CPU() }
        )
        val generation = initGeneration
        initializeWithFallback(modelPath, backends, nativeLibDir, generation)
    }

    override fun isReady(): Boolean = engine != null

    fun getActiveBackend(): String = activeBackend

    override fun getDescription(): String = "Gemma [${activeBackend}]"

    override fun cleanup() {
        initGeneration++
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        activeBackend = "None"
    }

    override suspend fun perceiveAndPlan(
        bitmap: Bitmap,
        goal: String = "",
        nodes: List<Pair<String, Rect>> = emptyList(),
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        appPackage: String = "",
        retryContext: String = "",
        previousAction: String = ""
    ): Pair<PerceptionResult, Plan> {
        val eng = engine ?: return fallback("engine_null")
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "conversation close failed: ${e.message}")
        }
        conversation = try {
            val samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.2)
            eng.createConversation(ConversationConfig(samplerConfig = samplerConfig))
        } catch (e: Exception) {
            Log.e(TAG, "createConversation failed", e)
            return fallback("conv_create_failed")
        }
        val conv = conversation ?: return fallback("conv_null")

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val imageBytes = stream.toByteArray()
        Log.d(TAG, "Sending prompt (${imageBytes.size / 1024}KB image + ${buildPrompt(goal, nodes, screenWidth, screenHeight, appPackage, retryContext, previousAction).length}ch prompt)")

        return try {
            val response = suspendCancellableCoroutine { cont ->
                val sb = StringBuilder()
                conv.sendMessageAsync(
                    Contents.of(listOf(
                        Content.ImageBytes(imageBytes),
                        Content.Text(buildPrompt(goal, nodes, screenWidth, screenHeight, appPackage, retryContext, previousAction)),
                    )),
                    object : MessageCallback {
                        override fun onMessage(message: com.google.ai.edge.litertlm.Message) { sb.append(message.toString()) }
                        override fun onDone() { cont.resume(sb.toString()) }
                        override fun onError(throwable: Throwable) { cont.resumeWithException(throwable) }
                    },
                    emptyMap(),
                )
                cont.invokeOnCancellation { conv.cancelProcess() }
            }
            Log.d(TAG, "Raw response (${response.length}ch): ${response.take(300)}")
            parseResponse(response)
        } catch (e: Exception) {
            if (e.message?.contains("not implemented for backend") == true && activeBackend == "NPU") {
                Log.w(TAG, "NPU vision unsupported — reinitializing on GPU/CPU and retrying")
                val reinitialized = reinitializeWithoutNpu()
                if (reinitialized) {
                    return try {
                        perceiveAndPlan(bitmap, goal, nodes, screenWidth, screenHeight, appPackage, retryContext, previousAction)
                    } catch (e2: Exception) {
                        Log.e(TAG, "perceiveAndPlan() failed after fallback", e2)
                        fallback("error_after_fallback: ${e2.message}")
                    }
                }
            }
            Log.e(TAG, "perceiveAndPlan() failed", e)
            fallback("error: ${e.message}")
        }
    }

    private fun parseResponse(raw: String): Pair<PerceptionResult, Plan> =
        companion_parseResponse(raw)

    private fun fallback(reason: String): Pair<PerceptionResult, Plan> =
        PerceptionResult(ScreenPhase.UNKNOWN, emptyMap(), null, 0f, reason) to Plan("", emptyList())

    private suspend fun reinitializeWithoutNpu(): Boolean = withContext(Dispatchers.IO) {
        Log.w(TAG, "NPU vision not supported — falling back to GPU/CPU")
        try {
            cleanup()
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val gpuCpuBackends = listOf(
                BackendFactory("GPU") { Backend.GPU() },
                BackendFactory("CPU") { Backend.CPU() }
            )
            val gen = initGeneration
            initializeWithFallback(lastModelPath ?: return@withContext false, gpuCpuBackends, nativeLibDir, gen)
            Log.i(TAG, "Fallback init succeeded on $activeBackend")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Fallback reinit failed: ${e.message}")
            false
        }
    }

    private fun initializeWithFallback(
        modelPath: String,
        backends: List<BackendFactory>,
        nativeLibDir: String,
        generation: Int
    ) {
        var lastError: Exception? = null
        for (factory in backends) {
            if (initGeneration != generation) {
                Log.w(TAG, "Initialize aborted (generation mismatch) — cleanup was called")
                return
            }
            try {
                initializeEngine(modelPath, factory, nativeLibDir)
                if (initGeneration != generation) {
                    Log.w(TAG, "Initialize aborted after engine creation — closing leaked engine")
                    engine?.close()
                    engine = null
                    return
                }
                activeBackend = factory.name
                Log.i(TAG, "Engine initialized on ${factory.name}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Backend ${factory.name} failed: ${e.message}")
                lastError = e
            }
        }
        if (initGeneration == generation) {
            throw lastError ?: IllegalStateException("All backends failed")
        }
    }

    private fun initializeEngine(modelPath: String, factory: BackendFactory, nativeLibDir: String) {
        val modelFile = java.io.File(modelPath)
        require(modelFile.exists() && modelFile.canRead()) { "Model file not readable: $modelPath" }

        if (factory.name == "NPU") configureNativeRuntime(nativeLibDir)

        val visionBackend: Backend = when (factory.name) {
            "NPU" -> Backend.NPU(nativeLibraryDir = nativeLibDir)
            else -> Backend.CPU()
        }

        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = factory.create(),
            visionBackend = visionBackend,
            maxNumImages = 1,
            cacheDir = context.cacheDir.path
        )

        val eng = Engine(engineConfig)
        eng.initialize()
        eng.createConversation(ConversationConfig()).close()
        engine = eng
    }

    @Synchronized
    private fun configureNativeRuntime(nativeLibDir: String) {
        if (nativeLibsConfigured) return
        Os.setenv("LD_LIBRARY_PATH", nativeLibDir, true)
        Os.setenv(
            "ADSP_LIBRARY_PATH",
            "$nativeLibDir;/system/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/dsp",
            true
        )
        nativeLibsConfigured = true
    }

    companion object {
        @Volatile private var instance: LiteRTLMManager? = null

        fun getInstance(context: Context): LiteRTLMManager =
            instance ?: synchronized(this) {
                instance ?: LiteRTLMManager(context.applicationContext).also { instance = it }
            }

        internal fun parseResponse(raw: String): Pair<PerceptionResult, Plan> = companion_parseResponse(raw)

        internal fun fallbackResult(reason: String): Pair<PerceptionResult, Plan> =
            PerceptionResult(ScreenPhase.UNKNOWN, emptyMap(), null, 0f, reason) to Plan("", emptyList())
    }

    private data class BackendFactory(val name: String, val create: () -> Backend)
}

private fun companion_parseResponse(raw: String): Pair<PerceptionResult, Plan> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    val json = if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    return try {
        val obj = JSONObject(json)

        val phase = try {
            ScreenPhase.valueOf(obj.getString("screenPhase"))
        } catch (_: Exception) { ScreenPhase.UNKNOWN }

        val dataObj = obj.optJSONObject("extractedData")
        val extracted = buildMap<String, String> {
            dataObj?.keys()?.forEach { k -> put(k, dataObj.getString(k)) }
        }

        val tapObj = obj.optJSONObject("tapTarget")
        val tap = tapObj?.let { TapTarget(it.getString("nodeText"), null) }

        val confidence = obj.optDouble("confidence", 0.0).toFloat()
        val perception = PerceptionResult(phase, extracted, tap, confidence, json)

        val summary = obj.optString("summaryForUser", "")
        val arr = obj.optJSONArray("actions") ?: JSONArray()
        val actions = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            PlanAction(
                type = a.optString("type", "none"),
                nodeIndex = if (a.has("nodeIndex")) a.getInt("nodeIndex") - 1 else null,
                nodeText = a.optString("nodeText").takeIf { it.isNotEmpty() },
                x = if (a.has("x")) a.getDouble("x").toFloat() else null,
                y = if (a.has("y")) a.getDouble("y").toFloat() else null,
                text = a.optString("text").takeIf { it.isNotEmpty() },
                app = a.optString("app").takeIf { it.isNotEmpty() },
                fallbackUri = a.optString("fallbackUri").takeIf { it.isNotEmpty() },
                waitForPhase = a.optString("waitForPhase").takeIf { it.isNotEmpty() },
            )
        }
        perception to Plan(summary, actions)
    } catch (e: JSONException) {
        Log.w(TAG, "Failed to parse response JSON: $json")
        PerceptionResult(ScreenPhase.UNKNOWN, emptyMap(), null, 0f, raw) to Plan(raw.take(120), emptyList())
    }
}
