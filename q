[33m32bb7a6[m[33m ([m[1;36mHEAD[m[33m -> [m[1;32mfix/gemmaBrain[m[33m, [m[1;31morigin/fix/gemmaBrain[m[33m)[m used gemma 4 E4B and fixed tap loop of destination address after typing
[33m6aeb6f1[m[33m ([m[1;32mmain[m[33m)[m Revert "added keyboard detection and prompt engg."
[33m4c31e9e[m fixing premature screen captures after actions
[33mc479773[m fix retry not firing
[33mfc332a0[m added keyboard detection and prompt engg.
[33m8f885a1[m Prompt Engineering
[33m9ec8003[m gemma to qwen added
[33me91c8c8[m Fix set_text
[33mfc57176[m Make gemma gpu default
[33m98f0785[m feat: add Gemma NPU single-model option as default inference mode
[33me2ba757[m Improved the on-device Gemma 2B prompt so the agent reliably picks tap_node vs type_text by adding keyboardvisible boolean and prompt engineering
[33me144959[m prompt engineer bad prompt, uber no work
[33m22039ef[m fix: use Content.ImageFile and Flow API for NPU inference matching working sample
[33m29e68e0[m fix: await IO cleanup and GC before loading next inference engine
[33mae80a40[m fix: unload and reload engine when inference mode is switched
[33m66fadc0[m fix: resolve Float/Double and override-default-value compile errors
[33mb5c038c[m feat: add dual-NPU inference pipeline (FastVLM vision + Gemma NPU text)
[33mdb12a58[m fix: abort model init if cleanup is called mid-initialization
[33md640908[m fix: debounce accessibility events so capture fires on settled window
[33m67f4e08[m fix: skip inference on stale node list during window transitions
[33md3b902d[m Fix compile warnings
[33m102f912[m fix: clarify tap vs type_text in prompt and add tap-before-type fallback
[33mf512eb4[m fix: use DFS text search for type_text instead of stale bounds lookup
[33m8da021a[m fix compile issue
[33m3fb2796[m feat: prefer GPU model with backend label in inference log
[33m7b6d42e[m Fix log tags
[33maa0b119[m fix: NPU vision fallback to GPU/CPU and add agent loop diagnostics
[33m176a0cd[m fix: resolve unit test runtime failures
[33mc218142[m feat: wire screen capture permission flow and manifest permissions
[33m20194f9[m feat: replace agent loop with HandlerThread capture and perceiveAndPlan
[33m8594362[m feat: add perceiveAndPlan with ImageBytes inference and json response parsing
[33mc128b29[m fix: update mock executor to match AutomationExecutor interface
[33m2e2550e[m fix: AccessibilityAutomationExecutor — DFS tapNode, dispatchText, isScreenSecure pixel scan
[33m4c00043[m fix: AutomationExecutor interface — tapNode(String), isScreenSecure(Bitmap)
[33m18c0c33[m fix: align Models.kt — restore OrionMode, fix TapTarget/PlanAction shapes
[33m4065b74[m something
[33m6c26cc0[m[33m ([m[1;31morigin/feature/android-agent[m[33m)[m fix: remove duplicate launcher icon for FlagSecureActivity
[33m91ece58[m chore: add Orion-prefixed TAG constants and lifecycle log calls to all classes
[33m837cdce[m chore: prefix all logcat tags with Orion for easier filtering
[33m714a61e[m fix: reset isLoopRunning in finally block and guard sendAgentMessage against null engine
[33md4651b8[m fix: guard model re-init on rotation and show error for missing target app
[33m6b03de8[m feat: add MainActivity and FlagSecureActivity with ViewBinding
[33m26a1c15[m feat: add XML layouts and drawables
[33m19ff254[m fix: @Volatile fields and bitmap recycle in ScreenCaptureService
[33m53ece3b[m feat: add ScreenCaptureService with frame capture, FLAG_SECURE detection, and agent loop
[33mb710a79[m fix: thread-safety in OrionAccessibilityService (ConcurrentHashMap, @Volatile, local capture)
[33m7dcf528[m feat: add OrionAccessibilityService with 500ms debounced capture trigger
[33m2fb03a3[m fix: make conversation @Volatile in LiteRTLMManager
[33m0ff1554[m feat: add LiteRTLMManager with NPU/GPU/CPU fallback and response parser
[33m900f3cf[m feat: add AccessibilityAutomationExecutor
[33m1d39b9c[m feat: add AutomationExecutor interface and MockAutomationExecutor
[33mc6bbeed[m Revert "feat: add staging product flavor and remove FlagSecureActivity launcher icon"
[33m938cce3[m feat: add staging product flavor and remove FlagSecureActivity launcher icon
[33m6a70ca9[m feat: add core data models
[33mcee1f64[m chore: add AndroidManifest, resources, QNN native libs
[33m76df117[m chore: fix dependency versions and build properties
[33me1b7348[m chore: initialize gradle build
[33me71deb6[m chore: initial commit
