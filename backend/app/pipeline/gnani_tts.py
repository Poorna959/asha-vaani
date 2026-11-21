"""Gnani Timbre v3.0 Realtime TTS WebSocket Adapter + Offline PCM Simulator.

Features:
- Decimal-safe sentence splitter (preserves '2.8 kilo' while splitting '.', '?', '!', '।')
  for minimal Time-to-First-Audio (TTFA).
- Clean RIFF/WAV header stripping so Android AudioTrack receives raw 16kHz 16-bit mono PCM.
- Instant asyncio.CancelledError handling for sub-150ms barge-in interruption.
- High-fidelity MockTimbreTTSClient generating valid 16kHz 16-bit LE PCM frames offline.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import re
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from typing import Optional

import numpy as np

from app.config import Settings, get_settings
from app.models import LanguageCode
from app.pipeline.danger_signs import resolve_short_language

logger = logging.getLogger(__name__)


DEFAULT_TIMBRE_VOICES: dict[str, str] = {
    "hi": "Lakshmi",
    "kn": "Lakshmi",
    "ta": "Lakshmi",
    "te": "Lakshmi",
    "en": "Lakshmi",
}

BCP47_BY_SHORT_LANG: dict[str, str] = {
    "hi": "hi-IN",
    "kn": "kn-IN",
    "ta": "ta-IN",
    "te": "te-IN",
    "en": "en-IN",
}

# Split on '.', '?', '!', or Devanagari danda '।' followed by whitespace or end-of-string
# (preserves decimals like '2.8' because '.' is followed by a digit, while splitting '30. Next')
SENTENCE_BOUNDARY_RE = re.compile(r"[.?!।]+(?=\s+|$)")


def split_into_sentences(text: str) -> list[str]:
    """Split reply text into speakable sentence chunks without breaking decimals like '2.8'."""
    cleaned = re.sub(r"\s+", " ", text).strip()
    if not cleaned:
        return []

    sentences: list[str] = []
    start = 0
    for match in SENTENCE_BOUNDARY_RE.finditer(cleaned):
        end = match.end()
        chunk = cleaned[start:end].strip()
        if chunk:
            sentences.append(chunk)
        start = end

    tail = cleaned[start:].strip()
    if tail:
        sentences.append(tail)
    return sentences


def strip_wav_header_if_present(audio_bytes: bytes) -> bytes:
    """Strip RIFF/WAVE header if present so callers always receive pure raw PCM."""
    if len(audio_bytes) >= 44 and audio_bytes[:4] == b"RIFF" and audio_bytes[8:12] == b"WAVE":
        data_pos = audio_bytes.find(b"data")
        if data_pos != -1 and data_pos + 8 <= len(audio_bytes):
            return audio_bytes[data_pos + 8 :]
        return audio_bytes[44:]
    return audio_bytes


class BaseTTSClient(ABC):
    """Common streaming TTS interface for live Gnani Timbre v3.0 and offline simulator."""

    @abstractmethod
    def synthesize_stream(
        self,
        text: str,
        language_code: LanguageCode | str = "hi-IN",
        voice: Optional[str] = None,
    ) -> AsyncIterator[bytes]:
        """Yield raw 16kHz 16-bit mono LE PCM audio byte chunks."""


class GnaniTimbreTTSClient(BaseTTSClient):
    """Live WebSocket client for Gnani Timbre v3.0 TTS (wss://api.vachana.ai/api/v1/tts)."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        ws_url: Optional[str] = None,
        sample_rate: int = 16000,
        model: str = "timbre-v3.0",
        speed: float = 1.0,
    ) -> None:
        settings = get_settings()
        self.api_key = api_key if api_key is not None else settings.gnani_api_key
        self.ws_url = ws_url or settings.gnani_tts_ws_url
        self.sample_rate = sample_rate
        self.model = model
        self.speed = speed

    async def synthesize_stream(
        self,
        text: str,
        language_code: LanguageCode | str = "hi-IN",
        voice: Optional[str] = None,
    ) -> AsyncIterator[bytes]:
        import websockets

        sentences = split_into_sentences(text)
        if not sentences:
            return

        short_lang = resolve_short_language(str(language_code), text)
        target_bcp47 = BCP47_BY_SHORT_LANG.get(short_lang, "hi-IN")
        selected_voice = voice or DEFAULT_TIMBRE_VOICES.get(short_lang, "Lakshmi")

        headers = {
            "X-API-Key-ID": self.api_key,
        }

        ws = await websockets.connect(
            self.ws_url,
            additional_headers=headers,
            max_size=8 * 1024 * 1024,
        )
        try:
            for sentence in sentences:
                payload = {
                    "text": sentence,
                    "voice": selected_voice,
                    "model": self.model,
                    "language": target_bcp47,
                    "speed": self.speed,
                    "audio_config": {
                        "sample_rate": self.sample_rate,
                        "num_channels": 1,
                        "sample_width": 2,
                        "encoding": "linear_pcm",
                        "container": "raw",
                    },
                }
                await ws.send(json.dumps(payload, ensure_ascii=False))

                first_chunk_of_sentence = True
                async for raw_msg in ws:
                    if isinstance(raw_msg, bytes):
                        pcm = strip_wav_header_if_present(raw_msg) if first_chunk_of_sentence else raw_msg
                        first_chunk_of_sentence = False
                        if pcm:
                            yield pcm
                        continue

                    try:
                        data = json.loads(raw_msg)
                    except json.JSONDecodeError:
                        continue

                    msg_type = str(data.get("type", "")).lower()
                    if msg_type == "error":
                        logger.error("Timbre v3.0 TTS error: %s", data.get("message") or data)
                        break

                    # Timbre v3.0 streams {"type":"audio","data":{"chunk_index":N,"audio":"<b64>","is_final":false}}
                    nested = data.get("data") if isinstance(data.get("data"), dict) else {}
                    b64_audio = (
                        nested.get("audio")
                        or nested.get("audio_chunk")
                        or nested.get("audio_content")
                        or (data.get("audio") if isinstance(data.get("audio"), str) else None)
                        or (data.get("audio_chunk") if isinstance(data.get("audio_chunk"), str) else None)
                        or (data.get("audio_content") if isinstance(data.get("audio_content"), str) else None)
                        or (data.get("data") if isinstance(data.get("data"), str) else None)
                    )
                    if isinstance(b64_audio, str) and b64_audio:
                        decoded = base64.b64decode(b64_audio)
                        pcm = (
                            strip_wav_header_if_present(decoded)
                            if first_chunk_of_sentence
                            else decoded
                        )
                        first_chunk_of_sentence = False
                        if pcm:
                            yield pcm

                    if (
                        msg_type in ("complete", "completed", "done")
                        or nested.get("is_final")
                        or data.get("is_final")
                        or data.get("final")
                        or data.get("status") == "completed"
                    ):
                        break
        except asyncio.CancelledError:
            raise
        finally:
            try:
                await ws.close()
            except Exception:
                pass


class MockTimbreTTSClient(BaseTTSClient):
    """Zero-credit offline TTS simulator emitting valid 16kHz 16-bit mono LE PCM frames."""

    def __init__(
        self,
        sample_rate: int = 16000,
        chunk_ms: int = 20,
        chunk_delay_s: float = 0.005,
        max_chunks_per_sentence: int = 12,
    ) -> None:
        self.sample_rate = sample_rate
        self.chunk_samples = int(sample_rate * chunk_ms / 1000)  # 320 samples = 640 bytes @ 16kHz
        self.chunk_delay_s = chunk_delay_s
        self.max_chunks_per_sentence = max_chunks_per_sentence

    async def synthesize_stream(
        self,
        text: str,
        language_code: LanguageCode | str = "hi-IN",
        voice: Optional[str] = None,
    ) -> AsyncIterator[bytes]:
        sentences = split_into_sentences(text)
        if not sentences:
            return

        # Synthesize gentle, valid 16-bit PCM tone envelopes per sentence (cancellable per 20ms frame)
        for sent_idx, sentence in enumerate(sentences):
            num_chunks = min(max(3, len(sentence.split())), self.max_chunks_per_sentence)
            freq_hz = 340.0 + (sent_idx % 3) * 55.0
            for chunk_idx in range(num_chunks):
                t0 = chunk_idx * self.chunk_samples
                t = (np.arange(t0, t0 + self.chunk_samples, dtype=np.float32)) / float(self.sample_rate)
                # Soft amplitude (15% of int16 max) so offline device playback is pleasant
                samples = (np.sin(2.0 * np.pi * freq_hz * t) * 4800.0).astype("<i2")
                yield samples.tobytes()
                if self.chunk_delay_s > 0:
                    await asyncio.sleep(self.chunk_delay_s)


def create_tts_client(settings: Optional[Settings] = None) -> BaseTTSClient:
    """Factory returning MockTimbreTTSClient when MOCK_GNANI=true, else live Timbre v3.0."""
    cfg = settings or get_settings()
    if cfg.mock_gnani:
        return MockTimbreTTSClient(sample_rate=cfg.sample_rate)
    return GnaniTimbreTTSClient(
        api_key=cfg.gnani_api_key,
        ws_url=cfg.gnani_tts_ws_url,
        sample_rate=cfg.sample_rate,
    )
