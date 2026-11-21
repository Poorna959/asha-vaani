"""Responsible AI & Medical Compliance Guardrail (FR-6 Post-LLM Output Interceptor).

Sits deterministically between the LLM and Gnani Timbre v3.0 TTS so that:
1. Active danger signs are ALWAYS template-locked to the approved localized PHC
   referral script (zero free-form medical commentary).
2. Any diagnostic label or medication/treatment prescription in a proposed LLM
   reply is blocked BEFORE TTS synthesis.
3. Adversarial user queries asking for diagnosis or medicine ("what medicine
   should I give?") receive a strict localized refusal + PHC referral.
4. Legitimate ASHA field register read-backs ("IFA goli", "TT injection",
   "Pentavalent", "weight 2.8 kilo") pass cleanly without false positives.
"""

from __future__ import annotations

import re
from typing import Literal, Optional
from pydantic import BaseModel, Field

from app.models import DangerSignAlert, LanguageCode
from app.pipeline.danger_signs import DangerSignDetector, resolve_short_language


OverrideType = Literal[
    "PASS",
    "DANGER_TEMPLATE_LOCK",
    "ADVICE_REFUSAL",
    "DIAGNOSIS_BLOCKED",
    "TREATMENT_BLOCKED",
]


class ComplianceResult(BaseModel):
    """Audit result returned by ComplianceGuardrail.enforce()."""

    safe_text: str
    was_blocked: bool = False
    override_type: OverrideType = "PASS"
    reasons: list[str] = Field(default_factory=list)


# Localized refusal + PHC referral templates (symptom-free, zero medical advice)
REFUSAL_TEMPLATES: dict[str, str] = {
    "en": (
        "I can only record visit notes and flag danger signs from the approved list. "
        "I cannot diagnose conditions or suggest medicine or treatment. "
        "Please refer the patient to the PHC or consult a doctor."
    ),
    "hi": (
        "मैं केवल विज़िट के नोट्स दर्ज कर सकती हूँ और तय सूची के खतरे के संकेत फ्लैग कर सकती हूँ। "
        "मैं बीमारी की पहचान या दवाई और इलाज की सलाह नहीं दे सकती। "
        "कृपया मरीज को पीएचसी (PHC) या डॉक्टर के पास रेफर करें।"
    ),
    "kn": (
        "ನಾನು ಭೇಟಿ ವಿವರಗಳನ್ನು ದಾಖಲಿಸಬಹುದು ಮತ್ತು ಪಟ್ಟಿಯಲ್ಲಿರುವ ಅಪಾಯದ ಲಕ್ಷಣಗಳನ್ನು ಮಾತ್ರ ಗುರುತಿಸಬಹುದು. "
        "ನಾನು ರೋಗ ನಿರ್ಣಯ ಅಥವಾ ಔಷಧಿ ಮತ್ತು ಚಿಕಿತ್ಸೆಯ ಸಲಹೆ ನೀಡಲು ಸಾಧ್ಯವಿಲ್ಲ. "
        "ದಯವಿಟ್ಟು ರೋಗಿಯನ್ನು PHC ಅಥವಾ ವೈದ್ಯರ ಬಳಿಗೆ ಕಳುಹಿಸಿ."
    ),
    "ta": (
        "நான் வருகை குறிப்புகளைப் பதிவு செய்து பட்டியலில் உள்ள அபாய அறிகுறிகளை மட்டுமே குறிக்க முடியும். "
        "நோய் கண்டறிதல் அல்லது மருந்து மற்றும் சிகிச்சை ஆலோசனை வழங்க முடியாது. "
        "தயவுசெய்து நோயாளியை PHC அல்லது மருத்துவரிடம் அனுப்பவும்."
    ),
    "te": (
        "నేను విజిట్ వివరాలు నమోదు చేసి జాబితాలోని ప్రమాద లక్షణాలను మాత్రమే ఫ్లాగ్ చేయగలను. "
        "వ్యాధి నిర్ధారణ లేదా మందులు, చికిత్స సలహా ఇవ్వలేను. "
        "దయచేసి రోగిని PHC లేదా డాక్టర్ వద్దకు పంపండి."
    ),
}

# Fallback emergency PHC referral if a violation occurs without a specific danger alert
DANGER_FALLBACK_TEMPLATES: dict[str, str] = {
    "en": "Danger sign detected. Please refer the patient to the PHC now. Do not give any medication.",
    "hi": "खतरे का संकेत मिला है। कृपया मरीज को तुरंत पीएचसी (PHC) रेफर करें। कोई दवाई न दें।",
    "kn": "ಅಪಾಯದ ಲಕ್ಷಣ ಕಂಡುಬಂದಿದೆ. ದಯವಿಟ್ಟು ತಕ್ಷಣ PHC ಗೆ ಕಳುಹಿಸಿ. ಯಾವುದೇ ಔಷಧಿ ನೀಡಬೇಡಿ.",
    "ta": "அபாய அறிகுறி கண்டறியப்பட்டது. உடனடியாக PHC க்கு அனுப்பவும். எந்த மருந்தும் கொடுக்க வேண்டாம்.",
    "te": "ప్రమాద లక్షణం గుర్తించబడింది. దయచేసి వెంటనే PHC కి తరలించండి. ఎలాంటి మందులు ఇవ్వకండి.",
}

# Patterns that detect diagnostic statements or disease labeling in proposed LLM output
DIAGNOSIS_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "diagnostic_disease_label",
        re.compile(
            r"(?<!\w)(?:pre[\s\-]?eclampsia|eclampsia|malaria|dengue|typhoid|pneumonia|"
            r"sepsis|meningitis|hypothermia|gestational\s+diabetes|hypertension|"
            r"jaundice|tuberculosis|infection|infected|placenta\s+previa|hemorrhagic\s+shock)(?!\w)",
            re.IGNORECASE,
        ),
    ),
    (
        "diagnostic_phrasing",
        re.compile(
            r"(?<!\w)(?:diagnosed\s+with|diagnosis\s+is|this\s+(?:looks|seems|appears)\s+like|"
            r"she\s+(?:likely\s+|probably\s+)?has\s+(?:a\s+|an\s+)?(?:disease|condition|anemia|infection)|"
            r"suffering\s+from|could\s+be\s+(?:a\s+|an\s+)?(?:sign\s+of|case\s+of)|"
            r"indicates\s+(?:a\s+|an\s+)?(?:disease|disorder|deficiency))(?!\w)",
            re.IGNORECASE,
        ),
    ),
    (
        "diagnostic_indic",
        re.compile(
            r"(?:यह\s+मलेरिया|प्री[\-\s]?एक्लैम्पसिया|इन्फेक्शन\s+है|संक्रमण\s+है|निमोनिया|"
            r"ಇನ್ಫೆಕ್ಷನ್|ಮಲೇರಿಯಾ|ನ್ಯುಮೋನಿಯಾ|தொற்று\s+உள்ளது|மலேரியா|ఇన్ఫెక్షన్|మలేరియా)",
            re.IGNORECASE,
        ),
    ),
)

# Patterns that detect drug prescriptions, dosages, or treatment advice in proposed LLM output
TREATMENT_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "specific_drug_name",
        re.compile(
            r"(?<!\w)(?:paracetamol|acetaminophen|ibuprofen|aspirin|amoxicillin|azithromycin|"
            r"antibiotic|antibiotics|methergine|misoprostol|oxytocin|dolo|crocin|calpol|"
            r"diclofenac|metformin|nifedipine|labetalol|magnesium\s+sulfate|steroid|steroids)(?!\w)",
            re.IGNORECASE,
        ),
    ),
    (
        "dosage_instruction",
        re.compile(
            r"(?<!\w)(?:\d+(?:\.\d+)?\s*(?:mg|ml|mcg|drops?|teaspoons?)|"
            r"twice\s+a\s+day|thrice\s+a\s+day|every\s+\d+\s+hours|"
            r"prescribe|prescription|dosage|dose\s+of)(?!\w)",
            re.IGNORECASE,
        ),
    ),
    (
        "treatment_directive",
        re.compile(
            r"(?<!\w)(?:(?:please\s+|you\s+(?:can|should)\s+)?(?:give|administer|take|apply|feed)\s+"
            r"(?:her\s+|him\s+|the\s+baby\s+|the\s+mother\s+)?"
            r"(?:medicine|medication|tablet|tablets|pill|pills|syrup|ointment|cream|drops|antibiotic|painkiller|home\s+remedy)|"
            r"apply\s+(?:oil|turmeric|ghee|paste|ointment|cream)|"
            r"treat\s+(?:it|her|him|the\s+fever)\s+with)(?!\w)",
            re.IGNORECASE,
        ),
    ),
    (
        "treatment_indic",
        re.compile(
            r"(?:पैरासिटामोल|एंटीबायोटिक|दवाई\s+(?:दें|दे\s+दें|खिलाएं)|गोली\s+खिलाएं|घरेलू\s+उपाय|"
            r"ಔಷಧಿ\s+ಕೊಡಿ|ಮಾತ್ರೆ\s+ಕೊಡಿ|ಮನೆಮದ್ದು|"
            r"மருந்து\s+கொடு|மாத்திரை\s+கொடு|"
            r"మందు\s+ఇవ్వండి|మాత్ర\s+ఇవ్వండి)",
            re.IGNORECASE,
        ),
    ),
)

# Adversarial user request patterns ("what medicine should I give?", "what disease is this?")
USER_MEDICAL_ADVICE_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(
        r"(?<!\w)(?:what|which)\s+(?:medicine|medication|drug|tablet|pill|syrup|antibiotic|treatment|remedy|disease|condition|illness)"
        r"|(?:should\s+i|can\s+i|how\s+much)\s+(?:give|administer|prescribe|treat|apply)"
        r"|(?:diagnose|diagnosis|prescribe|suggest\s+(?:a\s+)?(?:medicine|treatment|remedy|dosage))"
        r"|(?:is\s+(?:this|it|she)\s+(?:preeclampsia|eclampsia|malaria|infection|pneumonia|dengue|anemia|typhoid))(?!\w)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:कौन\s*सी\s*दवाई|क्या\s*दवाई\s*दूं|क्या\s*इलाज|kaun\s*si\s*dawai|kya\s*dawai\s*du|kya\s*bimari\s*hai|"
        r"ಯಾವ\s*ಔಷಧಿ|yava\s*aushadhi|yaava\s*matre|"
        r"என்ன\s*மருந்து|enna\s*marundhu|"
        r"ఏ\s*మందు|em\s*mandu)",
        re.IGNORECASE,
    ),
)


class ComplianceGuardrail:
    """Deterministic FR-6 safety firewall inspecting every reply before TTS speaks."""

    def __init__(self, danger_detector: Optional[DangerSignDetector] = None) -> None:
        self.danger_detector = danger_detector or DangerSignDetector()

    def is_medical_advice_request(self, user_utterance: str) -> bool:
        """Return True if the caller is asking for a diagnosis or treatment/medicine advice."""
        if not user_utterance:
            return False
        return any(pattern.search(user_utterance) for pattern in USER_MEDICAL_ADVICE_PATTERNS)

    def scan_proposed_reply(self, proposed_reply: str) -> tuple[Optional[OverrideType], list[str]]:
        """Inspect proposed LLM reply for diagnostic or treatment violations."""
        reasons: list[str] = []
        override: Optional[OverrideType] = None

        for label, pattern in TREATMENT_PATTERNS:
            match = pattern.search(proposed_reply)
            if match:
                reasons.append(f"{label}: '{match.group(0)}'")
                override = "TREATMENT_BLOCKED"

        for label, pattern in DIAGNOSIS_PATTERNS:
            match = pattern.search(proposed_reply)
            if match:
                reasons.append(f"{label}: '{match.group(0)}'")
                if override is None:
                    override = "DIAGNOSIS_BLOCKED"

        return override, reasons

    def get_refusal_message(self, language_code: LanguageCode | str, utterance: str = "") -> str:
        short_lang = resolve_short_language(language_code, utterance)
        return REFUSAL_TEMPLATES.get(short_lang, REFUSAL_TEMPLATES["en"])

    def enforce(
        self,
        proposed_reply: str,
        language_code: LanguageCode | str = "en-IN",
        user_utterance: str = "",
        danger_alerts: Optional[list[DangerSignAlert]] = None,
    ) -> ComplianceResult:
        """Enforce FR-6 compliance before any text is sent to Gnani Timbre v3.0 TTS."""
        alerts = danger_alerts or []
        short_lang = resolve_short_language(language_code, user_utterance or proposed_reply)

        # 1. If a danger sign is active, ALWAYS enforce the template-locked PHC referral script.
        if alerts:
            locked_script = self.danger_detector.build_referral_instruction(
                alerts=alerts,
                language_code=language_code if language_code != "indic-auto" else short_lang,
            )
            override_kind, violation_reasons = self.scan_proposed_reply(proposed_reply)
            reasons = ["danger_sign_template_locked"]
            if violation_reasons:
                reasons.extend(violation_reasons)
            if self.is_medical_advice_request(user_utterance):
                reasons.append("adversarial_medical_advice_request_blocked")
            was_blocked = bool(
                violation_reasons
                or self.is_medical_advice_request(user_utterance)
                or proposed_reply.strip() != locked_script.strip()
            )
            return ComplianceResult(
                safe_text=locked_script or DANGER_FALLBACK_TEMPLATES.get(short_lang, DANGER_FALLBACK_TEMPLATES["en"]),
                was_blocked=was_blocked,
                override_type="DANGER_TEMPLATE_LOCK",
                reasons=reasons,
            )

        # 2. If the caller explicitly asked for a diagnosis or medicine, refuse politely and refer to PHC.
        if self.is_medical_advice_request(user_utterance):
            _, violation_reasons = self.scan_proposed_reply(proposed_reply)
            reasons = ["medical_advice_request_refused", *violation_reasons]
            return ComplianceResult(
                safe_text=self.get_refusal_message(short_lang, user_utterance),
                was_blocked=True,
                override_type="ADVICE_REFUSAL",
                reasons=reasons,
            )

        # 3. Scan the proposed LLM text for any accidental diagnostic or prescription leakage.
        violation_type, violation_reasons = self.scan_proposed_reply(proposed_reply)
        if violation_type is not None:
            return ComplianceResult(
                safe_text=self.get_refusal_message(short_lang, user_utterance or proposed_reply),
                was_blocked=True,
                override_type=violation_type,
                reasons=violation_reasons,
            )

        # 4. Safe routine ASHA read-back passes untouched.
        return ComplianceResult(
            safe_text=proposed_reply.strip(),
            was_blocked=False,
            override_type="PASS",
            reasons=[],
        )
