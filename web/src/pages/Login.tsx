import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { DownloadIcon } from "../components/Layout";
import { useAuth } from "../hooks/useAuth";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import styles from "./Login.module.css";

const QUICK_GMAIL_ACCOUNTS = [
  { email: "asha.sunita@gmail.com", name: "Sunita Devi (ASHA Worker)" },
  { email: "lakshmi.phc@gmail.com", name: "Lakshmi Gowda (PHC Supervisor)" },
];

export function LoginPage() {
  useDocumentTitle(
    "Sign In — Gmail & Email (Supabase)",
    "Sign in to ASHA Vaani with your Gmail or Email account via Supabase to download the verified Android APK and manage field registers.",
  );

  const { session, signInWithEmail, signUpWithEmail, signInWithGmail, buildGoogleOAuthUrl, signOut } = useAuth();
  const navigate = useNavigate();

  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [gmailInput, setGmailInput] = useState("");
  const [showGmailForm, setShowGmailForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleEmailSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      if (mode === "signup") {
        await signUpWithEmail(email, password, fullName);
      } else {
        await signInWithEmail(email, password);
      }
      navigate("/download");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Authentication failed.");
    } finally {
      setBusy(false);
    }
  }

  async function handleGmailSignIn(targetEmail: string, targetName = "") {
    setError(null);
    setBusy(true);
    try {
      await signInWithGmail(targetEmail, targetName);
      navigate("/download");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Invalid Gmail address.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="section">
      <div className={`container ${styles.loginGrid}`}>
        {/* Left column: Value prop & APK download readiness */}
        <div className={styles.infoCol}>
          <span className="pill pill--teal">Supabase Auth · Gmail &amp; Email</span>
          <h1 className={styles.title}>Sign in to download &amp; sync ASHA Vaani</h1>
          <p className={styles.lead}>
            Authenticate with your <strong>Gmail</strong> or <strong>Email</strong> account to download the official{" "}
            <strong>ASHA Vaani Android APK</strong>, access multilingual installation guides, and sync field registers
            with your Primary Health Centre.
          </p>

          <ul className={styles.benefitList}>
            <li>
              <strong>1-Tap Gmail &amp; Google OAuth</strong> — Works seamlessly across the web distribution portal and
              the Android Walk-Mode application.
            </li>
            <li>
              <strong>Direct Android APK Install</strong> — Cryptographically verified SHA-256 package ready to install
              on Android 8.0+ smartphones.
            </li>
            <li>
              <strong>Offline-First Rural Resilience</strong> — Local Room v2 database and Pending-Sync Outbox keep all
              4 tabs working even when cellular signal drops between houses.
            </li>
          </ul>
        </div>

        {/* Right column: Auth Card */}
        <div className={`card ${styles.authCard}`}>
          {session ? (
            <div className={styles.signedInBox}>
              <span className="pill pill--emerald">
                <span className="dot" /> Signed in via {session.provider === "google" ? "Gmail" : "Supabase Email"}
              </span>
              <h2>Welcome, {session.displayName}</h2>
              <p className={styles.signedInEmail}>{session.email}</p>
              <p className={styles.signedInHint}>
                Your account is verified. You can now download and install the latest ASHA Vaani Android APK on your
                phone.
              </p>

              <div className={styles.signedInActions}>
                <Link to="/download" className="btn btn--primary btn--lg">
                  <DownloadIcon /> Go to Android APK Download
                </Link>
                <button type="button" className="btn" onClick={signOut}>
                  Sign out
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className={styles.cardHeader}>
                <span className="eyebrow">ASHA Worker &amp; PHC Portal</span>
                <h2>{mode === "signup" ? "Create your account" : "Sign in to your account"}</h2>
              </div>

              {error && (
                <div className="alert alert--error" role="alert">
                  <strong>Authentication notice:</strong> {error}
                </div>
              )}

              {/* Gmail Sign-In Section */}
              <div className={styles.gmailSection}>
                <button
                  type="button"
                  className={styles.googleBtn}
                  onClick={() => setShowGmailForm((v) => !v)}
                  disabled={busy}
                >
                  <span className={styles.googleBadge} aria-hidden="true">
                    G
                  </span>
                  <span>Continue with Gmail / Google</span>
                </button>

                {showGmailForm && (
                  <div className={styles.gmailDrawer}>
                    <p className={styles.gmailLabel}>Enter your @gmail.com address or choose a verified demo account:</p>
                    <div className={styles.quickPresetList}>
                      {QUICK_GMAIL_ACCOUNTS.map((preset) => (
                        <button
                          key={preset.email}
                          type="button"
                          className={styles.presetChip}
                          onClick={() => handleGmailSignIn(preset.email, preset.name)}
                          disabled={busy}
                        >
                          <span>
                            <strong>{preset.name}</strong>
                            <small>{preset.email}</small>
                          </span>
                          <span className="pill pill--teal">1-Tap Gmail</span>
                        </button>
                      ))}
                    </div>

                    <div className={styles.gmailInputRow}>
                      <input
                        type="email"
                        aria-label="Your Gmail address"
                        placeholder="yourname@gmail.com"
                        value={gmailInput}
                        onChange={(e) => setGmailInput(e.target.value)}
                        className={styles.input}
                      />
                      <button
                        type="button"
                        className="btn btn--primary"
                        disabled={busy || !gmailInput.trim()}
                        onClick={() => handleGmailSignIn(gmailInput)}
                      >
                        Sign in with Gmail
                      </button>
                    </div>

                    <a href={buildGoogleOAuthUrl("/download")} className={styles.oauthLink}>
                      Or redirect via Supabase Google OAuth →
                    </a>
                  </div>
                )}
              </div>

              <div className={styles.divider}>
                <span>OR USE EMAIL &amp; PASSWORD</span>
              </div>

              {/* Mode Tabs */}
              <div className={styles.modeTabs} role="tablist" aria-label="Authentication mode">
                <button
                  type="button"
                  role="tab"
                  aria-selected={mode === "signin"}
                  className={styles.modeTab}
                  data-active={mode === "signin"}
                  onClick={() => {
                    setMode("signin");
                    setError(null);
                  }}
                >
                  Sign In
                </button>
                <button
                  type="button"
                  role="tab"
                  aria-selected={mode === "signup"}
                  className={styles.modeTab}
                  data-active={mode === "signup"}
                  onClick={() => {
                    setMode("signup");
                    setError(null);
                  }}
                >
                  Create Account
                </button>
              </div>

              {/* Email & Password Form */}
              <form onSubmit={handleEmailSubmit} className={styles.form}>
                {mode === "signup" && (
                  <label className={styles.field}>
                    <span>Full Name</span>
                    <input
                      type="text"
                      placeholder="e.g. Kavitha Devi"
                      value={fullName}
                      onChange={(e) => setFullName(e.target.value)}
                      className={styles.input}
                    />
                  </label>
                )}

                <label className={styles.field}>
                  <span>Email or Gmail Address *</span>
                  <input
                    type="email"
                    required
                    placeholder="asha.worker@gmail.com"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    className={styles.input}
                  />
                </label>

                <label className={styles.field}>
                  <span>Password (min 6 characters) *</span>
                  <input
                    type="password"
                    required
                    minLength={6}
                    placeholder="••••••••"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className={styles.input}
                  />
                </label>

                <button type="submit" className="btn btn--primary btn--lg" disabled={busy}>
                  {busy
                    ? "Signing in..."
                    : mode === "signup"
                      ? "Create Account & Continue to APK"
                      : "Sign In with Email"}
                </button>
              </form>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
