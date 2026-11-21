#!/usr/bin/env node
// Publish an Android APK to the ASHA Vaani website.
//
//   npm run release -- <path/to/app-release.apk> [options]
//
// Options:
//   --version-name <x.y.z>  Override versionName (default: read from Gradle output-metadata.json)
//   --version-code <n>      Override versionCode (default: read from Gradle output-metadata.json)
//   --notes "<text>"        Release note line (repeatable)
//   --public-dir <dir>      Website public directory (default: ./public)
//   --allow-debug           Allow publishing a debug-signed APK (internal testing only)
//   --force                 Replace a release with the same or lower versionCode
//   --dry-run               Validate and print the result without writing files

import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { publishRelease, ReleaseError } from "./lib/release-core.mjs";

const projectRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

function usage() {
  return "Usage: npm run release -- <path/to/app.apk> [--version-name 1.0.0] [--version-code 2] " +
    '[--notes "..."] [--allow-debug] [--force] [--dry-run]';
}

async function main() {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      "version-name": { type: "string" },
      "version-code": { type: "string" },
      notes: { type: "string", multiple: true },
      "public-dir": { type: "string" },
      "allow-debug": { type: "boolean", default: false },
      force: { type: "boolean", default: false },
      "dry-run": { type: "boolean", default: false },
      help: { type: "boolean", short: "h", default: false },
    },
  });

  if (values.help) {
    console.log(usage());
    return;
  }
  if (positionals.length !== 1) throw new ReleaseError(usage());

  let versionCode;
  if (values["version-code"] !== undefined) {
    versionCode = Number(values["version-code"]);
    if (!Number.isInteger(versionCode) || versionCode < 1) {
      throw new ReleaseError("--version-code must be a positive integer.");
    }
  }

  const result = await publishRelease({
    apkPath: positionals[0],
    publicDir: path.resolve(values["public-dir"] ?? path.join(projectRoot, "public")),
    versionName: values["version-name"],
    versionCode,
    notes: values.notes ?? [],
    allowDebug: values["allow-debug"],
    force: values.force,
    dryRun: values["dry-run"],
  });

  for (const w of result.warnings) console.warn(`warning: ${w}`);
  const { release } = result;
  console.log(`${values["dry-run"] ? "[dry-run] Would publish" : "Published"} ASHA Vaani v${release.versionName} (code ${release.versionCode})`);
  console.log(`  file:    ${result.destination}`);
  console.log(`  size:    ${(release.sizeBytes / 1024 / 1024).toFixed(1)} MB`);
  console.log(`  sha256:  ${release.sha256}`);
  if (!values["dry-run"]) console.log("Rebuild and redeploy the site (npm run build) to make it live.");
}

main().catch((err) => {
  if (err instanceof ReleaseError) {
    console.error(`error: ${err.message}`);
  } else {
    console.error(err);
  }
  process.exitCode = 1;
});
