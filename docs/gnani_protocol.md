# Gnani API Platform Protocol Reference (Prisma v3.0 & Timbre v3.0)

This document summarizes the live-verified WebSocket streaming contract implemented in `backend/app/pipeline/gnani_stt.py` and `backend/app/pipeline/gnani_tts.py` for the Gnani Voice Hackathon.

## Authentication
All Gnani Vachana (`api.vachana.ai`) WebSocket connections pass the API key via handshake header:
- `X-API-Key-ID: <GNANI_API_KEY>`

## 1. Prisma v3.0 (Streaming STT)
- **Endpoint**: `wss://api.vachana.ai/stt/v3/stream` (configurable via `GNANI_STT_WS_URL`)
- **Model**: `prisma-v3.0`
- **Supported Language Codes**: `hi-IN`, `kn-IN`, `ta-IN`, `te-IN`, `en-IN`, `indic-auto`
- **Audio Format**: Raw 16-bit signed Little-Endian Linear PCM, 16,000 Hz, mono (`640` bytes per 20ms frame)
- **Keyterm Boosting**: Sent immediately after WebSocket connect via `update_settings` control frame containing clinical code-mix vocabulary (`"TT injection"`, `"ANC checkup"`, `"IFA goli"`, `"HBNC"`, `"PHC"`, `"BCG"`, `"OPV"`, `"Pentavalent"`, `"kilo"`, `"bleeding"`, `"fever"`, etc.).
- **Live Protocol Notes**:
  - Parses `detected_language_code` and Prisma's reported `latency` metadata into `TranscriptEvent`.
  - Automatically reconnects transparently on upstream `1001 idle_timeout` closures during long walk pauses, while exiting cleanly on normal `1000 OK` session close.
  - Because Prisma v3.0 may phonetically transliterate English clinical terms into the active Indic script (e.g., `"blurred vision"` → `ಬ್ಲರ್ಡ್ ವಿಷನ್`), our Layer-1 `DangerSignDetector` runs a zero-dependency ISCII-aligned cross-script transliterator (`transliterate_indic_to_latin`) alongside a Layer-2b post-LLM normalized note scan.

## 2. Timbre v3.0 (Streaming TTS)
- **Endpoint**: `wss://api.vachana.ai/api/v1/tts` (configurable via `GNANI_TTS_WS_URL`)
- **Model**: `timbre-v3.0`
- **Default Voice**: `Lakshmi` (supported live on `timbre-v3.0`; measured live TTFA `~399ms`).
- **Wire Frame Unwrapping**: Handles live nested `{"type": "audio", "data": {"chunk_index": N, "audio": "<base64>", "is_final": false}}` frames plus `{"type": "complete", ...}` termination frames.
- **Sentence-Chunked Dispatch**: Responses are split using a decimal-safe splitter (`2.8 kilo` is never split) and dispatched sentence-by-sentence for low Time-to-First-Audio (TTFA).
- **Parallel Danger-Sign Fast-Path**: When Layer-1 deterministic `DangerSignDetector` matches a canonical NHM danger sign, Timbre v3.0 starts streaming the template-locked localized PHC referral audio **in parallel** with background LLM extraction (`danger_fast_path=true`).
- **Audio Output**: `linear_pcm`, 16,000 Hz, mono, 16-bit LE. Any accidental 44-byte RIFF/WAV header on the first chunk is stripped before forwarding raw PCM to Android `AudioTrack`.
- **Instant Barge-In**: Active LLM + TTS tasks (including parallel fast-path TTS) are cancelled immediately (`asyncio.CancelledError`) on `SpeechStart`, emitting `barge_in_clear` for `< 150ms` speaker cutoff.

## Switching Between Offline Simulator & Live Gnani / Groq Keys
In `backend/.env` (never committed):
```env
# Offline zero-credit simulator
MOCK_GNANI=true

# Live Gnani API Platform + Groq LLM (with automatic backup failover)
MOCK_GNANI=false
GNANI_API_KEY=<your-live-gnani-api-key>
LLM_PROVIDER=groq
GROQ_API_KEY=<your-groq-api-key>
GROQ_MODEL=qwen/qwen3.8-27b
```
