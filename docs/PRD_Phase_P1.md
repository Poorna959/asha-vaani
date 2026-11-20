# Product Requirements Document (PRD) — Phase P1 (Updated)
## Backend Voice Orchestration Engine, Custom Register Builder & Safety Architecture

**Project**: ASHA Vaani (Voice Field Register for ASHA Workers)  
**Target Milestone**: Phase P1 (Core Backend, Custom Register Builder, Gnani Adapters, Simulator & Safety Engine)  
**Hackathon**: Gnani Voice Hackathon  
**Status**: Updated with Custom Intake & Dynamic Register Feature  

---

## 1. Executive Summary & Objective

In rural India, an **ASHA (Accredited Social Health Activist)** worker visits 15–25 households daily for routine ANC, newborn (HBNC), and immunisation tracking. Beyond routine checkups, ASHA workers also conduct **initial patient intakes (first visits)** and **special survey drives** (e.g., High-Risk Pregnancy baseline, TB/Malnutrition screening, NCD surveys).

**Phase P1 builds the entire core intelligence, speech orchestration, and dynamic data backbone**:
1. **Dynamic Custom Registers**: ASHA can create custom registers (e.g., *"ANC First Intake"*, *"High Risk Survey"*), define the fields she needs for that register, and populate patients via voice.
2. **Mobile-Friendly Field-Answer View**: When tapping a recorded patient, fields and answers are displayed in a clean, high-contrast mobile view (`Field: Answer`).
3. **Patient Search**: Fast in-register search by patient name.
4. **Excel / CSV Export**: 1-tap export of any register into `.xlsx` or `.csv` format to share with ANM / Medical Officer.
5. **Follow-Up Continuity**: Tapping a registered patient carries forward all baseline prerequisites into standard Walk-Mode voice visits, so only today's observations (weight, vaccines, danger signs) need to be spoken.
6. **Gnani Prisma v3.0 & Timbre v3.0 Adapters + Zero-Credit Simulator**: High-fidelity offline mock mode for testing without consuming credits before the hackathon.
7. **Gemini 2.5 Flash Clinical Extraction & State Engine**: Sub-800ms Time to First Audio (TTFA), voice corrections (*"No, weight is 2.8 kilo"*), and read-back before save.
8. **Deterministic Dual-Layer Danger-Sign & Compliance Engine (FR-6)**: Real-time multilingual detection of 12 NHM red flags across Hindi, Kannada, Tamil, Telugu, and English, with strict blocking of any medical diagnosis or medication prescription.

---

## 2. Dynamic Register & Intake Architecture

### Data Model & Relationship

```mermaid
erDiagram
    CUSTOM_REGISTER ||--o{ REGISTER_FIELD : contains
    CUSTOM_REGISTER ||--o{ PATIENT_RECORD : has
    PATIENT_RECORD ||--o{ VISIT_RECORD : history
    PATIENT_RECORD ||--o{ FOLLOW_UP : tracks

    CUSTOM_REGISTER {
        string id PK
        string name "e.g. ANC First Intake Register"
        string description
        string created_at
    }

    REGISTER_FIELD {
        string id PK
        string register_id FK
        string field_name "e.g. Age, Medical History, Blood Group"
        string field_type "TEXT | NUMBER | DATE | BOOLEAN"
        int order_index
    }

    PATIENT_RECORD {
        string id PK "e.g. BEN-001"
        string register_id FK
        string patient_name
        json field_answers "{\"Age\": 24, \"History\": \"High BP\"}"
        string created_at
    }

    VISIT_RECORD {
        string id PK
        string patient_id FK
        string visit_type "ANC | NEWBORN | IMMUNISATION"
        float weight_kg
        json vaccinations_due
        int has_danger_sign
        json danger_signs
        string created_at
    }
```

---

## 3. Detailed Functional Requirements (Updated)

### FR-1: Full-Duplex WebSocket Protocol (`/ws/session`)
* Real-time 16kHz PCM audio streaming + JSON control events.
* Measures and emits `ttfa_ms` (caller speech end to first audio byte).
* Instant barge-in cancellation (< 150ms).

### FR-2: Gnani Prisma v3.0 STT & Offline Simulator
* Keyterm boosting for: `["TT injection", "ANC checkup", "IFA goli", "HBNC", "PHC", "BCG", "OPV", "Pentavalent", "kilo", "gram", "fever", "bleeding", "BP", "iron tablet"]`.
* High-fidelity offline mock mode for zero-cost testing.

### FR-3: Gnani Timbre v3.0 TTS & Sentence-Chunked Streaming
* Synthesizes and streams audio sentence-by-sentence for minimal latency.
* Instant audio cancellation on interruption.

### FR-4: Clinical Data Extraction & Voice Correction (Gemini 2.5 Flash)
* Extracts structured visit data and handles Indic transliteration and numbers (*"do point aath kilo"* -> `2.8`).
* Generates field diffs for voice corrections (*"No, weight is 2.8 kilo"*).
* Enforces read-back confirmation before committing visits.

### FR-5: Deterministic Danger-Sign Escalation Engine
* Scans incoming transcripts against the 12 canonical NHM conditions in `docs/danger_signs.json` across EN, HI, KN, TA, TE.
* Triggers urgent referral scripts independently of LLM.

### FR-6: Responsible AI & Medical Compliance Guardrail
* Scans every outgoing agent response before TTS synthesis.
* Rejects diagnostic labels (*"preeclampsia"*, *"malaria"*) and prescription advice (*"give paracetamol"*, *"500mg"*).
* Enforces the template-locked NHM emergency referral script.

### FR-7: Custom Register & Dynamic Intake Engine (New Feature)
* **FR-7.1 (Register Creation)**: ASHA can create a new Register, give it a name (e.g. *"ANC First Intake Register"*, *"High Risk Survey"*), and define custom fields (e.g. `Age`, `Parity`, `Medical History`, `Blood Group`, `LMP`, `Phone`, `House No`).
* **FR-7.2 (Patient Entry via Voice)**: ASHA can speak patient details to add a row to the register; the agent maps spoken values into the register's defined fields.
* **FR-7.3 (Mobile-Friendly Field-Answer View)**: Selecting a recorded patient displays clean, readable key-value cards:
  ```text
  ┌──────────────────────────────────────────────┐
  │ Patient: Sunita Devi (ID: BEN-001)           │
  ├──────────────────────────────────────────────┤
  │ Age: 24                                      │
  │ Category: ANC (Second Pregnancy)             │
  │ Medical History: Prior High BP, Mild Anemia  │
  │ LMP: 10-Oct-2026                             │
  │ Blood Group: B+                              │
  │ House / Ward: House 14, Ward 2               │
  └──────────────────────────────────────────────┘
  ```
* **FR-7.4 (Patient Search)**: In-register real-time search by patient name.
* **FR-7.5 (Excel / CSV Export)**:
  - Generates downloadable/shareable `.xlsx` and `.csv` files for any register.
  - Columns dynamically match the register's defined fields plus system audit dates.
* **FR-7.6 (Follow-Up Continuity)**: Tapping a patient allows launching a routine follow-up visit where the baseline medical history and demographics are pre-loaded into the session, requiring the worker to only log today's vitals and observations.

---

## 4. Verification & Automated Test Matrix

| Test Module | File | What is Verified |
| :--- | :--- | :--- |
| **Custom Registers** | `tests/test_registers.py` | Creating registers, dynamic fields, adding patient entries, searching by name, and generating valid CSV/Excel exports. |
| **Danger Signs** | `tests/test_danger_signs.py` | 12 NHM danger signs tested across EN, HI, KN, TA, TE transliterations. |
| **Compliance Guardrail (FR-6)** | `tests/test_compliance.py` | Diagnosis and prescription injection blocked and replaced with NHM referral script. |
| **Clinical Extraction** | `tests/test_extraction.py` | Code-mix extraction, voice correction delta diffing, and baseline profile context injection. |
| **WebSocket Pipeline** | `tests/test_ws_pipeline.py` | Full-duplex audio stream, barge-in clear, and TTFA telemetry. |
