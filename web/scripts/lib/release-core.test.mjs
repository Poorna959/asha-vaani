// @vitest-environment node
import { mkdtemp, readFile, writeFile, mkdir, rm, access } from "node:fs/promises";
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  apkFileName,
  applyRelease,
  emptyManifest,
  inspectApk,
  publishRelease,
  readGradleMetadata,
  ReleaseError,
  validateManifest,
} from "./release-core.mjs";

const ZIP = Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.from("fake apk body")]);
const sha = (buf) => createHash("sha256").update(buf).digest("hex");

function release(overrides = {}) {
  return {
    versionName: "1.0.0",
    versionCode: 1,
    releasedAt: "2026-11-22T00:00:00.000Z",
    file: "downloads/asha-vaani-v1.0.0.apk",
    sizeBytes: 10,
    sha256: "a".repeat(64),
    notes: [],
    ...overrides,
  };
}

let dir;
let publicDir;
let buildDir;

async function writeApk(name = "app-release.apk", meta) {
  const apk = path.join(buildDir, name);
  await writeFile(apk, ZIP);
  if (meta) await writeFile(path.join(buildDir, "output-metadata.json"), JSON.stringify(meta));
  return apk;
}

function gradleMeta({ variantName = "release", versionName = "1.0.0", versionCode = 1, outputFile = "app-release.apk" } = {}) {
  return {
    version: 3,
    applicationId: "com.ashavaani.app",
    variantName,
    elements: [{ type: "SINGLE", versionCode, versionName, outputFile }],
  };
}

beforeEach(async () => {
  dir = await mkdtemp(path.join(os.tmpdir(), "asha-release-"));
  publicDir = path.join(dir, "public");
  buildDir = path.join(dir, "build");
  await mkdir(publicDir, { recursive: true });
  await mkdir(buildDir, { recursive: true });
});

afterEach(async () => {
  await rm(dir, { recursive: true, force: true });
});

describe("validateManifest", () => {
  it("accepts the empty coming-soon manifest", () => {
    expect(validateManifest(emptyManifest())).toEqual([]);
  });

  it("reports invalid release fields", () => {
    const m = { ...emptyManifest(), latest: release({ sha256: "nope", file: "../evil.apk", versionCode: 0 }) };
    const errors = validateManifest(m);
    expect(errors.join(" ")).toMatch(/sha256/);
    expect(errors.join(" ")).toMatch(/file/);
    expect(errors.join(" ")).toMatch(/versionCode/);
  });

  it("rejects non-objects and wrong schema versions", () => {
    expect(validateManifest(null)).toHaveLength(1);
    expect(validateManifest({ ...emptyManifest(), schemaVersion: 2 })[0]).toMatch(/schemaVersion/);
  });
});

describe("apkFileName", () => {
  it("builds a safe file name from a semantic version", () => {
    expect(apkFileName("1.2.3")).toBe("asha-vaani-v1.2.3.apk");
    expect(apkFileName("1.2.3-beta.1")).toBe("asha-vaani-v1.2.3-beta.1.apk");
  });
  it("rejects path traversal and junk", () => {
    expect(() => apkFileName("../../etc")).toThrow(ReleaseError);
    expect(() => apkFileName("1.0")).toThrow(ReleaseError);
  });
});

describe("applyRelease", () => {
  it("moves the previous latest into history, newest first", () => {
    let m = applyRelease(emptyManifest(), release());
    m = applyRelease(m, release({ versionName: "1.1.0", versionCode: 2, file: "downloads/asha-vaani-v1.1.0.apk" }));
    expect(m.latest.versionCode).toBe(2);
    expect(m.history.map((r) => r.versionCode)).toEqual([1]);
  });

  it("refuses a non-increasing versionCode unless forced", () => {
    const m = applyRelease(emptyManifest(), release({ versionCode: 3 }));
    expect(() => applyRelease(m, release({ versionCode: 3 }))).toThrow(/greater than/);
    const forced = applyRelease(m, release({ versionCode: 3, notes: ["rebuilt"] }), { force: true });
    expect(forced.latest.notes).toEqual(["rebuilt"]);
    expect(forced.history).toHaveLength(0);
  });
});

describe("inspectApk", () => {
  it("returns size and sha256 for a ZIP-signed file", async () => {
    const apk = await writeApk();
    await expect(inspectApk(apk)).resolves.toEqual({ sizeBytes: ZIP.length, sha256: sha(ZIP) });
  });

  it("rejects missing files, wrong extensions and non-zip content", async () => {
    await expect(inspectApk(path.join(buildDir, "missing.apk"))).rejects.toThrow(/not found/);
    const txt = path.join(buildDir, "notes.txt");
    await writeFile(txt, ZIP);
    await expect(inspectApk(txt)).rejects.toThrow(/not an \.apk/);
    const bogus = path.join(buildDir, "bogus.apk");
    await writeFile(bogus, "hello world");
    await expect(inspectApk(bogus)).rejects.toThrow(/ZIP signature/);
  });
});

describe("readGradleMetadata", () => {
  it("reads version info written by the Android Gradle Plugin", async () => {
    const apk = await writeApk("app-release.apk", gradleMeta({ versionName: "2.0.0", versionCode: 7 }));
    await expect(readGradleMetadata(apk)).resolves.toEqual({
      applicationId: "com.ashavaani.app",
      variantName: "release",
      versionName: "2.0.0",
      versionCode: 7,
    });
  });

  it("returns null when there is no metadata", async () => {
    const apk = await writeApk();
    await expect(readGradleMetadata(apk)).resolves.toBeNull();
  });
});

describe("publishRelease", () => {
  it("copies the APK and writes a valid manifest using Gradle metadata", async () => {
    const apk = await writeApk("app-release.apk", gradleMeta({ versionName: "1.0.0", versionCode: 4 }));
    const result = await publishRelease({
      apkPath: apk,
      publicDir,
      notes: ["First public release", "  "],
      now: new Date("2026-12-01T10:00:00Z"),
    });

    expect(result.release).toMatchObject({
      versionName: "1.0.0",
      versionCode: 4,
      file: "downloads/asha-vaani-v1.0.0.apk",
      sha256: sha(ZIP),
      notes: ["First public release"],
      releasedAt: "2026-12-01T10:00:00.000Z",
    });
    const written = JSON.parse(await readFile(path.join(publicDir, "release.json"), "utf8"));
    expect(validateManifest(written)).toEqual([]);
    expect(written.latest.versionCode).toBe(4);
    const copied = await readFile(path.join(publicDir, "downloads", "asha-vaani-v1.0.0.apk"));
    expect(sha(copied)).toBe(sha(ZIP));
  });

  it("blocks debug builds unless --allow-debug is passed", async () => {
    const apk = await writeApk("app-debug.apk", gradleMeta({ variantName: "debug", outputFile: "app-debug.apk" }));
    await expect(publishRelease({ apkPath: apk, publicDir })).rejects.toThrow(/DEBUG build/);
    const ok = await publishRelease({ apkPath: apk, publicDir, allowDebug: true });
    expect(ok.warnings.join(" ")).toMatch(/DEBUG/);
  });

  it("rejects an APK for a different application id", async () => {
    const meta = { ...gradleMeta(), applicationId: "com.other.app" };
    const apk = await writeApk("app-release.apk", meta);
    await expect(publishRelease({ apkPath: apk, publicDir })).rejects.toThrow(/does not match/);
  });

  it("requires explicit versions when metadata is missing", async () => {
    const apk = await writeApk();
    await expect(publishRelease({ apkPath: apk, publicDir })).rejects.toThrow(/Could not determine version/);
    const ok = await publishRelease({ apkPath: apk, publicDir, versionName: "1.0.1", versionCode: 2 });
    expect(ok.release.file).toBe("downloads/asha-vaani-v1.0.1.apk");
  });

  it("does not write anything on --dry-run", async () => {
    const apk = await writeApk("app-release.apk", gradleMeta());
    await publishRelease({ apkPath: apk, publicDir, dryRun: true });
    await expect(access(path.join(publicDir, "release.json"))).rejects.toThrow();
    await expect(access(path.join(publicDir, "downloads"))).rejects.toThrow();
  });

  it("refuses to overwrite a corrupt manifest", async () => {
    await writeFile(path.join(publicDir, "release.json"), "{ not json");
    const apk = await writeApk("app-release.apk", gradleMeta());
    await expect(publishRelease({ apkPath: apk, publicDir })).rejects.toThrow(/not valid JSON/);
  });
});
