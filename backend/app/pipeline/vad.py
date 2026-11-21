"""Adaptive Noise-Floor VAD & Endpointing Controller (with optional Silero ONNX support).

Designed for household and street noise:
- Processes 16kHz 16-bit mono LE PCM frames (e.g. 20ms = 640 bytes / 32ms = 1024 bytes).
- Continuously adapts to steady ambient street/household noise floor via EMA tracking.
- Requires consecutive speech frames (min_speech_ms) before emitting SPEECH_START so
  brief street clicks, honks, or taps don't false-trigger barge-in.
- Emits SPEECH_END after `silence_tail_ms` (default 550ms) of trailing silence to
  minimize Time-to-First-Audio (TTFA) without cutting off mid-thought.
- Automatically loads Silero VAD ONNX (`silero_vad.onnx`) via onnxruntime if available.
"""

from __future__ import annotations

import logging
from enum import Enum
from pathlib import Path
from typing import Any, Optional

import numpy as np

logger = logging.getLogger(__name__)


class VadEvent(str, Enum):
    SILENCE = "SILENCE"
    SPEECH_START = "SPEECH_START"
    SPEECH_CONTINUE = "SPEECH_CONTINUE"
    SPEECH_END = "SPEECH_END"


class AdaptiveEndpointVAD:
    """Real-time speech activity detector with noise-floor adaptation and barge-in debounce."""

    def __init__(
        self,
        sample_rate: int = 16000,
        silence_tail_ms: int = 550,
        speech_threshold: float = 0.5,
        min_speech_ms: int = 60,
        min_rms_floor: float = 0.015,
        snr_ratio: float = 2.6,
        silero_model_path: Optional[Path] = None,
    ) -> None:
        self.sample_rate = sample_rate
        self.silence_tail_ms = silence_tail_ms
        self.speech_threshold = speech_threshold
        self.min_speech_ms = min_speech_ms
        self.min_rms_floor = min_rms_floor
        self.snr_ratio = snr_ratio

        self._noise_floor: float = 0.008
        self._in_speech: bool = False
        self._speech_ms_accum: float = 0.0
        self._silence_ms_accum: float = 0.0

        # Optional Silero VAD ONNX runtime session
        self._onnx_session: Any = None
        self._silero_state: Optional[np.ndarray] = None
        if silero_model_path and silero_model_path.exists():
            self._init_silero(silero_model_path)

    def _init_silero(self, model_path: Path) -> None:
        try:
            import onnxruntime as ort

            opts = ort.SessionOptions()
            opts.inter_op_num_threads = 1
            opts.intra_op_num_threads = 1
            self._onnx_session = ort.InferenceSession(str(model_path), sess_options=opts)
            self._silero_state = np.zeros((2, 1, 128), dtype=np.float32)
            logger.info("Loaded Silero VAD ONNX model from %s", model_path)
        except Exception as exc:  # pragma: no cover
            logger.warning("Could not load Silero ONNX (%s); using adaptive SNR VAD.", exc)
            self._onnx_session = None

    @property
    def noise_floor_rms(self) -> float:
        """Return current learned ambient noise floor RMS in [0, 1] scale."""
        return round(float(self._noise_floor), 5)

    def reset(self) -> None:
        """Reset turn speech/silence accumulators while keeping learned noise floor."""
        self._in_speech = False
        self._speech_ms_accum = 0.0
        self._silence_ms_accum = 0.0
        if self._silero_state is not None:
            self._silero_state.fill(0.0)

    def _speech_probability(self, samples_f32: np.ndarray) -> float:
        """Compute [0, 1] speech likelihood using Silero ONNX (if loaded) or adaptive SNR."""
        if self._onnx_session is not None and len(samples_f32) == 512:
            try:
                ort_inputs = {
                    "input": samples_f32.reshape(1, -1),
                    "state": self._silero_state,
                    "sr": np.array(self.sample_rate, dtype=np.int64),
                }
                out, next_state = self._onnx_session.run(None, ort_inputs)
                self._silero_state = next_state
                return float(out[0][0])
            except Exception:  # pragma: no cover
                pass

        rms = float(np.sqrt(np.mean(samples_f32 * samples_f32))) if samples_f32.size else 0.0
        dynamic_gate = max(self.min_rms_floor, self._noise_floor * self.snr_ratio)

        # Slowly track stationary ambient background noise only when below speech gate
        if not self._in_speech and rms < dynamic_gate:
            self._noise_floor = 0.92 * self._noise_floor + 0.08 * rms

        if rms <= 1e-6:
            return 0.0
        ratio = rms / dynamic_gate
        # Smooth sigmoid-like mapping centered at ratio == 1.0 -> prob == 0.5
        return float(min(1.0, max(0.0, ratio / (1.0 + ratio))))

    def process_pcm(self, pcm_bytes: bytes) -> VadEvent:
        """Process one raw 16kHz 16-bit signed LE PCM frame and return its VadEvent."""
        if len(pcm_bytes) < 2:
            return VadEvent.SILENCE

        # Ensure even byte count for int16 view
        usable = pcm_bytes[: len(pcm_bytes) - (len(pcm_bytes) % 2)]
        samples_i16 = np.frombuffer(usable, dtype="<i2")
        if samples_i16.size == 0:
            return VadEvent.SILENCE

        frame_ms = (samples_i16.size / float(self.sample_rate)) * 1000.0
        samples_f32 = samples_i16.astype(np.float32) / 32768.0
        prob = self._speech_probability(samples_f32)
        is_voice = prob >= self.speech_threshold

        if is_voice:
            self._silence_ms_accum = 0.0
            if not self._in_speech:
                self._speech_ms_accum += frame_ms
                if self._speech_ms_accum >= self.min_speech_ms:
                    self._in_speech = True
                    return VadEvent.SPEECH_START
                return VadEvent.SILENCE
            return VadEvent.SPEECH_CONTINUE

        # Non-voice frame
        if self._in_speech:
            self._silence_ms_accum += frame_ms
            if self._silence_ms_accum >= self.silence_tail_ms:
                self._in_speech = False
                self._speech_ms_accum = 0.0
                self._silence_ms_accum = 0.0
                return VadEvent.SPEECH_END
            return VadEvent.SPEECH_CONTINUE

        self._speech_ms_accum = 0.0
        return VadEvent.SILENCE
