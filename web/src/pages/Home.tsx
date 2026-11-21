import { Link } from "react-router";
import { DownloadCta, ReleaseStatusBadge } from "../components/DownloadCta";
import { WalkModePreview } from "../components/WalkModePreview";
import {
  DANGER_SIGN_COUNT,
  DANGER_SIGN_DISCLAIMER,
  DANGER_SIGN_GROUPS,
  DANGER_SIGN_LIST_VERSION,
} from "../content/dangerSigns";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import styles from "./Home.module.css";

const LANGUAGES = [
  { code: "hi-IN", native: "हिन्दी", english: "Hindi + English code-mix", sample: "“Sunita Devi, ANC checkup, vajan 54.5 kilo, 30 IFA goli”" },
  { code: "kn-IN", native: "ಕನ್ನಡ", english: "Kannada + English code-mix", sample: "“ಅಲ್ಲ, ತೂಕ 2.8 ಕಿಲೋ — BCG ಮತ್ತು OPV-0 ಬಾಕಿ ಇದೆ”" },
  { code: "ta-IN", native: "தமிழ்", english: "Tamil + English code-mix", sample: "“லட்சுமி, ANC பரிசோதனை, எடை 52 கிலோ, TT ஊசி”" },
  { code: "te-IN", native: "తెలుగు", english: "Telugu + English code-mix", sample: "“రాధమ్మ, HBNC నవజాత శిశువు బరువు 2.9 కిలోలు”" },
  { code: "en-IN", native: "English", english: "Indian English clinical terms", sample: "“TT injection, ANC checkup, IFA tablets, 2.8 kilo, PHC referral”" },
];

const WORKFLOW_STEPS = [
  {
    step: "01",
    title: "Speak the visit naturally while walking",
    body: "Tap the 80dp microphone orb with one thumb and speak in Hindi, Kannada, Tamil, Telugu, or English code-mix. Gnani Prisma v3.0 streaming STT recognizes 23 clinical health keyterms (ANC, HBNC, TT injection, IFA goli, PHC) even in household and street noise.",
  },
  {
    step: "02",
    title: "Listen to mandatory read-back & barge in to fix mistakes",
    body: "Before any visit is saved, Gnani Timbre v3.0 (voice Lakshmi) reads the structured form aloud. Say “no, weight is 2.8 kilo” at any moment — audio cuts in under 150 ms and the updated field highlights in warm amber.",
  },
  {
    step: "03",
    title: "Confirm by voice to populate follow-ups & alert the PHC",
    body: "A spoken confirmation saves the visit to the local Room database, schedules the next visit on the Follow-Up Queue with zero typing, and streams urgent danger-sign flags to the PHC Supervisor console.",
  },
];

const FEATURES = [
  {
    tag: "PS1 Pillar 1 · Speech Layer",
    title: "Sub-800 ms Time-to-First-Audio & Street-Noise DSP",
    body: "Hardware AcousticEchoCanceler, NoiseSuppressor, and AutomaticGainControl on Android paired with server-side adaptive VAD and full-duplex WebSocket streaming (16 kHz PCM, 20 ms frames).",
  },
  {
    tag: "PS1 Pillar 2 · Clinical Accuracy",
    title: "Structured Visit Form with Mandatory Read-Back",
    body: "Captures beneficiary_id, beneficiary_name, visit_type (ANC / HBNC / Immunisation), weight_kg, ifa_tablets_given, vaccinations_due, next_visit_date, and notes — never saved without spoken confirmation.",
  },
  {
    tag: "PS1 Pillar 3 · Responsible AI",
    title: "3-Layer Deterministic Danger-Sign Firewall",
    body: "Cross-script ISCII phonetic matching against the 12-item NHM catalog plus a pre-TTS FR-6 Compliance Guardrail that locks output to localized PHC referral scripts and blocks any diagnosis, medicine, or dosage.",
  },
  {
    tag: "Custom Requirement · FR-7",
    title: "Custom First-Visit Registers & 1-Tap Excel Export",
    body: "Create custom named registers with dynamic fields (Age, Blood Group, LMP, Parity, Phone, Ward), onboard patients by voice, view clean vertical Field : Answer profiles (never cramped spreadsheet grids), and export to .xlsx or .csv.",
  },
  {
    tag: "Rural Resilience · FR-P3.3",
    title: "Offline-First Room DB & Pending-Sync Outbox",
    body: "Every register, patient profile, visit, and follow-up is cached in Android Room v2. Mutations recorded in dead zones queue in PendingSyncEntity and replay in FIFO order with bounded reconnect backoff (1s → 8s).",
  },
  {
    tag: "Outdoor Ergonomics · WCAG AAA+",
    title: "Sunlit One-Thumb Walk-Mode Material 3 UI",
    body: "17.4:1 contrast (#0F172A ink on crisp #FFFFFF cards with 1.5dp slate borders), tabular numeric typography so live diffs never jitter, and all primary controls inside the lower 45% natural thumb arc.",
  },
];

const FAQS = [
  {
    q: "Why does ASHA Vaani never give a medical diagnosis or suggest medicine?",
    a: "Under PS1 Responsible AI compliance, ASHA Vaani is strictly a voice field register and danger-sign referral tool. When spoken notes match any of the 12 canonical NHM danger signs, the pre-TTS ComplianceGuardrail deterministically instructs the ASHA worker in her language to refer the patient to the Primary Health Centre (PHC) immediately and flags the record — with zero diagnosis, medication names, or dosage advice.",
  },
  {
    q: "Does the app work when cellular coverage drops between houses?",
    a: "Yes. All 4 tabs (Walk Mode, Registers, Follow-Ups, Visits) are backed by a local Android Room v2 database and a FIFO Pending-Sync Outbox. When connectivity returns, queued saves replay automatically.",
  },
  {
    q: "How are custom first-time patient registers displayed on mobile?",
    a: "Patient profiles inside custom registers always render as vertical Field : Answer cards for clear outdoor reading. Spreadsheet tables are generated only when the ASHA worker taps Export Excel (.xlsx) or Export CSV (.csv).",
  },
  {
    q: "When will the Android APK be available for download on this site?",
    a: "The Android client is currently in final build verification. As soon as the signed APK is published via the release pipeline, the Download page automatically switches from 'Coming soon' to a direct APK download with SHA-256 checksum and QR code.",
  },
];

export function HomePage() {
  useDocumentTitle(
    undefined,
    "ASHA Vaani — Zero-typing Indic voice field register for ASHA workers. Turn spoken ANC, newborn HBNC, and immunisation home-visit notes into structured records, follow-up lists, and instant PHC danger-sign referrals.",
  );

  return (
    <>
      {/* Hero */}
      <section className={styles.hero}>
        <div className={`container ${styles.heroGrid}`}>
          <div className={styles.heroCopy}>
            <div className={styles.badgeRow}>
              <span className="pill pill--teal">Gnani Voice Hackathon · PS1 Healthcare</span>
              <ReleaseStatusBadge />
            </div>

            <h1 className={styles.heroTitle}>
              Speak the home visit.{" "}
              <span className={styles.heroAccent}>The field register fills itself</span> before the next house.
            </h1>

            <p className={styles.heroLead}>
              <strong>ASHA Vaani</strong> is a zero-typing Android voice agent for Accredited Social Health Activists.
              It turns spoken pregnancy (ANC), newborn (HBNC), and immunisation notes in{" "}
              <strong>Hindi, Kannada, Tamil, Telugu, and English code-mix</strong> into a verified visit record, an
              automatic follow-up queue, and immediate PHC danger-sign referrals — built for direct sunlight and
              household or street noise.
            </p>

            <div className={styles.heroActions}>
              <DownloadCta />
              <Link to="/#how-it-works" className="btn btn--lg">
                See how Walk Mode works
              </Link>
            </div>

            <dl className={styles.metricsStrip} aria-label="Target performance benchmarks">
              <div>
                <dt>Time-to-First-Audio</dt>
                <dd>&lt; 800 ms</dd>
              </div>
              <div>
                <dt>Barge-In Cutoff</dt>
                <dd>&lt; 150 ms</dd>
              </div>
              <div>
                <dt>NHM Danger Signs</dt>
                <dd>{DANGER_SIGN_COUNT} canonical</dd>
              </div>
              <div>
                <dt>Languages</dt>
                <dd>4 Indic + EN</dd>
              </div>
            </dl>
          </div>

          <div className={styles.heroPreview}>
            <WalkModePreview />
          </div>
        </div>
      </section>

      {/* Supported languages */}
      <section className="section section--white" aria-labelledby="languages-heading">
        <div className="container">
          <div className="section-head">
            <span className="eyebrow">Indic + English Code-Mix · Gnani Prisma v3.0 &amp; Timbre v3.0</span>
            <h2 id="languages-heading">Built for the way ASHA workers actually speak in the field</h2>
            <p>
              Health visits mix native regional sentences with clinical shorthand like <code>ANC checkup</code>,{" "}
              <code>TT injection</code>, <code>IFA goli</code>, and weights in kilos. ASHA Vaani streams 16 kHz PCM
              audio over WebSockets with 23 boosted clinical keyterms.
            </p>
          </div>

          <ul className={styles.langGrid}>
            {LANGUAGES.map((l) => (
              <li key={l.code} className={`card ${styles.langCard}`} lang={l.code}>
                <div className={styles.langHeader}>
                  <span className={styles.langNative}>{l.native}</span>
                  <code className={styles.langCode}>{l.code}</code>
                </div>
                <p className={styles.langEnglish} lang="en-IN">
                  {l.english}
                </p>
                <p className={styles.langSample}>{l.sample}</p>
              </li>
            ))}
          </ul>
        </div>
      </section>

      {/* How a visit works */}
      <section id="how-it-works" tabIndex={-1} className="section" aria-labelledby="workflow-heading">
        <div className="container">
          <div className="section-head">
            <span className="eyebrow">Zero-Typing Primary Loop</span>
            <h2 id="workflow-heading">From spoken note to confirmed record in three voice steps</h2>
            <p>
              Designed for one-thumb operation while walking between households — no typing, no tiny dropdowns, and
              never an unconfirmed save.
            </p>
          </div>

          <ol className={styles.stepsGrid}>
            {WORKFLOW_STEPS.map((item) => (
              <li key={item.step} className={`card ${styles.stepCard}`}>
                <span className={styles.stepNum}>{item.step}</span>
                <h3>{item.title}</h3>
                <p>{item.body}</p>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* Safety / Danger signs */}
      <section id="safety" tabIndex={-1} className={`section ${styles.safetySection}`} aria-labelledby="safety-heading">
        <div className="container">
          <div className={styles.safetyTop}>
            <div className="section-head" style={{ marginBottom: 0 }}>
              <span className="pill pill--crimson">PS1 Pillar 3 · Strict Medical Compliance</span>
              <h2 id="safety-heading">
                Instant PHC referral on {DANGER_SIGN_COUNT} canonical NHM danger signs — zero diagnosis, ever
              </h2>
              <p>
                Whenever spoken notes mention a maternal or newborn danger sign from{" "}
                <code>docs/danger_signs.json</code> (v{DANGER_SIGN_LIST_VERSION}), the parallel danger-sign fast-path
                immediately speaks a localized referral to the Primary Health Centre and flags the visit record (
                <code>has_danger_sign = true</code>).
              </p>
            </div>

            <div className={`card ${styles.guardrailBox}`}>
              <h3>FR-6 Pre-TTS Compliance Guardrail</h3>
              <p className={styles.disclaimerQuote}>“{DANGER_SIGN_DISCLAIMER}”</p>
              <ul>
                <li>Deterministic cross-script keyword &amp; ISCII phonetic scanner across 5 languages.</li>
                <li>Blocks disease diagnosis, medicine names, and dosage instructions before audio reaches TTS.</li>
                <li>Verified against a 113-case multilingual adversarial red-team suite (100% pass rate).</li>
              </ul>
            </div>
          </div>

          <div className={styles.dangerGroups}>
            {DANGER_SIGN_GROUPS.map((group) => (
              <div key={group.id} className={`card ${styles.dangerGroupCard}`}>
                <div className={styles.dangerGroupHeader}>
                  <div>
                    <span className="eyebrow">{group.subtitle}</span>
                    <h3>{group.title}</h3>
                  </div>
                  <span className="pill pill--crimson">{group.signs.length} signs</span>
                </div>
                <ul className={styles.dangerList}>
                  {group.signs.map((sign) => (
                    <li key={sign.code} className={styles.dangerItem}>
                      <div>
                        <p className={styles.dangerName}>{sign.name}</p>
                        <code className={styles.dangerItemCode}>{sign.code}</code>
                      </div>
                      <span
                        className={`pill ${sign.priority === "IMMEDIATE_EMERGENCY" ? "pill--crimson" : "pill--amber"}`}
                      >
                        {sign.priority === "IMMEDIATE_EMERGENCY" ? "Immediate PHC" : "High Priority"}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        </div>
      </section>

      {/* Feature grid */}
      <section id="features" tabIndex={-1} className="section section--white" aria-labelledby="features-heading">
        <div className="container">
          <div className="section-head">
            <span className="eyebrow">Architecture &amp; Field Capabilities</span>
            <h2 id="features-heading">Engineered for rural daylight, noisy doorsteps, and patchy networks</h2>
            <p>
              Every layer of ASHA Vaani — from Android hardware audio filters to custom first-visit registers — is
              built around an ASHA worker’s daily beat.
            </p>
          </div>

          <div className={styles.featureGrid}>
            {FEATURES.map((f) => (
              <article key={f.title} className={`card ${styles.featureCard}`}>
                <span className={styles.featureTag}>{f.tag}</span>
                <h3>{f.title}</h3>
                <p>{f.body}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      {/* FAQ */}
      <section className="section" aria-labelledby="faq-heading">
        <div className="container">
          <div className="section-head">
            <span className="eyebrow">Questions &amp; Answers</span>
            <h2 id="faq-heading">Frequently asked questions</h2>
          </div>

          <div className={styles.faqList}>
            {FAQS.map((item) => (
              <details key={item.q} className={`card ${styles.faqItem}`}>
                <summary>{item.q}</summary>
                <p>{item.a}</p>
              </details>
            ))}
          </div>

          <div className={`card ${styles.bottomCtaCard}`}>
            <div>
              <h2>Ready to install ASHA Vaani on an Android phone?</h2>
              <p>
                Check the release status, SHA-256 verification digest, and step-by-step installation guide in English,
                हिन्दी, ಕನ್ನಡ, தமிழ், and తెలుగు.
              </p>
            </div>
            <DownloadCta />
          </div>
        </div>
      </section>
    </>
  );
}
