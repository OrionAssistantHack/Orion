package com.orion.inference

import android.content.Context
import android.system.Os
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.orion.core.Plan
import com.orion.core.PlanAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LiteRTLMManager private constructor(private val context: Context) {

    private var engine: Engine? = null
    @Volatile private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var activeBackend: String = "None"
    @Volatile private var nativeLibsConfigured = false

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        cleanup()
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val backends = listOf(
            BackendFactory("NPU") { Backend.NPU(nativeLibraryDir = nativeLibDir) },
            BackendFactory("GPU") { Backend.GPU() },
            BackendFactory("CPU") { Backend.CPU() }
        )
        initializeWithFallback(modelPath, backends, nativeLibDir)
    }

    fun startConversation() {
        val config = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT)
        )
        conversation?.close()
        conversation = engine?.createConversation(config)
    }

    fun sendAgentMessage(screenshotPath: String, prompt: String): Flow<String> {
        ensureConversation()
        val contents = Contents.of(
            Content.ImageFile(screenshotPath),
            Content.Text(prompt)
        )
        return conversation!!.sendMessageAsync(contents).map { msg -> msg.extractText() }
    }

    fun getActiveBackend(): String = activeBackend

    fun cleanup() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        activeBackend = "None"
    }

    private fun initializeWithFallback(
        modelPath: String,
        backends: List<BackendFactory>,
        nativeLibDir: String
    ) {
        var lastError: Exception? = null
        for (factory in backends) {
            try {
                initializeEngine(modelPath, factory, nativeLibDir)
                activeBackend = factory.name
                Log.i(TAG, "Engine initialized on ${factory.name}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Backend ${factory.name} failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("All backends failed")
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

    private fun ensureConversation() {
        if (conversation == null) startConversation()
    }

    private fun Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        private const val TAG = "LiteRTLMManager"
        private const val SYSTEM_PROMPT =
            "You are Orion, an AI agent that controls Android apps on behalf of the user. " +
            "You receive a screenshot of the current screen and a list of interactive UI nodes. " +
            "Always respond with a single valid JSON object and nothing else."

        @Volatile private var instance: LiteRTLMManager? = null

        fun getInstance(context: Context): LiteRTLMManager =
            instance ?: synchronized(this) {
                instance ?: LiteRTLMManager(context.applicationContext).also { instance = it }
            }

        fun buildPrompt(goal: String, nodeList: String, retryContext: String?): String {
            val retrySection = if (retryContext != null) "\nPrevious attempt failed: $retryContext\n" else ""
            return """
Goal: $goal
$retrySection
Visible UI nodes:
$nodeList

Respond ONLY with this JSON:
{
  "screenPhase": "UNKNOWN|HOME|SEARCH_INPUT|FARE_ESTIMATE|CONFIRMATION",
  "extractedData": {},
  "confidence": 0.0,
  "summaryForUser": "...",
  "actions": [
    {"type": "tap_node|type_text", "nodeIndex": 0, "nodeText": "...", "text": "...", "x": 0.0, "y": 0.0}
  ]
}
""".trimIndent()
        }

        fun parseResponse(raw: String): Plan {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start == -1 || end == -1) return Plan("Could not parse response", emptyList())
            return try {
                val jsonStr = raw.substring(start, end + 1)
                val summary = extractJsonString(jsonStr, "summaryForUser") ?: "Processing…"
                val actionsBlock = extractJsonArray(jsonStr, "actions")
                    ?: return Plan(summary, emptyList())
                val actions = splitJsonObjects(actionsBlock).map { obj ->
                    PlanAction(
                        type = extractJsonString(obj, "type") ?: "tap_node",
                        nodeText = extractJsonString(obj, "nodeText"),
                        nodeIndex = extractJsonInt(obj, "nodeIndex"),
                        x = extractJsonDouble(obj, "x")?.toFloat(),
                        y = extractJsonDouble(obj, "y")?.toFloat(),
                        text = extractJsonString(obj, "text")
                    )
                }
                Plan(summary, actions)
            } catch (e: Exception) {
                Plan("Parse error: ${e.message}", emptyList())
            }
        }

        /** Extract a string value for [key] from a flat JSON object string. */
        private fun extractJsonString(json: String, key: String): String? {
            val pattern = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            return pattern.find(json)?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                ?.replace("\\n", "\n")
                ?.ifEmpty { null }
        }

        /** Extract an integer value for [key] from a flat JSON object string. */
        private fun extractJsonInt(json: String, key: String): Int? {
            val pattern = Regex(""""$key"\s*:\s*(-?\d+)""")
            return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
        }

        /** Extract a double value for [key] from a flat JSON object string. */
        private fun extractJsonDouble(json: String, key: String): Double? {
            val pattern = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)""")
            return pattern.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
        }

        /**
         * Extract the raw content inside the first JSON array for [key].
         * Returns the content between [ and ] (exclusive).
         */
        private fun extractJsonArray(json: String, key: String): String? {
            val keyIdx = json.indexOf("\"$key\"")
            if (keyIdx == -1) return null
            val arrStart = json.indexOf('[', keyIdx)
            if (arrStart == -1) return null
            var depth = 0
            for (i in arrStart until json.length) {
                when (json[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return json.substring(arrStart + 1, i)
                    }
                }
            }
            return null
        }

        /**
         * Split a JSON array body (content between outer [ ]) into individual object strings.
         */
        private fun splitJsonObjects(arrayBody: String): List<String> {
            val objects = mutableListOf<String>()
            var depth = 0
            var start = -1
            for (i in arrayBody.indices) {
                when (arrayBody[i]) {
                    '{' -> {
                        if (depth == 0) start = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && start != -1) {
                            objects.add(arrayBody.substring(start, i + 1))
                            start = -1
                        }
                    }
                }
            }
            return objects
        }
    }

    private data class BackendFactory(val name: String, val create: () -> Backend)
}
