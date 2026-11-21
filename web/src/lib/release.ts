/**
 * Release manifest contract (public/release.json), schemaVersion 1.
 * The same file is the update-check contract for the Android app — see docs/ANDROID_INTEGRATION.md.
 * Mirrors scripts/lib/release-core.mjs (writer side).
 */

export const MANIFEST_SCHEMA_VERSION = 1;

export interface AppInfo {
  name: string;
  packageName: string;
  minSdk: number;
  minAndroidVersion: string;
}

export interface Release {
  versionName: string;
  versionCode: number;
  releasedAt: string;
  /** Path relative to the site root, always under downloads/ */
  file: string;
  sizeBytes: number;
  sha256: string;
  notes: string[];
}

export interface ReleaseManifest {
  schemaVersion: 1;
  app: AppInfo;
  /** null while the Android app has not been published yet ("coming soon"). */
  latest: Release | null;
  history: Release[];
}

export class ManifestError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ManifestError";
  }
}

const VERSION_NAME_RE = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;
const SHA256_RE = /^[a-f0-9]{64}$/;
const FILE_RE = /^downloads\/[A-Za-z0-9._+-]+\.apk$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseRelease(value: unknown, where: string): Release {
  if (!isRecord(value)) throw new ManifestError(`${where} must be an object.`);
  const { versionName, versionCode, releasedAt, file, sizeBytes, sha256, notes } = value;
  if (typeof versionName !== "string" || !VERSION_NAME_RE.test(versionName)) {
    throw new ManifestError(`${where}.versionName is invalid.`);
  }
  if (typeof versionCode !== "number" || !Number.isInteger(versionCode) || versionCode < 1) {
    throw new ManifestError(`${where}.versionCode is invalid.`);
  }
  if (typeof releasedAt !== "string" || Number.isNaN(Date.parse(releasedAt))) {
    throw new ManifestError(`${where}.releasedAt is invalid.`);
  }
  // Strict pattern: no traversal, no absolute or external URLs can be injected via the manifest.
  if (typeof file !== "string" || !FILE_RE.test(file)) {
    throw new ManifestError(`${where}.file is invalid.`);
  }
  if (typeof sizeBytes !== "number" || !Number.isInteger(sizeBytes) || sizeBytes <= 0) {
    throw new ManifestError(`${where}.sizeBytes is invalid.`);
  }
  if (typeof sha256 !== "string" || !SHA256_RE.test(sha256)) {
    throw new ManifestError(`${where}.sha256 is invalid.`);
  }
  if (!Array.isArray(notes) || notes.some((n) => typeof n !== "string")) {
    throw new ManifestError(`${where}.notes is invalid.`);
  }
  return { versionName, versionCode, releasedAt, file, sizeBytes, sha256, notes: notes as string[] };
}

/** Validates untrusted JSON and returns a typed manifest, or throws ManifestError. */
export function parseManifest(value: unknown): ReleaseManifest {
  if (!isRecord(value)) throw new ManifestError("Manifest must be an object.");
  if (value.schemaVersion !== MANIFEST_SCHEMA_VERSION) {
    throw new ManifestError(`Unsupported manifest schemaVersion: ${String(value.schemaVersion)}.`);
  }
  const app = value.app;
  if (
    !isRecord(app) ||
    typeof app.name !== "string" ||
    typeof app.packageName !== "string" ||
    typeof app.minSdk !== "number" ||
    typeof app.minAndroidVersion !== "string"
  ) {
    throw new ManifestError("Manifest app info is invalid.");
  }
  const latest = value.latest === null ? null : parseRelease(value.latest, "latest");
  if (!Array.isArray(value.history)) throw new ManifestError("Manifest history must be an array.");
  const history = value.history.map((r, i) => parseRelease(r, `history[${i}]`));
  return {
    schemaVersion: MANIFEST_SCHEMA_VERSION,
    app: {
      name: app.name,
      packageName: app.packageName,
      minSdk: app.minSdk,
      minAndroidVersion: app.minAndroidVersion,
    },
    latest,
    history,
  };
}

/** Absolute-path URL for a release file, honouring Vite's base path. */
export function releaseFileUrl(release: Release, base: string = import.meta.env.BASE_URL): string {
  const prefix = base.endsWith("/") ? base : `${base}/`;
  return `${prefix}${release.file}`;
}

export function releaseFileName(release: Release): string {
  return release.file.slice(release.file.lastIndexOf("/") + 1);
}

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 100 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
}

export function formatReleaseDate(iso: string, locale = "en-IN"): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return new Intl.DateTimeFormat(locale, { day: "numeric", month: "short", year: "numeric" }).format(date);
}
