import { Link } from "react-router";
import { useRelease } from "../hooks/useRelease";
import { formatBytes, releaseFileName, releaseFileUrl } from "../lib/release";
import { DownloadIcon } from "./Layout";

interface DownloadCtaProps {
  /** On the /download page the button downloads the APK directly; elsewhere it can either download or go to /download. */
  direct?: boolean;
  size?: "md" | "lg";
  className?: string;
}

/**
 * Primary download call-to-action.
 * - Loading: skeleton placeholder with the same footprint so layout never shifts.
 * - Ready + latest: direct APK download (or link to /download when direct=false).
 * - Ready + coming soon (latest === null): links to /download (or disabled on /download itself).
 * - Error: links to /download where the full retry UI lives.
 */
export function DownloadCta({ direct = false, size = "lg", className = "" }: DownloadCtaProps) {
  const { state } = useRelease();
  const sizeClass = size === "lg" ? "btn--lg" : "";

  if (state.status === "loading") {
    return (
      <span
        className={`btn ${sizeClass} ${className}`}
        aria-busy="true"
        aria-live="polite"
      >
        <span className="skeleton" style={{ width: 190, height: 20 }} />
      </span>
    );
  }

  if (state.status === "ready" && state.manifest.latest) {
    const release = state.manifest.latest;
    const meta = `v${release.versionName} · ${formatBytes(release.sizeBytes)}`;
    if (direct) {
      return (
        <a
          href={releaseFileUrl(release)}
          download={releaseFileName(release)}
          className={`btn btn--primary ${sizeClass} ${className}`}
        >
          <DownloadIcon size={20} />
          <span>Download APK</span>
          <span className="btn__meta">({meta})</span>
        </a>
      );
    }
    return (
      <Link to="/download" className={`btn btn--primary ${sizeClass} ${className}`}>
        <DownloadIcon size={20} />
        <span>Download for Android</span>
        <span className="btn__meta">({meta})</span>
      </Link>
    );
  }

  // Coming soon or error
  if (direct) {
    return (
      <button
        type="button"
        disabled
        aria-disabled="true"
        className={`btn ${sizeClass} ${className}`}
      >
        <ClockIcon />
        <span>Android APK — Coming soon</span>
      </button>
    );
  }

  return (
    <Link to="/download" className={`btn btn--primary ${sizeClass} ${className}`}>
      <DownloadIcon size={20} />
      <span>Get the Android app</span>
      <span className="btn__meta">(Coming soon)</span>
    </Link>
  );
}

export function ReleaseStatusBadge() {
  const { state } = useRelease();
  if (state.status === "loading") {
    return <span className="pill">Checking release…</span>;
  }
  if (state.status === "error") {
    return (
      <span className="pill pill--crimson">
        <span className="dot" /> Release status unavailable
      </span>
    );
  }
  const { latest, app } = state.manifest;
  if (latest) {
    return (
      <span className="pill pill--emerald">
        <span className="dot" /> v{latest.versionName} available · Android {app.minAndroidVersion}+
      </span>
    );
  }
  return (
    <span className="pill pill--amber">
      <span className="dot" /> Android {app.minAndroidVersion}+ · Release in preparation
    </span>
  );
}

function ClockIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true" focusable="false">
      <circle cx="12" cy="12" r="8.5" stroke="currentColor" strokeWidth="2" />
      <path d="M12 7.5v5l3.2 2" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
