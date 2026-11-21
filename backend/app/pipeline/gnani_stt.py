"""Gnani Prisma v3.0 Realtime STT WebSocket Adapter + High-Fidelity Offline Simulator."""

from __future__ import annotations

import asyncio
import json
import logging
import time
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from typing import Any, Optional

from pydantic import BaseModel

from app.config import Settings, get_settings
from app.models import LanguageCode

logger = logging.getLogger(__name__)


# Clinical Indic + English code-mix vocabulary boosted on Gnani Prisma v3.0
ASHA_CLINICAL_KEYTERMS: tuple[str, ...] = (
    "TT injection",
    "TT1",
    "TT2",
    "ANC checkup",
    "IFA goli",
    "HBNC",
    "PHC",
    "BCG",
    "OPV",
    "OPV-0",
    "Pentavalent",
    "Rotavirus",
    "ASHA",
    "kilo",
    "gram",
    "fever",
    "bleeding",
    "BP",
    "iron tablet",
    "Sunita Devi",
    "Lakshmi Gowda",
    "Meena Kumari",
    "Kavitha Reddy",
)


class TranscriptEvent(BaseModel):
    """Normalized STT transcript event emitted by Prisma v3.0 or Mock simulator."""

    text: str
    is_final: bool = False
    language: str = "hi-IN"
    confidence: float = 1.0
    latency_ms: float = 0.0


class BaseSTTClient(ABC):
    """Common streaming STT interface for live Gnani Prisma v3.0 and offline simulator."""

    @abstractmethod
    async def connect(
        self,
        language_code: LanguageCode | str = "indic-auto",
        keyterms: Optional[list[str]] = None,
    ) -> None:
        """Open streaming STT session with language and clinical keyterm configuration."""

    @abstractmethod
    async def send_audio(self, pcm_bytes: bytes) -> None:
        """Stream raw 16kHz 16-bit mono LE PCM audio bytes."""

    @abstractmethod
    async def finalize_turn(self) -> None:
        """Signal end-of-utterance to flush final transcript."""

    @abstractmethod
    async def update_language(self, language_code: LanguageCode | str) -> None:
        """Update active language code mid-session when user taps the language pill."""

    @abstractmethod
    def transcripts(self) -> AsyncIterator[TranscriptEvent]:
        """Yield partial and final TranscriptEvent items as they arrive."""

    @abstractmethod
    async def close(self) -> None:
        """Close the streaming STT session cleanly."""


class GnaniPrismaSTTClient(BaseSTTClient):
    """Live WebSocket client for Gnani Prisma v3.0 STT (wss://api.vachana.ai/stt/v3/stream)."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        ws_url: Optional[str] = None,
        sample_rate: int = 16000,
        model: str = "prisma-v3.0",
    ) -> None:
        settings = get_settings()
        self.api_key = api_key if api_key is not None else settings.gnani_api_key
        self.ws_url = ws_url or settings.gnani_stt_ws_url
        self.sample_rate = sample_rate
        self.model = model
        self.language_code: str = "indic-auto"
        self.keyterms: list[str] = list(ASHA_CLINICAL_KEYTERMS)
        self._ws: Any = None
        self._last_audio_ts: float = 0.0
        self._closed: bool = False
        self._connect_lock: asyncio.Lock = asyncio.Lock()
        self._ws_ready: asyncio.Event = asyncio.Event()

    async def _open_socket(self) -> None:
        import websockets

        headers = {
            "X-API-Key-ID": self.api_key,
            "language_code": self.language_code,
            "model": self.model,
        }
        ws = await websockets.connect(
            self.ws_url,
            additional_headers=headers,
            max_size=4 * 1024 * 1024,
        )
        # Send initial Prisma v3.0 keyterm & audio configuration control frame
        await ws.send(
            json.dumps(
                {
                    "type": "update_settings",
                    "model": self.model,
                    "language_code": self.language_code,
                    "sample_rate": self.sample_rate,
                    "encoding": "linear16",
                    "keyterms": self.keyterms,
                }
            )
        )
        self._ws = ws
        self._ws_ready.set()

    async def _reconnect(self) -> None:
        async with self._connect_lock:
            if self._closed:
                return
            if self._ws is not None:
                try:
                    await self._ws.close()
                except Exception:
                    pass
                self._ws = None
            self._ws_ready.clear()
            await self._open_socket()

    async def connect(
        self,
        language_code: LanguageCode | str = "indic-auto",
        keyterms: Optional[list[str]] = None,
    ) -> None:
        self.language_code = str(language_code)
        if keyterms is not None:
            self.keyterms = list(keyterms)
        self._closed = False
        async with self._connect_lock:
            await self._open_socket()

    async def send_audio(self, pcm_bytes: bytes) -> None:
        if self._closed or not pcm_bytes:
            return
        self._last_audio_ts = time.perf_counter()
        if self._ws is None:
            await self._reconnect()
        try:
            if self._ws is not None:
                await self._ws.send(pcm_bytes)
        except Exception as exc:
            if self._closed:
                return
            logger.info("Prisma STT socket reconnecting after idle/close (%s)", exc)
            await self._reconnect()
            if self._ws is not None:
                await self._ws.send(pcm_bytes)

    async def finalize_turn(self) -> None:
        if self._ws is None or self._closed:
            return
        try:
            await self._ws.send(json.dumps({"type": "finalize"}))
        except Exception as exc:  # pragma: no cover
            logger.debug("Prisma finalize control frame skipped: %s", exc)

    async def update_language(self, language_code: LanguageCode | str) -> None:
        self.language_code = str(language_code)
        if self._ws is None or self._closed:
            return
        try:
            await self._ws.send(
                json.dumps(
                    {
                        "type": "update_settings",
                        "language_code": self.language_code,
                        "keyterms": self.keyterms,
                    }
                )
            )
        except Exception:
            # Socket closed while idle; next send_audio will reconnect with updated language_code
            self._ws = None
            self._ws_ready.clear()

    async def transcripts(self) -> AsyncIterator[TranscriptEvent]:
        while not self._closed:
            await self._ws_ready.wait()
            if self._closed:
                break
            current_ws = self._ws
            if current_ws is None:
                self._ws_ready.clear()
                continue
            should_exit = False
            try:
                async for raw_message in current_ws:
                    if isinstance(raw_message, bytes):
                        continue
                    try:
                        data = json.loads(raw_message)
                    except json.JSONDecodeError:
                        continue

                    text = (
                        data.get("transcript")
                        or data.get("text")
                        or data.get("asr_text")
                        or ""
                    ).strip()
                    if not text:
                        continue

                    is_final = bool(
                        data.get("is_final")
                        if "is_final" in data
                        else data.get("final", data.get("type") == "final_transcript")
                    )
                    detected_lang = str(
                        data.get("detected_language_code")
                        or data.get("language_code")
                        or data.get("language")
                        or self.language_code
                    )
                    confidence = float(data.get("confidence", 1.0))
                    reported_latency = data.get("latency")
                    if isinstance(reported_latency, (int, float)) and reported_latency >= 0:
                        latency_ms = float(reported_latency)
                    else:
                        latency_ms = (
                            (time.perf_counter() - self._last_audio_ts) * 1000.0
                            if self._last_audio_ts
                            else 0.0
                        )
                    yield TranscriptEvent(
                        text=text,
                        is_final=is_final,
                        language=detected_lang,
                        confidence=confidence,
                        latency_ms=round(latency_ms, 1),
                    )
                # Normal WebSocket closure (1000 OK without idle timeout) ends the stream cleanly.
                close_code = getattr(current_ws, "close_code", None)
                close_reason = str(getattr(current_ws, "close_reason", "") or "").lower()
                if close_code == 1000 and "idle" not in close_reason:
                    should_exit = True
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                if not self._closed:
                    logger.info("Prisma STT receive stream ended (%s); awaiting reconnect", exc)
            finally:
                if self._ws is current_ws:
                    self._ws = None
                    self._ws_ready.clear()
            if should_exit or self._closed:
                break
            await asyncio.sleep(0.05)

    async def close(self) -> None:
        self._closed = True
        self._ws_ready.set()
        if self._ws is not None:
            try:
                await self._ws.close()
            except Exception:
                pass
            self._ws = None


class MockPrismaSTTClient(BaseSTTClient):
    """Zero-credit offline simulator mimicking Gnani Prisma v3.0 streaming STT."""

    DEFAULT_SCENARIOS: tuple[str, ...] = (
        "Sunita Devi ANC checkup weight 3.2 kilo IFA goli 30 TT2 injection due",
        "No, weight is 2.8 kilo",
        "Haan theek hai",
    )

    def __init__(
        self,
        scenarios: Optional[list[str]] = None,
        partial_delay_s: float = 0.01,
    ) -> None:
        self.language_code: str = "indic-auto"
        self.keyterms: list[str] = list(ASHA_CLINICAL_KEYTERMS)
        self._scenarios: list[str] = list(scenarios) if scenarios is not None else list(self.DEFAULT_SCENARIOS)
        self._scenario_idx: int = 0
        self._partial_delay_s = partial_delay_s
        self._audio_bytes_in_turn: int = 0
        self._queue: asyncio.Queue[Optional[TranscriptEvent]] = asyncio.Queue()
        self._connected: bool = False

    async def connect(
        self,
        language_code: LanguageCode | str = "indic-auto",
        keyterms: Optional[list[str]] = None,
    ) -> None:
        self.language_code = str(language_code)
        if keyterms is not None:
            self.keyterms = list(keyterms)
        self._connected = True

    async def send_audio(self, pcm_bytes: bytes) -> None:
        if not self._connected or not pcm_bytes:
            return
        self._audio_bytes_in_turn += len(pcm_bytes)

    async def inject_simulated_utterance(
        self,
        text: str,
        language_code: Optional[str] = None,
    ) -> None:
        """Emit progressive partial transcripts followed by a final transcript."""
        lang = language_code or self.language_code
        words = text.strip().split()
        if not words:
            return

        # Emit 2-3 progressive partials like real streaming ASR
        if len(words) >= 3:
            mid = max(1, len(words) // 2)
            await self._queue.put(
                TranscriptEvent(
                    text=" ".join(words[:mid]),
                    is_final=False,
                    language=lang,
                    confidence=0.88,
                    latency_ms=45.0,
                )
            )
            if self._partial_delay_s > 0:
                await asyncio.sleep(self._partial_delay_s)

        await self._queue.put(
            TranscriptEvent(
                text=text.strip(),
                is_final=True,
                language=lang,
                confidence=0.97,
                latency_ms=85.0,
            )
        )

    async def finalize_turn(self) -> None:
        """When audio was streamed in mock mode, emit the next scripted ASHA scenario."""
        if not self._connected or self._audio_bytes_in_turn == 0:
            return
        self._audio_bytes_in_turn = 0
        if not self._scenarios:
            return
        utterance = self._scenarios[self._scenario_idx % len(self._scenarios)]
        self._scenario_idx += 1
        await self.inject_simulated_utterance(utterance, self.language_code)

    async def update_language(self, language_code: LanguageCode | str) -> None:
        self.language_code = str(language_code)

    async def transcripts(self) -> AsyncIterator[TranscriptEvent]:
        while True:
            event = await self._queue.get()
            if event is None:
                break
            yield event

    async def close(self) -> None:
        if self._connected:
            self._connected = False
            await self._queue.put(None)


def create_stt_client(settings: Optional[Settings] = None) -> BaseSTTClient:
    """Factory returning MockPrismaSTTClient when MOCK_GNANI=true, else live Prisma v3.0."""
    cfg = settings or get_settings()
    if cfg.mock_gnani:
        return MockPrismaSTTClient()
    return GnaniPrismaSTTClient(
        api_key=cfg.gnani_api_key,
        ws_url=cfg.gnani_stt_ws_url,
        sample_rate=cfg.sample_rate,
    )
