"""Gemini 2.5 Flash Clinical Extraction, Code-Mix Normalization & Read-Back State Engine.

Implements:
- Clean LLMProvider abstraction (Gemini 2.5 Flash primary + resilient offline fallback).
- Indic + English code-mix number/unit/clinical-term normalization ("do point aath kilo" -> 2.8).
- Pre-selected patient baseline context inheritance (FR-7.6).
- Deterministic delta diff computation for voice corrections ("no, weight is 2.8 kilo").
- Mandatory read-back-before-save gate across EN, HI, KN, TA, TE.
"""

from __future__ import annotations

import json
import logging
import re
from abc import ABC, abstractmethod
from datetime import date, timedelta
from typing import Any, Optional

import httpx
from pydantic import BaseModel, Field

from app.config import Settings, get_settings
from app.models import (
    CustomRegister,
    FieldDiff,
    LanguageCode,
    RegisterField,
    RegisterIntakeDraft,
    SessionStatus,
    VisitDraft,
    VisitType,
    VoiceExtractionOutput,
    VoiceIntent,
)
from app.pipeline.danger_signs import resolve_short_language
from app.services.followup import compute_next_visit_plan

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Explicit Pydantic Schema for Gemini Structured Output (no free-form dict)
# ---------------------------------------------------------------------------


class GeminiExtractionPayload(BaseModel):
    """Typed JSON response schema enforced by Gemini 2.5 Flash."""

    intent: VoiceIntent = Field(
        description=(
            "LOG_VISIT when adding visit details, CORRECT_FIELD when fixing a previously "
            "captured field, CONFIRM_SAVE when the ASHA worker explicitly confirms saving "
            "after read-back, CANCEL when discarding, ASK_CLARIFICATION if unclear."
        )
    )
    beneficiary_id: Optional[str] = None
    beneficiary_name: Optional[str] = None
    visit_type: Optional[VisitType] = None
    weight_kg: Optional[float] = None
    vaccinations_due: Optional[list[str]] = None
    next_visit_date: Optional[str] = Field(default=None, description="ISO date YYYY-MM-DD if spoken")
    ifa_tablets_given: Optional[int] = None
    notes: Optional[str] = None
    spoken_readback: str = Field(
        default="",
        description="Concise, polite read-back in the ASHA worker's language. No diagnosis or medicine advice.",
    )


class ClinicalTurnResult(BaseModel):
    """Complete state transition produced for a single voice turn."""

    updated_draft: VisitDraft
    extraction: VoiceExtractionOutput
    status: SessionStatus
    should_save: bool = False
    readback_completed: bool = False


# ---------------------------------------------------------------------------
# Indic + English Code-Mix Normalizers
# ---------------------------------------------------------------------------

DIGIT_WORDS: dict[str, str] = {
    # English
    "zero": "0",
    "one": "1",
    "two": "2",
    "three": "3",
    "four": "4",
    "five": "5",
    "six": "6",
    "seven": "7",
    "eight": "8",
    "nine": "9",
    "ten": "10",
    # Hindi transliterated
    "ek": "1",
    "do": "2",
    "teen": "3",
    "char": "4",
    "paanch": "5",
    "panch": "5",
    "chhe": "6",
    "che": "6",
    "saat": "7",
    "aath": "8",
    "ath": "8",
    "nau": "9",
    "das": "10",
    "bees": "20",
    "tees": "30",
    "chalis": "40",
    "pachas": "50",
    "saath": "60",
    # Kannada transliterated
    "ondu": "1",
    "eradu": "2",
    "mooru": "3",
    "naalku": "4",
    "aidu": "5",
    "aaru": "6",
    "elu": "7",
    "entu": "8",
    "ombattu": "9",
    "hattu": "10",
    # Tamil transliterated
    "onnu": "1",
    "rendu": "2",
    "moonu": "3",
    "naalu": "4",
    "anju": "5",
    "ezhu": "7",
    "ettu": "8",
    # Telugu transliterated
    "okati": "1",
    "rendu_te": "2",
    "moodu": "3",
    "naalugu": "4",
    "enimidi": "8",
}

VACCINE_CANONICAL_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("TT2", re.compile(r"(?<!\w)(?:tt[\s\-]?2|second\s+tt|tt\s+second)(?!\w)", re.IGNORECASE)),
    ("TT1", re.compile(r"(?<!\w)(?:tt[\s\-]?1|first\s+tt|tt\s+first)(?!\w)", re.IGNORECASE)),
    ("TT Injection", re.compile(r"(?<!\w)tt(?:\s+injection|\s+vaccine|\s+shot|\s+tika)?(?!\w)", re.IGNORECASE)),
    ("BCG", re.compile(r"(?<!\w)bcg(?!\w)", re.IGNORECASE)),
    ("OPV-0", re.compile(r"(?<!\w)(?:opv[\s\-]?0|polio\s+zero|birth\s+polio)(?!\w)", re.IGNORECASE)),
    ("OPV-1", re.compile(r"(?<!\w)opv[\s\-]?1(?!\w)", re.IGNORECASE)),
    ("OPV", re.compile(r"(?<!\w)(?:opv|polio\s+drops?)(?!\w)", re.IGNORECASE)),
    ("Pentavalent-1", re.compile(r"(?<!\w)penta(?:valent)?[\s\-]?1(?!\w)", re.IGNORECASE)),
    ("Pentavalent-2", re.compile(r"(?<!\w)penta(?:valent)?[\s\-]?2(?!\w)", re.IGNORECASE)),
    ("Pentavalent-3", re.compile(r"(?<!\w)penta(?:valent)?[\s\-]?3(?!\w)", re.IGNORECASE)),
    ("Pentavalent", re.compile(r"(?<!\w)pentavalent(?!\w)", re.IGNORECASE)),
    ("Rotavirus-1", re.compile(r"(?<!\w)rota(?:virus)?[\s\-]?1(?!\w)", re.IGNORECASE)),
    ("Rotavirus", re.compile(r"(?<!\w)rotavirus(?!\w)", re.IGNORECASE)),
    ("Hepatitis B", re.compile(r"(?<!\w)hep(?:atitis)?[\s\-]?b(?!\w)", re.IGNORECASE)),
    ("MR-1", re.compile(r"(?<!\w)(?:mr[\s\-]?1|measles)(?!\w)", re.IGNORECASE)),
)

CONFIRM_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(
        r"^(?:yes|yeah|yep|ok|okay|correct|confirm|confirmed|save|save\s+it|yes\s+save|"
        r"haan|han|haa|theek\s+hai|thik\s+hai|haan\s+theek\s+hai|sahi\s+hai|save\s+karo|seve\s+karo|"
        r"sari|houdu|howdu|aam|aama|seri|avunu|sare)[\s.!।,]*$",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:हाँ\s*ठीक\s*है|ठीक\s*है|सही\s*है|सेव\s*करो|सेव\s*कर\s*दो|"
        r"ಹೌದು|ಸರಿ\s*ಇದೆ|ಉಳಿಸಿ|ಸೇವ್\s*ಮಾಡಿ|"
        r"ஆம்|சரி|சேமி|"
        r"అవును|సరే|సేవ్\s*చేయండి)",
        re.IGNORECASE,
    ),
)

CANCEL_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(
        r"(?<!\w)(?:cancel|discard|reset|abort|radd\s+karo|mat\s+save|don't\s+save|do\s+not\s+save)(?!\w)"
        r"|रद्द\s*करो|मत\s*सेव|ಬೇಡ|ರದ್ದು|வேண்டாம்|వద్దు",
        re.IGNORECASE,
    ),
)

CORRECTION_CUE_PATTERN = re.compile(
    r"(?<!\w)(?:no|nope|wrong|change|update|correct|actually|not|nahi|nahin|galat|badal|illa|illai|ledu)(?!\w)"
    r"|नहीं|गलत|बदल|ಇಲ್ಲ|ತಪ್ಪು|இல்லை|లేదు",
    re.IGNORECASE,
)

MONTH_MAP = {
    "jan": 1, "january": 1,
    "feb": 2, "february": 2,
    "mar": 3, "march": 3,
    "apr": 4, "april": 4,
    "may": 5,
    "jun": 6, "june": 6,
    "jul": 7, "july": 7,
    "aug": 8, "august": 8,
    "sep": 9, "sept": 9, "september": 9,
    "oct": 10, "october": 10,
    "nov": 11, "november": 11,
    "dec": 12, "december": 12,
}


def normalize_indic_numbers(text: str) -> str:
    """Convert spoken Indic/English decimal phrases like 'do point aath' into '2.8'."""
    word_alt = "|".join(sorted(DIGIT_WORDS.keys(), key=len, reverse=True))
    decimal_pattern = re.compile(
        rf"(?<!\w)({word_alt}|\d+)\s+(?:point|dashamlav|dot)\s+({word_alt}|\d+)(?!\w)",
        re.IGNORECASE,
    )

    def _replace_decimal(match: re.Match[str]) -> str:
        left = match.group(1).lower()
        right = match.group(2).lower()
        left_num = DIGIT_WORDS.get(left, left)
        right_num = DIGIT_WORDS.get(right, right)
        return f"{left_num}.{right_num}"

    result = decimal_pattern.sub(_replace_decimal, text)

    # Also convert standalone spoken number right before weight/tablet units
    unit_pattern = re.compile(
        rf"(?<!\w)({word_alt})\s+(kilo|kg|kilogram|gram|goli|tablets?|days?|weeks?)(?!\w)",
        re.IGNORECASE,
    )

    def _replace_unit_num(match: re.Match[str]) -> str:
        num = DIGIT_WORDS.get(match.group(1).lower(), match.group(1))
        return f"{num} {match.group(2)}"

    return unit_pattern.sub(_replace_unit_num, result)


def _extract_weight_kg(text: str) -> Optional[float]:
    norm = normalize_indic_numbers(text)

    # Grams -> convert to kg (e.g. "2800 gram" -> 2.8)
    gram_match = re.search(
        r"(?<!\w)(\d{3,4}(?:\.\d+)?)\s*(?:grams?|gm|g)\b",
        norm,
        re.IGNORECASE,
    )
    if gram_match:
        return round(float(gram_match.group(1)) / 1000.0, 2)

    # Kilograms (e.g. "2.8 kilo", "54.5 kg", "weight is 2.8", "wajan 52")
    kg_match = re.search(
        r"(?<!\w)(\d{1,3}(?:\.\d{1,2})?)\s*(?:kilo|kilos|kg|kgs|kilogram|kilograms|किलो|ಕಿಲೋ|கிலோ|కిలో(?:లు)?)(?!\w)",
        norm,
        re.IGNORECASE,
    )
    if kg_match:
        return round(float(kg_match.group(1)), 2)

    labeled_match = re.search(
        r"(?:weight|wajan|vajan|तौल|वजन|ತೂಕ|எடை|బరువు)\s*(?:is|hai|=|:|to)?\s*(\d{1,3}(?:\.\d{1,2})?)(?!\w)",
        norm,
        re.IGNORECASE,
    )
    if labeled_match:
        val = float(labeled_match.group(1))
        if val >= 500:
            return round(val / 1000.0, 2)
        return round(val, 2)

    return None


def _extract_vaccines(text: str) -> list[str]:
    found: list[str] = []
    for canonical, pattern in VACCINE_CANONICAL_PATTERNS:
        if pattern.search(text):
            # Avoid adding generic "TT Injection" if specific "TT1"/"TT2" already matched
            if canonical == "TT Injection" and any(v in found for v in ("TT1", "TT2")):
                continue
            if canonical == "OPV" and any(v.startswith("OPV-") for v in found):
                continue
            if canonical == "Pentavalent" and any(v.startswith("Pentavalent-") for v in found):
                continue
            if canonical == "Rotavirus" and any(v.startswith("Rotavirus-") for v in found):
                continue
            if canonical not in found:
                found.append(canonical)
    return found


def _extract_ifa_tablets(text: str) -> Optional[int]:
    norm = normalize_indic_numbers(text)
    explicit = re.search(
        r"(?:(\d{1,3})\s*(?:(?:ifa|आईएफए)\s*(?:goli|goliyan|tablets?|pills?|गोली|गोलियां|ಗುಳಿಗೆ|மாத்திரை|మాత్రలు)?|iron\s*(?:goli|tablets?|गोली))|"
        r"(?:(?:ifa|आईएफए)\s*(?:goli|goliyan|tablets?|pills?|गोली|गोलियां|ಗುಳಿಗೆ|மாத்திரை|మాత్రలు)|iron\s*(?:goli|tablets?|गोली))\s*(?:given|di|diya|count|दी)?\s*(\d{1,3}))",
        norm,
        re.IGNORECASE,
    )
    if explicit:
        raw_num = explicit.group(1) or explicit.group(2)
        return int(raw_num)

    if re.search(r"(?<!\w)(?:ifa\s*(?:goli|tablets?|गोली)|iron\s*tablet|आईएफए\s*गोली)(?!\w)", norm, re.IGNORECASE):
        return 30  # Standard NHM monthly ANC supply when count not specified
    return None


def _extract_visit_type(text: str) -> Optional[VisitType]:
    lower = text.lower()
    if re.search(r"(?<!\w)(?:anc|pregnancy|pregnant|garbhavati|antenatal)(?!\w)|गर्भवती|एएनसी|ಗರ್ಭಿಣಿ", lower):
        return "ANC"
    if re.search(r"(?<!\w)(?:hbnc|newborn|new\s+born|neonate|navjat|baby)(?!\w)|नवजात|ನವಜಾತ", lower):
        return "NEWBORN"
    if re.search(r"(?<!\w)(?:immunisation|immunization|vaccination|vaccine|tikakaran|pentavalent|bcg|opv|polio)(?!\w)|टीकाकरण", lower):
        return "IMMUNISATION"
    return None


def _extract_next_visit_date(text: str, today: Optional[date] = None) -> Optional[str]:
    ref = today or date.today()
    iso_match = re.search(r"\b(20\d{2}-\d{2}-\d{2})\b", text)
    if iso_match:
        return iso_match.group(1)

    # e.g. "25 December" or "December 25"
    month_alt = "|".join(MONTH_MAP.keys())
    dm_match = re.search(rf"\b(\d{{1,2}})\s+({month_alt})(?:\s+(20\d{{2}}))?\b", text, re.IGNORECASE)
    if dm_match:
        day = int(dm_match.group(1))
        month = MONTH_MAP[dm_match.group(2).lower()]
        year = int(dm_match.group(3)) if dm_match.group(3) else ref.year
        try:
            candidate = date(year, month, day)
            if not dm_match.group(3) and candidate < ref:
                candidate = date(year + 1, month, day)
            return candidate.isoformat()
        except ValueError:
            pass

    md_match = re.search(rf"\b({month_alt})\s+(\d{{1,2}})(?:,?\s+(20\d{{2}}))?\b", text, re.IGNORECASE)
    if md_match:
        month = MONTH_MAP[md_match.group(1).lower()]
        day = int(md_match.group(2))
        year = int(md_match.group(3)) if md_match.group(3) else ref.year
        try:
            candidate = date(year, month, day)
            if not md_match.group(3) and candidate < ref:
                candidate = date(year + 1, month, day)
            return candidate.isoformat()
        except ValueError:
            pass

    # Relative phrasing ("next week", "in 7 days", "after 14 days", "next month")
    days_match = re.search(r"(?:in|after)\s+(\d{1,2})\s+days?", text, re.IGNORECASE)
    if days_match:
        return (ref + timedelta(days=int(days_match.group(1)))).isoformat()
    if re.search(r"\bnext\s+week\b", text, re.IGNORECASE):
        return (ref + timedelta(days=7)).isoformat()
    if re.search(r"\bnext\s+month\b", text, re.IGNORECASE):
        return (ref + timedelta(days=28)).isoformat()
    return None


NON_SURNAME_TOKENS: frozenset[str] = frozenset(
    {
        "anc", "hbnc", "visit", "checkup", "check", "weight", "wajan", "vajan",
        "kilo", "kg", "gram", "baby", "newborn", "infant", "mother", "pregnant",
        "pregnancy", "immunisation", "immunization", "vaccination", "vaccine",
        "tt", "tt1", "tt2", "bcg", "opv", "pentavalent", "ifa", "iron", "goli",
        "fever", "high", "bleeding", "fits", "convulsions", "house", "ward",
        "age", "umar", "blood", "group", "history", "medical", "phone", "mobile",
        "lmp", "parity", "gravida", "no", "not", "is", "has", "had", "for",
        "ka", "ki", "ke", "ko", "mein", "me", "hai", "se", "and", "or", "with",
        "next", "due", "given", "took", "normal", "severe", "today", "yesterday",
        "patient", "beneficiary", "name",
    }
)


def _match_roster_patient(
    text: str,
    roster: list[dict[str, Any]],
) -> Optional[dict[str, Any]]:
    lower = text.lower()
    # 1. Exact ID match (e.g. BEN-001)
    for entry in roster:
        pid = str(entry.get("id", "")).lower()
        if pid and re.search(rf"(?<!\w){re.escape(pid)}(?!\w)", lower):
            return entry

    # 2. Full name or first+last match
    for entry in roster:
        full_name = str(entry.get("patient_name", "")).strip()
        clean_name = re.sub(r"\s*\([^)]*\)", "", full_name).strip().lower()
        if clean_name and clean_name in lower:
            return entry

    # 3. First name match (only if unique, >= 4 chars, and NOT followed by a conflicting surname)
    hits: list[dict[str, Any]] = []
    for entry in roster:
        full_name = str(entry.get("patient_name", "")).strip()
        clean_parts = re.sub(r"\s*\([^)]*\)", "", full_name).strip().split()
        if not clean_parts:
            continue
        first = clean_parts[0].lower()
        expected_last = clean_parts[1].lower() if len(clean_parts) >= 2 else None
        if len(first) < 4:
            continue
        m = re.search(rf"(?<!\w){re.escape(first)}(?!\w)", lower)
        if not m:
            continue
        # Inspect immediate next word: if it looks like a different surname (e.g. "Priya Sharma"
        # vs roster "Priya Nair"), reject the false first-name hit.
        after = text[m.end() :].lstrip(" ,:")
        next_tok_match = re.match(r"([A-Za-z]+)", after)
        if expected_last and next_tok_match:
            next_tok = next_tok_match.group(1)
            next_low = next_tok.lower()
            if (
                next_low != expected_last
                and next_low not in NON_SURNAME_TOKENS
                and len(next_low) >= 3
            ):
                continue
        hits.append(entry)
    if len(hits) == 1:
        return hits[0]

    # 4. House number match ("House 14")
    house_match = re.search(r"\bhouse\s*(?:no\.?|number)?\s*0*(\d+)\b", lower)
    if house_match:
        target_num = house_match.group(1)
        for entry in roster:
            house_val = str(entry.get("field_answers", {}).get("House / Ward", "")).lower()
            if re.search(rf"\bhouse\s*0*{re.escape(target_num)}\b", house_val):
                return entry
    return None


def _extract_unseen_beneficiary_name(text: str) -> Optional[str]:
    """Extract a walk-in patient name when not found in the village roster (offline fallback)."""
    cleaned = text.strip()
    if not cleaned:
        return None

    # 1. Explicit introducer ("patient Priya Sharma", "name is Radha Bai", "beneficiary Sita")
    explicit = re.search(
        r"(?<!\w)(?:patient(?:\s+name)?|beneficiary(?:\s+name)?|mother(?:\s+name)?|name)\s*(?:is|hai|:|-)?\s*"
        r"([A-Za-z][A-Za-z'.-]+(?:\s+[A-Za-z][A-Za-z'.-]+){0,2})",
        cleaned,
        re.IGNORECASE,
    )
    if explicit:
        words = [w for w in explicit.group(1).split() if w.lower() not in NON_SURNAME_TOKENS]
        if words:
            return " ".join(w.capitalize() for w in words[:3])

    # 2. Leading 2-to-3 word proper name before clinical cues ("Priya Sharma ANC checkup...")
    leading = re.match(
        r"^([A-Za-z][A-Za-z'.-]{1,}(?:\s+[A-Za-z][A-Za-z'.-]{1,}){1,2}?)(?=\s*[,:\-]|"
        r"\s+(?:anc|hbnc|pregnancy|pregnant|newborn|baby|immunisation|immunization|checkup|visit|"
        r"weight|wajan|age|umar|blood|house|ward|tt|tt1|tt2|ifa|bcg|opv|bleeding|high\s+fever|has|ka|ki)\b)",
        cleaned,
        re.IGNORECASE,
    )
    if leading:
        raw_words = leading.group(1).split()
        filtered = [w for w in raw_words if w.lower() not in NON_SURNAME_TOKENS]
        if len(filtered) == len(raw_words) and len(filtered) >= 2:
            return " ".join(w.capitalize() for w in filtered)

    return None


# ---------------------------------------------------------------------------
# Localized Read-Back Builder (Zero Diagnosis / Zero Medicine Advice)
# ---------------------------------------------------------------------------


def build_localized_readback(
    draft: VisitDraft,
    diffs: list[FieldDiff],
    intent: VoiceIntent,
    language_code: LanguageCode | str,
    utterance: str = "",
) -> str:
    """Build concise, natural localized read-back asking for confirmation before saving."""
    lang = resolve_short_language(language_code, utterance)

    if intent == "CANCEL":
        return {
            "hi": "ठीक है, यह विज़िट रिकॉर्ड रद्द कर दिया गया है।",
            "kn": "ಸರಿ, ಈ ಭೇಟಿ ದಾಖಲೆಯನ್ನು ರದ್ದುಗೊಳಿಸಲಾಗಿದೆ.",
            "ta": "சரி, இந்த வருகை பதிவு ரத்து செய்யப்பட்டது.",
            "te": "సరే, ఈ విజిట్ రికార్డు రద్దు చేయబడింది.",
        }.get(lang, "Okay, this visit draft has been cancelled.")

    # Fast delta read-back when correcting a specific field (keeps TTFA & turn time minimal)
    if intent == "CORRECT_FIELD" and diffs:
        parts = []
        for d in diffs:
            if d.field == "weight_kg":
                parts.append(f"weight {d.new_value} kilo")
            elif d.field == "next_visit_date":
                parts.append(f"next visit {d.new_value}")
            elif d.field == "vaccinations_due":
                joined = ", ".join(d.new_value or [])
                parts.append(f"vaccinations due {joined}")
            else:
                parts.append(f"{d.field.replace('_', ' ')} {d.new_value}")
        change_summary = ", ".join(parts)

        if lang == "hi":
            w_diff = next((d for d in diffs if d.field == "weight_kg"), None)
            if w_diff and len(diffs) == 1:
                return f"वजन {w_diff.new_value} किलो अपडेट कर दिया है। क्या मैं यह रिकॉर्ड सेव कर लूं?"
            return f"रिकॉर्ड अपडेट कर दिया है: {change_summary}। क्या मैं सेव कर लूं?"
        if lang == "kn":
            w_diff = next((d for d in diffs if d.field == "weight_kg"), None)
            if w_diff and len(diffs) == 1:
                return f"ತೂಕವನ್ನು {w_diff.new_value} ಕಿಲೋ ಎಂದು ಬದಲಾಯಿಸಲಾಗಿದೆ. ಈ ದಾಖಲೆಯನ್ನು ಉಳಿಸಬೇಕೆ?"
            return f"ವಿವರಗಳನ್ನು ಬದಲಾಯಿಸಲಾಗಿದೆ: {change_summary}. ದಾಖಲೆಯನ್ನು ಉಳಿಸಬೇಕೆ?"
        if lang == "ta":
            w_diff = next((d for d in diffs if d.field == "weight_kg"), None)
            if w_diff and len(diffs) == 1:
                return f"எடை {w_diff.new_value} கிலோ என மாற்றப்பட்டது. இந்த பதிவை சேமிக்கலாமா?"
            return f"பதிவு மாற்றப்பட்டது: {change_summary}. சேமிக்கலாமா?"
        if lang == "te":
            w_diff = next((d for d in diffs if d.field == "weight_kg"), None)
            if w_diff and len(diffs) == 1:
                return f"బరువు {w_diff.new_value} కిలోలుగా మార్చబడింది. ఈ రికార్డును సేవ్ చేయాలా?"
            return f"వివరాలు మార్చబడ్డాయి: {change_summary}. సేవ్ చేయాలా?"
        return f"Updated {change_summary}. Shall I save the record?"

    # Full structured read-back before saving
    name = draft.beneficiary_name or "beneficiary"
    vtype = draft.visit_type or "visit"
    weight_str = f"{draft.weight_kg} kilo" if draft.weight_kg is not None else None
    vaccines_str = ", ".join(draft.vaccinations_due) if draft.vaccinations_due else None
    ifa_str = f"{draft.ifa_tablets_given} IFA goli" if draft.ifa_tablets_given else None
    next_str = draft.next_visit_date

    if lang == "hi":
        bits = [f"{name}", f"{vtype} जांच"]
        if draft.weight_kg is not None:
            bits.append(f"वजन {draft.weight_kg} किलो")
        if ifa_str:
            bits.append(f"{draft.ifa_tablets_given} आईएफए गोली")
        if vaccines_str:
            bits.append(f"टीकाकरण बाकी: {vaccines_str}")
        if next_str:
            bits.append(f"अगली विज़िट {next_str}")
        return f"{', '.join(bits)}। क्या यह सही है, सेव कर लूं?"

    if lang == "kn":
        bits = [f"{name}", f"{vtype} ಭೇಟಿ"]
        if draft.weight_kg is not None:
            bits.append(f"ತೂಕ {draft.weight_kg} ಕಿಲೋ")
        if vaccines_str:
            bits.append(f"ಬಾಕಿ ಲಸಿಕೆ: {vaccines_str}")
        if next_str:
            bits.append(f"ಮುಂದಿನ ಭೇಟಿ {next_str}")
        return f"{', '.join(bits)}. ಇದು ಸರಿಯಾಗಿದೆಯೇ, ದಾಖಲೆಯನ್ನು ಉಳಿಸಬೇಕೆ?"

    if lang == "ta":
        bits = [f"{name}", f"{vtype} வருகை"]
        if draft.weight_kg is not None:
            bits.append(f"எடை {draft.weight_kg} கிலோ")
        if vaccines_str:
            bits.append(f"நிலுவை தடுப்பூசி: {vaccines_str}")
        if next_str:
            bits.append(f"அடுத்த வருகை {next_str}")
        return f"{', '.join(bits)}. இது சரியா, பதிவை சேமிக்கலாமா?"

    if lang == "te":
        bits = [f"{name}", f"{vtype} విజిట్"]
        if draft.weight_kg is not None:
            bits.append(f"బరువు {draft.weight_kg} కిలోలు")
        if vaccines_str:
            bits.append(f"బాకీ టీకాలు: {vaccines_str}")
        if next_str:
            bits.append(f"తదుపరి విజిట్ {next_str}")
        return f"{', '.join(bits)}. ఇది సరైనదేనా, రికార్డు సేవ్ చేయాలా?"

    bits = [f"{name}", f"{vtype} visit"]
    if weight_str:
        bits.append(f"weight {weight_str}")
    if ifa_str:
        bits.append(f"{ifa_str} given")
    if vaccines_str:
        bits.append(f"vaccinations due: {vaccines_str}")
    if next_str:
        bits.append(f"next visit {next_str}")
    return f"Recorded {', '.join(bits)}. Is this correct — shall I save?"


# ---------------------------------------------------------------------------
# Provider Abstraction: Rule-Based Offline Fallback + Gemini 2.5 Flash
# ---------------------------------------------------------------------------


class LLMProvider(ABC):
    """Abstract interface for clinical structured visit extraction."""

    last_used_model: str = "rule_based_offline"

    @abstractmethod
    async def extract(
        self,
        utterance: str,
        current_draft: VisitDraft,
        language_code: LanguageCode | str,
        patient_context: Optional[dict[str, Any]] = None,
        roster: Optional[list[dict[str, Any]]] = None,
    ) -> GeminiExtractionPayload:
        """Parse an ASHA worker utterance into a typed extraction payload."""


class RuleBasedVisitExtractor(LLMProvider):
    """Deterministic code-mix extractor used offline, in tests, and as zero-downtime fallback."""

    last_used_model: str = "rule_based_offline"

    async def extract(
        self,
        utterance: str,
        current_draft: VisitDraft,
        language_code: LanguageCode | str = "indic-auto",
        patient_context: Optional[dict[str, Any]] = None,
        roster: Optional[list[dict[str, Any]]] = None,
    ) -> GeminiExtractionPayload:
        cleaned = utterance.strip()
        roster_list = roster or []

        if any(p.search(cleaned) for p in CANCEL_PATTERNS):
            return GeminiExtractionPayload(intent="CANCEL")

        weight_kg = _extract_weight_kg(cleaned)
        vaccines = _extract_vaccines(cleaned)
        ifa = _extract_ifa_tablets(cleaned)
        visit_type = _extract_visit_type(cleaned)
        next_date = _extract_next_visit_date(cleaned)

        has_new_field_data = any(
            x is not None and x != []
            for x in (weight_kg, vaccines, ifa, visit_type, next_date)
        )

        # Confirmation only when user did not provide new/corrected field values
        if not has_new_field_data and any(p.search(cleaned) for p in CONFIRM_PATTERNS):
            return GeminiExtractionPayload(intent="CONFIRM_SAVE")

        # Resolve beneficiary from pre-selected patient context or village roster
        beneficiary_id: Optional[str] = None
        beneficiary_name: Optional[str] = None
        if patient_context:
            beneficiary_id = patient_context.get("beneficiary_id")
            beneficiary_name = patient_context.get("beneficiary_name")
            if visit_type is None and patient_context.get("category") in ("ANC", "NEWBORN", "IMMUNISATION"):
                visit_type = patient_context["category"]
        else:
            matched = _match_roster_patient(cleaned, roster_list)
            if matched:
                beneficiary_id = matched["id"]
                beneficiary_name = matched["patient_name"]
                if visit_type is None and matched.get("category") in ("ANC", "NEWBORN", "IMMUNISATION"):
                    visit_type = matched["category"]
            elif not current_draft.beneficiary_name and not CORRECTION_CUE_PATTERN.search(cleaned):
                beneficiary_name = _extract_unseen_beneficiary_name(cleaned)

        # Detect whether this utterance is correcting an existing field in the draft
        is_correction = bool(
            CORRECTION_CUE_PATTERN.search(cleaned)
            and has_new_field_data
            and (
                current_draft.beneficiary_name is not None
                or current_draft.weight_kg is not None
                or current_draft.visit_type is not None
            )
        )
        intent: VoiceIntent = "CORRECT_FIELD" if is_correction else (
            "LOG_VISIT" if (has_new_field_data or beneficiary_name) else "ASK_CLARIFICATION"
        )

        return GeminiExtractionPayload(
            intent=intent,
            beneficiary_id=beneficiary_id,
            beneficiary_name=beneficiary_name,
            visit_type=visit_type,
            weight_kg=weight_kg,
            vaccinations_due=vaccines or None,
            next_visit_date=next_date,
            ifa_tablets_given=ifa,
            notes=cleaned if intent == "LOG_VISIT" else None,
            spoken_readback="",
        )


SYSTEM_INSTRUCTION = """You are ASHA Vaani, a voice field-register assistant for rural Indian ASHA health workers.
Your ONLY job is to extract structured home-visit data from Indic + English code-mix speech (Hindi, Kannada, Tamil, Telugu, English) and generate a short read-back asking for confirmation before saving.

STRICT RULES:
1. NEVER diagnose any illness or disease.
2. NEVER suggest, prescribe, or recommend any medicine, dosage, or home remedy.
3. Understand local clinical code-mix terms: "TT injection", "TT2", "ANC checkup", "IFA goli", "HBNC", "BCG", "OPV", "Pentavalent", spoken weights like "do point aath kilo" -> 2.8.
4. Only return fields mentioned in the current utterance (leave unmentioned fields null so existing draft values are preserved).
5. If the worker corrects a value (e.g. "no, weight is 2.8 kilo"), set intent="CORRECT_FIELD" and return only the corrected field(s).
6. Set intent="CONFIRM_SAVE" ONLY when the worker explicitly confirms saving (e.g. "yes", "haan theek hai", "sari", "save").
7. Keep spoken_readback concise (1-2 short sentences) in the worker's active language, ending with a save confirmation question.
"""


def is_live_api_key(api_key: Optional[str]) -> bool:
    """Return True only when a non-empty, non-placeholder API key is configured."""
    if not api_key:
        return False
    cleaned = api_key.strip()
    if not cleaned:
        return False
    lower = cleaned.lower()
    return not (lower.startswith("your_") or lower.startswith("mock_") or lower == "changeme")


def is_live_gemini_key(api_key: Optional[str]) -> bool:
    """Backward-compatible alias checking for a non-placeholder API key."""
    return is_live_api_key(api_key)


GROQ_SYSTEM_INSTRUCTION = SYSTEM_INSTRUCTION + """
OUTPUT FORMAT: Return ONLY a single compact JSON object (no markdown, no extra text) with exact keys:
{
  "intent": "LOG_VISIT" | "CORRECT_FIELD" | "CONFIRM_SAVE" | "CANCEL" | "ASK_CLARIFICATION",
  "beneficiary_id": string | null,
  "beneficiary_name": string | null,
  "visit_type": "ANC" | "NEWBORN" | "IMMUNISATION" | null,
  "weight_kg": number | null,
  "vaccinations_due": string[] | null,
  "next_visit_date": "YYYY-MM-DD" | null,
  "ifa_tablets_given": integer | null,
  "notes": string | null,
  "spoken_readback": string
}
Match beneficiary_id from village_roster whenever the patient name matches (even in Devanagari/Kannada/Tamil/Telugu script).
"""


def _strip_json_fences(raw_text: str) -> str:
    text = raw_text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.IGNORECASE)
        text = re.sub(r"\s*```$", "", text)
    return text.strip()


def _sanitize_visit_payload(data: dict[str, Any]) -> dict[str, Any]:
    """Normalize minor LLM JSON quirks before strict Pydantic validation."""
    cleaned = dict(data)
    if isinstance(cleaned.get("intent"), str):
        cleaned["intent"] = cleaned["intent"].strip().upper()
    vtype = cleaned.get("visit_type")
    if isinstance(vtype, str):
        up = vtype.strip().upper()
        if up in ("ANC", "NEWBORN", "IMMUNISATION"):
            cleaned["visit_type"] = up
        elif up == "IMMUNIZATION":
            cleaned["visit_type"] = "IMMUNISATION"
        elif up in ("HBNC", "NEONATE", "BABY"):
            cleaned["visit_type"] = "NEWBORN"
        else:
            cleaned["visit_type"] = None
    vaxes = cleaned.get("vaccinations_due")
    if isinstance(vaxes, str):
        cleaned["vaccinations_due"] = [v.strip() for v in vaxes.split(",") if v.strip()] or None
    elif isinstance(vaxes, list):
        cleaned["vaccinations_due"] = [str(v).strip() for v in vaxes if str(v).strip()] or None
    if cleaned.get("spoken_readback") is None:
        cleaned["spoken_readback"] = ""
    return cleaned


GROQ_BACKUP_MODEL = "openai/gpt-oss-20b"


def _groq_reasoning_extra(model: str) -> dict[str, Any]:
    """Disable/minimize reasoning tokens on Groq models for sub-second voice latency."""
    low = model.lower()
    if "qwen3" in low or "qwen/qwen3" in low:
        return {"reasoning_effort": "none"}
    if "gpt-oss" in low:
        return {"reasoning_effort": "low"}
    return {}


class GroqVisitExtractor(LLMProvider):
    """Low-latency Groq OpenAI-compatible structured clinical extractor with offline fallback."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        base_url: Optional[str] = None,
        timeout_s: float = 6.0,
        fallback: Optional[LLMProvider] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        settings = get_settings()
        self.api_key = api_key if api_key is not None else settings.groq_api_key
        self.model = model or settings.groq_model
        self.base_url = (base_url or settings.groq_base_url).rstrip("/")
        self.timeout_s = timeout_s
        self.fallback = fallback or RuleBasedVisitExtractor()
        self._client: Optional[httpx.AsyncClient] = http_client
        self.last_used_model: str = self.model if is_live_api_key(self.api_key) else "rule_based_offline"

    def _get_client(self) -> Optional[httpx.AsyncClient]:
        if self._client is None and is_live_api_key(self.api_key):
            self._client = httpx.AsyncClient(
                base_url=self.base_url,
                headers={
                    "Authorization": f"Bearer {self.api_key.strip()}",
                    "Content-Type": "application/json",
                },
                timeout=self.timeout_s,
            )
        return self._client

    async def extract(
        self,
        utterance: str,
        current_draft: VisitDraft,
        language_code: LanguageCode | str = "indic-auto",
        patient_context: Optional[dict[str, Any]] = None,
        roster: Optional[list[dict[str, Any]]] = None,
    ) -> GeminiExtractionPayload:
        client = self._get_client()
        if client is None:
            self.last_used_model = "rule_based_offline"
            return await self.fallback.extract(
                utterance=utterance,
                current_draft=current_draft,
                language_code=language_code,
                patient_context=patient_context,
                roster=roster,
            )

        roster_summary = [
            {
                "id": p.get("id"),
                "name": p.get("patient_name"),
                "category": p.get("category"),
                "house": p.get("field_answers", {}).get("House / Ward", ""),
            }
            for p in (roster or [])[:25]
        ]

        prompt = json.dumps(
            {
                "today": date.today().isoformat(),
                "active_language": language_code,
                "current_draft": current_draft.model_dump(),
                "preselected_patient_baseline": patient_context,
                "village_roster": roster_summary,
                "asha_utterance": utterance,
            },
            ensure_ascii=False,
        )

        candidate_models = [self.model]
        if self.model != GROQ_BACKUP_MODEL:
            candidate_models.append(GROQ_BACKUP_MODEL)

        for idx, model_name in enumerate(candidate_models):
            body: dict[str, Any] = {
                "model": model_name,
                "messages": [
                    {"role": "system", "content": GROQ_SYSTEM_INSTRUCTION},
                    {"role": "user", "content": prompt},
                ],
                "temperature": 0.1,
                "max_tokens": 350,
                "response_format": {"type": "json_object"},
                **_groq_reasoning_extra(model_name),
            }
            try:
                response = await client.post("/chat/completions", json=body)
                if response.status_code in (429, 503) and idx + 1 < len(candidate_models):
                    logger.warning(
                        "Groq model %s returned HTTP %s; retrying on backup model %s.",
                        model_name,
                        response.status_code,
                        candidate_models[idx + 1],
                    )
                    continue
                response.raise_for_status()
                data = response.json()
                content = data["choices"][0]["message"]["content"]
                if content:
                    parsed = json.loads(_strip_json_fences(content))
                    if isinstance(parsed, dict):
                        self.last_used_model = model_name
                        return GeminiExtractionPayload.model_validate(_sanitize_visit_payload(parsed))
            except Exception as exc:  # pragma: no cover - network/API failure fallback
                if idx + 1 < len(candidate_models):
                    logger.warning("Groq model %s failed (%s); trying backup model.", model_name, exc)
                    continue
                logger.warning("Groq extraction failed (%s); falling back to RuleBasedVisitExtractor.", exc)

        self.last_used_model = "rule_based_fallback"
        return await self.fallback.extract(
            utterance=utterance,
            current_draft=current_draft,
            language_code=language_code,
            patient_context=patient_context,
            roster=roster,
        )


def resolve_active_llm_info(settings: Settings) -> tuple[str, str]:
    """Return (llm_mode, llm_model) reflecting the active configured LLM provider."""
    pref = (settings.llm_provider or "gemini").strip().lower()
    if pref == "groq" and is_live_api_key(settings.groq_api_key):
        return ("groq", settings.groq_model)
    if pref == "gemini" and is_live_api_key(settings.gemini_api_key):
        return ("gemini", settings.gemini_model)
    if is_live_api_key(settings.groq_api_key):
        return ("groq", settings.groq_model)
    if is_live_api_key(settings.gemini_api_key):
        return ("gemini", settings.gemini_model)
    fallback_model = settings.groq_model if pref == "groq" else settings.gemini_model
    return ("offline_fallback", fallback_model)


def create_visit_extractor(settings: Optional[Settings] = None) -> LLMProvider:
    """Factory selecting Groq, Gemini, or deterministic offline fallback based on Settings."""
    cfg = settings or get_settings()
    mode, _ = resolve_active_llm_info(cfg)
    if mode == "groq":
        return GroqVisitExtractor(
            api_key=cfg.groq_api_key,
            model=cfg.groq_model,
            base_url=cfg.groq_base_url,
        )
    if mode == "gemini":
        return GeminiVisitExtractor(
            api_key=cfg.gemini_api_key,
            model=cfg.gemini_model,
        )
    return RuleBasedVisitExtractor()


class GeminiVisitExtractor(LLMProvider):
    """Gemini 2.5 Flash structured clinical extractor with zero-thinking latency tuning."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        fallback: Optional[LLMProvider] = None,
    ) -> None:
        settings = get_settings()
        self.api_key = api_key if api_key is not None else settings.gemini_api_key
        self.model = model or settings.gemini_model
        self.fallback = fallback or RuleBasedVisitExtractor()
        self._client: Any = None
        self.last_used_model: str = self.model if is_live_gemini_key(self.api_key) else "rule_based_offline"

    def _get_client(self) -> Any:
        if self._client is None and is_live_gemini_key(self.api_key):
            from google import genai

            self._client = genai.Client(api_key=self.api_key.strip())
        return self._client

    async def extract(
        self,
        utterance: str,
        current_draft: VisitDraft,
        language_code: LanguageCode | str = "indic-auto",
        patient_context: Optional[dict[str, Any]] = None,
        roster: Optional[list[dict[str, Any]]] = None,
    ) -> GeminiExtractionPayload:
        client = self._get_client()
        if client is None:
            return await self.fallback.extract(
                utterance=utterance,
                current_draft=current_draft,
                language_code=language_code,
                patient_context=patient_context,
                roster=roster,
            )

        from google.genai import types

        roster_summary = [
            {
                "id": p.get("id"),
                "name": p.get("patient_name"),
                "category": p.get("category"),
                "house": p.get("field_answers", {}).get("House / Ward", ""),
            }
            for p in (roster or [])[:25]
        ]

        prompt = json.dumps(
            {
                "today": date.today().isoformat(),
                "active_language": language_code,
                "current_draft": current_draft.model_dump(),
                "preselected_patient_baseline": patient_context,
                "village_roster": roster_summary,
                "asha_utterance": utterance,
            },
            ensure_ascii=False,
        )

        try:
            response = await client.aio.models.generate_content(
                model=self.model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    system_instruction=SYSTEM_INSTRUCTION,
                    response_mime_type="application/json",
                    response_schema=GeminiExtractionPayload,
                    thinking_config=types.ThinkingConfig(thinking_budget=0),
                    temperature=0.1,
                ),
            )
            if response.parsed is not None:
                self.last_used_model = self.model
                if isinstance(response.parsed, GeminiExtractionPayload):
                    return response.parsed
                return GeminiExtractionPayload.model_validate(response.parsed)
            if response.text:
                self.last_used_model = self.model
                return GeminiExtractionPayload.model_validate_json(response.text)
        except Exception as exc:  # pragma: no cover - network/API failure fallback
            logger.warning("Gemini extraction failed (%s); falling back to RuleBasedVisitExtractor.", exc)

        self.last_used_model = "rule_based_fallback"
        return await self.fallback.extract(
            utterance=utterance,
            current_draft=current_draft,
            language_code=language_code,
            patient_context=patient_context,
            roster=roster,
        )


# ---------------------------------------------------------------------------
# Stateful Turn Processor & Read-Back Gate
# ---------------------------------------------------------------------------


class ClinicalStateEngine:
    """Applies extracted updates to VisitDraft, computes diffs, and enforces read-back before save."""

    def __init__(self, provider: Optional[LLMProvider] = None) -> None:
        self.provider = provider or GeminiVisitExtractor()

    async def process_turn(
        self,
        utterance: str,
        current_draft: VisitDraft,
        language_code: LanguageCode | str = "indic-auto",
        readback_completed: bool = False,
        patient_context: Optional[dict[str, Any]] = None,
        roster: Optional[list[dict[str, Any]]] = None,
        reference_date: Optional[date] = None,
    ) -> ClinicalTurnResult:
        """Process one finalized voice turn and return updated draft, diffs, and status."""
        # Seed draft with pre-selected patient baseline (FR-7.6) if not yet populated
        working = current_draft.model_copy(deep=True)
        if patient_context:
            if not working.beneficiary_id and patient_context.get("beneficiary_id"):
                working.beneficiary_id = patient_context["beneficiary_id"]
            if not working.beneficiary_name and patient_context.get("beneficiary_name"):
                working.beneficiary_name = patient_context["beneficiary_name"]
            if not working.visit_type and patient_context.get("category") in (
                "ANC",
                "NEWBORN",
                "IMMUNISATION",
            ):
                working.visit_type = patient_context["category"]

        raw_payload = await self.provider.extract(
            utterance=utterance,
            current_draft=working,
            language_code=language_code,
            patient_context=patient_context,
            roster=roster,
        )

        # Handle CANCEL immediately
        if raw_payload.intent == "CANCEL":
            reply = raw_payload.spoken_readback or build_localized_readback(
                working, [], "CANCEL", language_code, utterance
            )
            return ClinicalTurnResult(
                updated_draft=VisitDraft(),
                extraction=VoiceExtractionOutput(
                    intent="CANCEL",
                    extracted_fields={},
                    field_diffs=[],
                    spoken_readback=reply,
                ),
                status="LISTENING",
                should_save=False,
                readback_completed=False,
            )

        # Apply non-null extracted fields and record exact diffs against previous values
        before_snapshot = working.model_dump()
        extracted_fields: dict[str, Any] = {}
        field_diffs: list[FieldDiff] = []

        patch_candidates: dict[str, Any] = {
            "beneficiary_id": raw_payload.beneficiary_id,
            "beneficiary_name": raw_payload.beneficiary_name,
            "visit_type": raw_payload.visit_type,
            "weight_kg": raw_payload.weight_kg,
            "vaccinations_due": raw_payload.vaccinations_due,
            "next_visit_date": raw_payload.next_visit_date,
            "ifa_tablets_given": raw_payload.ifa_tablets_given,
            "notes": raw_payload.notes,
        }

        for field_name, new_val in patch_candidates.items():
            if new_val is None:
                continue
            if field_name == "vaccinations_due" and len(new_val) == 0:
                continue
            old_val = before_snapshot.get(field_name)
            if field_name == "notes" and old_val and raw_payload.intent == "LOG_VISIT":
                merged_notes = f"{old_val}; {new_val}" if new_val not in old_val else old_val
                setattr(working, field_name, merged_notes)
                extracted_fields[field_name] = merged_notes
            else:
                setattr(working, field_name, new_val)
                extracted_fields[field_name] = new_val

            if old_val is not None and old_val != [] and old_val != "" and old_val != new_val:
                field_diffs.append(FieldDiff(field=field_name, old_value=old_val, new_value=new_val))

        # Auto-compute smart next_visit_date & due vaccines when visit_type is known
        baseline_fields = (patient_context or {}).get("baseline_fields")
        if working.visit_type and not working.next_visit_date:
            due_date, _, suggested_vaccines = compute_next_visit_plan(
                visit_type=working.visit_type,
                reference_date=reference_date,
                baseline_fields=baseline_fields,
                existing_vaccines_due=working.vaccinations_due,
            )
            working.next_visit_date = due_date
            if not working.vaccinations_due and suggested_vaccines:
                working.vaccinations_due = suggested_vaccines

        # Enforce mandatory Read-Back Gate before saving
        has_minimum_fields = bool(working.beneficiary_name and working.visit_type)
        if raw_payload.intent == "CONFIRM_SAVE" and readback_completed and has_minimum_fields:
            lang = resolve_short_language(language_code, utterance)
            saved_msg = {
                "hi": "धन्यवाद, विज़िट रिकॉर्ड और फॉलो-अप सेव हो गया है।",
                "kn": "ಧನ್ಯವಾದಗಳು, ಭೇಟಿ ದಾಖಲೆ ಮತ್ತು ಫಾಲೋ-ಅಪ್ ಉಳಿಸಲಾಗಿದೆ.",
                "ta": "நன்றி, வருகை பதிவு மற்றும் பின்தொடர்தல் சேமிக்கப்பட்டது.",
                "te": "ధన్యవాదాలు, విజిట్ రికార్డు మరియు ఫాలో-అప్ సేవ్ చేయబడింది.",
            }.get(lang, "Thank you, the visit record and follow-up have been saved.")
            return ClinicalTurnResult(
                updated_draft=working,
                extraction=VoiceExtractionOutput(
                    intent="CONFIRM_SAVE",
                    extracted_fields=extracted_fields,
                    field_diffs=[],
                    spoken_readback=saved_msg,
                ),
                status="SAVED",
                should_save=True,
                readback_completed=True,
            )

        effective_intent: VoiceIntent = (
            "CORRECT_FIELD" if (raw_payload.intent == "CORRECT_FIELD" or field_diffs) else raw_payload.intent
        )
        if effective_intent == "CONFIRM_SAVE" and not readback_completed:
            # Never save without reading back first!
            effective_intent = "LOG_VISIT"

        spoken_reply = raw_payload.spoken_readback.strip() or build_localized_readback(
            draft=working,
            diffs=field_diffs,
            intent=effective_intent,
            language_code=language_code,
            utterance=utterance,
        )

        next_status: SessionStatus = (
            "AWAITING_CONFIRMATION" if has_minimum_fields else "LISTENING"
        )
        return ClinicalTurnResult(
            updated_draft=working,
            extraction=VoiceExtractionOutput(
                intent=effective_intent,
                extracted_fields=extracted_fields,
                field_diffs=field_diffs,
                spoken_readback=spoken_reply,
            ),
            status=next_status,
            should_save=False,
            readback_completed=has_minimum_fields,
        )


# ---------------------------------------------------------------------------
# FR-7.2: Voice-Driven Custom Register Patient Intake Engine
# ---------------------------------------------------------------------------


class KeyValueAnswer(BaseModel):
    """Typed dynamic field-answer pair compatible with Gemini structured output."""

    field_name: str
    value: str


class GeminiRegisterIntakePayload(BaseModel):
    """Typed JSON schema for extracting custom-register intake fields via Gemini 2.5 Flash."""

    intent: VoiceIntent = "LOG_VISIT"
    patient_name: Optional[str] = None
    category: Optional[VisitType] = None
    answers: list[KeyValueAnswer] = Field(default_factory=list)
    spoken_readback: str = ""


class RegisterIntakeTurnResult(BaseModel):
    """State transition produced for one voice turn in custom-register intake mode."""

    updated_draft: RegisterIntakeDraft
    intent: VoiceIntent
    field_diffs: list[FieldDiff] = Field(default_factory=list)
    spoken_readback: str = ""
    status: SessionStatus = "LISTENING"
    should_save: bool = False
    readback_completed: bool = False


FIELD_ALIAS_MAP: dict[str, tuple[str, ...]] = {
    "age": ("age", "umar", "umra", "vayasu", "vayas"),
    "blood group": ("blood group", "blood"),
    "medical history": ("medical history", "history", "prior history"),
    "house / ward": ("house / ward", "house/ward", "house no", "house number", "house", "ward"),
    "house": ("house no", "house number", "house"),
    "ward": ("ward no", "ward"),
    "phone": ("phone number", "phone", "mobile number", "mobile"),
    "lmp": ("lmp", "last menstrual period", "last period"),
    "parity": ("parity", "gravida", "pregnancy count"),
    "gravida": ("gravida", "parity"),
    "birth weight": ("birth weight", "weight", "wajan"),
    "date of birth": ("date of birth", "dob", "birth date"),
}

REGISTER_INTAKE_SYSTEM_INSTRUCTION = """You are ASHA Vaani helping an ASHA worker onboard a patient into a custom register by voice.
Map spoken details (Hindi, Kannada, Tamil, Telugu, English code-mix) strictly into the register's defined field names.

STRICT RULES:
1. NEVER diagnose any illness or disease.
2. NEVER suggest, prescribe, or recommend any medicine, dosage, or treatment.
3. Return only fields mentioned in the current utterance (omit unmentioned fields).
4. Use exact field_name values from register_fields whenever possible.
5. Set intent="CORRECT_FIELD" when the worker fixes a value, "CONFIRM_SAVE" ONLY when explicitly confirming after read-back, "CANCEL" when discarding.
6. Keep spoken_readback concise (1-2 short sentences) in the active language asking to confirm before saving.
"""


def _aliases_for_field(field: RegisterField) -> list[str]:
    lower_name = field.field_name.strip().lower()
    aliases: list[str] = [field.field_name.strip()]
    for key, extra in FIELD_ALIAS_MAP.items():
        if key == lower_name or key in lower_name:
            aliases.extend(extra)
    # Also allow slash-separated components (e.g., "House / Ward" -> "House", "Ward")
    if "/" in field.field_name:
        for part in field.field_name.split("/"):
            p = part.strip()
            if len(p) >= 3:
                aliases.append(p)
    # Deduplicate longest-first so specific cues win
    seen: set[str] = set()
    ordered: list[str] = []
    for a in sorted(aliases, key=len, reverse=True):
        k = a.lower()
        if k not in seen:
            seen.add(k)
            ordered.append(a)
    return ordered


def _coerce_field_value(raw_val: str, field_type: str) -> Any:
    val = raw_val.strip(" ,;.-:")
    if not val:
        return None
    norm = normalize_indic_numbers(val)

    if field_type == "NUMBER":
        m = re.search(r"-?\d+(?:\.\d+)?", norm)
        if m:
            num_str = m.group(0)
            return float(num_str) if "." in num_str else int(num_str)
        return val

    if field_type == "BOOLEAN":
        low = norm.lower()
        if re.search(r"\b(?:yes|true|haan|han|houdu|aam|avunu|present|positive)\b", low):
            return True
        if re.search(r"\b(?:no|false|nahi|nahin|illa|illai|ledu|none|absent|negative)\b", low):
            return False
        return val

    if field_type == "DATE":
        parsed = _extract_next_visit_date(norm)
        return parsed or val

    return val


def _extract_dynamic_register_fields_offline(
    utterance: str,
    register: CustomRegister,
) -> tuple[Optional[str], dict[str, Any]]:
    """Extract patient_name and dynamic field answers for any CustomRegister offline."""
    cleaned = utterance.strip()
    if not cleaned:
        return None, {}

    # Build match spans for all defined fields in this register
    spans: list[tuple[int, int, RegisterField]] = []
    for field in register.fields:
        for alias in _aliases_for_field(field):
            escaped = re.escape(alias).replace(r"\ ", r"\s+")
            pattern = re.compile(rf"(?<!\w){escaped}(?!\w)", re.IGNORECASE)
            for match in pattern.finditer(cleaned):
                spans.append((match.start(), match.end(), field))

    # Keep non-overlapping spans, preferring earliest start then longest cue
    spans.sort(key=lambda item: (item[0], -(item[1] - item[0])))
    chosen: list[tuple[int, int, RegisterField]] = []
    last_end = -1
    for start, end, field in spans:
        if start >= last_end:
            chosen.append((start, end, field))
            last_end = end

    answers: dict[str, Any] = {}
    for idx, (start, end, field) in enumerate(chosen):
        next_start = chosen[idx + 1][0] if idx + 1 < len(chosen) else len(cleaned)
        raw_segment = cleaned[end:next_start]
        # Strip leading connector ("is", "hai", ":", "-", "=")
        raw_segment = re.sub(r"^\s*(?:is|hai|are|was|to|=|:|-)+\s*", "", raw_segment, flags=re.IGNORECASE)
        # Stop at sentence-ending punctuation (preserving decimals) or trailing connector
        raw_segment = re.split(r"[;!?।]|\.(?=\s|$)", raw_segment)[0]
        raw_segment = re.sub(r",?\s*\b(?:and|aur|matthu|marrum|mariyu)\s*$", "", raw_segment, flags=re.IGNORECASE)
        raw_segment = raw_segment.strip(" ,;:-")

        # Special clean-up for House / Ward when matched via "house" keyword so "House 22" keeps "House"
        if field.field_name.lower().startswith("house") and re.match(r"^\d+", raw_segment):
            raw_segment = f"House {raw_segment}"
        # Special clean-up for Age ("24 years" -> 24) handled by NUMBER coercion
        coerced = _coerce_field_value(raw_segment, field.field_type)
        if coerced is not None and coerced != "":
            answers[field.field_name] = coerced

    # Extract patient_name: explicit cue first, else text before first matched field
    patient_name = _extract_unseen_beneficiary_name(cleaned)
    if not patient_name and chosen and chosen[0][0] > 0:
        prefix = cleaned[: chosen[0][0]].strip(" ,;:-")
        # Strip leading introducer / correction phrases
        prefix = re.sub(
            r"^(?:add|new\s+patient|register|patient|name|beneficiary|entry\s+for|for)\s*(?:is|hai|:|-)?\s*",
            "",
            prefix,
            flags=re.IGNORECASE,
        ).strip(" ,;:-")
        if prefix and not CORRECTION_CUE_PATTERN.search(prefix):
            words = prefix.split()
            if 1 <= len(words) <= 3 and all(re.fullmatch(r"[A-Za-z][A-Za-z'.-]*", w) for w in words):
                if not any(w.lower() in NON_SURNAME_TOKENS for w in words):
                    patient_name = " ".join(w.capitalize() for w in words)

    return patient_name, answers


def build_register_intake_readback(
    draft: RegisterIntakeDraft,
    diffs: list[FieldDiff],
    intent: VoiceIntent,
    language_code: LanguageCode | str,
    utterance: str = "",
) -> str:
    """Build localized read-back for custom register patient intake (zero diagnosis/medicine)."""
    lang = resolve_short_language(language_code, utterance)

    if intent == "CANCEL":
        return {
            "hi": "ठीक है, यह रजिस्टर एंट्री रद्द कर दी गई है।",
            "kn": "ಸರಿ, ಈ ರಿಜಿಸ್ಟರ್ ದಾಖಲೆಯನ್ನು ರದ್ದುಗೊಳಿಸಲಾಗಿದೆ.",
            "ta": "சரி, இந்த பதிவேடு உள்ளீடு ரத்து செய்யப்பட்டது.",
            "te": "సరే, ఈ రిజిస్టర్ ఎంట్రీ రద్దు చేయబడింది.",
        }.get(lang, "Okay, this register entry has been cancelled.")

    if intent == "CORRECT_FIELD" and diffs:
        summary = ", ".join(f"{d.field} {d.new_value}" for d in diffs)
        if lang == "hi":
            return f"जानकारी अपडेट कर दी है: {summary}। क्या मैं {draft.register_name} में सेव कर लूं?"
        if lang == "kn":
            return f"ವಿವರಗಳನ್ನು ಬದಲಾಯಿಸಲಾಗಿದೆ: {summary}. {draft.register_name} ನಲ್ಲಿ ಉಳಿಸಬೇಕೆ?"
        if lang == "ta":
            return f"விவரங்கள் மாற்றப்பட்டன: {summary}. {draft.register_name} பதிவேட்டில் சேமிக்கலாமா?"
        if lang == "te":
            return f"వివరాలు మార్చబడ్డాయి: {summary}. {draft.register_name} లో సేవ్ చేయాలా?"
        return f"Updated {summary}. Shall I save to {draft.register_name}?"

    if not draft.patient_name:
        return {
            "hi": f"{draft.register_name} के लिए कृपया मरीज का नाम और विवरण बताएं।",
            "kn": f"{draft.register_name} ಗಾಗಿ ದಯವಿಟ್ಟು ರೋಗಿಯ ಹೆಸರು ಮತ್ತು ವಿವರಗಳನ್ನು ಹೇಳಿ.",
            "ta": f"{draft.register_name} பதிவேட்டிற்கு நோயாளியின் பெயர் மற்றும் விவரங்களைச் சொல்லவும்.",
            "te": f"{draft.register_name} కోసం దయచేసి రోగి పేరు మరియు వివరాలు చెప్పండి.",
        }.get(lang, f"Please tell me the patient name and details for {draft.register_name}.")

    pairs = [f"{k}: {v}" for k, v in draft.field_answers.items() if v is not None and v != ""]
    fields_text = ", ".join(pairs)
    details = f"{draft.patient_name}, {fields_text}" if fields_text else draft.patient_name

    if lang == "hi":
        return f"{draft.register_name} के लिए दर्ज किया: {details}। क्या यह सही है, सेव कर लूं?"
    if lang == "kn":
        return f"{draft.register_name} ಗಾಗಿ ದಾಖಲಿಸಲಾಗಿದೆ: {details}. ಇದು ಸರಿಯಾಗಿದೆಯೇ, ಉಳಿಸಬೇಕೆ?"
    if lang == "ta":
        return f"{draft.register_name} க்காக பதிவு செய்யப்பட்டது: {details}. இது சரியா, சேமிக்கலாமா?"
    if lang == "te":
        return f"{draft.register_name} కోసం నమోదు చేయబడింది: {details}. ఇది సరైనదేనా, సేవ్ చేయాలా?"
    return f"For {draft.register_name}: {details}. Is this correct — shall I save?"


GROQ_REGISTER_INTAKE_SYSTEM_INSTRUCTION = REGISTER_INTAKE_SYSTEM_INSTRUCTION + """
OUTPUT FORMAT: Return ONLY a single compact JSON object (no markdown, no extra text) with exact keys:
{
  "intent": "LOG_VISIT" | "CORRECT_FIELD" | "CONFIRM_SAVE" | "CANCEL" | "ASK_CLARIFICATION",
  "patient_name": string | null,
  "category": "ANC" | "NEWBORN" | "IMMUNISATION" | null,
  "answers": [{"field_name": string, "value": string}],
  "spoken_readback": string
}
"""


def _sanitize_register_intake_payload(data: dict[str, Any]) -> dict[str, Any]:
    cleaned = dict(data)
    if isinstance(cleaned.get("intent"), str):
        cleaned["intent"] = cleaned["intent"].strip().upper()
    cat = cleaned.get("category")
    if isinstance(cat, str):
        up = cat.strip().upper()
        if up in ("ANC", "NEWBORN", "IMMUNISATION"):
            cleaned["category"] = up
        elif up == "IMMUNIZATION":
            cleaned["category"] = "IMMUNISATION"
        elif up in ("HBNC", "NEONATE", "BABY"):
            cleaned["category"] = "NEWBORN"
        else:
            cleaned["category"] = None
    raw_answers = cleaned.get("answers")
    if isinstance(raw_answers, dict):
        cleaned["answers"] = [
            {"field_name": str(k), "value": str(v)}
            for k, v in raw_answers.items()
            if v is not None and str(v).strip() != ""
        ]
    elif isinstance(raw_answers, list):
        norm_list = []
        for item in raw_answers:
            if isinstance(item, dict) and item.get("field_name") and item.get("value") is not None:
                norm_list.append(
                    {"field_name": str(item["field_name"]), "value": str(item["value"])}
                )
        cleaned["answers"] = norm_list
    else:
        cleaned["answers"] = []
    if cleaned.get("spoken_readback") is None:
        cleaned["spoken_readback"] = ""
    return cleaned


class RegisterIntakeEngine:
    """Voice-driven dynamic custom-register intake engine (FR-7.2) with Groq, Gemini & offline fallback."""

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
        groq_api_key: Optional[str] = None,
        groq_model: Optional[str] = None,
        groq_base_url: Optional[str] = None,
        llm_provider: Optional[str] = None,
        timeout_s: float = 6.0,
        http_client: Optional[httpx.AsyncClient] = None,
    ) -> None:
        settings = get_settings()
        self.llm_provider = (llm_provider if llm_provider is not None else settings.llm_provider).strip().lower()
        self.api_key = api_key if api_key is not None else settings.gemini_api_key
        self.model = model or settings.gemini_model
        self.groq_api_key = groq_api_key if groq_api_key is not None else settings.groq_api_key
        self.groq_model = groq_model or settings.groq_model
        self.groq_base_url = (groq_base_url or settings.groq_base_url).rstrip("/")
        self.timeout_s = timeout_s
        self._client: Any = None
        self._groq_client: Optional[httpx.AsyncClient] = http_client
        self.last_used_model: str = "rule_based_offline"

    def _get_client(self) -> Any:
        if self._client is None and is_live_api_key(self.api_key):
            from google import genai

            self._client = genai.Client(api_key=self.api_key.strip())
        return self._client

    def _get_groq_client(self) -> Optional[httpx.AsyncClient]:
        if self._groq_client is None and is_live_api_key(self.groq_api_key):
            self._groq_client = httpx.AsyncClient(
                base_url=self.groq_base_url,
                headers={
                    "Authorization": f"Bearer {self.groq_api_key.strip()}",
                    "Content-Type": "application/json",
                },
                timeout=self.timeout_s,
            )
        return self._groq_client

    async def _extract_with_groq(
        self,
        utterance: str,
        register: CustomRegister,
        current_draft: RegisterIntakeDraft,
        language_code: LanguageCode | str,
    ) -> Optional[GeminiRegisterIntakePayload]:
        client = self._get_groq_client()
        if client is None:
            return None

        prompt = json.dumps(
            {
                "today": date.today().isoformat(),
                "active_language": language_code,
                "register_name": register.name,
                "register_fields": [
                    {"field_name": f.field_name, "field_type": f.field_type}
                    for f in register.fields
                ],
                "current_draft": current_draft.model_dump(),
                "asha_utterance": utterance,
            },
            ensure_ascii=False,
        )

        candidate_models = [self.groq_model]
        if self.groq_model != GROQ_BACKUP_MODEL:
            candidate_models.append(GROQ_BACKUP_MODEL)

        for idx, model_name in enumerate(candidate_models):
            body: dict[str, Any] = {
                "model": model_name,
                "messages": [
                    {"role": "system", "content": GROQ_REGISTER_INTAKE_SYSTEM_INSTRUCTION},
                    {"role": "user", "content": prompt},
                ],
                "temperature": 0.1,
                "max_tokens": 350,
                "response_format": {"type": "json_object"},
                **_groq_reasoning_extra(model_name),
            }
            try:
                response = await client.post("/chat/completions", json=body)
                if response.status_code in (429, 503) and idx + 1 < len(candidate_models):
                    logger.warning(
                        "Groq register intake model %s returned HTTP %s; retrying on backup model %s.",
                        model_name,
                        response.status_code,
                        candidate_models[idx + 1],
                    )
                    continue
                response.raise_for_status()
                data = response.json()
                content = data["choices"][0]["message"]["content"]
                if content:
                    parsed = json.loads(_strip_json_fences(content))
                    if isinstance(parsed, dict):
                        self.last_used_model = model_name
                        return GeminiRegisterIntakePayload.model_validate(
                            _sanitize_register_intake_payload(parsed)
                        )
            except Exception as exc:  # pragma: no cover
                if idx + 1 < len(candidate_models):
                    logger.warning(
                        "Groq register intake model %s failed (%s); trying backup model.",
                        model_name,
                        exc,
                    )
                    continue
                logger.warning("Groq register intake failed (%s); falling back offline.", exc)

        self.last_used_model = "rule_based_fallback"
        return None

    async def _extract_with_llm(
        self,
        utterance: str,
        register: CustomRegister,
        current_draft: RegisterIntakeDraft,
        language_code: LanguageCode | str,
    ) -> Optional[GeminiRegisterIntakePayload]:
        if self.llm_provider == "groq" and is_live_api_key(self.groq_api_key):
            return await self._extract_with_groq(utterance, register, current_draft, language_code)
        if self.llm_provider == "gemini" and is_live_api_key(self.api_key):
            return await self._extract_with_gemini(utterance, register, current_draft, language_code)
        if is_live_api_key(self.groq_api_key):
            return await self._extract_with_groq(utterance, register, current_draft, language_code)
        if is_live_api_key(self.api_key):
            return await self._extract_with_gemini(utterance, register, current_draft, language_code)
        self.last_used_model = "rule_based_offline"
        return None

    async def _extract_with_gemini(
        self,
        utterance: str,
        register: CustomRegister,
        current_draft: RegisterIntakeDraft,
        language_code: LanguageCode | str,
    ) -> Optional[GeminiRegisterIntakePayload]:
        client = self._get_client()
        if client is None:
            return None

        from google.genai import types

        prompt = json.dumps(
            {
                "today": date.today().isoformat(),
                "active_language": language_code,
                "register_name": register.name,
                "register_fields": [
                    {"field_name": f.field_name, "field_type": f.field_type}
                    for f in register.fields
                ],
                "current_draft": current_draft.model_dump(),
                "asha_utterance": utterance,
            },
            ensure_ascii=False,
        )
        try:
            response = await client.aio.models.generate_content(
                model=self.model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    system_instruction=REGISTER_INTAKE_SYSTEM_INSTRUCTION,
                    response_mime_type="application/json",
                    response_schema=GeminiRegisterIntakePayload,
                    thinking_config=types.ThinkingConfig(thinking_budget=0),
                    temperature=0.1,
                ),
            )
            if response.parsed is not None:
                self.last_used_model = self.model
                if isinstance(response.parsed, GeminiRegisterIntakePayload):
                    return response.parsed
                return GeminiRegisterIntakePayload.model_validate(response.parsed)
            if response.text:
                self.last_used_model = self.model
                return GeminiRegisterIntakePayload.model_validate_json(response.text)
        except Exception as exc:  # pragma: no cover
            logger.warning("Gemini register intake failed (%s); falling back offline.", exc)
        self.last_used_model = "rule_based_fallback"
        return None

    async def process_turn(
        self,
        utterance: str,
        register: CustomRegister,
        current_draft: RegisterIntakeDraft,
        language_code: LanguageCode | str = "indic-auto",
        readback_completed: bool = False,
    ) -> RegisterIntakeTurnResult:
        """Process one voice turn while onboarding a patient into a CustomRegister."""
        cleaned = utterance.strip()
        working = current_draft.model_copy(deep=True)
        field_type_map = {f.field_name.lower(): (f.field_name, f.field_type) for f in register.fields}

        gemini_payload = await self._extract_with_llm(
            utterance=cleaned,
            register=register,
            current_draft=working,
            language_code=language_code,
        )

        if gemini_payload is not None:
            intent = gemini_payload.intent
            new_name = gemini_payload.patient_name
            new_category = gemini_payload.category
            new_answers: dict[str, Any] = {}
            for kv in gemini_payload.answers:
                key_low = kv.field_name.strip().lower()
                if key_low in field_type_map:
                    canon_name, ftype = field_type_map[key_low]
                    new_answers[canon_name] = _coerce_field_value(kv.value, ftype)
                else:
                    new_answers[kv.field_name.strip()] = kv.value.strip()
            llm_reply = gemini_payload.spoken_readback.strip()
        else:
            llm_reply = ""
            if any(p.search(cleaned) for p in CANCEL_PATTERNS):
                intent = "CANCEL"
                new_name = None
                new_category = None
                new_answers = {}
            else:
                new_name, new_answers = _extract_dynamic_register_fields_offline(cleaned, register)
                new_category = _extract_visit_type(cleaned)
                has_new_data = bool(new_name or new_answers or new_category)

                if not has_new_data and any(p.search(cleaned) for p in CONFIRM_PATTERNS):
                    intent = "CONFIRM_SAVE"
                elif (
                    CORRECTION_CUE_PATTERN.search(cleaned)
                    and has_new_data
                    and (working.patient_name or working.field_answers)
                ):
                    intent = "CORRECT_FIELD"
                elif has_new_data:
                    intent = "LOG_VISIT"
                else:
                    intent = "ASK_CLARIFICATION"

        if intent == "CANCEL":
            reply = llm_reply or build_register_intake_readback(
                working, [], "CANCEL", language_code, cleaned
            )
            fresh = RegisterIntakeDraft(
                register_id=register.id,
                register_name=register.name,
                category=working.category,
                expected_fields=[f.field_name for f in register.fields],
            )
            return RegisterIntakeTurnResult(
                updated_draft=fresh,
                intent="CANCEL",
                field_diffs=[],
                spoken_readback=reply,
                status="LISTENING",
                should_save=False,
                readback_completed=False,
            )

        diffs: list[FieldDiff] = []
        if new_name:
            if working.patient_name and working.patient_name != new_name:
                diffs.append(
                    FieldDiff(field="patient_name", old_value=working.patient_name, new_value=new_name)
                )
            working.patient_name = new_name

        if new_category:
            working.category = new_category

        for key, val in new_answers.items():
            if val is None or val == "":
                continue
            old_val = working.field_answers.get(key)
            working.field_answers[key] = val
            if old_val is not None and old_val != "" and old_val != val:
                diffs.append(FieldDiff(field=key, old_value=old_val, new_value=val))

        has_minimum = bool(working.patient_name)
        if intent == "CONFIRM_SAVE" and readback_completed and has_minimum:
            lang = resolve_short_language(language_code, cleaned)
            saved_msg = {
                "hi": f"धन्यवाद, {working.patient_name} का रिकॉर्ड {register.name} में सेव हो गया है।",
                "kn": f"ಧನ್ಯವಾದಗಳು, {working.patient_name} ಅವರ ದಾಖಲೆಯನ್ನು {register.name} ನಲ್ಲಿ ಉಳಿಸಲಾಗಿದೆ.",
                "ta": f"நன்றி, {working.patient_name} பதிவு {register.name} பதிவேட்டில் சேமிக்கப்பட்டது.",
                "te": f"ధన్యవాదాలు, {working.patient_name} రికార్డు {register.name} లో సేవ్ చేయబడింది.",
            }.get(lang, f"Thank you, {working.patient_name} has been saved to {register.name}.")
            return RegisterIntakeTurnResult(
                updated_draft=working,
                intent="CONFIRM_SAVE",
                field_diffs=[],
                spoken_readback=saved_msg,
                status="SAVED",
                should_save=True,
                readback_completed=True,
            )

        effective_intent: VoiceIntent = (
            "CORRECT_FIELD" if (intent == "CORRECT_FIELD" or diffs) else intent
        )
        if effective_intent == "CONFIRM_SAVE" and not readback_completed:
            effective_intent = "LOG_VISIT"

        reply = llm_reply or build_register_intake_readback(
            draft=working,
            diffs=diffs,
            intent=effective_intent,
            language_code=language_code,
            utterance=cleaned,
        )
        return RegisterIntakeTurnResult(
            updated_draft=working,
            intent=effective_intent,
            field_diffs=diffs,
            spoken_readback=reply,
            status="AWAITING_CONFIRMATION" if has_minimum else "LISTENING",
            should_save=False,
            readback_completed=has_minimum,
        )
