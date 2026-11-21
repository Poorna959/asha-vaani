"""Tests for Issue #3: Deterministic Multilingual Danger-Sign Escalation Engine (12 NHM Red Flags)."""

from __future__ import annotations

import pytest

from app.pipeline.danger_signs import DangerSignDetector


@pytest.fixture(scope="module")
def detector() -> DangerSignDetector:
    return DangerSignDetector()


def test_loads_all_12_canonical_nhm_danger_signs(detector: DangerSignDetector):
    assert len(detector.canonical_codes) == 12
    assert "DS_MAT_BLEEDING" in detector.canonical_codes
    assert "DS_NB_HIGH_FEVER_COLD" in detector.canonical_codes
    assert "DS_NB_UMBILICAL_PUS" in detector.canonical_codes


@pytest.mark.parametrize(
    ("utterance", "expected_code", "visit_type"),
    [
        # 1. Maternal bleeding
        ("Sunita Devi has bleeding in pregnancy", "DS_MAT_BLEEDING", "ANC"),
        ("गर्भावस्था में खून आ रहा है", "DS_MAT_BLEEDING", "ANC"),
        ("ಗರ್ಭಿಣಿಗೆ ರಕ್ತಸ್ರಾವ ಆಗುತ್ತಿದೆ", "DS_MAT_BLEEDING", "ANC"),
        ("கர்ப்பிணிக்கு ரத்தப்போக்கு உள்ளது", "DS_MAT_BLEEDING", "ANC"),
        ("ఆమెకు రక్తస్రావం అవుతోంది", "DS_MAT_BLEEDING", "ANC"),
        # 2. Maternal convulsions
        ("Mother had convulsions this morning", "DS_MAT_CONVULSIONS", "ANC"),
        ("उसको दौरे आ रहे हैं", "DS_MAT_CONVULSIONS", "ANC"),
        ("ಅವಳಿಗೆ ಮೂರ್ಛೆ ಬಂದಿದೆ", "DS_MAT_CONVULSIONS", "ANC"),
        ("வலிப்பு வந்தது", "DS_MAT_CONVULSIONS", "ANC"),
        ("ఆమెకు ఫిట్స్ వచ్చాయి", "DS_MAT_CONVULSIONS", "ANC"),
        # 3. Severe headache + blurred vision
        ("Severe headache and blurred vision since morning", "DS_MAT_HEADACHE_VISION", "ANC"),
        ("तेज सिरदर्द और धुंधला दिखना", "DS_MAT_HEADACHE_VISION", "ANC"),
        ("ತೀವ್ರ ತಲೆನೋವು ಮತ್ತು ಕಣ್ಣು ಮಂಜಾಗುವುದು", "DS_MAT_HEADACHE_VISION", "ANC"),
        ("கடுமையான தலைவலி பார்வை மங்கல்", "DS_MAT_HEADACHE_VISION", "ANC"),
        ("తీవ్ర తలనొప్పి కళ్ళు మసకబారడం", "DS_MAT_HEADACHE_VISION", "ANC"),
        # 4. Maternal high fever
        ("Pregnant mother has high fever", "DS_MAT_HIGH_FEVER", "ANC"),
        ("उसको तेज बुखार है", "DS_MAT_HIGH_FEVER", "ANC"),
        ("ತಾಯಿಗೆ ತೀವ್ರ ಜ್ವರ ಇದೆ", "DS_MAT_HIGH_FEVER", "ANC"),
        ("அதிக காய்ச்சல் உள்ளது", "DS_MAT_HIGH_FEVER", "ANC"),
        ("ఆమెకు తీవ్ర జ్వరం ఉంది", "DS_MAT_HIGH_FEVER", "ANC"),
        # 5. Reduced/absent fetal movement
        ("No baby movement since yesterday", "DS_MAT_NO_FETAL_MOVEMENT", "ANC"),
        ("bacha hil nahi raha kal se", "DS_MAT_NO_FETAL_MOVEMENT", "ANC"),
        ("ಮಗು ಕದಲುತಿಲ್ಲ", "DS_MAT_NO_FETAL_MOVEMENT", "ANC"),
        ("குழந்தை அசையவில்லை", "DS_MAT_NO_FETAL_MOVEMENT", "ANC"),
        ("బిడ్డ కదలడం లేదు", "DS_MAT_NO_FETAL_MOVEMENT", "ANC"),
        # 6. Water breaking
        ("Water broke at home one hour ago", "DS_MAT_WATER_BREAKING", "ANC"),
        ("पानी की थैली फट गई pani beh raha", "DS_MAT_WATER_BREAKING", "ANC"),
        ("ನೀರಿನ ಚೀಲ ಒಡೆದಿದೆ", "DS_MAT_WATER_BREAKING", "ANC"),
        ("பனிக்குடம் உடைந்தது", "DS_MAT_WATER_BREAKING", "ANC"),
        ("ఉమ్మనీరు పోతోంది", "DS_MAT_WATER_BREAKING", "ANC"),
        # 7. Newborn high fever / hypothermia
        ("High fever in a newborn", "DS_NB_HIGH_FEVER_COLD", "NEWBORN"),
        ("बच्चे को बुखार है और शरीर ठंडा", "DS_NB_HIGH_FEVER_COLD", "NEWBORN"),
        ("ಮಗುವಿಗೆ ಜ್ವರ ಬಂದಿದೆ", "DS_NB_HIGH_FEVER_COLD", "NEWBORN"),
        ("குழந்தைக்கு காய்ச்சல் அதிகம்", "DS_NB_HIGH_FEVER_COLD", "NEWBORN"),
        ("శిశువుకు జ్వరం ఉంది", "DS_NB_HIGH_FEVER_COLD", "NEWBORN"),
        # 8. Newborn fast breathing / chest indrawing
        ("Baby has fast breathing and chest in-drawing", "DS_NB_FAST_BREATHING", "NEWBORN"),
        ("नवजात की तेज सांस चल रही है", "DS_NB_FAST_BREATHING", "NEWBORN"),
        ("ಮಗುವಿಗೆ ವೇಗದ ಉಸಿರಾಟ ಇದೆ", "DS_NB_FAST_BREATHING", "NEWBORN"),
        ("குழந்தை வேகமாக மூச்சு விடுகிறது", "DS_NB_FAST_BREATHING", "NEWBORN"),
        ("శిశువు వేగంగా శ్వాస తీసుకుంటోంది", "DS_NB_FAST_BREATHING", "NEWBORN"),
        # 9. Newborn poor feeding
        ("Newborn stopped sucking breast milk", "DS_NB_POOR_FEEDING", "NEWBORN"),
        ("बच्चा दूध नहीं पी रहा", "DS_NB_POOR_FEEDING", "NEWBORN"),
        ("ಮಗು ಹಾಲು ಕುಡಿಯುತ್ತಿಲ್ಲ", "DS_NB_POOR_FEEDING", "NEWBORN"),
        ("குழந்தை பால் குடிக்கவில்லை", "DS_NB_POOR_FEEDING", "NEWBORN"),
        ("శిశువు పాలు తాగడం లేదు", "DS_NB_POOR_FEEDING", "NEWBORN"),
        # 10. Newborn lethargy / unconscious
        ("Baby is very lethargic and unresponsive", "DS_NB_LETHARGY", "NEWBORN"),
        ("सुस्त बच्चा और बेहोश लग रहा है", "DS_NB_LETHARGY", "NEWBORN"),
        ("ಮಗು ತೀರಾ ಸುಸ್ತು ಆಗಿದೆ", "DS_NB_LETHARGY", "NEWBORN"),
        ("குழந்தைக்கு சுயநினைவில்லை", "DS_NB_LETHARGY", "NEWBORN"),
        ("శిశువుకు స్పృహ లేదు", "DS_NB_LETHARGY", "NEWBORN"),
        # 11. Newborn convulsions
        ("Newborn fits started 10 minutes ago", "DS_NB_CONVULSIONS", "NEWBORN"),
        ("नवजात को दौरे आ रहे हैं", "DS_NB_CONVULSIONS", "NEWBORN"),
        ("ಮಗುವಿಗೆ ಫಿಟ್ಸ್ ಬಂದಿದೆ", "DS_NB_CONVULSIONS", "NEWBORN"),
        ("குழந்தைக்கு வலிப்பு வந்தது", "DS_NB_CONVULSIONS", "NEWBORN"),
        ("శిశువుకు ఫిట్స్ వస్తున్నాయి", "DS_NB_CONVULSIONS", "NEWBORN"),
        # 12. Newborn umbilical pus
        ("Umbilical pus and foul smell cord", "DS_NB_UMBILICAL_PUS", "NEWBORN"),
        ("नाभि से मवाद निकल रहा है", "DS_NB_UMBILICAL_PUS", "NEWBORN"),
        ("ಹೊಕ್ಕುಳಲ್ಲಿ ಕೀವು ಬರುತ್ತಿದೆ", "DS_NB_UMBILICAL_PUS", "NEWBORN"),
        ("தொப்புளில் சீழ் வடிகிறது", "DS_NB_UMBILICAL_PUS", "NEWBORN"),
        ("బొడ్డులో చీము కారుతోంది", "DS_NB_UMBILICAL_PUS", "NEWBORN"),
    ],
)
def test_all_12_danger_signs_across_5_languages(
    detector: DangerSignDetector,
    utterance: str,
    expected_code: str,
    visit_type: str,
):
    alerts = detector.detect(utterance, language_code="indic-auto", visit_type=visit_type)
    codes = [a.code for a in alerts]
    assert expected_code in codes, f"Expected {expected_code} for '{utterance}', got {codes}"


@pytest.mark.parametrize(
    "routine_utterance",
    [
        "Sunita Devi ANC checkup weight 54.2 kilo IFA goli 30 given TT injection due",
        "Mild headache yesterday, resting well now, BP is normal",
        "Mother drinking garam paani, taking iron tablet daily, benefits of nutrition explained",
        "Normal hokkalu checkup, newborn weight 2.8 kilo, breastfeeding well",
        "No bleeding, no high fever, baby is active and feeding normally",
        "Bleeding nahi hai, tez bukhar nahi hai, sab normal hai",
        "Rakthasrava illa, teevra jwara illa, normal ANC checkup",
        "Rathapokku illai, athiga kaichal illai",
        "Rakthasravam ledu, teevra jwaram ledu",
    ],
)
def test_no_false_positives_on_routine_or_negated_notes(
    detector: DangerSignDetector,
    routine_utterance: str,
):
    alerts = detector.detect(routine_utterance, language_code="indic-auto")
    assert alerts == [], f"Unexpected false positive for '{routine_utterance}': {alerts}"


def test_clause_level_negation_does_not_suppress_real_danger_sign(detector: DangerSignDetector):
    alerts = detector.detect(
        "No fever, but bleeding started this morning",
        language_code="en-IN",
        visit_type="ANC",
    )
    assert [a.code for a in alerts] == ["DS_MAT_BLEEDING"]


def test_newborn_context_promotes_generic_high_fever_and_fits(detector: DangerSignDetector):
    alerts = detector.detect("The newborn has high fever since morning", language_code="en-IN")
    assert [a.code for a in alerts] == ["DS_NB_HIGH_FEVER_COLD"]

    alerts_fits = detector.detect("Baby is having fits", language_code="en-IN")
    assert [a.code for a in alerts_fits] == ["DS_NB_CONVULSIONS"]


def test_localized_instructions_and_priority_sorting(detector: DangerSignDetector):
    # Mix a HIGH sign (severe headache) + IMMEDIATE_EMERGENCY sign (bleeding) in Hindi
    alerts = detector.detect(
        "Severe headache and bleeding in pregnancy",
        language_code="hi-IN",
        visit_type="ANC",
    )
    assert [a.code for a in alerts] == ["DS_MAT_BLEEDING", "DS_MAT_HEADACHE_VISION"]
    assert alerts[0].priority == "IMMEDIATE_EMERGENCY"
    assert alerts[1].priority == "HIGH"
    assert "पीएचसी" in alerts[0].localized_instruction

    # Auto-detect Kannada script under indic-auto
    kn_alerts = detector.detect("ಗರ್ಭಿಣಿಗೆ ರಕ್ತಸ್ರಾವ", language_code="indic-auto")
    assert len(kn_alerts) == 1
    assert "PHC" in kn_alerts[0].localized_instruction
    assert "ಅಪಾಯದ ಲಕ್ಷಣ" in kn_alerts[0].localized_instruction

    combined = detector.build_referral_instruction(alerts, language_code="hi-IN")
    assert "रक्तस्राव" in combined
    assert "सिरदर्द" in combined


def test_danger_signs_catalog_contains_no_diagnosis_or_treatment_terms(detector: DangerSignDetector):
    """Guarantee the canonical JSON list itself obeys 'list only, no diagnosis or treatment'."""
    import json

    raw = json.loads(detector.path.read_text(encoding="utf-8"))
    forbidden_terms = (
        "eclampsia",
        "pre-eclampsia",
        "hypothermia",
        "infection",
        "malaria",
        "pneumonia",
        "anemia",
        "paracetamol",
        "antibiotic",
        "ultrasound",
        "dosage",
    )
    for item in raw["danger_signs"]:
        name_lower = item["canonical_name"].lower()
        for term in forbidden_terms:
            assert term not in name_lower, f"Diagnostic/treatment term '{term}' in {item['code']} canonical_name"

        for lang, script in item["referral_advice"].items():
            script_lower = script.lower()
            for term in forbidden_terms:
                assert term not in script_lower, (
                    f"Diagnostic/treatment term '{term}' in {item['code']} ({lang}) referral_advice"
                )
            assert any(marker in script for marker in ("PHC", "पीएचसी", "పీహెచ్‌సీ")), (
                f"Missing PHC referral instruction in {item['code']} ({lang})"
            )


@pytest.mark.parametrize(
    ("utterance", "expected_code", "language_code", "visit_type"),
    [
        # Exact user-reported live test cases (English + Kannada STT transliterations from real phone)
        ("Sunita Devi ANC checkup, severe headache and blurred vision.", "DS_MAT_HEADACHE_VISION", "en-IN", "ANC"),
        ("Newborn checkup, pus around navel and redness.", "DS_NB_UMBILICAL_PUS", "en-IN", "NEWBORN"),
        ("ಸುನಿತಾ ದೇವಿ, ಎ ಎನ್ ಸಿ ಚೆಕಪ್, ಸಿವಿಯರ್ ಹೆಡೆಕ್ ಆ್ಯಂಡ್ ಬ್ಲರ್ಡ್ ವಿಷನ್.", "DS_MAT_HEADACHE_VISION", "kn-IN", "ANC"),
        ("ಸುನಿತಾ ದೇವಿ, ಎ ಎನ್ ಸಿ ಚೆಕಪ್, ಸಿವಿಯರ್ ಹೆಡೇಕ್ ಆ್ಯಂಡ್ ಬ್ಲಡ್ ವಿಷನ್.", "DS_MAT_HEADACHE_VISION", "kn-IN", "ANC"),
        ("ಫೋನ್ ಚೆಕ್ ಅಪ್ ಪ್ಲಸ್ ಅರೋನ್ ನೇವಲ್ ಅಂಡ್ ರೆಡ್ನೆಸ್.", "DS_NB_UMBILICAL_PUS", "kn-IN", "NEWBORN"),
        ("ಬೇಬಿ ಫೀವರ್ ಇದೆ.", "DS_NB_HIGH_FEVER_COLD", "kn-IN", "NEWBORN"),
        ("ਬੇਬੀ ਹੈਜ਼ ਅ ਫੀਵਰ।", "DS_NB_HIGH_FEVER_COLD", "indic-auto", "NEWBORN"),
        # Hindi transliterations of English clinical danger signs
        ("सुनीता देवी को सिवियर हेडेक और ब्लर्ड विज़न है", "DS_MAT_HEADACHE_VISION", "hi-IN", "ANC"),
        ("सुनीता देवी को प्रेगनेंसी में ब्लीडिंग हो रही है", "DS_MAT_BLEEDING", "hi-IN", "ANC"),
        ("बच्चे को हाई फीवर है", "DS_NB_HIGH_FEVER_COLD", "hi-IN", "NEWBORN"),
        ("नवजात को फास्ट ब्रीदिंग हो रही है", "DS_NB_FAST_BREATHING", "hi-IN", "NEWBORN"),
        ("नवजात की नाभि में लाली और मवाद है", "DS_NB_UMBILICAL_PUS", "hi-IN", "NEWBORN"),
        # Common English STT phonetic variations & synonyms
        ("Newborn checkup, pus around naval and redness", "DS_NB_UMBILICAL_PUS", "en-IN", "NEWBORN"),
        ("Baby belly button pus and redness", "DS_NB_UMBILICAL_PUS", "en-IN", "NEWBORN"),
        ("Sunita Devi has blurry vision and bad headache", "DS_MAT_HEADACHE_VISION", "en-IN", "ANC"),
        ("Mother cannot see clearly and has heavy headache", "DS_MAT_HEADACHE_VISION", "en-IN", "ANC"),
        ("Pregnant mother has fever 103 since night", "DS_MAT_HIGH_FEVER", "en-IN", "ANC"),
        ("Water bag broke and fluid leaking", "DS_MAT_WATER_BREAKING", "en-IN", "ANC"),
        ("Baby is not moving since morning", "DS_MAT_NO_FETAL_MOVEMENT", "en-IN", "ANC"),
        ("Baby is refusing milk and not feeding", "DS_NB_POOR_FEEDING", "en-IN", "NEWBORN"),
        ("Baby is very drowsy and not waking up", "DS_NB_LETHARGY", "en-IN", "NEWBORN"),
        ("Baby is having seizures and twitching", "DS_NB_CONVULSIONS", "en-IN", "NEWBORN"),
        ("Baby has rapid breathing and trouble breathing", "DS_NB_FAST_BREATHING", "en-IN", "NEWBORN"),
    ],
)
def test_stt_transliteration_and_phonetic_edge_cases(
    detector: DangerSignDetector,
    utterance: str,
    expected_code: str,
    language_code: str,
    visit_type: str,
):
    alerts = detector.detect(utterance, language_code=language_code, visit_type=visit_type)
    codes = [a.code for a in alerts]
    assert expected_code in codes, f"Expected {expected_code} for '{utterance}', got {codes}"


@pytest.mark.parametrize(
    "safe_utterance",
    [
        "Normal navel checkup, clean cord, no redness, no pus",
        "No severe headache, no blurred vision, BP normal",
        "ಸಿವಿಯರ್ ಹೆಡೆಕ್ ಇಲ್ಲ, ನಾರ್ಮಲ್ ಚೆಕಪ್",
        "No umbilical pus, baby feeding well and active",
        "Mild headache yesterday, feeling fine now",
    ],
)
def test_edge_case_negations_and_normal_navel_do_not_false_trigger(
    detector: DangerSignDetector,
    safe_utterance: str,
):
    alerts = detector.detect(safe_utterance, language_code="indic-auto")
    assert alerts == [], f"Unexpected false positive for '{safe_utterance}': {alerts}"
