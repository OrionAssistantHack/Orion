<p align="center">
  <img src="orion-logo.png" alt="Orion" width="160"/>
</p>

# Orion

> *"The power of the sun, in the palm of my hand."*

**Orion is Computer Use for mobile.** It is an on-device agentic AI framework for Android that gives a small, quantized vision-language model full control of your phone — perceiving the screen, reasoning about the next step, and executing native gestures — so your phone can finally act on the context only it has.

The agent operates *the apps you already have*, with the accounts you are already signed into and the preferences you have already set. There is no cloud API to wire up, no integration to maintain, and your screen never has to leave the device.

A worked example: every morning we used to open Uber, Lyft, and Waymo by hand to compare cab fares from Caltrain to work. Orion now does that for us, end-to-end, on the phone.

For the longer story behind the project — inspiration, what we built, what we learned, and what is next — see [TEAM_DESCRIPTION.md](TEAM_DESCRIPTION.md).

## Architecture at a glance

Orion is a closed loop running entirely on-device:

- **Perception** — `MediaProjection` captures the current screen; a vision-language model interprets the pixels (no brittle accessibility tree).
- **Reasoning** — a quantized **Gemma** model running on the **Qualcomm Hexagon NPU** via **LiteRT-LM** chooses the next action given the goal, the current screen, and a compact memory of recent steps.
- **Action** — the Android **Accessibility Service** dispatches taps, swipes, and text input to drive the device exactly the way a human would.
- **Runtime** — LiteRT-LM is bundled with Qualcomm QNN HTP backends ([app/src/main/jniLibs/arm64-v8a/](app/src/main/jniLibs/arm64-v8a/)). As part of this work we also ported **Qwen 2.5-VL** to LiteRT-LM and contributed custom export support for that model family upstream.

Everything important — the screen, the model, the decisions — stays on the device.

## Team

| Name | Email |
| --- | --- |
| Aneesh Bhattacharya | aneeshbhattacharya19@gmail.com |
| Saksham Jain | sakshamjain2703@gmail.com |
| Shubham Gupta | shubhamgupto@gmail.com |
| Prateek Sengar | prateeksengar2000@gmail.com |
| Ajit Chourasia | ajitkc@gmail.com |

## Platform & requirements

Orion is an Android application targeting devices with a Qualcomm Snapdragon NPU (Hexagon, QNN HTP v79). It has been developed and validated on a **Samsung Galaxy S25 Ultra**.

**Device requirements**
- Android 7.0 (API 24) or newer; built against API 36
- `arm64-v8a` ABI
- Qualcomm Snapdragon SoC with Hexagon NPU (required for on-device Gemma inference via LiteRT-LM + QNN)
- ~3 GB free storage for the model artifact
- Ability to grant the Accessibility, MediaProjection (screen capture), and "Display over other apps" permissions
- The **Gemma LiteRT-LM model artifact** — `gemma-4-E4B-it.litertlm` from [huggingface.co/litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/blob/main/gemma-4-E4B-it.litertlm), pushed to `/data/local/tmp/` on the device

**Build host requirements**
- JDK 17
- Android SDK with `platform-tools` (the run script expects `adb` at `$ANDROID_HOME/platform-tools/adb`, defaulting to `~/Android/Sdk/platform-tools/adb`)
- The bundled Gradle wrapper (`./gradlew`) — no system Gradle install needed
- A USB-debug-enabled device or running emulator

## Setup from scratch

1. **Install JDK 17.**
   ```bash
   # Ubuntu / Debian
   sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
   # macOS (Homebrew)
   brew install openjdk@17
   ```
2. **Install the Android SDK + platform tools.** Either install Android Studio, or install the command-line tools and run `sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"`. Set `ANDROID_HOME` to your SDK root (e.g. `~/Android/Sdk`).
3. **Clone this repository.**
   ```bash
   git clone https://github.com/OrionAssistantHack/agent-poc.git
   cd agent-poc
   ```
4. **Connect a Snapdragon Android device** with USB debugging enabled (`adb devices` should list it), or boot a compatible emulator. Note: a non-Snapdragon device or generic emulator can install the app and exercise the UI, but the on-device Gemma engine will not run — Orion is meaningful only on hardware with a Hexagon NPU.
5. **Download the Gemma LiteRT-LM model and push it to the device.** Orion loads `gemma-4-E4B-it.litertlm` from `/data/local/tmp/` on the device.
   ```bash
   # 1. Download (requires accepting the model's license on Hugging Face)
   #    https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/blob/main/gemma-4-E4B-it.litertlm
   wget https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm

   # 2. Push to the device
   adb push gemma-4-E4B-it.litertlm /data/local/tmp/

   # 3. Verify
   adb shell ls -lh /data/local/tmp/gemma-4-E4B-it.litertlm
   ```
6. **Build the debug APK** to verify the toolchain:
   ```bash
   ./gradlew assembleDebug
   ```

## Run & usage

The fastest path is the included helper script, which builds, installs, launches, and tails filtered logs:

```bash
./run_linux.sh                 # auto-picks the only attached device
./run_linux.sh <adb-serial>    # or pass a serial when multiple are attached
ANDROID_SERIAL=<serial> ./run_linux.sh
```

If you prefer the manual flow:

```bash
./gradlew installDebug
adb shell am start -n com.orion/com.orion.OnboardingActivity
adb logcat Orion.*:V *:S
```

**On first launch**, the onboarding flow walks you through three permissions:

1. **Accessibility Service** — enable "Orion Agent" under Settings → Accessibility. This is how the agent dispatches taps, swipes, and text.
2. **Screen Capture (MediaProjection)** — accept the system dialog so the agent can perceive the current screen.
3. **Display over other apps** — required for the Orion picture-in-picture overlay that shows agent state.

**Driving the agent.** Open Orion, type a goal in plain English (e.g. *"Book the cheapest cab from Caltrain to my office"*), and confirm. Orion brings up a small floating overlay, switches to the relevant apps, and runs the perceive → reason → act loop until the goal is reached or you cancel. Stop it any time by tapping the overlay or disabling the accessibility service.

## Tests

Unit tests (JVM, Robolectric) cover the goal parser, app registry, comparison session, inference manager, automation executor, and core model classes. Instrumented tests cover activity wiring.

```bash
# Unit tests — no device needed
./gradlew testDebugUnitTest

# Instrumented tests — needs a connected device or emulator
./gradlew connectedDebugAndroidTest
```

A clean `BUILD SUCCESSFUL` from `testDebugUnitTest` is sufficient to verify the dev setup before flashing to a device.

## Notes

- **Edge-first by design.** Perception, reasoning, and action all run on the phone. The only network usage is whatever the third-party apps Orion drives do on their own — Orion itself does not stream your screen or your goals to a server.
- **Why a Snapdragon device.** The Gemma planner runs on the Hexagon NPU through QNN HTP v79; the bundled `.so` files in [app/src/main/jniLibs/arm64-v8a/](app/src/main/jniLibs/arm64-v8a/) target that backend.
- **Hardened against screen-capture leaks.** A `FlagSecureActivity` and the accessibility-driven flow are written so secrets typed into other apps are not exfiltrated through the projection stream beyond what the agent itself observes locally.
- **Power user knob.** `run_linux.sh` filters logcat to the Orion tags (`Orion.MainActivity`, `Orion.A11y`, `Orion.Executor`, `Orion.ScreenCapture`, `Orion.LiteRTLMManager`, `Orion.GemmaNPU`, `Orion.DualNPU`) so you can watch the agent loop in real time.
- **Roadmap.** Better long-horizon planning, richer memory, broader app coverage, and stronger safety primitives. We intend to open-source Orion as the *OpenClaw for mobile*.

## References

- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — the on-device LLM runtime Orion uses for Gemma inference.
- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) — reference Android integrations for AI Edge models.
- [Qualcomm QNN SDK](https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk) — Hexagon NPU runtime backing LiteRT-LM on Snapdragon.
- [Gemma open models](https://ai.google.dev/gemma) — the small, on-device LLM family used as the agent planner.
- [Qwen 2.5-VL](https://github.com/QwenLM/Qwen2.5-VL) — vision-language model we ported to LiteRT-LM for on-device perception.
- [Android Accessibility Service](https://developer.android.com/guide/topics/ui/accessibility/service) — the API used to dispatch gestures and text.
- [Android MediaProjection](https://developer.android.com/reference/android/media/projection/MediaProjection) — the API used to capture screen frames.

## License

Released under the [MIT License](LICENSE).
