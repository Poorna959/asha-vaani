#!/usr/bin/env bash
# One-command Hackathon Demo Launcher for ASHA Vaani (Issue #20 / P3-05).
#
# What it does:
#   1. Checks for a USB-connected Android device and configures `adb reverse tcp:8000 tcp:8000`.
#   2. Optionally installs & launches the debug APK when `--install` is passed.
#   3. Launches the FastAPI orchestrator (`uv run uvicorn app.main:app`) on port 8000.
#   4. Polls `/health` until ready and prints live judge URLs (including `/dashboard`).
#
# Usage:
#   ./scripts/start_demo.sh             # Start live backend + adb reverse
#   ./scripts/start_demo.sh --install   # Also install latest APK & launch app on phone
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
APK_PATH="$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
ADB_BIN="$(command -v adb || echo "/home/poornachandra-s/Android/Sdk/platform-tools/adb")"
PORT="${PORT:-8000}"
DO_INSTALL=false

for arg in "$@"; do
  case "$arg" in
    --install) DO_INSTALL=true ;;
    *) echo "Unknown option: $arg (supported: --install)" >&2; exit 1 ;;
  esac
done

# 1. Android USB bridge (adb reverse) if device is attached
if [[ -x "$ADB_BIN" ]]; then
  DEVICE_COUNT=$("$ADB_BIN" devices 2>/dev/null | awk 'NR>1 && $2=="device" {c++} END {print c+0}')
  if [[ "$DEVICE_COUNT" -gt 0 ]]; then
    echo ">> Android device detected ($DEVICE_COUNT connected). Configuring adb reverse tcp:$PORT -> tcp:$PORT..."
    "$ADB_BIN" reverse "tcp:$PORT" "tcp:$PORT"
    if [[ "$DO_INSTALL" == true ]]; then
      if [[ -f "$APK_PATH" ]]; then
        echo ">> Installing $APK_PATH ..."
        "$ADB_BIN" install -r "$APK_PATH"
        "$ADB_BIN" shell am start -n com.ashavaani.app/.MainActivity >/dev/null
      else
        echo "WARN: APK not found at $APK_PATH — run ./scripts/deploy_android.sh --build first."
      fi
    fi
  else
    echo ">> No USB Android device detected (wireless LAN mode still available)."
  fi
fi

# 2. Launch FastAPI orchestrator with clean shutdown trap
UVICORN_PID=""
cleanup() {
  echo ""
  echo ">> Shutting down ASHA Vaani backend..."
  if [[ -n "$UVICORN_PID" ]] && kill -0 "$UVICORN_PID" 2>/dev/null; then
    kill "$UVICORN_PID" 2>/dev/null || true
    wait "$UVICORN_PID" 2>/dev/null || true
  fi
}
trap cleanup INT TERM EXIT

echo ">> Starting ASHA Vaani FastAPI Orchestrator on 0.0.0.0:$PORT ..."
(
  cd "$BACKEND_DIR"
  exec uv run uvicorn app.main:app --host 0.0.0.0 --port "$PORT"
) &
UVICORN_PID=$!

# 3. Poll /health until ready
HEALTH_JSON=""
for _ in $(seq 1 30); do
  if ! kill -0 "$UVICORN_PID" 2>/dev/null; then
    echo "ERROR: Uvicorn exited prematurely." >&2
    exit 1
  fi
  if HEALTH_JSON="$(curl -fsS "http://127.0.0.1:$PORT/health" 2>/dev/null)"; then
    break
  fi
  sleep 0.5
done

if [[ -z "$HEALTH_JSON" ]]; then
  echo "ERROR: Backend did not become healthy within 15 seconds." >&2
  exit 1
fi

LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"

echo ""
echo "========================================================================"
echo "  ASHA VAANI — LIVE HACKATHON DEMO READY (Gnani Voice Hackathon PS1)"
echo "========================================================================"
echo "  Health Check:              http://127.0.0.1:$PORT/health"
echo "  PHC Supervisor Dashboard:  http://127.0.0.1:$PORT/dashboard"
echo "  Interactive API Docs:      http://127.0.0.1:$PORT/docs"
if [[ -n "$LAN_IP" ]]; then
  echo "  Wireless Phone URL (LAN):  http://$LAN_IP:$PORT"
fi
echo "  Active Backend Config:     $HEALTH_JSON"
echo "========================================================================"
echo "  Press Ctrl+C to stop the demo server."
echo ""

wait "$UVICORN_PID"
