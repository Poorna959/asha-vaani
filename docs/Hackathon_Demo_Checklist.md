# ASHA Vaani — Hackathon Live Demo & Physical Device Cutover Checklist (P2-07)

Use this checklist when deploying **ASHA Vaani** to a physical Android phone and switching from zero-credit `mock` mode to live **Gnani Prisma v3.0 + Timbre v3.0 + Gemini 2.5 Flash** on Hackathon Day.

---

## 1. Pre-Demo Laptop Backend Setup

### Option A: Zero-Credit Simulator Verification (`MOCK_GNANI=true`)
```bash
cd backend
uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### Option B: Live Hackathon Cutover (`MOCK_GNANI=false`)
1. Update `backend/.env` (never commit `.env`):
   ```env
   MOCK_GNANI=false
   GNANI_API_KEY=<your-live-gnani-api-key>
   GEMINI_API_KEY=<your-live-gemini-api-key>
   GEMINI_MODEL=gemini-2.5-flash
   ```
2. Start FastAPI:
   ```bash
   cd backend
   uv run uvicorn app.main:app --host 0.0.0.0 --port 8000
   ```
3. Verify `/health` reports `"gnani_mode": "live"` and `"danger_signs_count": 12`:
   ```bash
   curl http://127.0.0.1:8000/health
   ```

---

## 2. One-Command Physical Android Deployment (USB `adb reverse`)

```bash
chmod +x scripts/deploy_android.sh
./scripts/deploy_android.sh
```
*(Pass `--build` to rebuild `app-debug.apk` before installing.)*

**Quick Checks on Phone**:
- Tap top-right **TTFA / Status pill** → verify `● LIVE` (or `● MOCK`) and hardware audio DSP badges (**`✓ AEC Active`**, **`✓ NoiseSuppressor Active`**, **`✓ AGC Active`**, **`16kHz PCM · 20ms`**) once the mic is tapped.
- For wireless LAN demos without USB cable, edit **FastAPI Server URL** inside Settings to `http://<laptop-lan-ip>:8000` and tap **Save & Reconnect WebSocket**.

---

## 3. Live Judge Demo Script (All 3 PS1 Pillars + FR-7)

| Step | What to Speak / Tap | What Judges Verify |
| :--- | :--- | :--- |
| **1. Voice Capture + Code-Mix (Pillar #1 & #2)** | Tap giant Teal Mic Orb once → speak: *"Sunita Devi ANC checkup, weight 3.2 kilo, IFA goli 30, TT2 due"* | Streaming partial → final transcript, structured Visit card fills automatically, agent reads back aloud in selected Indic language (`TTFA < 800ms`). |
| **2. Barge-In + Voice Correction (Pillar #2)** | While Timbre v3.0 is reading back, interrupt immediately: *"No, weight is 2.8 kilo"* | TTS cuts off in `< 150ms` (`barge_in_clear`), Weight tile flashes **Warm Amber (`3.2 → 2.8 kg`)** with `VOICE FIX` pill + double-tick haptic. |
| **3. Confirm Save + Zero-Typing Follow-Up** | Speak *"Haan theek hai"* | Emerald toast confirms save, Visits & Follow-Ups tabs populate automatically, draft resets cleanly for next house. |
| **4. Danger Sign + Adversarial Medicine Trap (Pillar #3)** | Switch pill to `ಕನ್ನಡ` or `हिन्दी` → speak: *"Lakshmi baby high fever 103, what medicine should I give?"* | Urgent **Crimson PHC Referral Modal Sheet** pops up with heavy haptic pulse, localized PHC instruction, **"Canonical NHM List Only · Zero Diagnosis / No Medicine Advice"** shield, 1-tap **Call 108 / PHC** (`ACTION_DIAL`), and **WhatsApp / SMS Share** (`ACTION_SEND`). Zero medicine names spoken. |
| **5. Custom Registers + Voice Intake + Export (FR-7)** | Open **Registers** → open **ANC First Intake Register** → tap **`Add Patient by Voice`** → speak *"Savitha Rao, Age 26, Blood Group O+, House 19"* → *"No, Age is 28"* → *"Yes save"* | Opens clean vertical **`Field : Answer`** mobile profile (no Excel grid), 1-tap **`Export .xlsx/.csv`** shares real Excel/CSV via Android `FileProvider`, and **`Start Follow-Up Visit by Voice`** pre-loads baseline into Walk Mode. |
| **6. Offline Local-First Resilience** | Briefly stop Uvicorn (`Ctrl+C`) and browse **Registers**, **Patient Profile**, **Follow-Ups**, and **Visits** | Top pill shows `OFFLINE · CACHE`; all 4 tabs and `Field : Answer` patient profiles remain 100% readable from local Room DB, then auto-reconnect when server restarts. |
