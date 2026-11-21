# Gnani API Platform Protocol Reference (Prisma v3.0 & Timbre v3.0)

This document summarizes the WebSocket streaming contract implemented in `backend/app/pipeline/gnani_stt.py` and `backend/app/pipeline/gnani_tts.py` for the Gnani Voice Hackathon.

## Authentication
All Gnani Vachana (`api.vachana.ai`) WebSocket connections pass the API key via handshake header:
- `X-API-Key-ID: <GNANI_API_KEY>`

## 1. Prisma v3.0 (Streaming STT)
- **Endpoint**: `wss://api.vachana.ai/stt/v3/stream` (configurable via `GNANI_STT_WS_URL`)
- **Model**: `prisma-v3.0`
- **Supported Language Codes**: `hi-IN`, `kn-IN`, `ta-IN`, `te-IN`, `en-IN`, `indic-auto`
- **Audio Format**: Raw 16-bit signed Little-Endian Linear PCM, 16,000 Hz, mono (`640` bytes per 20ms frame)
- **Keyterm Boosting**: Sent immediately after WebSocket connect via `update_settings` control frame containing clinical code-mix vocabulary (`"TT injection"`, `"ANC checkup"`, `"IFA goli"`, `"HBNC"`, `"PHC"`, `"BCG"`, `"OPV"`, `"Pentavalent"`, `"kilo"`, `"bleeding"`, `"fever"`, etc.).

## 2. Timbre v3.0 (Streaming TTS)
- **Endpoint**: `wss://api.vachana.ai/api/v1/tts` (configurable via `GNANI_TTS_WS_URL`)
- **Model**: `timbre-v3.0`
- **Sentence-Chunked Dispatch**: Responses are split using a decimal-safe splitter (`2.8 kilo` is never split) and dispatched sentence-by-sentence for sub-800ms Time-to-First-Audio (TTFA).
- **Audio Output**: `linear_pcm`, 16,000 Hz, mono, 16-bit LE. Any accidental 44-byte RIFF/WAV header on the first chunk is stripped before forwarding raw PCM to Android `AudioTrack`.
- **Instant Barge-In**: Active TTS synthesis tasks are cancelled immediately (`asyncio.CancelledError`) and the WebSocket closed when caller speech starts.

## Switching Between Offline Simulator & Live Gnani Keys
In `backend/.env`:
```env
# Offline zero-credit simulator (default before hackathon)
MOCK_GNANI=true

# Live Gnani API Platform (flip on hackathon day)
MOCK_GNANI=false
GNANI_API_KEY=<your-live-gnani-api-key>
```
