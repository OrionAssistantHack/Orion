#!/usr/bin/env bash
# Build, install, launch the Orion agent-poc app on the connected device/emulator,
# and start tailing logs. Ctrl+C to stop logs (app keeps running).
set -euo pipefail
cd "$(dirname "$0")"

PKG="com.orion"
ACTIVITY="com.orion.OnboardingActivity"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"

# Pick a device. Priority: $1 arg > $ANDROID_SERIAL > only attached device > error.
if [[ -n "${1:-}" ]]; then SERIAL="$1"; shift
elif [[ -n "${ANDROID_SERIAL:-}" ]]; then SERIAL="$ANDROID_SERIAL"
else
  mapfile -t DEVS < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
  if [[ ${#DEVS[@]} -eq 0 ]]; then
    echo "No device/emulator detected. Connect one with USB debugging or start an emulator."; exit 1
  elif [[ ${#DEVS[@]} -gt 1 ]]; then
    echo "Multiple devices attached:"; printf '  %s\n' "${DEVS[@]}"
    echo "Pass one as arg: ./run_linux.sh <serial>   or set ANDROID_SERIAL"; exit 1
  fi
  SERIAL="${DEVS[0]}"
fi
export ANDROID_SERIAL="$SERIAL"
echo "--- using device: $SERIAL ---"

if ! ./gradlew installDebug 2>&1 | tee /tmp/orion-install.log; then
  if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" /tmp/orion-install.log; then
    echo "--- signature mismatch — uninstalling existing $PKG and retrying ---"
    "$ADB" -s "$SERIAL" uninstall "$PKG" || true
    ./gradlew installDebug
  else
    exit 1
  fi
fi

"$ADB" -s "$SERIAL" shell am start -n "$PKG/$ACTIVITY"

"$ADB" -s "$SERIAL" logcat -c
echo "--- tailing logs (Ctrl+C to stop) ---"
"$ADB" -s "$SERIAL" logcat \
  Orion.MainActivity:V \
  Orion.A11y:V \
  Orion.Executor:V \
  Orion.ScreenCapture:V \
  Orion.LiteRTLMManager:V \
  Orion.GemmaNPU:V \
  Orion.DualNPU:V \
  AndroidRuntime:E \
  System.err:W \
  *:S
