// Pure-ish release logic for publishing an Android APK to the website.
// Kept free of CLI concerns so it can be unit tested (see release-core.test.mjs).

import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { copyFile, mkdir, open, readFile, rename, stat, writeFile } from "node:fs/promises";
import path from "node:path";

export const MANIFEST_SCHEMA_VERSION = 1;
export const APK_MIME = "application/vnd.android.package-archive";
export const DOWNLOADS_DIR = "downloads";

/** Application constants mirrored from ../android/app/build.gradle.kts. */
export const DEFAULT_APP = Object.freeze({
  name: "ASHA Vaani",
  packageName: "com.ashavaani.app",
  minSdk: 26,
  minAndroidVersion: "8.0",
});

const VERSION_NAME_RE = /^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/;
const SHA256_RE = /^[a-f0-9]{64}$/;
const ZIP_MAGIC = Buffer.from([0x50, 0x4b, 0x03, 0x04]);

export class ReleaseError extends Error {
  constructor(message) {
    super(message);
    this.name = "ReleaseError";
  }
}

export function emptyManifest(app = DEFAULT_APP) {
  return { schemaVersion: MANIFEST_SCHEMA_VERSION, app: { ...app }, latest: null, history: [] };
}

export function isValidVersionName(value) {
  return typeof value === "string" && VERSION_NAME_RE.test(value);
}

export function apkFileName(versionName) {
  if (!isValidVersionName(versionName)) {
    throw new ReleaseError(`Invalid versionName "${versionName}". Expected semantic version like 1.0.0.`);
  }
  return `asha-vaani-v${versionName}.apk`;
}

/** Returns a list of human-readable problems; empty array means the manifest is valid. */
export function validateManifest(manifest) {
  const errors = [];
  if (!manifest || typeof manifest !== "object") return ["Manifest must be a JSON object."];
  if (manifest.schemaVersion !== MANIFEST_SCHEMA_VERSION) {
    errors.push(`schemaVersion must be ${MANIFEST_SCHEMA_VERSION}.`);
  }
  const app = manifest.app;
  if (!app || typeof app !== "object") {
    errors.push("app is required.");
  } else {
    if (typeof app.name !== "string" || !app.name) errors.push("app.name is required.");
    if (typeof app.packageName !== "string" || !app.packageName) errors.push("app.packageName is required.");
    if (!Number.isInteger(app.minSdk) || app.minSdk < 1) errors.push("app.minSdk must be a positive integer.");
    if (typeof app.minAndroidVersion !== "string") errors.push("app.minAndroidVersion is required.");
  }
  if (manifest.latest !== null) errors.push(...validateRelease(manifest.latest, "latest"));
  if (!Array.isArray(manifest.history)) {
    errors.push("history must be an array.");
  } else {
    manifest.history.forEach((r, i) => errors.push(...validateRelease(r, `history[${i}]`)));
  }
  return errors;
}

export function validateRelease(release, where = "release") {
  const errors = [];
  if (!release || typeof release !== "object") return [`${where} must be an object.`];
  if (!isValidVersionName(release.versionName)) errors.push(`${where}.versionName is invalid.`);
  if (!Number.isInteger(release.versionCode) || release.versionCode < 1) {
    errors.push(`${where}.versionCode must be a positive integer.`);
  }
  if (typeof release.releasedAt !== "string" || Number.isNaN(Date.parse(release.releasedAt))) {
    errors.push(`${where}.releasedAt must be an ISO date.`);
  }
  if (typeof release.file !== "string" || !release.file.startsWith(`${DOWNLOADS_DIR}/`) || !release.file.endsWith(".apk")) {
    errors.push(`${where}.file must be a path under ${DOWNLOADS_DIR}/ ending in .apk.`);
  }
  if (!Number.isInteger(release.sizeBytes) || release.sizeBytes <= 0) {
    errors.push(`${where}.sizeBytes must be a positive integer.`);
  }
  if (typeof release.sha256 !== "string" || !SHA256_RE.test(release.sha256)) {
    errors.push(`${where}.sha256 must be a lowercase hex SHA-256 digest.`);
  }
  if (!Array.isArray(release.notes) || release.notes.some((n) => typeof n !== "string")) {
    errors.push(`${where}.notes must be an array of strings.`);
  }
  return errors;
}

export async function sha256File(filePath) {
  const hash = createHash("sha256");
  await new Promise((resolve, reject) => {
    createReadStream(filePath)
      .on("data", (chunk) => hash.update(chunk))
      .on("error", reject)
      .on("end", resolve);
  });
  return hash.digest("hex");
}

/** Validates that the file looks like an APK (a ZIP archive) and returns its size and digest. */
export async function inspectApk(apkPath) {
  if (path.extname(apkPath).toLowerCase() !== ".apk") {
    throw new ReleaseError(`"${apkPath}" is not an .apk file.`);
  }
  let info;
  try {
    info = await stat(apkPath);
  } catch {
    throw new ReleaseError(`APK not found: ${apkPath}`);
  }
  if (!info.isFile() || info.size < ZIP_MAGIC.length) {
    throw new ReleaseError(`"${apkPath}" is empty or not a regular file.`);
  }
  const handle = await open(apkPath, "r");
  try {
    const header = Buffer.alloc(ZIP_MAGIC.length);
    await handle.read(header, 0, ZIP_MAGIC.length, 0);
    if (!header.equals(ZIP_MAGIC)) {
      throw new ReleaseError(`"${apkPath}" is not a valid APK (missing ZIP signature).`);
    }
  } finally {
    await handle.close();
  }
  return { sizeBytes: info.size, sha256: await sha256File(apkPath) };
}

/**
 * Reads the output-metadata.json that the Android Gradle Plugin writes next to every APK.
 * Returns null when the file does not exist (e.g. APK copied from elsewhere).
 */
export async function readGradleMetadata(apkPath) {
  const metaPath = path.join(path.dirname(apkPath), "output-metadata.json");
  let raw;
  try {
    raw = await readFile(metaPath, "utf8");
  } catch {
    return null;
  }
  let meta;
  try {
    meta = JSON.parse(raw);
  } catch {
    throw new ReleaseError(`Could not parse ${metaPath}.`);
  }
  const fileName = path.basename(apkPath);
  const elements = Array.isArray(meta.elements) ? meta.elements : [];
  const element = elements.find((e) => e && e.outputFile === fileName) ?? elements[0];
  if (!element) return null;
  return {
    applicationId: typeof meta.applicationId === "string" ? meta.applicationId : null,
    variantName: typeof meta.variantName === "string" ? meta.variantName : null,
    versionName: typeof element.versionName === "string" ? element.versionName : null,
    versionCode: Number.isInteger(element.versionCode) ? element.versionCode : null,
  };
}

/** Returns a new manifest with `release` as latest; the previous latest moves into history. */
export function applyRelease(manifest, release, { force = false } = {}) {
  const releaseErrors = validateRelease(release);
  if (releaseErrors.length) throw new ReleaseError(releaseErrors.join(" "));

  const previous = manifest.latest;
  if (previous && release.versionCode <= previous.versionCode && !force) {
    throw new ReleaseError(
      `versionCode ${release.versionCode} must be greater than the current latest (${previous.versionCode}). ` +
        "Bump versionCode in build.gradle.kts or pass --force to replace it.",
    );
  }
  const others = [previous, ...manifest.history].filter(
    (r) => r && r.versionCode !== release.versionCode,
  );
  others.sort((a, b) => b.versionCode - a.versionCode);
  return { ...manifest, latest: release, history: others };
}

export async function loadManifest(manifestPath) {
  let raw;
  try {
    raw = await readFile(manifestPath, "utf8");
  } catch {
    return emptyManifest();
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    throw new ReleaseError(`${manifestPath} is not valid JSON.`);
  }
  const errors = validateManifest(parsed);
  if (errors.length) throw new ReleaseError(`${manifestPath} is invalid: ${errors.join(" ")}`);
  return parsed;
}

async function writeJsonAtomic(filePath, data) {
  const tmp = `${filePath}.${process.pid}.tmp`;
  await writeFile(tmp, `${JSON.stringify(data, null, 2)}\n`, "utf8");
  await rename(tmp, filePath);
}

/**
 * Publishes an APK into `<publicDir>/downloads/` and updates `<publicDir>/release.json`.
 * @returns {{ release: object, manifest: object, destination: string, warnings: string[] }}
 */
export async function publishRelease({
  apkPath,
  publicDir,
  versionName,
  versionCode,
  notes = [],
  allowDebug = false,
  force = false,
  dryRun = false,
  now = new Date(),
}) {
  const warnings = [];
  const resolvedApk = path.resolve(apkPath);
  const { sizeBytes, sha256 } = await inspectApk(resolvedApk);
  const meta = await readGradleMetadata(resolvedApk);
  const manifestPath = path.join(publicDir, "release.json");
  const manifest = await loadManifest(manifestPath);

  if (meta?.variantName && meta.variantName.toLowerCase().includes("debug")) {
    if (!allowDebug) {
      throw new ReleaseError(
        "This is a DEBUG build. Debug APKs are signed with a shared debug key and should not be distributed. " +
          "Build a signed release (./gradlew :app:assembleRelease) or pass --allow-debug for internal testing.",
      );
    }
    warnings.push("Publishing a DEBUG build (--allow-debug).");
  }
  if (meta?.applicationId && meta.applicationId !== manifest.app.packageName) {
    throw new ReleaseError(
      `APK applicationId "${meta.applicationId}" does not match manifest packageName "${manifest.app.packageName}".`,
    );
  }

  const finalVersionName = versionName ?? meta?.versionName ?? null;
  const finalVersionCode = versionCode ?? meta?.versionCode ?? null;
  if (!finalVersionName || finalVersionCode == null) {
    throw new ReleaseError(
      "Could not determine version. Pass --version-name and --version-code (no output-metadata.json next to the APK).",
    );
  }
  if (meta?.versionName && versionName && meta.versionName !== versionName) {
    warnings.push(`--version-name ${versionName} overrides Gradle versionName ${meta.versionName}.`);
  }

  const fileName = apkFileName(finalVersionName);
  const release = {
    versionName: finalVersionName,
    versionCode: Number(finalVersionCode),
    releasedAt: now.toISOString(),
    file: `${DOWNLOADS_DIR}/${fileName}`,
    sizeBytes,
    sha256,
    notes: notes.map((n) => n.trim()).filter(Boolean),
  };
  const nextManifest = applyRelease(manifest, release, { force });
  const destination = path.join(publicDir, DOWNLOADS_DIR, fileName);

  if (!dryRun) {
    await mkdir(path.dirname(destination), { recursive: true });
    if (path.resolve(destination) !== resolvedApk) {
      await copyFile(resolvedApk, destination);
    }
    const copiedDigest = await sha256File(destination);
    if (copiedDigest !== sha256) throw new ReleaseError("Copied APK checksum mismatch; aborting.");
    await writeJsonAtomic(manifestPath, nextManifest);
  }
  return { release, manifest: nextManifest, destination, warnings };
}
