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
import java.io.File

class GemmaNpuEngine private constructor(private val context: Context) : InferenceEngine {

    private var engine: Engine? = null
    @Volatile private var initGeneration = 0
    @Volatile private var nativeLibsConfigured = false

    companion object {
        private const val TAG = "Orion.GemmaNPU"
        const val MODEL_FILENAME = "gemma-4-E2B-it_qualcomm_sm8750.litertlm"

        @Volatile private var instance: GemmaNpuEngine? = null

        fun getInstance(context: Context): GemmaNpuEngine =
            instance ?: synchronized(this) {
                instance ?: GemmaNpuEngine(context.applicationContext).also { instance = it }
            }
    }

    override fun isReady(): Boolean = engine != null
    override fun getDescription(): String = "Gemma [NPU]"

    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        cleanup()
        val generation = initGeneration
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        configureNativeRuntime(nativeLibDir)
        if (initGeneration != generation) return@withContext
        Log.i(TAG, "Initializing with model: $modelPath")
        try {
            val cfg = EngineConfig(
                modelPath = modelPath,
                backend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                visionBackend = Backend.NPU(nativeLibraryDir = nativeLibDir),
                maxNumImages = 1,
                cacheDir = context.cacheDir.path
            )
            val eng = Engine(cfg)
            eng.initialize()
            if (initGeneration != generation) { eng.close(); return@withContext }
            eng.createConversation(ConversationConfig()).close()
            if (initGeneration != generation) { eng.close(); return@withContext }
            engine = eng
            Log.i(TAG, "Gemma NPU engine ready")
        } catch (e: Exception) {
            Log.e(TAG, "Gemma NPU init failed: ${e.message}")
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
        previousAction: String,
        keyboardVisible: Boolean,
        focusedInputIndex: Int
    ): Pair<PerceptionResult, Plan> {
        val eng = engine ?: return LiteRTLMManager.fallbackResult("engine_not_ready")

        val imageFile = File(context.cacheDir, "gemma_npu_${System.currentTimeMillis()}.png")
        try {
            imageFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write temp image: ${e.message}")
            return LiteRTLMManager.fallbackResult("image_write_error")
        }

        val conv = eng.createConversation(ConversationConfig())
        return try {
            val prompt = buildPrompt(goal, nodes, screenWidth, screenHeight, appPackage, retryContext, previousAction, keyboardVisible, focusedInputIndex)
            val contents = Contents.of(
                Content.ImageFile(imageFile.absolutePath),
                Content.Text(prompt)
            )
            val sb = StringBuilder()
            conv.sendMessageAsync(contents).collect { msg ->
                sb.append(msg.extractText())
            }
            Log.d(TAG, "Raw response (${sb.length}ch): ${sb.take(300)}")
            LiteRTLMManager.parseResponse(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "perceiveAndPlan failed: ${e.message}")
            LiteRTLMManager.fallbackResult("error: ${e.message}")
        } finally {
            try { conv.close() } catch (_: Exception) {}
            imageFile.delete()
        }
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
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }

    private fun com.google.ai.edge.litertlm.Message.extractText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
}
