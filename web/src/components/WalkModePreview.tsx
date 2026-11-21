import { useState } from "react";
import styles from "./WalkModePreview.module.css";

export type PreviewScenarioId = "anc" | "correction" | "danger";

interface Scenario {
  id: PreviewScenarioId;
  tabLabel: string;
  langPill: string;
  langTag: string;
  stateLabel: string;
  stateTone: "teal" | "amber" | "crimson";
  spokenQuote: string;
  visitType: string;
  beneficiary: string;
  beneficiaryId: string;
  weight: string;
  weightPrev?: string;
  ifa: string;
  vaccines: string;
  nextVisit: string;
  readBackText: string;
  dangerAlert?: {
    code: string;
    sign: string;
    referralScript: string;
  };
}

const SCENARIOS: Scenario[] = [
  {
    id: "anc",
    tabLabel: "1. Routine ANC Capture",
    langPill: "हिन्दी · Code-Mix",
    langTag: "hi-IN",
    stateLabel: "READ-BACK · AWAITING CONFIRM",
    stateTone: "teal",
    spokenQuote: "“Sunita Devi, ANC checkup, vajan 54.5 kilo, 30 IFA goli di, TT booster due, agla visit 15 December.”",
    visitType: "ANC · Pregnancy",
    beneficiary: "Sunita Devi",
    beneficiaryId: "BEN-204",
    weight: "54.5 kg",
    ifa: "30 tablets",
    vaccines: "TT Booster",
    nextVisit: "15 Dec 2026",
    readBackText:
      "Sunita Devi का ANC विज़िट: वज़न 54.5 किलो, 30 IFA गोली दी गई, TT Booster बाकी है, अगली विज़िट 15 दिसंबर। क्या यह सही है?",
  },
  {
    id: "correction",
    tabLabel: "2. Barge-In Voice Correction",
    langPill: "ಕನ್ನಡ · Code-Mix",
    langTag: "kn-IN",
    stateLabel: "FIELD DIFF · UPDATED BY VOICE",
    stateTone: "amber",
    spokenQuote: "“ಅಲ್ಲ, ತೂಕ 2.8 ಕಿಲೋ — no, weight is 2.8 kilo.”",
    visitType: "HBNC · Newborn",
    beneficiary: "Baby of Kavitha",
    beneficiaryId: "NB-118",
    weight: "2.8 kg",
    weightPrev: "3.2 kg",
    ifa: "—",
    vaccines: "BCG, OPV-0",
    nextVisit: "29 Nov 2026",
    readBackText:
      "ತೂಕ 3.2 ಕಿಲೋ ಇಂದ 2.8 ಕಿಲೋಗೆ ಬದಲಾಗಿದೆ (Weight updated to 2.8 kg). BCG ಮತ್ತು OPV-0 ಬಾಕಿ ಇದೆ. ಉಳಿಸಲೇ?",
  },
  {
    id: "danger",
    tabLabel: "3. Danger-Sign PHC Escalation",
    langPill: "தமிழ் · Urgent",
    langTag: "ta-IN",
    stateLabel: "PHC REFERRAL · FLAGGED",
    stateTone: "crimson",
    spokenQuote: "“லட்சுமி, கர்ப்ப காலத்தில் இரத்தப்போக்கு இருக்கு — bleeding in pregnancy.”",
    visitType: "ANC · Emergency",
    beneficiary: "Lakshmi M.",
    beneficiaryId: "BEN-309",
    weight: "51.0 kg",
    ifa: "—",
    vaccines: "—",
    nextVisit: "Immediate PHC",
    readBackText:
      "அபாய அறிகுறி கண்டறியப்பட்டது. உடனடியாக ஆரம்ப சுகாதார நிலையத்திற்கு (PHC) அழைத்துச் செல்லவும்.",
    dangerAlert: {
      code: "DS_MAT_BLEEDING",
      sign: "Bleeding during pregnancy",
      referralScript: "Immediate referral to Primary Health Centre (PHC) now. Record flagged (has_danger_sign = true). Zero diagnosis or medication advice.",
    },
  },
];

/**
 * Interactive preview of the Android Walk-Mode screen showing all 3 PS1 functional pillars:
 * 1) Voice capture in Indic + English code-mix
 * 2) Mandatory read-back with warm-amber FieldDiff voice correction
 * 3) Deterministic danger-sign PHC referral banner
 */
export function WalkModePreview() {
  const [activeId, setActiveId] = useState<PreviewScenarioId>("anc");
  const scenario = SCENARIOS.find((s) => s.id === activeId) ?? SCENARIOS[0]!;

  return (
    <div className={styles.wrapper}>
      <div className={styles.tabs} role="tablist" aria-label="Walk Mode interactive scenarios">
        {SCENARIOS.map((s) => {
          const selected = s.id === scenario.id;
          return (
            <button
              key={s.id}
              type="button"
              role="tab"
              aria-selected={selected}
              aria-controls={`phone-panel-${s.id}`}
              id={`phone-tab-${s.id}`}
              className={styles.tab}
              data-selected={selected}
              data-tone={s.stateTone}
              onClick={() => setActiveId(s.id)}
            >
              {s.tabLabel}
            </button>
          );
        })}
      </div>

      <div
        id={`phone-panel-${scenario.id}`}
        role="tabpanel"
        aria-labelledby={`phone-tab-${scenario.id}`}
        className={styles.phoneFrame}
        data-tone={scenario.stateTone}
      >
        {/* Top bar */}
        <div className={styles.phoneTopBar}>
          <div className={styles.phoneTitleRow}>
            <span className={styles.phoneAppTitle}>ASHA Vaani · Walk Mode</span>
            <span className={styles.ttfaBadge}>TTFA 640 ms</span>
          </div>
          <div className={styles.langRow}>
            <span className={styles.langChipActive}>{scenario.langPill}</span>
            <span className={styles.stateChip} data-tone={scenario.stateTone}>
              <span className="dot" /> {scenario.stateLabel}
            </span>
          </div>
        </div>

        {/* Spoken utterance bubble */}
        <div className={styles.transcriptCard} lang={scenario.langTag}>
          <span className={styles.microLabel}>SPOKEN BY ASHA (PRISMA v3.0 STREAMING STT)</span>
          <p className={styles.transcriptQuote}>{scenario.spokenQuote}</p>
        </div>

        {/* Danger escalation banner when active */}
        {scenario.dangerAlert && (
          <div className={styles.dangerSheet} role="note" aria-label="Urgent PHC referral alert">
            <div className={styles.dangerHeader}>
              <span className={styles.dangerBadge}>URGENT PHC REFERRAL</span>
              <code className={styles.dangerCode}>{scenario.dangerAlert.code}</code>
            </div>
            <p className={styles.dangerTitle}>{scenario.dangerAlert.sign}</p>
            <p className={styles.dangerBody}>{scenario.dangerAlert.referralScript}</p>
          </div>
        )}

        {/* Structured visit card */}
        <div className={styles.visitCard}>
          <div className={styles.visitHeader}>
            <div>
              <span className={styles.microLabel}>BENEFICIARY</span>
              <p className={styles.beneficiaryName}>{scenario.beneficiary}</p>
            </div>
            <div className={styles.visitBadges}>
              <span className={styles.idChip}>{scenario.beneficiaryId}</span>
              <span className={styles.typeChip}>{scenario.visitType}</span>
            </div>
          </div>

          <dl className={styles.fieldGrid}>
            <div className={styles.fieldCell} data-diff={Boolean(scenario.weightPrev)}>
              <dt>WEIGHT</dt>
              <dd>
                {scenario.weightPrev && (
                  <span className={styles.prevValue} aria-label={`previous value ${scenario.weightPrev}`}>
                    {scenario.weightPrev}
                  </span>
                )}
                <span>{scenario.weight}</span>
              </dd>
            </div>
            <div className={styles.fieldCell}>
              <dt>IFA TABLETS</dt>
              <dd>{scenario.ifa}</dd>
            </div>
            <div className={styles.fieldCell}>
              <dt>VACCINES DUE</dt>
              <dd>{scenario.vaccines}</dd>
            </div>
            <div className={styles.fieldCell}>
              <dt>NEXT VISIT</dt>
              <dd>{scenario.nextVisit}</dd>
            </div>
          </dl>
        </div>

        {/* Read-back bar */}
        <div className={styles.readBackBar} lang={scenario.langTag}>
          <div className={styles.readBackHeader}>
            <span className={styles.microLabel}>MANDATORY READ-BACK (TIMBRE v3.0 · LAKSHMI)</span>
            <span className={styles.bargeInTag}>Barge-in ready (&lt;150 ms)</span>
          </div>
          <p className={styles.readBackText}>{scenario.readBackText}</p>
        </div>

        {/* One-thumb bottom mic bar */}
        <div className={styles.bottomBar} aria-hidden="true">
          <span className={styles.navPillActive}>Walk Mode</span>
          <span className={styles.micOrb} data-tone={scenario.stateTone}>
            <MicSvg />
          </span>
          <span className={styles.navPill}>Registers · Follow-Ups</span>
        </div>
      </div>
    </div>
  );
}

function MicSvg() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="9" y="3" width="6" height="11" rx="3" stroke="currentColor" strokeWidth="2.2" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3m-3.5 0h7" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  );
}
