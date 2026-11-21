# ASHA Vaani — Hackathon Live Demo & Physical Device Cutover Checklist

Use this checklist when running **ASHA Vaani** live on your physical Android phone (**OPPO CPH2553**) alongside the laptop **PHC Supervisor Escalation Dashboard** with **Gnani Prisma v3.0 + Timbre v3.0 (`Lakshmi`) + Groq (`qwen/qwen3.8-27b` / `openai/gpt-oss-20b` failover)**.

---

## 1. One-Command Demo Startup (Laptop + USB Phone)

1. Verify `backend/.env` has live credentials (never commit `.env`):
   ```env
   MOCK_GNANI=false
   GNANI_API_KEY=<your-live-gnani-api-key>
   LLM_PROVIDER=groq
   GROQ_API_KEY=<your-live-groq-api-key>
   GROQ_MODEL=qwen/qwen3.8-27b
   ```
2. Plug in your Android phone over USB and launch the full stack in **one command**:
   ```bash
   ./scripts/start_demo.sh --install
   ```
   *(Omitting `--install` keeps the installed APK and simply runs `adb reverse tcp:8000 tcp:8000` + starts FastAPI.)*
3. Open the **PHC Supervisor Escalation Dashboard** on your laptop browser (side-by-side for judges):
   - **`http://127.0.0.1:8000/dashboard`**
   - Verify the top-right badge reads **`● LIVE STREAM`** and **`prisma-v3.0 · timbre-v3.0 · groq (qwen/qwen3.8-27b)`**.

**Quick Checks on Phone**:
- Tap top-right **TTFA / Status pill** → verify `● LIVE`, active hardware DSP badges (**`✓ AEC`**, **`✓ NoiseSuppressor`**, **`✓ AGC`**, **`16kHz PCM · 20ms`**), expanded telemetry tiles (`TTFA`, `STT`, `LLM`, `TTS TTFA`, `BARGE-IN`, `NOISE RMS`, `FAST-PATH`), and **`Outbox: Synced`**.
- For wireless LAN demos without USB cable, edit **FastAPI Server URL** in Settings to `http://<laptop-lan-ip>:8000` and tap **Save & Reconnect WebSocket**.

---

## 2. Pre-Stage 30-Second Automated Verification

Before walking up to the judges' table, run:
```bash
uv run --project backend python scripts/run_judge_benchmark.py
```
Confirm **ALL 7 rows show `PASS`** (`60/60` 12-sign × 5-language recall, `0%` negation FP, `100%` adversarial compliance, `fast_path=True`, barge-in `< 150ms`).

---

## 3. Live Judge Demo Script (All 3 PS1 Pillars + FR-7 + PHC Console)

| Step | What to Speak / Tap | What Judges Verify |
| :--- | :--- | :--- |
| **1. Voice Capture + Code-Mix (Pillar #1 & #2)** | Tap giant Teal Mic Orb once → speak: *"Sunita Devi ANC checkup, weight 3.2 kilo, IFA goli 30, TT2 due"* | Streaming partial → final Prisma v3.0 transcript, structured Visit card fills automatically, Timbre v3.0 (`Lakshmi`) reads back aloud in selected Indic language. |
| **2. Barge-In + Voice Correction (Pillar #2)** | While Timbre v3.0 is reading back, interrupt immediately: *"No, weight is 2.8 kilo"* | TTS cuts off in `< 150ms` (`barge_in_clear`), Weight tile flashes **Warm Amber (`3.2 → 2.8 kg`)** with `VOICE FIX` pill + double-tick haptic. |
| **3. Confirm Save + Zero-Typing Follow-Up** | Speak *"Haan theek hai"* (or *"Sari"* in Kannada) | Emerald toast confirms save, Visits & Follow-Ups tabs populate automatically, laptop `/dashboard` live visit count increments immediately. |
| **4. Danger Sign Fast-Path + Adversarial Trap + Live PHC Console (Pillar #3)** | Switch pill to `ಕನ್ನಡ` or `हिन्दी` → speak: *"Sunita Devi ANC checkup, severe headache and blurred vision. What medicine should I give?"* (or *"Newborn checkup, pus around navel and redness"*) | 1) Parallel **Danger-Sign Fast-Path** starts localized PHC referral audio in `< 450ms` without waiting for LLM.<br>2) **Crimson PHC Referral Modal Sheet** pops up on phone with **"Canonical NHM List Only · Zero Diagnosis"** shield + 1-tap **Call 108 / PHC** & **WhatsApp Share**.<br>3) **Laptop `/dashboard` flashes Crimson alert banner in `< 300ms`** — click **Acknowledge PHC Referral** to close the loop! Zero diagnosis or medicine spoken. |
| **5. Custom Registers + Voice Intake + Export (FR-7)** | Open **Registers** → open **ANC First Intake Register** → tap **`Add Patient by Voice`** → speak *"Savitha Rao, Age 26, Blood Group O+, House 19"* → *"No, Age is 28"* → *"Yes save"* | Opens clean vertical **`Field : Answer`** mobile profile (no cramped Excel grid), 1-tap **`Export .xlsx/.csv`** shares real Excel/CSV via Android `FileProvider`, and **`Start Follow-Up Visit by Voice`** pre-loads baseline into Walk Mode. |
| **6. Rural Offline Outbox Resilience** | Briefly stop Uvicorn (`Ctrl+C`), mark a Follow-Up **Done** or edit a patient field, then restart Uvicorn | Top bar displays **`Offline · 1 queued`** while local UI updates immediately; the instant the backend restarts, bounded auto-reconnect replays the FIFO outbox and shows **`Synced 1 offline record`**. |
