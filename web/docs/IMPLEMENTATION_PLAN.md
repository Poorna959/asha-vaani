# ASHA Vaani Web — Design & Implementation Plan

## Intent
Public website where people learn what ASHA Vaani is and download the Android APK.
The Android app is still in development, so the site must work in a **"coming soon"** state
today and switch to a real download the moment a release is published — with no code change.

## Decisions (from the project owner)
| Question | Decision |
| :--- | :--- |
| Location | Located in `web/` inside `/home/poornachandra-s/Desktop/poorna` alongside `backend/` and `android/`. |
| Authentication | **Supabase Auth (Gmail / Google OAuth + Email & Password)** at `/login`. |
| Scope | Public product page + Supabase Login + APK download (`v0.3.0` live). |

## Grounding (read-only analysis of `poorna/`)
- Product: zero-typing voice field register for ASHA workers (PS1, `AGENTS.md`).
- Visit types: ANC, Newborn/HBNC, Immunisation. Languages: English, हिन्दी, ಕನ್ನಡ, தமிழ், తెలుగు (+ auto).
- Danger signs: canonical 12-item list `docs/danger_signs.json` v1.1 — "referral list only, no diagnosis or treatment advice".
- Android: `com.ashavaani.app`, `minSdk 26` (Android 8.0+), permissions `INTERNET`, `ACCESS_NETWORK_STATE`,
  `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `VIBRATE`. Gradle writes `output-metadata.json` (versionName/versionCode/variant) beside every APK.
- Design tokens: Deep Clinical Teal `#0F766E`, Slate ink `#0F172A`, Crimson `#DC2626`, Amber `#D97706`, Emerald `#059669`,
  crisp 1.5px `#CBD5E1` borders (UI/UX spec P2 §2).

## Approaches considered
1. **Static SPA + release manifest (chosen).** Vite + React + TypeScript. The APK and a `release.json` manifest are
   published by a CLI script; the site fetches the manifest at runtime. Deploys to any static host / CDN, no server to run or secure.
2. Small server (Node/FastAPI) serving releases + download counters. Rejected: no auth/admin in scope, so a server adds
   operational and security surface without a user-facing feature.
3. Page served from the existing FastAPI backend. Rejected: requires modifying the repository.

## Architecture
```
asha-vaani-web/
├─ public/release.json          ← release manifest (the contract; latest:null = coming soon)
├─ public/downloads/*.apk       ← published APKs (written by the release script)
├─ scripts/release.mjs          ← CLI: validate APK → sha256/size → copy → update manifest
├─ scripts/lib/release-core.mjs ← pure, unit-tested release logic
├─ src/lib/release.ts           ← runtime manifest validation + formatting
├─ src/hooks/useRelease.ts      ← fetch manifest (loading / ready / error + retry)
├─ src/pages/{Home,Download,Privacy,NotFound}
└─ deploy configs: vercel.json, public/_headers (Netlify/Cloudflare), nginx.conf, Dockerfile
```

## Routes
| Route | Content |
| :--- | :--- |
| `/` | Product story: how a visit works, read-back & voice correction, danger-sign escalation (12 signs), languages, field-ready design, custom registers, FAQ |
| `/download` | Release card (version, date, size, SHA-256 copy), device-aware CTA (Android → download, desktop → QR code, iPhone → not supported), multilingual install guide, checksum verification, release history |
| `/privacy` | Permissions & data handling, stated only from what the code actually does |
| `*` | 404 |

## Android integration
`release.json` is a stable, versioned (`schemaVersion: 1`) contract. The future Android app can poll it for
in-app update prompts (compare `latest.versionCode` with `BuildConfig.VERSION_CODE`). See `docs/ANDROID_INTEGRATION.md`.

## Phases
1. Foundation: Vite/React/TS, lint/type-check, test runner.
2. Design system: tokens, layout, header/footer, buttons, cards.
3. Release pipeline: manifest schema, validator, release CLI + tests.
4. Pages & workflows: Home, Download (all states), Privacy, 404, install guide i18n.
5. Tests: unit + component tests for every state; release CLI tests.
6. Verification: build, preview, browser screenshots (mobile/desktop), Lighthouse.
7. Deployment configs & docs.

## Non-goals
Auth, admin UI, server-side counters, PHC dashboard (already exists in the backend), any change to `poorna/`.
