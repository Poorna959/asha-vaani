"""Deterministic Multilingual Danger-Sign Escalation Engine (12 NHM Red Flags).

Matches incoming transcripts strictly against the canonical list in docs/danger_signs.json
across English, Hindi, Kannada, Tamil, and Telugu. Runs independently of the LLM with
sub-millisecond latency, word-boundary precision, negation awareness, and maternal/newborn
disambiguation.
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from app.config import get_settings
from app.models import DangerPriority, DangerSignAlert, LanguageCode, VisitType


# Danger signs whose canonical symptoms are inherently absence/negative states
# (e.g., "not moving", "unable to feed", "unresponsive") so negation words inside
# or adjacent to the phrase must NOT suppress escalation.
NEGATIVE_STATE_CODES = frozenset(
    {
        "DS_MAT_NO_FETAL_MOVEMENT",
        "DS_NB_POOR_FEEDING",
        "DS_NB_LETHARGY",
    }
)

PRE_NEGATION_TOKENS = frozenset(
    {
        "no",
        "not",
        "without",
        "none",
        "never",
        "nahi",
        "nahin",
        "na",
        "नहीं",
        "नही",
        "illa",
        "ಇಲ್ಲ",
        "illai",
        "இல்லை",
        "ledu",
        "లేదు",
    }
)

POST_NEGATION_TOKENS = frozenset(
    {
        "nahi",
        "nahin",
        "नहीं",
        "नही",
        "illa",
        "ಇಲ್ಲ",
        "illai",
        "இல்லை",
        "ledu",
        "లేదు",
        "absent",
        "normal",
        "cleared",
        "resolved",
        "stopped",
    }
)

NEWBORN_CONTEXT_PATTERN = re.compile(
    r"(?:(?<!\w)(?:newborn|new\s+born|baby|infant|neonate|navjat|bacha|bachhe|bachha|magu|kuzhandhai|sisuvu|bidda)(?!\w)"
    r"|नवजात|बच्चे|बच्चा|ಮಗು|குழந்தை|శిశువు|బిడ్డ)",
    re.IGNORECASE,
)

PRIORITY_RANK: dict[str, int] = {
    "IMMEDIATE_EMERGENCY": 0,
    "HIGH": 1,
}


@dataclass(frozen=True)
class _CompiledRule:
    code: str
    category: str
    canonical_name: str
    priority: DangerPriority
    referral_advice: dict[str, str]
    patterns: tuple[tuple[str, re.Pattern[str]], ...]


def _normalize_text(text: str) -> str:
    nfc = unicodedata.normalize("NFC", text)
    return re.sub(r"\s+", " ", nfc).strip()


def _is_ascii_phrase(phrase: str) -> bool:
    return all(ord(ch) < 128 for ch in phrase)


def _compile_keyword_pattern(keyword: str) -> re.Pattern[str]:
    norm = _normalize_text(keyword)
    parts = [re.escape(token) for token in re.split(r"[\s\-]+", norm) if token]
    body = r"[\s\-]+".join(parts)
    if _is_ascii_phrase(norm):
        return re.compile(rf"(?<!\w){body}(?!\w)", re.IGNORECASE)
    return re.compile(body, re.IGNORECASE)


def _detect_script_language(text: str) -> str:
    """Infer short language key ('hi'|'kn'|'ta'|'te'|'en') from Indic Unicode ranges."""
    counts = {"hi": 0, "kn": 0, "ta": 0, "te": 0}
    for ch in text:
        cp = ord(ch)
        if 0x0900 <= cp <= 0x097F:
            counts["hi"] += 1
        elif 0x0C80 <= cp <= 0x0CFF:
            counts["kn"] += 1
        elif 0x0B80 <= cp <= 0x0BFF:
            counts["ta"] += 1
        elif 0x0C00 <= cp <= 0x0C7F:
            counts["te"] += 1
    best_lang, best_count = max(counts.items(), key=lambda item: item[1])
    return best_lang if best_count > 0 else "en"


def resolve_short_language(
    language_code: Optional[str],
    utterance: str = "",
) -> str:
    """Resolve BCP-47 language code (or 'indic-auto') into 'en'|'hi'|'kn'|'ta'|'te'."""
    if not language_code or language_code == "indic-auto":
        return _detect_script_language(utterance)
    prefix = language_code.split("-")[0].lower()
    if prefix in {"en", "hi", "kn", "ta", "te"}:
        return prefix
    return "en"


def _is_negated_match(text: str, start: int, end: int) -> bool:
    """Check whether a positive-symptom match is immediately negated in context."""
    # Split clause around sentence/comma boundaries so "no fever, bleeding present" doesn't
    # mistakenly negate "bleeding".
    before = text[:start]
    clause_before = re.split(r"[,.;!?\n।]|\b(?:but|lekin|par|magar|aadare|aanaal|kaani)\b", before, flags=re.IGNORECASE)[-1]
    pre_tokens = [tok.lower() for tok in re.findall(r"[^\s,.;!?\n।]+", clause_before)[-3:]]
    if any(tok in PRE_NEGATION_TOKENS for tok in pre_tokens):
        return True

    after = text[end:]
    clause_after = re.split(r"[,.;!?\n।]|\b(?:but|lekin|par|magar|aadare|aanaal|kaani)\b", after, flags=re.IGNORECASE)[0]
    post_tokens = [tok.lower() for tok in re.findall(r"[^\s,.;!?\n।]+", clause_after)[:3]]
    if any(tok in POST_NEGATION_TOKENS for tok in post_tokens):
        return True

    return False


class DangerSignDetector:
    """Deterministic matcher for the canonical 12-item NHM ASHA danger-sign list."""

    def __init__(self, danger_signs_path: Optional[Path] = None) -> None:
        self.path = danger_signs_path or get_settings().danger_signs_path
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        self.version: str = raw.get("version", "1.0")
        self.disclaimer: str = raw.get("disclaimer", "")
        self._rules: list[_CompiledRule] = []
        self._rules_by_code: dict[str, _CompiledRule] = {}

        for item in raw["danger_signs"]:
            seen_kw: set[str] = set()
            compiled_patterns: list[tuple[str, re.Pattern[str]]] = []
            for kw_list in item["keywords"].values():
                for kw in kw_list:
                    norm_kw = _normalize_text(kw).lower()
                    if not norm_kw or norm_kw in seen_kw:
                        continue
                    seen_kw.add(norm_kw)
                    compiled_patterns.append((kw, _compile_keyword_pattern(kw)))
            # Sort longest keywords first so specific phrases win over shorter stems
            compiled_patterns.sort(key=lambda pair: len(pair[0]), reverse=True)

            rule = _CompiledRule(
                code=item["code"],
                category=item["category"],
                canonical_name=item["canonical_name"],
                priority=item["priority"],
                referral_advice=dict(item["referral_advice"]),
                patterns=tuple(compiled_patterns),
            )
            self._rules.append(rule)
            self._rules_by_code[rule.code] = rule

    @property
    def canonical_codes(self) -> list[str]:
        """Return all canonical danger-sign codes from the approved list."""
        return [rule.code for rule in self._rules]

    def _to_alert(self, rule: _CompiledRule, short_lang: str, matched_keyword: str) -> DangerSignAlert:
        instruction = rule.referral_advice.get(short_lang) or rule.referral_advice["en"]
        return DangerSignAlert(
            code=rule.code,
            category=rule.category,
            canonical_name=rule.canonical_name,
            priority=rule.priority,
            localized_instruction=instruction,
            matched_keyword=matched_keyword,
        )

    def detect(
        self,
        text: str,
        language_code: LanguageCode | str = "indic-auto",
        visit_type: Optional[VisitType] = None,
    ) -> list[DangerSignAlert]:
        """Scan utterance deterministically and return prioritized DangerSignAlert list."""
        normalized = _normalize_text(text)
        if not normalized:
            return []

        short_lang = resolve_short_language(language_code, normalized)
        has_newborn_context = visit_type == "NEWBORN" or bool(
            NEWBORN_CONTEXT_PATTERN.search(normalized)
        )

        matched: dict[str, DangerSignAlert] = {}
        for rule in self._rules:
            for raw_kw, pattern in rule.patterns:
                match = pattern.search(normalized)
                if match is None:
                    continue
                if rule.code not in NEGATIVE_STATE_CODES and _is_negated_match(
                    normalized, match.start(), match.end()
                ):
                    continue
                matched[rule.code] = self._to_alert(rule, short_lang, raw_kw)
                break

        # Disambiguate maternal vs newborn overlapping symptoms
        if has_newborn_context:
            if "DS_MAT_HIGH_FEVER" in matched:
                if "DS_NB_HIGH_FEVER_COLD" not in matched:
                    nb_fever_rule = self._rules_by_code["DS_NB_HIGH_FEVER_COLD"]
                    matched["DS_NB_HIGH_FEVER_COLD"] = self._to_alert(
                        nb_fever_rule,
                        short_lang,
                        matched["DS_MAT_HIGH_FEVER"].matched_keyword,
                    )
                matched.pop("DS_MAT_HIGH_FEVER", None)

            if "DS_MAT_CONVULSIONS" in matched:
                if "DS_NB_CONVULSIONS" not in matched:
                    nb_conv_rule = self._rules_by_code["DS_NB_CONVULSIONS"]
                    matched["DS_NB_CONVULSIONS"] = self._to_alert(
                        nb_conv_rule,
                        short_lang,
                        matched["DS_MAT_CONVULSIONS"].matched_keyword,
                    )
                matched.pop("DS_MAT_CONVULSIONS", None)
        else:
            # If maternal visit and generic "fits"/"valippu" triggered both, keep maternal
            if "DS_MAT_CONVULSIONS" in matched and "DS_NB_CONVULSIONS" in matched:
                matched.pop("DS_NB_CONVULSIONS", None)

        alerts = list(matched.values())
        alerts.sort(key=lambda a: (PRIORITY_RANK.get(a.priority, 99), a.code))
        return alerts

    def build_referral_instruction(
        self,
        alerts: list[DangerSignAlert],
        language_code: LanguageCode | str = "en-IN",
    ) -> str:
        """Combine one or more alerts into a clear, spoken PHC referral script."""
        if not alerts:
            return ""
        if len(alerts) == 1:
            return alerts[0].localized_instruction
        short_lang = resolve_short_language(language_code)
        instructions = []
        for alert in alerts:
            rule = self._rules_by_code.get(alert.code)
            if rule:
                instructions.append(rule.referral_advice.get(short_lang) or rule.referral_advice["en"])
            else:
                instructions.append(alert.localized_instruction)
        return " ".join(instructions)
