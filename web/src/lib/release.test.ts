import { describe, expect, it } from "vitest";
import { detectPlatform } from "./device";
import {
  formatBytes,
  formatReleaseDate,
  ManifestError,
  parseManifest,
  releaseFileName,
  releaseFileUrl,
} from "./release";

const BASE_MANIFEST = {
  schemaVersion: 1,
  app: {
    name: "ASHA Vaani",
    packageName: "com.ashavaani.app",
    minSdk: 26,
    minAndroidVersion: "8.0",
  },
  latest: null,
  history: [],
};

const SAMPLE_RELEASE = {
  versionName: "1.0.0",
  versionCode: 2,
  releasedAt: "2026-11-22T00:00:00.000Z",
  file: "downloads/asha-vaani-v1.0.0.apk",
  sizeBytes: 18_218_373,
  sha256: "978ee6fa78baefd61049db549bbae0d54781a863681195b1b0a9796e80b4e6e9",
  notes: ["Initial signed release"],
};

describe("parseManifest", () => {
  it("parses the coming-soon manifest (latest === null)", () => {
    const parsed = parseManifest(BASE_MANIFEST);
    expect(parsed.latest).toBeNull();
    expect(parsed.app.packageName).toBe("com.ashavaani.app");
  });

  it("parses a published release manifest with history", () => {
    const parsed = parseManifest({
      ...BASE_MANIFEST,
      latest: SAMPLE_RELEASE,
      history: [{ ...SAMPLE_RELEASE, versionName: "0.2.0", versionCode: 1, file: "downloads/asha-vaani-v0.2.0.apk" }],
    });
    expect(parsed.latest?.versionName).toBe("1.0.0");
    expect(parsed.history).toHaveLength(1);
  });

  it("rejects path traversal or external URLs in release.file", () => {
    expect(() =>
      parseManifest({ ...BASE_MANIFEST, latest: { ...SAMPLE_RELEASE, file: "../secret.apk" } }),
    ).toThrow(ManifestError);
    expect(() =>
      parseManifest({ ...BASE_MANIFEST, latest: { ...SAMPLE_RELEASE, file: "https://evil.example/a.apk" } }),
    ).toThrow(ManifestError);
  });

  it("rejects malformed sha256 or schemaVersion", () => {
    expect(() =>
      parseManifest({ ...BASE_MANIFEST, latest: { ...SAMPLE_RELEASE, sha256: "short" } }),
    ).toThrow(ManifestError);
    expect(() => parseManifest({ ...BASE_MANIFEST, schemaVersion: 99 })).toThrow(ManifestError);
  });
});

describe("release helpers", () => {
  it("formats byte sizes and release URLs cleanly", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(18_218_373)).toBe("17.4 MB");
    expect(releaseFileName(SAMPLE_RELEASE)).toBe("asha-vaani-v1.0.0.apk");
    expect(releaseFileUrl(SAMPLE_RELEASE, "/")).toBe("/downloads/asha-vaani-v1.0.0.apk");
    expect(releaseFileUrl(SAMPLE_RELEASE, "/subpath")).toBe("/subpath/downloads/asha-vaani-v1.0.0.apk");
    expect(formatReleaseDate("2026-11-22T00:00:00.000Z")).toMatch(/2026/);
  });
});

describe("detectPlatform", () => {
  it("detects Android, iOS, iPadOS desktop-mode, and desktop browsers", () => {
    expect(detectPlatform("Mozilla/5.0 (Linux; Android 14; CPH2553) AppleWebKit/537.36")).toBe("android");
    expect(detectPlatform("Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X)")).toBe("ios");
    expect(detectPlatform("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)", 5)).toBe("ios");
    expect(detectPlatform("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36", 0)).toBe("desktop");
  });
});
