# ASHA Vaani Web — Public Product & Android APK Distribution Site

> **Gnani Voice Hackathon · PS1 (Healthcare)**  
> Production web application and Android APK distribution portal for **ASHA Vaani** (`com.ashavaani.app`), located in [`/home/poornachandra-s/Desktop/poorna/web`](.) alongside [`../backend`](../backend) and [`../android`](../android).

---

## Key Capabilities

1. **Zero-Code "Coming Soon" → Live APK Download Transition**:
   - Driven by a runtime-validated release manifest ([`public/release.json`](public/release.json), `schemaVersion: 1`).
   - While `"latest": null`, the site displays a clear **"Coming Soon — In Final Build Verification"** status with the release readiness checklist and disabled direct download button.
   - Running `npm run release -- <path/to/app.apk>` validates the APK's ZIP signature and Gradle `output-metadata.json`, computes the file size and **SHA-256** checksum, copies the binary into `public/downloads/asha-vaani-v<version>.apk`, and atomically updates `public/release.json`.
   - The Download page immediately unlocks the direct `.apk` download button, 1-click SHA-256 & terminal verification command copy buttons, a locally generated SVG **QR code** for mobile scanning, release notes, and previous-version history.
2. **Interactive Walk-Mode Preview (All 3 PS1 Pillars)**:
   - Interactive 3-scenario preview on the Home page demonstrating:
     1. **Routine ANC Voice Capture** in Hindi + English code-mix (`Prisma v3.0` streaming STT).
     2. **Mandatory Read-Back & Sub-150 ms Barge-In Voice Correction** in Kannada (`3.2 kg → 2.8 kg` warm-amber `FieldDiff` highlight).
     3. **Deterministic Danger-Sign PHC Escalation** in Tamil (`DS_MAT_BLEEDING` crimson referral banner with `Timbre v3.0` read-back).
3. **Complete 12-Item NHM Danger-Sign & FR-6 Compliance Reference**:
   - Displays all 6 Maternal (ANC) and 6 Newborn (HBNC) danger signs from `docs/danger_signs.json` (v1.1) along with the strict zero-diagnosis / zero-medication guardrail policy.
4. **Multilingual 5-Language APK Installation Guide**:
   - Full step-by-step sideloading and troubleshooting instructions in **English (`en-IN`), हिन्दी (`hi-IN`), ಕನ್ನಡ (`kn-IN`), தமிழ் (`ta-IN`), and తెలుగు (`te-IN`)**, auto-selecting the user's browser locale with 1-tap language tabs.
5. **Device-Aware UX & Permissions Transparency (`/privacy`)**:
   - Detects Android, iOS/iPadOS, and desktop browsers to display tailored download guidance.
   - Documents all 5 `AndroidManifest.xml` permissions (`RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`, `VIBRATE`) and local-first Room v2 data handling.

---

## Quick Start

### Prerequisites
- Node.js `>= 20` (tested on Node `v24.20.0`, npm `11.19.0`)

### 1. Install & Run Development Server
```bash
cd /home/poornachandra-s/Desktop/poorna/web
npm install
npm run dev
```

### 2. Run Typecheck, Automated Tests (`30` tests), and Production Build
```bash
npm run check
```
Individual commands:
- `npm run typecheck` — TypeScript strict project references check (`tsc -b`)
- `npm test` — Vitest unit + component + release CLI test suite (`30` tests across 3 suites)
- `npm run build` — Production bundle into `dist/`
- `npm run preview` — Serve the production `dist/` bundle locally

---

## Publishing the Android APK When Ready

See [`docs/ANDROID_INTEGRATION.md`](docs/ANDROID_INTEGRATION.md) for the full contract.

```bash
# Publish a signed release APK from ../android:
npm run release -- ../android/app/build/outputs/apk/release/app-release.apk \
  --notes "Initial signed release"

# Or publish the debug APK from ../android for internal / hackathon testing:
npm run release -- ../android/app/build/outputs/apk/debug/app-debug.apk \
  --allow-debug \
  --force \
  --notes "Hackathon judge test build"

# Rebuild the static site:
npm run build
```

---

## Deployment

The build output in `dist/` is a self-contained static application with preconfigured headers for:
- **Netlify / Cloudflare Pages**: [`public/_headers`](public/_headers) sets `Cache-Control: no-cache, no-store, must-revalidate` on `/release.json` and `Content-Type: application/vnd.android.package-archive` on `/downloads/*.apk`.
- **Vercel**: [`vercel.json`](vercel.json) configures security headers, APK MIME type, `/release.json` no-cache headers, and SPA route rewrites.
- **Docker / Nginx / Cloud Run**: [`Dockerfile`](Dockerfile) and [`nginx.conf`](nginx.conf) build and serve the production bundle on port `8080`.
  ```bash
  docker build -t asha-vaani-web .
  docker run --rm -p 8080:8080 asha-vaani-web
  ```
