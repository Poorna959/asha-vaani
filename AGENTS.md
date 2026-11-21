# Project Rules: PS1 · Healthcare — Voice Field Register for ASHA Workers (ASHA Vaani)

Every feature, backend module, prompt, test, and Android UI screen in this repository MUST strictly satisfy the **Gnani Voice Hackathon PS1** requirements below.

## Core Mission
Build a zero-typing voice agent on Android that turns an ASHA worker's spoken home-visit notes (pregnancy/ANC, newborn/HBNC, immunisation) into a structured visit record and a follow-up list before she reaches the next house — working reliably in household and street noise across supported Indic languages (Hindi, Kannada, Tamil, Telugu) with English code-mix.

## Mandatory Hackathon Platform Constraints
- **Connection**: WebSocket streaming only. **No telephony and no phone numbers.**
- **Required STT**: **Prisma v3.0** on the Gnani API Platform (`wss://api.vachana.ai/stt/v3/stream`).
- **Required TTS**: **Timbre v3.0** on the Gnani API Platform (`wss://api.vachana.ai/api/v1/tts`).
- **Orchestration**: Custom FastAPI async WebSocket orchestrator. Hosted end-to-end agent platforms CANNOT be the core loop.
- **LLM**: Gemini 2.5 Flash (`gemini-2.5-flash`) with offline deterministic fallback.

## Mandatory Three Functional Pillars (PS1)
1. **Voice Capture**:
   - Streaming STT that handles Indic + English code-mix and local health words (`"TT injection"`, `"ANC checkup"`, `"IFA goli"`, `"2.8 kilo"`).
   - Must work with household and street noise (Android hardware `AcousticEchoCanceler` + `NoiseSuppressor` + `AutomaticGainControl` + server-side Silero VAD/endpointing + instant barge-in).
2. **Structured Record with Read-Back**:
   - Agent fills a structured visit form (`beneficiary_id`, `visit_type`, `weight_kg`, `vaccinations_due`, `next_visit_date`, plus `beneficiary_name`, `ifa_tablets_given`, `notes`).
   - **Mandatory Read-Back Before Save**: Before saving, the agent MUST read the form aloud so she can confirm it, and she can fix any mistake by voice (`"no, weight is 2.8 kilo"`) with instant barge-in interruption and visual field-diff highlight.
   - Confirmed visits automatically populate the **Follow-Up List** with zero typing.
3. **Danger-Sign Escalation (Strict Compliance)**:
   - When spoken notes mention a danger sign from the team's canonical list (`docs/danger_signs.json`, including bleeding in pregnancy and high fever in a newborn), the agent MUST:
     1. Tell her **in her language** to **refer to the PHC now**.
     2. **Flag the record** (`has_danger_sign = True`, `danger_signs = [...]`).
   - **List Only — Zero Diagnosis or Treatment Advice**: Never diagnose any condition or suggest/prescribe any medicine, dosage, or treatment. Both `docs/danger_signs.json` and the FR-6 pre-TTS `ComplianceGuardrail` enforce this deterministically.

## Additional Custom Requirement (Custom First-Visit Registers)
- ASHA workers can create custom named registers with custom dynamic fields for first-time patient intake or surveys, search patients by name inside each register, view recorded patients in a clean mobile `Field: Answer` format (not a cramped in-app Excel grid), export any register to `.xlsx` or `.csv`, and tap a recorded patient to carry baseline prerequisites directly into routine voice follow-ups.

## Judging Criteria Guardrails
- **Live Judge Test (30%)**: Optimize for sub-800ms Time-to-First-Audio (TTFA), accurate capture of Indian names and numbers, and complete task success.
- **Speech-Layer Depth (20%)**: End-to-end streaming, sub-150ms barge-in cutoff, dynamic endpointing, code-mix keyterms, and noise suppression.
- **Fit for Target User (20%)**: Zero-typing Walk-Mode Android UI usable with one thumb outdoors; local-first persistence.
- **Responsible AI & Compliance (10%)**: Guardrails must hold live under adversarial judge prompts.
