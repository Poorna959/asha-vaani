#!/usr/bin/env bash
# Deploy ASHA Vaani Android Debug APK to a USB-connected Android device with adb reverse.
# Usage:
#   ./scripts/deploy_android.sh           # Install existing APK & launch
#   ./scripts/deploy_android.sh --build   # Rebuild app-debug.apk first, then install & launch
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
JAVA_21_HOME="${JAVA_HOME:-/home/poornachandra-s/.jdks/temurin-21}"
ADB_BIN="$(command -v adb || echo "/home/poornachandra-s/Android/Sdk/platform-tools/adb")"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "ERROR: adb not found. Install Android platform-tools or add adb to PATH." >&2
  exit 1
fi

if [[ "${1:-}" == "--build" || ! -f "$APK_PATH" ]]; then
  echo ">> Building app-debug.apk with JDK 21 ($JAVA_21_HOME)..."
  (cd "$REPO_ROOT/android" && JAVA_HOME="$JAVA_21_HOME" ./gradlew :app:assembleDebug)
fi

echo ">> Checking connected Android devices..."
DEVICE_COUNT=$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {count++} END {print count+0}')
if [[ "$DEVICE_COUNT" -eq 0 ]]; then
  echo "ERROR: No authorized Android device connected via USB."
  echo "  1. Enable Developer Options -> USB Debugging on your phone."
  echo "  2. Plug in USB cable and tap 'Allow USB debugging'."
  echo "  3. Re-run: ./scripts/deploy_android.sh"
  exit 1
fi

echo ">> Forwarding FastAPI port 8000 over USB (adb reverse)..."
"$ADB_BIN" reverse tcp:8000 tcp:8000

echo ">> Installing $APK_PATH ..."
"$ADB_BIN" install -r "$APK_PATH"

echo ">> Launching ASHA Vaani..."
"$ADB_BIN" shell am start -n com.ashavaani.app/.MainActivity

echo ""
echo "SUCCESS! ASHA Vaani is installed and running."
echo "Make sure the FastAPI backend is running on your laptop:"
echo "  cd $REPO_ROOT/backend && uv run uvicorn app.main:app --host 0.0.0.0 --port 8000"
