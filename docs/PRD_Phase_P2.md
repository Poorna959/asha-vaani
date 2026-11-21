# Product Requirements Document (PRD) — Phase P2
## Native Android Client (Kotlin + Jetpack Compose) — ASHA Vaani

**Project**: ASHA Vaani (Voice Field Register for ASHA Workers)  
**Target Milestone**: Phase P2 (Native Android App, Low-Latency PCM Audio Engine, Local-First Room DB, Outdoor Walk-Mode & Custom Register UI)  
**Hackathon**: Gnani Voice Hackathon — PS1 (Healthcare)  
**Backend Target**: Phase P1 FastAPI Orchestrator (`/ws/session` + `/api/*`, commit `4030817`)  

---

## 1. Executive Summary & Objective

Phase P2 delivers the **zero-typing, one-thumb outdoor Android app** that connects to the Phase P1 FastAPI voice orchestrator over full-duplex WebSockets (defaulting to `ws://127.0.0.1:8000/ws/session` via `adb reverse tcp:8000 tcp:8000`, with an editable server URL in Settings for Wi-Fi/tunnel demos).

Built specifically for rural ASHA workers walking between houses in bright sunlight and street/household noise, the app provides:
1. **Hardware-Accelerated Speech Layer**: 16kHz 16-bit mono PCM capture via `AudioRecord` (`VOICE_COMMUNICATION`) with Android hardware `AcousticEchoCanceler`, `NoiseSuppressor`, and `AutomaticGainControl`, paired with streaming `AudioTrack` playback and `< 150ms` barge-in flush on `barge_in_clear`.
2. **4-Tab One-Thumb Outdoor Navigation**: **Walk Mode** (Home) | **Registers** | **Follow-Ups** | **Visits**.
3. **Zero-Typing Walk-Mode**: Hands-free continuous listening (1-tap Start/Stop mic), 1-tap Indic language pill (`हिन्दी` | `ಕನ್ನಡ` | `தமிழ்` | `తెలుగు` | `Auto`), live structured visit/intake card with animated amber `FieldDiff` voice-correction highlights, and a collapsible **Simulator Quick-Test Drawer** when backend runs in `mock` mode.
4. **Urgent Red PHC Referral Modal Sheet**: Pops up immediately on `danger_escalation` with the localized PHC instruction, `"List-Only / Zero Diagnosis"` compliance badge, 1-tap **Call 108 / PHC** (`ACTION_DIAL`), and 1-tap **WhatsApp / SMS Share** (`ACTION_SEND`).
5. **Custom Registers (FR-7.1 – FR-7.6)**: `+ New Register` builder with preset field chips + custom fields, in-register name search, clean mobile `Field: Answer` patient profile screen (explicitly **no in-app Excel grid**), prominent **Add Patient by Voice** FAB (FR-7.2), 1-tap **Start Follow-Up Visit** (FR-7.6), optional manual edit fallback, and 1-tap `.xlsx`/`.csv` share export via Android `FileProvider`.
6. **Local-First Room DB**: Write-through cache mirroring registers, patients, visits, and follow-ups on every WebSocket save and REST refresh so all 4 tabs load instantaneously and survive transient network drops.

---

## 2. Technical Architecture & Build Constraints

| Component | Technology / Constraint |
| :--- | :--- |
| **Language & UI** | Kotlin 2.0 + Jetpack Compose (Material 3), Navigation Compose |
| **SDK Targets** | `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`, JDK 21 (`/home/poornachandra-s/.jdks/temurin-21`) |
| **Audio Capture** | `AudioRecord` (`MediaRecorder.AudioSource.VOICE_COMMUNICATION`, 16kHz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, 640-byte / 20ms frames) + `AcousticEchoCanceler` + `NoiseSuppressor` + `AutomaticGainControl` |
| **Audio Playback** | `AudioTrack` (`USAGE_VOICE_COMMUNICATION`, 16kHz, `CHANNEL_OUT_MONO`, `ENCODING_PCM_16BIT`, `MODE_STREAM`) with instant `flushImmediately()` (`pause()` → `flush()` → `play()`) on `barge_in_clear` |
| **Networking** | OkHttp 4 (WebSocket binary + JSON frames for `/ws/session` + REST client for `/api/*`), Kotlinx Serialization |
| **Persistence** | Room Database (`ksp`) with write-through local-first caching + SharedPreferences/DataStore for Backend URL, PHC number, and active language |
| **File Sharing** | Android `FileProvider` + `Intent.ACTION_SEND` for `.xlsx` and `.csv` register exports |

---

## 3. Navigation & Screen Specifications

### 3.1 Top-Level 4-Tab Bottom Bar
1. **Walk Mode** (Home — default tab)
2. **Registers** (Custom Registers list → Register detail & search → `Field: Answer` Patient Profile)
3. **Follow-Ups** (Village follow-up queue: Today / Escalated / Upcoming + 1-tap Mark Complete)
4. **Visits** (Saved home visits history with `All` / `PHC Escalated` filter chips)

### 3.2 Walk Mode Screen (Home)
* **Top Bar**: App title + compact **TTFA / Status Pill** (e.g., `28ms • MOCK` or `420ms • LIVE`, green/amber/red connection dot). Tapping opens the **Settings & Judge Telemetry Sheet** (editable Backend URL defaulting to `http://127.0.0.1:8000`, editable PHC/108 phone number, live hardware `AEC / NS / AGC` availability badges).
* **1-Tap Language Pill Row**: `हिन्दी` (`hi-IN`) | `ಕನ್ನಡ` (`kn-IN`) | `தமிழ்` (`ta-IN`) | `తెలుగు` (`te-IN`) | `Auto` (`indic-auto`). Emits `{"type": "language_change", "language_code": "..."}`.
* **Active Mode Banner**:
  - Routine Visit Mode (or pre-selected patient baseline banner when launched from FR-7.6 `"Start Follow-Up Visit"`).
  - Custom Register Voice Intake banner (when launched from FR-7.2 `"Add Patient by Voice"` inside a custom register, with a `"Back to Visits"` chip).
* **Live Structured Form Card**:
  - In Visit Mode: `Beneficiary`, `Visit Type` (`ANC`/`NEWBORN`/`IMMUNISATION`), `Weight (kg)`, `IFA Tablets`, `Vaccinations Due`, `Next Visit Date`, `Danger Flags`.
  - In Register Intake Mode: `Patient Name` + dynamic `Field: Answer` rows for all `expected_fields` of that register.
  - **Voice-Diff Animation**: Whenever `field_diff` arrives (e.g., `weight_kg: 3.2 → 2.8`), highlight the updated field in warm Amber (`#D97706`) with strikethrough old value → bold new value.
* **Live Transcript Strip**: Shows partial (italic) and final STT transcript + agent's spoken read-back text.
* **One-Thumb Mic Control**: Giant centered Start/Stop hands-free mic button with live RMS audio level pulse ring + quick `"Reset"` action.
* **Collapsible Simulator Quick-Test Drawer**: Automatically available when `/health` reports `gnani_mode == "mock"`, offering 1-tap demo utterance chips and a quick text-turn input box to test `/ws/session` without live Gnani keys.

### 3.3 Urgent Red PHC Referral Modal Sheet (PS1 Pillar #3)
* Triggered immediately on `danger_escalation` WebSocket event.
* High-contrast Crimson (`#DC2626`) header with alert code, canonical symptom name, and priority (`IMMEDIATE_EMERGENCY` / `HIGH`).
* Displays localized PHC referral instruction in the ASHA's active language.
* Explicit **"Canonical NHM List Only — No Diagnosis or Treatment Advice"** compliance badge.
* **Primary Actions**:
  - **Call 108 / PHC** (`Intent.ACTION_DIAL` — safe dialer handoff, no `CALL_PHONE` permission required).
  - **Share Referral via WhatsApp / SMS** (`Intent.ACTION_SEND` with formatted beneficiary name, flagged symptom, timestamp, and PHC referral text).
  - **Dismiss / Confirm Record Flagged** button.

### 3.4 Custom Registers Tab (FR-7.1 – FR-7.6)
* **Registers List**: Displays all custom registers with field count badges + **`+ New Register`** FAB.
* **`+ New Register` Bottom Sheet**: Register name, description, quick-add preset field chips (`Age`, `Blood Group`, `LMP`, `Parity`, `Medical History`, `Phone`, `House / Ward`), plus custom field name + type (`TEXT` | `NUMBER` | `DATE` | `BOOLEAN`) adder.
* **Register Detail Screen**:
  - Real-time in-register search bar (`GET /api/registers/{id}/patients?q=...` with local Room fallback).
  - Clean patient list cards (name, ID, category badge).
  - Top-bar **Export** menu (`Export Excel (.xlsx)` / `Export CSV (.csv)`) downloading from `/api/registers/{id}/export` and launching Android's native share sheet via `FileProvider`.
  - Prominent **`Add Patient by Voice`** FAB (sends `register_select` and navigates to Walk Mode in FR-7.2 intake mode) + secondary manual `+ Add` icon.
* **Patient Profile Screen (`Field: Answer` View)**:
  - Clean, high-contrast vertical `Field: Answer` cards (never a cramped spreadsheet grid).
  - Primary CTA: **`Start Follow-Up Visit`** (sends `patient_select` and switches to Walk Mode with baseline pre-loaded — FR-7.6).
  - Secondary CTA: **`Edit Fields`** modal for optional manual updates (`PATCH /api/patients/{id}`).

### 3.5 Follow-Ups & Visits Tabs
* **Follow-Ups Tab**: Grouped/filterable queue (`Pending` / `Escalated` / `Completed`) showing beneficiary name, due date, purpose, vaccines due, and 1-tap **Mark Complete** (`PATCH /api/follow-ups/{id}`) or **Start Visit** (`patient_select`).
* **Visits Tab**: Chronological list of saved home visits with `All` and `PHC Escalated` filter chips, showing weight, vaccines, IFA count, next visit date, and red danger-sign badge when `has_danger_sign == true`.

---

## 4. Visual Theme (Outdoor Daylight High-Contrast)

* **Primary**: Deep Clinical Teal (`#0F766E`)
* **Background / Surface**: Crisp Off-White (`#F8FAFC`) / Pure White (`#FFFFFF`) cards with high-contrast slate borders (`#CBD5E1`) for readability in direct sunlight
* **Voice-Diff Highlight**: Warm Amber (`#D97706` border / `#FEF3C7` surface tint)
* **Danger Escalation**: Urgent Crimson (`#DC2626` header / `#FEE2E2` surface)
* **Saved / Verified**: Emerald (`#059669`)

---

## 5. Phase P2 Ordered Implementation Issues

> UI/UX design tokens, one-thumb ergonomics, haptics, and screen blueprints are locked in `docs/UI_UX_Design_Phase_P2.md`.

| Issue | Scope | Verification |
| :--- | :--- | :--- |
| **P2-01 (#9)** | Android Gradle Kotlin DSL scaffold (`android/`), Material 3 Outdoor Daylight theme (`Theme.kt`, `SunlitCard`, `HapticCue`), 4-tab Navigation shell with Follow-Ups badge, `FileProvider`, and Kotlinx Serialization protocol models | `./gradlew :app:compileDebugKotlin` succeeds |
| **P2-02 (#10)** | Low-latency 16kHz PCM audio engine (`PcmAudioCapturer` with hardware AEC/NS/AGC + normalized `rmsLevel`; `PcmAudioPlayer` with instant barge-in flush + `isPlaying` state) | Unit tests for PCM RMS/frame chunking & compile check |
| **P2-03 (#11)** | Full-duplex OkHttp `/ws/session` client (`ConnectionState` offline resilience), REST API client, and local-first Room DB write-through repository | Unit tests for protocol JSON parsing, URL building & Room entities |
| **P2-04 (#12)** | Walk-Mode Home screen (native language pills, live visit/intake form with animated amber `FieldDiff`, 4-bar `"Speak to interrupt"` waveform, 80dp RMS mic dock + haptics, mock simulator drawer, urgent Crimson PHC Referral Modal Sheet, Settings & Judge Telemetry sheet) | Unit tests for `WalkModeViewModel` state transitions & barge-in/escalation handling |
| **P2-05 (#13)** | Custom Registers tab (`+ New Register` builder, sticky name search, clean `Field: Answer` patient profile with zero Excel grid, FR-7.2 voice intake FAB, FR-7.6 follow-up launch, `.xlsx`/`.csv` `FileProvider` share export) | Unit tests for `RegistersViewModel` & export helper |
| **P2-06 (#14)** | Visits History tab (`All` / `PHC Escalated`), Follow-Up Queue tab (`PHC Urgent` pinned + live nav badge), complete unit test suite, verified `app-debug.apk` build & `README.md` setup guide | `./gradlew :app:assembleDebug :app:testDebugUnitTest` passes cleanly |
| **P2-07 (#15)** | End-to-end live backend integration (`adb reverse`), physical Android device deployment, hardware AEC/NS/AGC check, offline Room resilience test, and live Gnani/Gemini hackathon cutover | Live phone smoke-test passes all PS1 pillars + FR-7 flows |
