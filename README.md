# ASHA Vaani — Voice Field Register for ASHA Workers

> **Gnani Voice Hackathon · PS1 (Healthcare)**  
> Zero-typing Indic voice field register that turns an ASHA worker's spoken home-visit notes (ANC, Newborn/HBNC, Immunisation) into a structured visit record, follow-up queue, and urgent real-time PHC escalation before she reaches the next house — working reliably in household and street noise.

---

## Architecture Overview

- **Speech Capture & Synthesis (Mandatory Gnani v3.0 Stack)**:
  - **Gnani Prisma v3.0 (STT)** (`wss://api.vachana.ai/stt/v3/stream`) with 23 clinical code-mix keyterms (`"TT injection"`, `"ANC checkup"`, `"IFA goli"`, `"HBNC"`, `"PHC"`, etc.) and automatic `1001 idle_timeout` reconnect.
  - **Gnani Timbre v3.0 (TTS)** (`wss://api.vachana.ai/api/v1/tts`, voice `Lakshmi`) with decimal-safe sentence chunking and a **Parallel Danger-Sign TTS Fast-Path** that starts localized PHC referral audio immediately without blocking on LLM extraction.
- **Custom Async Orchestrator**: FastAPI full-duplex WebSocket hub (`/ws/session`) with adaptive noise-floor VAD (plus optional Silero ONNX), sub-150ms barge-in cancellation (`barge_in_clear`), and granular speech-layer telemetry (`ttfa_ms`, `stt_latency_ms`, `llm_latency_ms`, `tts_ttfa_ms`, `barge_in_cutoff_ms`, `noise_floor_rms`, `active_llm_model`, `danger_fast_path`).
- **Clinical Extraction & Read-Back**:
  - **Groq (`qwen/qwen3.8-27b`)** with automatic HTTP `429`/`503` failover onto **`openai/gpt-oss-20b`**, optional **Gemini 2.5 Flash**, and deterministic zero-downtime offline code-mix fallback (`RuleBasedVisitExtractor`).
  - Exact voice-correction `FieldDiff` highlighting (`"no, weight is 2.8 kilo"` → Warm Amber diff) and **mandatory read-back before save**.
- **3-Layer Deterministic Danger-Sign & Responsible AI Guardrails**:
  - Strict matching against our canonical 12-item NHM list ([docs/danger_signs.json](docs/danger_signs.json)) across **Hindi, Kannada, Tamil, Telugu, and English** — including zero-dependency ISCII-aligned cross-script transliteration for phonetic Indic STT outputs and a post-LLM normalized note scan.
  - Pre-TTS **FR-6 Compliance Firewall** locks active danger signs to localized PHC referral scripts and blocks any diagnosis, medicine name, dosage, or prompt-injection attempt before audio reaches Timbre v3.0 (`100%` pass rate across our 113-case multilingual red-team suite).
- **Real-Time PHC Supervisor Escalation Console (`/dashboard`)**:
  - Zero-build FastAPI web dashboard fed by Server-Sent Events (`GET /api/events/stream`) that flashes Crimson danger alerts live on the laptop the instant an ASHA worker flags a danger sign on Android, with 1-click **Acknowledge PHC Referral** (`PATCH /api/visits/{id}/acknowledge`).
- **Native Android Outdoor Client (`android/`)**:
  - Kotlin 2.0 + Jetpack Compose Material 3 Outdoor Daylight UI, **Supabase Gmail & Email Authentication** (`LoginScreen` + OAuth deep-link `ashavaani://auth/callback` + 1-tap offline field demo access), 16kHz PCM `AudioRecord`/`AudioTrack` with hardware `AEC`/`NS`/`AGC` + `AcousticEchoGate`, **Room v2 Offline Pending-Sync Outbox** (`PendingSyncEntity` + FIFO replay on reconnect), bounded WebSocket reconnect backoff (`1s → 2s → 4s → 8s`), expanded Judge Telemetry HUD, and **Custom First-Visit Registers** (`Field : Answer` profiles, phonetic duplicate patient upsert, live search, `.xlsx`/`.csv` `FileProvider` export, FR-7.2 voice intake, and FR-7.6 follow-up baseline continuity).
- **Web Product & Android APK Distribution Portal (`web/`)**:
  - React 19 + TypeScript + Vite web portal with **Supabase Gmail & Email Sign-In (`/login`)**, interactive Walk-Mode preview, 12-item NHM danger-sign reference, 5-language APK installation guide, QR code & SHA-256 verification, and direct Android APK download (`web/public/downloads/asha-vaani-v0.3.0.apk`), ready for Vercel deployment (`web/vercel.json`).

---

## Quick Start (1-Command Hackathon Demo)

### 1. Install Backend Dependencies & Configure `.env`
```bash
cd backend
uv sync
cp .env.example .env
```
Configure `backend/.env` (never committed to git):
```env
# Live Gnani + Groq Hackathon Mode
MOCK_GNANI=false
GNANI_API_KEY=<your-gnani-api-key>
LLM_PROVIDER=groq
GROQ_API_KEY=<your-groq-api-key>
GROQ_MODEL=qwen/qwen3.8-27b
```
*(Leave `MOCK_GNANI=true` and keys empty for 100% offline simulator mode.)*

### 2. Launch Full Demo Stack in One Command
Plug in the Android phone via USB (optional) and run:
```bash
./scripts/start_demo.sh
```
*(Pass `--install` to also install the latest `app-debug.apk` and launch the app.)*

This automatically configures `adb reverse tcp:8000 tcp:8000`, starts FastAPI on port `8000`, verifies `/health`, and prints your live URLs:
- **PHC Supervisor Escalation Dashboard**: `http://127.0.0.1:8000/dashboard`
- **Health Telemetry**: `http://127.0.0.1:8000/health`

---

## Automated Verification & Judge Benchmark

### Backend Test Suite (`293` tests)
```bash
cd backend
uv run pytest -q
```

### Automated PS1 Judge Scorecard Benchmark
```bash
# Deterministic in-process mock benchmark (TTFA, fast-path, barge-in, 12x5 NHM recall, adversarial compliance)
uv run --project backend python scripts/run_judge_benchmark.py

# Or benchmark against a live running backend:
uv run --project backend python scripts/run_judge_benchmark.py --url http://127.0.0.1:8000
```

### Native Android Build & JVM Tests (`37` unit tests)
```bash
cd android
JAVA_HOME=/home/poornachandra-s/.jdks/temurin-21 ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
```
Output APK: `android/app/build/outputs/apk/debug/app-debug.apk`  
Deploy to physical phone: `./scripts/deploy_android.sh` (or `./scripts/deploy_android.sh --build`).

### Web Distribution Portal (`web/` · Vercel Ready)
```bash
cd web
npm run check          # TypeScript + 30 Vitest tests + production Vite build into web/dist/
npm run release:debug  # Publish latest ../android debug APK into web/public/downloads/ & web/public/release.json
```

---

## Key Endpoints

| Method & Path | Purpose |
| :--- | :--- |
| `WS /ws/session` | Full-duplex 16kHz PCM audio + JSON state stream (`transcript`, `form_update`, `register_intake_update`, `danger_escalation`, `barge_in_clear`, `visit_saved`, `patient_saved`, `metrics`) |
| `GET /dashboard` | Real-Time PHC Supervisor Escalation Web Console (live SSE alerts + 1-click PHC acknowledge) |
| `GET /api/events/stream` | Server-Sent Events (SSE) live stream of `danger_escalation`, `visit_saved`, `visit_acknowledged`, `patient_saved` |
| `GET /api/dashboard/summary` | Atomic KPI + recent live alerts + visits + follow-ups snapshot |
| `PATCH /api/visits/{id}/acknowledge` | Mark an escalated visit acknowledged by the PHC Medical Officer |
| `GET /health` | Server health, active Gnani/LLM modes (`groq` / `gemini` / `offline_fallback`), models, danger-sign count |
| `POST /api/registers` | Create a custom register with dynamic fields |
| `GET /api/registers/{id}/patients?q=` | List or search patients by name inside a register |
| `GET /api/patients/{id}/profile` | Mobile-friendly `Field: Answer` patient profile |
| `GET /api/patients/{id}/followup-context` | Baseline prerequisites for follow-up voice continuity |
| `GET /api/registers/{id}/export?format=xlsx\|csv` | 1-tap Excel (`.xlsx`) or UTF-8 CSV export |
| `GET /api/visits?only_danger=false` | Logged home visits (filterable by danger-sign escalations) |
| `GET /api/follow-ups` / `PATCH /api/follow-ups/{id}` | Smart follow-up queue management |
| `GET /api/danger-signs` | Canonical 12-item NHM danger-sign catalog |

---

## Documentation
- [PS1 Project Rules (AGENTS.md)](AGENTS.md)
- [Phase P1 PRD (Backend Orchestrator)](docs/PRD_Phase_P1.md)
- [Phase P2 PRD (Native Android Client)](docs/PRD_Phase_P2.md)
- [Phase P3 PRD (Judge-Hardening, Offline Sync & PHC Dashboard)](docs/PRD_Phase_P3.md)
- [Phase P2 Material 3 UI/UX Design Spec](docs/UI_UX_Design_Phase_P2.md)
- [Hackathon Live Demo & Physical Device Checklist](docs/Hackathon_Demo_Checklist.md)
- [Canonical 12-Item NHM Danger-Sign List](docs/danger_signs.json)
- [Gnani Prisma v3.0 & Timbre v3.0 Protocol Reference](docs/gnani_protocol.md)
