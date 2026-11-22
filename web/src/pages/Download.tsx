import { useMemo, useState } from "react";
import { Link } from "react-router";
import { CopyField } from "../components/CopyField";
import { DownloadCta } from "../components/DownloadCta";
import { DownloadIcon } from "../components/Layout";
import { QrCode } from "../components/QrCode";
import {
  DEFAULT_GUIDE_LANGUAGE,
  INSTALL_GUIDES,
  pickGuideLanguage,
  type GuideLanguage,
} from "../content/installGuide";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { useRelease } from "../hooks/useRelease";
import { currentPlatform, type Platform } from "../lib/device";
import {
  formatBytes,
  formatReleaseDate,
  releaseFileName,
  releaseFileUrl,
  type AppInfo,
  type Release,
} from "../lib/release";
import styles from "./Download.module.css";

const PREPARATION_CHECKLIST = [
  {
    label: "Speech Layer (Prisma v3.0 STT + Timbre v3.0 TTS)",
    status: "Ready",
    detail: "Full-duplex 16 kHz PCM WebSocket streaming with 23 clinical code-mix keyterms and <800 ms TTFA target.",
  },
  {
    label: "12-Item NHM Danger-Sign & FR-6 Compliance Firewall",
    status: "Ready",
    detail: "Cross-script ISCII phonetic matching across 5 languages; pre-TTS guardrail blocks diagnosis or dosage.",
  },
  {
    label: "Sunlit Material 3 Walk-Mode UI & Room v2 Offline Outbox",
    status: "In final verification",
    detail: "4-tab Android client (Walk Mode, Custom Registers, Follow-Ups, Visits) with FIFO PendingSyncEntity replay.",
  },
  {
    label: "Signed Release APK & SHA-256 Publication",
    status: "Pending",
    detail: "Published via npm run release as soon as the final Android build is signed off.",
  },
];

export function DownloadPage() {
  useDocumentTitle(
    "Download for Android",
    "Download the ASHA Vaani Android APK, verify its SHA-256 checksum, and follow step-by-step installation instructions in English, Hindi, Kannada, Tamil, and Telugu.",
  );

  const { state, retry } = useRelease();
  const platform = useMemo<Platform>(() => currentPlatform(), []);
  const initialLang = useMemo<GuideLanguage>(() => {
    if (typeof navigator === "undefined") return DEFAULT_GUIDE_LANGUAGE;
    return pickGuideLanguage(navigator.languages?.length ? navigator.languages : [navigator.language]);
  }, []);
  const [guideLang, setGuideLang] = useState<GuideLanguage>(initialLang);

  const activeGuide = INSTALL_GUIDES.find((g) => g.lang === guideLang) ?? INSTALL_GUIDES[0]!;

  return (
    <div className="section">
      <div className={`container ${styles.pageGrid}`}>
        {/* Header */}
        <div className={styles.pageHead}>
          <span className="eyebrow">Android Distribution · Direct APK</span>
          <h1>Download ASHA Vaani for Android</h1>
          <p>
            Official Android package distribution with cryptographic SHA-256 verification and multilingual installation
            instructions for ASHA workers and PHC supervisors.
          </p>
        </div>

        {/* Platform notice banner */}
        <PlatformHint platform={platform} />

        {/* Release state area */}
        {state.status === "loading" && <ReleaseLoadingCard />}

        {state.status === "error" && (
          <div className="alert alert--error" role="alert">
            <div className={styles.errorContent}>
              <div>
                <strong>Could not load release information</strong>
                <p>{state.message}</p>
              </div>
              <button type="button" className="btn btn--sm" onClick={retry}>
                Retry
              </button>
            </div>
          </div>
        )}

        {state.status === "ready" && state.manifest.latest === null && (
          <ComingSoonCard app={state.manifest.app} onRefresh={retry} />
        )}

        {state.status === "ready" && state.manifest.latest !== null && (
          <PublishedReleaseCard
            app={state.manifest.app}
            release={state.manifest.latest}
            history={state.manifest.history}
          />
        )}

        {/* Multilingual Install Guide */}
        <section id="install" tabIndex={-1} className={`card ${styles.guideCard}`} aria-labelledby="install-heading">
          <div className={styles.guideHeader}>
            <div>
              <span className="eyebrow">Step-by-Step Setup · 5 Languages</span>
              <h2 id="install-heading" lang={activeGuide.bcp47}>
                {activeGuide.heading}
              </h2>
            </div>

            <div className={styles.langTabs} role="tablist" aria-label="Installation guide language">
              {INSTALL_GUIDES.map((g) => {
                const selected = g.lang === activeGuide.lang;
                return (
                  <button
                    key={g.lang}
                    type="button"
                    role="tab"
                    lang={g.bcp47}
                    aria-selected={selected}
                    aria-controls={`guide-panel-${g.lang}`}
                    id={`guide-tab-${g.lang}`}
                    className={styles.langTab}
                    data-selected={selected}
                    onClick={() => setGuideLang(g.lang)}
                  >
                    {g.label}
                  </button>
                );
              })}
            </div>
          </div>

          <div
            id={`guide-panel-${activeGuide.lang}`}
            role="tabpanel"
            aria-labelledby={`guide-tab-${activeGuide.lang}`}
            lang={activeGuide.bcp47}
            className={styles.guideBody}
          >
            <ol className={styles.guideSteps}>
              {activeGuide.steps.map((s, idx) => (
                <li key={s.title} className={styles.guideStep}>
                  <span className={styles.guideStepNum}>{idx + 1}</span>
                  <div>
                    <h3>{s.title}</h3>
                    <p>{s.body}</p>
                  </div>
                </li>
              ))}
            </ol>

            <div className={styles.troubleshootBox}>
              <h3>{activeGuide.troubleshootingTitle}</h3>
              <div className={styles.troubleshootGrid}>
                {activeGuide.troubleshooting.map((t) => (
                  <div key={t.title} className={styles.troubleshootItem}>
                    <h4>{t.title}</h4>
                    <p>{t.body}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        {/* System requirements & permissions link */}
        <section className={`card ${styles.requirementsCard}`} aria-labelledby="req-heading">
          <div className={styles.requirementsGrid}>
            <div>
              <span className="eyebrow">Device Requirements</span>
              <h2 id="req-heading">What you need on the Android phone</h2>
              <p>
                ASHA Vaani runs on standard entry-level Android smartphones used in rural public health programmes.
              </p>
            </div>
            <dl className={styles.reqList}>
              <div>
                <dt>Operating System</dt>
                <dd>Android 8.0 (Oreo, API 26) or newer — target SDK 34</dd>
              </div>
              <div>
                <dt>Package ID</dt>
                <dd>
                  <code>com.ashavaani.app</code>
                </dd>
              </div>
              <div>
                <dt>Audio Hardware</dt>
                <dd>Built-in microphone &amp; loudspeaker (hardware AEC / NS / AGC attached automatically)</dd>
              </div>
              <div>
                <dt>Permissions</dt>
                <dd>
                  Microphone, Network, Audio Settings, Vibration —{" "}
                  <Link to="/privacy">read full permissions &amp; privacy breakdown</Link>
                </dd>
              </div>
            </dl>
          </div>
        </section>
      </div>
    </div>
  );
}

function PlatformHint({ platform }: { platform: Platform }) {
  if (platform === "ios") {
    return (
      <div className="alert alert--warning" role="status">
        <div>
          <strong>iPhone / iPad detected — Android device required</strong>
          <p>
            ASHA Vaani is a native Android application (<code>.apk</code>) built for Android 8.0 and above. Open this
            page on an Android phone or scan the QR code from an Android device once the release is live.
          </p>
        </div>
      </div>
    );
  }
  if (platform === "android") {
    return (
      <div className="alert alert--info" role="status">
        <div>
          <strong>Android device detected</strong>
          <p>
            You can download and install the APK directly on this phone as soon as a release is published, following
            the 5-step guide below in your preferred language.
          </p>
        </div>
      </div>
    );
  }
  return null;
}

function ReleaseLoadingCard() {
  return (
    <div className={`card ${styles.releaseCard}`} aria-busy="true" aria-label="Loading release details">
      <div style={{ display: "grid", gap: "0.9rem" }}>
        <span className="skeleton" style={{ width: 160, height: 26 }} />
        <span className="skeleton" style={{ width: "65%", height: 36 }} />
        <span className="skeleton" style={{ width: "85%", height: 20 }} />
        <span className="skeleton" style={{ width: 240, height: 56 }} />
      </div>
    </div>
  );
}

function ComingSoonCard({ app, onRefresh }: { app: AppInfo; onRefresh: () => void }) {
  return (
    <section className={`card ${styles.releaseCard}`} aria-labelledby="coming-soon-heading">
      <div className={styles.comingSoonGrid}>
        <div className={styles.releaseMain}>
          <div className={styles.statusRow}>
            <span className="pill pill--amber">
              <span className="dot" /> Android APK · Coming Soon
            </span>
            <span className="pill">
              Package <code>{app.packageName}</code>
            </span>
            <span className="pill">
              Android {app.minAndroidVersion}+ (API {app.minSdk}+)
            </span>
          </div>

          <h2 id="coming-soon-heading" className={styles.releaseHeading}>
            {app.name} for Android is in final build verification
          </h2>

          <p className={styles.releaseLead}>
            The Android application is currently being finalized. As soon as the signed APK is published, this card
            automatically unlocks the direct download button, file size, SHA-256 checksum, and mobile QR code.
          </p>

          <div className={styles.ctaRow}>
            <DownloadCta direct />
            <button type="button" className="btn" onClick={onRefresh}>
              Check again now
            </button>
          </div>

          <dl className={styles.metaGrid}>
            <div>
              <dt>APPLICATION</dt>
              <dd>{app.name}</dd>
            </div>
            <div>
              <dt>PACKAGE ID</dt>
              <dd>
                <code>{app.packageName}</code>
              </dd>
            </div>
            <div>
              <dt>MINIMUM ANDROID</dt>
              <dd>
                Android {app.minAndroidVersion} (API {app.minSdk})
              </dd>
            </div>
            <div>
              <dt>RELEASE STATUS</dt>
              <dd>Awaiting signed APK publication</dd>
            </div>
          </dl>
        </div>

        <div className={styles.checklistBox}>
          <h3>Release Readiness Checklist</h3>
          <ul className={styles.checklist}>
            {PREPARATION_CHECKLIST.map((item) => (
              <li key={item.label} className={styles.checklistItem}>
                <div className={styles.checklistTop}>
                  <strong>{item.label}</strong>
                  <span
                    className={`pill ${
                      item.status === "Ready"
                        ? "pill--emerald"
                        : item.status === "Pending"
                          ? "pill--amber"
                          : "pill--teal"
                    }`}
                  >
                    {item.status}
                  </span>
                </div>
                <p>{item.detail}</p>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

function PublishedReleaseCard({
  app,
  release,
  history,
}: {
  app: AppInfo;
  release: Release;
  history: Release[];
}) {
  const fileUrl = releaseFileUrl(release);
  const fileName = releaseFileName(release);
  const absoluteDownloadUrl = useMemo(() => {
    if (typeof window === "undefined") return fileUrl;
    return new URL(fileUrl, window.location.origin).toString();
  }, [fileUrl]);

  const verifyCmd = `echo "${release.sha256}  ${fileName}" | sha256sum -c -`;

  return (
    <>
      <section className={`card ${styles.releaseCard}`} aria-labelledby="latest-release-heading">
        <div className={styles.publishedGrid}>
          <div className={styles.releaseMain}>
            <div className={styles.statusRow}>
              <span className="pill pill--emerald">
                <span className="dot" /> Latest Release · v{release.versionName}
              </span>
              <span className="pill">Build code {release.versionCode}</span>
              <span className="pill">Released {formatReleaseDate(release.releasedAt)}</span>
            </div>

            <h2 id="latest-release-heading" className={styles.releaseHeading}>
              {app.name} v{release.versionName}
            </h2>

            <div className={styles.ctaRow}>
              <DownloadCta direct />
            </div>

            <dl className={styles.metaGrid}>
              <div>
                <dt>FILE NAME</dt>
                <dd>
                  <code>{fileName}</code>
                </dd>
              </div>
              <div>
                <dt>SIZE</dt>
                <dd>{formatBytes(release.sizeBytes)}</dd>
              </div>
              <div>
                <dt>PACKAGE ID</dt>
                <dd>
                  <code>{app.packageName}</code>
                </dd>
              </div>
              <div>
                <dt>MINIMUM ANDROID</dt>
                <dd>
                  Android {app.minAndroidVersion}+ (API {app.minSdk}+)
                </dd>
              </div>
            </dl>

            <div className={styles.checksumArea}>
              <CopyField label="SHA-256 Checksum" value={release.sha256} />
              <CopyField label="Verify downloaded APK in terminal" value={verifyCmd} />
            </div>

            {release.notes.length > 0 && (
              <div className={styles.notesBox}>
                <h3>What’s in this release</h3>
                <ul>
                  {release.notes.map((n) => (
                    <li key={n}>{n}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>

          <aside className={styles.qrAside} aria-label="Scan QR code on Android phone">
            <QrCode
              value={absoluteDownloadUrl}
              size={176}
              label={`QR code to download ${app.name} v${release.versionName}`}
            />
            <h3>Scan on Android phone</h3>
            <p>Point your phone’s camera at this QR code to download {fileName} directly.</p>
          </aside>
        </div>
      </section>

      {history.length > 0 && (
        <section className={`card ${styles.historyCard}`} aria-labelledby="history-heading">
          <span className="eyebrow">Previous Builds</span>
          <h2 id="history-heading">Release history</h2>
          <div className={styles.historyList}>
            {history.map((r) => (
              <div key={r.versionCode} className={styles.historyItem}>
                <div>
                  <strong>
                    v{r.versionName} <span className={styles.historyCode}>(code {r.versionCode})</span>
                  </strong>
                  <p className={styles.historyMeta}>
                    {formatReleaseDate(r.releasedAt)} · {formatBytes(r.sizeBytes)} · SHA-256{" "}
                    <code>{r.sha256.slice(0, 16)}…</code>
                  </p>
                </div>
                <a href={releaseFileUrl(r)} download={releaseFileName(r)} className="btn btn--sm">
                  <DownloadIcon size={16} /> Download v{r.versionName}
                </a>
              </div>
            ))}
          </div>
        </section>
      )}
    </>
  );
}
