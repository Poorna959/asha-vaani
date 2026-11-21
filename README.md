# ASHA Vaani — Voice Field Register for ASHA Workers

> **Gnani Voice Hackathon · PS1 (Healthcare)**  
> Zero-typing Indic voice field register that turns an ASHA worker's spoken home-visit notes (ANC, Newborn/HBNC, Immunisation) into a structured visit record, follow-up queue, and urgent PHC escalation before she reaches the next house — working reliably in household and street noise.

---

## Architecture Overview

- **Speech Capture & Synthesis**: WebSocket streaming via **Gnani Prisma v3.0 (STT)** with 23 clinical code-mix keyterms (`"TT injection"`, `"ANC checkup"`, `"IFA goli"`, `"HBNC"`, `"PHC"`, etc.) and **Gnani Timbre v3.0 (TTS)** with decimal-safe sentence chunking for sub-800ms Time-to-First-Audio (TTFA).
- **Custom Async Orchestrator**: FastAPI full-duplex WebSocket hub (`/ws/session`) with adaptive noise-floor VAD (plus optional Silero ONNX), sub-150ms barge-in cancellation (`barge_in_clear`), and real-time `ttfa_ms` telemetry.
- **Clinical Extraction & Read-Back**: **Gemini 2.5 Flash** (`thinking_budget=0`, Pydantic structured schema) with deterministic offline code-mix fallback (`RuleBasedVisitExtractor`), exact voice-correction `FieldDiff` highlighting (`"no, weight is 2.8 kilo"`), and mandatory read-back before save.
- **Deterministic Danger-Sign & Responsible AI Guardrails**:
  - Strict matching against our curated 12-item NHM list ([docs/danger_signs.json](docs/danger_signs.json)) across **Hindi, Kannada, Tamil, Telugu, and English**.
  - Pre-TTS **FR-6 Compliance Firewall** locks active danger signs to localized PHC referral scripts and blocks any diagnosis or medicine/dosage advice under adversarial prompts.
- **Custom First-Visit Registers & Export**: Create named custom registers with dynamic fields, search patients by name, view clean mobile `Field: Answer` profiles, export to `.xlsx` or `.csv`, and tap any patient to carry baseline prerequisites directly into routine voice follow-ups.

---

## Quick Start (Backend)

### 1. Install Dependencies
```bash
cd backend
uv sync
```

### 2. Configure Environment
Copy `.env.example` to `.env`:
```bash
cp .env.example .env
```
- **Offline / Zero-Credit Simulator Mode (default)**: `MOCK_GNANI=true`
- **Live Gnani API Platform Mode (Hackathon Day)**:
  ```env
  MOCK_GNANI=false
  GNANI_API_KEY=<your-gnani-api-key>
  GEMINI_API_KEY=<your-gemini-api-key>
  ```

### 3. Run the Backend Server
```bash
cd backend
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```
Verify server health:
```bash
curl http://127.0.0.1:8000/health
```

### 4. Run the Automated Test Suite
```bash
cd backend
uv run pytest -v
```

---

## Quick Start (Native Android App — Phase P2)

Built with **Kotlin 2.0 + Jetpack Compose Material 3** (`compileSdk = 34`, `minSdk = 26`, JDK 21), hardware `AcousticEchoCanceler` + `NoiseSuppressor` + `AutomaticGainControl`, full-duplex OkHttp `/ws/session` PCM streaming, and local-first Room DB caching.

### 1. Build Debug APK & Run Android Unit Tests
Make sure `android/local.properties` points to your Android SDK (`sdk.dir=/home/poornachandra-s/Android/Sdk`) and run with **JDK 21**:
```bash
cd android
JAVA_HOME=/home/poornachandra-s/.jdks/temurin-21 ./gradlew :app:assembleDebug :app:testDebugUnitTest
```
Output APK: `android/app/build/outputs/apk/debug/app-debug.apk`

### 2. Deploy to Physical Android Phone (USB `adb reverse`)
1. Enable **USB Debugging** on the Android phone and connect via USB.
2. Start the FastAPI backend on your laptop (`uv run uvicorn app.main:app --host 0.0.0.0 --port 8000`).
3. Forward port `8000` over USB and install the APK:
   ```bash
   adb devices
   adb reverse tcp:8000 tcp:8000
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   ```
4. Launch **ASHA Vaani** on the phone — it defaults to `http://127.0.0.1:8000` (editable anytime by tapping the top-right **TTFA / Status pill** for LAN Wi-Fi demos).

---

## Key API Endpoints

| Method & Path | Purpose |
| :--- | :--- |
| `WS /ws/session` | Full-duplex 16kHz PCM audio + JSON state stream (`transcript`, `form_update`, `register_intake_update`, `danger_escalation`, `barge_in_clear`, `visit_saved`, `patient_saved`, `metrics`) |
| `GET /health` | Server health, active Gnani mode (`mock`/`live`), models, danger-sign count |
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
- [Phase P2 Material 3 UI/UX Design Spec](docs/UI_UX_Design_Phase_P2.md)
- [Canonical 12-Item NHM Danger-Sign List](docs/danger_signs.json)
- [Gnani Prisma v3.0 & Timbre v3.0 Protocol Reference](docs/gnani_protocol.md)
