"""Tests for Issue #6: Gnani Prisma v3.0 & Timbre v3.0 WebSocket Adapters + Offline Simulator."""

from __future__ import annotations

import asyncio
import base64
import json
import struct

import pytest
import websockets

from app.config import Settings
from app.pipeline.gnani_stt import (
    ASHA_CLINICAL_KEYTERMS,
    GnaniPrismaSTTClient,
    MockPrismaSTTClient,
    create_stt_client,
)
from app.pipeline.gnani_tts import (
    GnaniTimbreTTSClient,
    MockTimbreTTSClient,
    create_tts_client,
    split_into_sentences,
    strip_wav_header_if_present,
)


def test_split_into_sentences_preserves_clinical_decimals_and_indic_danda():
    en = "Recorded Sunita Devi, weight 2.8 kilo, IFA goli 30. Is this correct? Shall I save!"
    assert split_into_sentences(en) == [
        "Recorded Sunita Devi, weight 2.8 kilo, IFA goli 30.",
        "Is this correct?",
        "Shall I save!",
    ]

    hi = "वजन 54.5 किलो अपडेट कर दिया है। क्या मैं यह रिकॉर्ड सेव कर लूं?"
    assert split_into_sentences(hi) == [
        "वजन 54.5 किलो अपडेट कर दिया है।",
        "क्या मैं यह रिकॉर्ड सेव कर लूं?",
    ]


def test_strip_wav_header_strips_riff_and_keeps_raw_pcm():
    raw_pcm = struct.pack("<4h", 100, -100, 250, -250)
    # Build minimal 44-byte RIFF/WAVE header + PCM
    wav_bytes = (
        b"RIFF"
        + struct.pack("<I", 36 + len(raw_pcm))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
        + b"data"
        + struct.pack("<I", len(raw_pcm))
        + raw_pcm
    )
    assert strip_wav_header_if_present(wav_bytes) == raw_pcm
    assert strip_wav_header_if_present(raw_pcm) == raw_pcm


async def test_mock_prisma_stt_streams_partial_then_final_and_updates_language():
    stt = MockPrismaSTTClient(
        scenarios=["Sunita Devi ANC checkup weight 2.8 kilo"],
        partial_delay_s=0.0,
    )
    await stt.connect(language_code="hi-IN")
    assert "TT injection" in stt.keyterms
    assert "IFA goli" in stt.keyterms

    await stt.send_audio(b"\x01\x02" * 320)
    await stt.finalize_turn()
    await stt.update_language("kn-IN")
    await stt.inject_simulated_utterance("No, weight is 2.8 kilo")
    await stt.close()

    events = [ev async for ev in stt.transcripts()]
    assert len(events) == 4  # 2 partials + 2 finals
    assert events[0].is_final is False
    assert events[1].is_final is True
    assert events[1].text == "Sunita Devi ANC checkup weight 2.8 kilo"
    assert events[1].language == "hi-IN"
    assert events[3].is_final is True
    assert events[3].language == "kn-IN"


async def test_mock_timbre_tts_emits_valid_pcm_and_cancels_cleanly_on_barge_in():
    tts = MockTimbreTTSClient(sample_rate=16000, chunk_ms=20, chunk_delay_s=0.01)

    chunks = [c async for c in tts.synthesize_stream("Weight is 2.8 kilo. Shall I save?", "en-IN")]
    assert len(chunks) >= 6
    assert all(len(c) == 640 for c in chunks)  # 20ms @ 16kHz 16-bit mono = 640 bytes

    # Simulate barge-in cancelling TTS after 2 chunks
    received_before_cancel = 0

    async def consume_tts():
        nonlocal received_before_cancel
        async for _ in tts.synthesize_stream(
            "Long readback sentence one with many words. Sentence two with more words.",
            "hi-IN",
        ):
            received_before_cancel += 1
            if received_before_cancel >= 2:
                await asyncio.sleep(1.0)

    task = asyncio.create_task(consume_tts())
    while received_before_cancel < 2:
        await asyncio.sleep(0.005)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert received_before_cancel == 2


async def test_live_gnani_prisma_stt_client_against_local_ws_server():
    """Verify real WebSocket headers, keyterms payload, binary PCM streaming & transcript parsing."""
    captured_headers: dict[str, str] = {}
    captured_settings: list[dict] = []
    received_audio = bytearray()

    async def fake_prisma_server(ws):
        captured_headers.update({k.lower(): v for k, v in ws.request.headers.items()})
        async for msg in ws:
            if isinstance(msg, bytes):
                received_audio.extend(msg)
                await ws.send(
                    json.dumps({"transcript": "Sunita Devi ANC", "is_final": False, "language_code": "hi-IN"})
                )
            else:
                data = json.loads(msg)
                if data.get("type") == "update_settings":
                    captured_settings.append(data)
                elif data.get("type") == "finalize":
                    await ws.send(
                        json.dumps(
                            {
                                "transcript": "Sunita Devi ANC checkup TT injection",
                                "is_final": True,
                                "language_code": "hi-IN",
                                "confidence": 0.96,
                            }
                        )
                    )
                    await ws.close()

    async with websockets.serve(fake_prisma_server, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        client = GnaniPrismaSTTClient(
            api_key="test-secret-key",
            ws_url=f"ws://127.0.0.1:{port}",
        )
        await client.connect(language_code="hi-IN", keyterms=list(ASHA_CLINICAL_KEYTERMS))
        await client.send_audio(b"\x00\x01" * 320)
        await client.finalize_turn()

        events = [ev async for ev in client.transcripts()]
        await client.close()

    assert captured_headers["x-api-key-id"] == "test-secret-key"
    assert captured_headers["model"] == "prisma-v3.0"
    assert len(captured_settings) == 1
    assert "IFA goli" in captured_settings[0]["keyterms"]
    assert len(received_audio) == 640
    assert len(events) == 2
    assert events[0].is_final is False
    assert events[1].is_final is True
    assert "TT injection" in events[1].text


async def test_live_gnani_timbre_tts_client_against_local_ws_server():
    """Verify real Timbre v3.0 sentence-chunked WS requests, WAV header strip, and base64 decode."""
    captured_headers: dict[str, str] = {}
    captured_requests: list[dict] = []
    raw_pcm = b"\x10\x00\x20\x00" * 40
    wav_first_chunk = (
        b"RIFF"
        + struct.pack("<I", 36 + len(raw_pcm))
        + b"WAVEfmt "
        + struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16)
        + b"data"
        + struct.pack("<I", len(raw_pcm))
        + raw_pcm
    )

    async def fake_timbre_server(ws):
        captured_headers.update({k.lower(): v for k, v in ws.request.headers.items()})
        async for msg in ws:
            req = json.loads(msg)
            captured_requests.append(req)
            await ws.send(
                json.dumps(
                    {
                        "audio": base64.b64encode(wav_first_chunk).decode("ascii"),
                        "is_final": True,
                    }
                )
            )

    async with websockets.serve(fake_timbre_server, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        client = GnaniTimbreTTSClient(
            api_key="test-timbre-key",
            ws_url=f"ws://127.0.0.1:{port}",
        )
        audio_chunks = [
            chunk
            async for chunk in client.synthesize_stream(
                "Weight is 2.8 kilo. Shall I save?",
                language_code="hi-IN",
            )
        ]

    assert captured_headers["x-api-key-id"] == "test-timbre-key"
    assert len(captured_requests) == 2
    assert captured_requests[0]["model"] == "timbre-v3.0"
    assert captured_requests[0]["text"] == "Weight is 2.8 kilo."
    assert captured_requests[1]["text"] == "Shall I save?"
    assert audio_chunks == [raw_pcm, raw_pcm]


def test_adapter_factories_respect_mock_gnani_flag():
    mock_cfg = Settings(mock_gnani=True)
    assert isinstance(create_stt_client(mock_cfg), MockPrismaSTTClient)
    assert isinstance(create_tts_client(mock_cfg), MockTimbreTTSClient)

    live_cfg = Settings(mock_gnani=False, gnani_api_key="live-key")
    assert isinstance(create_stt_client(live_cfg), GnaniPrismaSTTClient)
    assert isinstance(create_tts_client(live_cfg), GnaniTimbreTTSClient)
