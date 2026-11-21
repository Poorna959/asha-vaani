import { Link } from "react-router";
import { DANGER_SIGN_COUNT, DANGER_SIGN_DISCLAIMER } from "../content/dangerSigns";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import styles from "./Privacy.module.css";

interface PermissionEntry {
  permission: string;
  required: boolean;
  purpose: string;
}

const ANDROID_PERMISSIONS: PermissionEntry[] = [
  {
    permission: "android.permission.RECORD_AUDIO",
    required: true,
    purpose:
      "Captures 16 kHz mono PCM voice notes ONLY while the ASHA worker actively taps the Walk-Mode microphone orb. Hardware AcousticEchoCanceler, NoiseSuppressor, and AutomaticGainControl attach to the audio session.",
  },
  {
    permission: "android.permission.INTERNET",
    required: true,
    purpose:
      "Streams spoken audio frames over WebSocket (/ws/session) to the ASHA Vaani orchestrator and syncs structured visits, custom registers, and follow-ups over REST (/api/*).",
  },
  {
    permission: "android.permission.ACCESS_NETWORK_STATE",
    required: true,
    purpose:
      "Detects offline vs. online transitions so the app can switch cleanly to Room v2 offline cache and replay queued PendingSyncEntity mutations when connectivity recovers.",
  },
  {
    permission: "android.permission.MODIFY_AUDIO_SETTINGS",
    required: true,
    purpose:
      "Routes mandatory Timbre v3.0 read-back audio and urgent PHC referral alerts to the outdoor loudspeaker so the ASHA worker can hear read-back clearly in household and street noise.",
  },
  {
    permission: "android.permission.VIBRATE",
    required: false,
    purpose:
      "Provides tactile one-thumb haptic feedback on microphone start/stop, FieldDiff voice correction, confirmed saves, and double-pulse alerts on danger-sign escalation.",
  },
];

const DATA_HANDLING = [
  {
    title: "Local-First On-Device Persistence (Room v2)",
    body: "Visits, follow-ups, custom registers, patient profiles, and the offline pending-sync outbox are stored locally in the app’s private SQLite Room database on the Android device. Exported .xlsx and .csv files are shared explicitly by the user via Android’s native FileProvider share sheet.",
  },
  {
    title: "Voice Streaming & Ephemeral Audio Processing",
    body: "Live 16 kHz PCM audio is streamed over full-duplex WebSockets only during an active voice capture turn for Speech-to-Text (Gnani Prisma v3.0) and read-back Speech Synthesis (Gnani Timbre v3.0). The app never records background audio when the microphone orb is idle.",
  },
  {
    title: "Zero Diagnosis & Pre-TTS Medical Compliance Firewall",
    body: `ASHA Vaani enforces ${DANGER_SIGN_COUNT} canonical NHM danger signs deterministically. Every outgoing response passes through the FR-6 ComplianceGuardrail before synthesis: “${DANGER_SIGN_DISCLAIMER}”`,
  },
  {
    title: "Zero Ad SDKs, Zero Third-Party Analytics Trackers",
    body: "The Android APK embeds only Jetpack Compose Material 3, AndroidX Room, OkHttp, and Kotlinx Serialization. It contains no advertising identifiers, social trackers, or third-party analytics SDKs.",
  },
];

export function PrivacyPage() {
  useDocumentTitle(
    "Permissions, Privacy & Safety",
    "Android permissions, data handling, local-first storage, and Responsible AI medical compliance guardrails for ASHA Vaani.",
  );

  return (
    <div className="section">
      <div className={`container ${styles.grid}`}>
        <div className={styles.head}>
          <span className="eyebrow">Transparency · Android Manifest &amp; Responsible AI</span>
          <h1>Permissions, Data Handling &amp; Medical Safety</h1>
          <p>
            Every permission requested by <code>com.ashavaani.app</code> maps directly to a field-register capability declared in{" "}
            <code>AndroidManifest.xml</code>.
          </p>
        </div>

        <section className={`card ${styles.cardSection}`} aria-labelledby="permissions-heading">
          <span className="eyebrow">AndroidManifest.xml</span>
          <h2 id="permissions-heading">Android permissions requested by the APK</h2>
          <div className={styles.permList}>
            {ANDROID_PERMISSIONS.map((p) => (
              <div key={p.permission} className={styles.permItem}>
                <div className={styles.permTop}>
                  <code>{p.permission}</code>
                  <span className={`pill ${p.required ? "pill--teal" : ""}`}>
                    {p.required ? "Core permission" : "Haptics"}
                  </span>
                </div>
                <p>{p.purpose}</p>
              </div>
            ))}
          </div>
        </section>

        <section className={styles.dataGrid} aria-label="Data handling principles">
          {DATA_HANDLING.map((d) => (
            <article key={d.title} className={`card ${styles.dataCard}`}>
              <h2>{d.title}</h2>
              <p>{d.body}</p>
            </article>
          ))}
        </section>

        <div className={`card ${styles.footerBox}`}>
          <div>
            <h2>Looking for the Android APK or SHA-256 checksum?</h2>
            <p>Visit the Download page for release verification and multilingual setup steps.</p>
          </div>
          <Link to="/download" className="btn btn--primary">
            Go to Download page
          </Link>
        </div>
      </div>
    </div>
  );
}
