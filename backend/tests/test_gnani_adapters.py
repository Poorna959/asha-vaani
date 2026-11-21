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


@pytest.mark.asyncio
async def test_live_timbre_v3_nested_data_audio_and_lakshmi_voice():
    """Verify Timbre v3.0 nested {"type":"audio","data":{"audio":"..."}} + {"type":"complete"} format."""
    captured_requests: list[dict] = []
    pcm_chunk_1 = b"\x01\x00\x02\x00" * 32
    pcm_chunk_2 = b"\x03\x00\x04\x00" * 32

    async def fake_timbre_v3_server(ws):
        async for msg in ws:
            captured_requests.append(json.loads(msg))
            await ws.send(json.dumps({"type": "start", "message": "Streaming started"}))
            await ws.send(
                json.dumps(
                    {
                        "type": "audio",
                        "data": {
                            "chunk_index": 1,
                            "audio": base64.b64encode(pcm_chunk_1).decode("ascii"),
                            "is_final": False,
                        },
                    }
                )
            )
            await ws.send(
                json.dumps(
                    {
                        "type": "audio",
                        "data": {
                            "chunk_index": 2,
                            "audio": base64.b64encode(pcm_chunk_2).decode("ascii"),
                            "is_final": False,
                        },
                    }
                )
            )
            await ws.send(
                json.dumps(
                    {
                        "type": "complete",
                        "data": {"chunk_index": 3, "audio": "", "is_final": True},
                    }
                )
            )

    async with websockets.serve(fake_timbre_v3_server, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        client = GnaniTimbreTTSClient(api_key="live-key", ws_url=f"ws://127.0.0.1:{port}")
        chunks = [c async for c in client.synthesize_stream("Weight 2.8 kilo.", language_code="kn-IN")]

    assert captured_requests[0]["voice"] == "Lakshmi"
    assert captured_requests[0]["language"] == "kn-IN"
    assert chunks == [pcm_chunk_1, pcm_chunk_2]


@pytest.mark.asyncio
async def test_p307_prisma_stt_update_language_switches_live_without_reconnect_stall():
    """Verify live in-place language switching via update_settings (no 4.5s reconnect blank gap)."""
    seen_header_langs: list[str] = []
    seen_settings_langs: list[str] = []
    active_lang = ["indic-auto"]

    async def fake_prisma(ws):
        header_lang = ws.request.headers.get("language_code", "")
        seen_header_langs.append(header_lang)
        active_lang[0] = header_lang
        async for msg in ws:
            if isinstance(msg, bytes):
                continue
            data = json.loads(msg)
            if data.get("type") == "update_settings" and data.get("language_code"):
                active_lang[0] = data["language_code"]
                seen_settings_langs.append(active_lang[0])
            elif data.get("type") == "finalize":
                await ws.send(
                    json.dumps(
                        {
                            "transcript": "Sunita Devi weight 54 kilo",
                            "is_final": True,
                            "language_code": active_lang[0],
                        }
                    )
                )
                await ws.close()

    async with websockets.serve(fake_prisma, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        client = GnaniPrismaSTTClient(api_key="live-key", ws_url=f"ws://127.0.0.1:{port}")
        await client.connect(language_code="indic-auto")
        # Switch to en-IN live via update_settings (single persistent socket, zero reconnect stall!)
        await client.update_language("en-IN")
        await client.finalize_turn()
        events = [ev async for ev in client.transcripts()]
        await client.close()

    assert seen_header_langs == ["indic-auto"]
    assert seen_settings_langs == ["indic-auto", "en-IN"]
    assert len(events) == 1
    assert events[0].language == "en-IN"


@pytest.mark.asyncio
async def test_prisma_stt_finalize_flushes_digital_silence_for_server_vad_and_survives_reconnect():
    """Verify Prisma VAD server gets trailing zero-PCM on finalize_turn() and transcripts() never dies on reconnect."""
    conn_count = 0
    zero_bytes_after_finalize = 0
    seen_headers: list[str] = []

    async def fake_prisma_vad(ws):
        nonlocal conn_count, zero_bytes_after_finalize
        conn_count += 1
        my_conn = conn_count
        seen_headers.append(ws.request.headers.get("language_code", ""))
        await ws.send(
            json.dumps(
                {
                    "type": "connected",
                    "message": "STT service ready — VAD service connected",
                    "config": {"sample_rate": 16000, "chunk_size": 512, "language_code": seen_headers[-1]},
                }
            )
        )
        got_finalize = False
        async for msg in ws:
            if isinstance(msg, bytes):
                if got_finalize and not any(msg):
                    zero_bytes_after_finalize += len(msg)
                    if zero_bytes_after_finalize >= 25600:  # >= 800ms of zero-PCM flush
                        await ws.send(
                            json.dumps(
                                {
                                    "type": "transcript",
                                    "text": f"Turn {my_conn} Sunita Devi weight 54 kilo",
                                    "is_final": True,
                                    "detected_language_code": seen_headers[-1],
                                }
                            )
                        )
                        if my_conn == 1:
                            # Simulate upstream idle close between recording sessions!
                            await ws.close(code=1001, reason="idle_timeout")
                            return
                        else:
                            await ws.close(code=1000, reason="")
                            return
            else:
                data = json.loads(msg)
                if data.get("type") == "finalize":
                    got_finalize = True

    async with websockets.serve(fake_prisma_vad, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        client = GnaniPrismaSTTClient(
            api_key="live-key",
            ws_url=f"ws://127.0.0.1:{port}",
            flush_silence_ms=960,
        )
        await client.connect(language_code="en-IN")
        collected = []

        async def consume():
            async for ev in client.transcripts():
                collected.append(ev)

        consumer = asyncio.create_task(consume())
        await asyncio.sleep(0.05)  # allow "connected" frame to mark server VAD ready

        # Session 1: stream speech bytes then abrupt finalize_turn()
        await client.send_audio(b"\x10\x20" * 320)
        await client.finalize_turn()
        await asyncio.sleep(0.25)

        # Session 2 after upstream 1001 idle_timeout: switch language & stream again!
        await client.update_language("hi-IN")
        zero_bytes_after_finalize = 0
        await client.send_audio(b"\x30\x40" * 320)
        await asyncio.sleep(0.05)
        await client.finalize_turn()
        await asyncio.wait_for(consumer, timeout=3.0)
        await client.close()

    assert conn_count == 2
    assert seen_headers == ["en-IN", "hi-IN"]
    assert len(collected) == 2
    assert "Turn 1" in collected[0].text
    assert "Turn 2" in collected[1].text
    assert collected[1].language == "hi-IN"
