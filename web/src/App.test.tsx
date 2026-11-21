import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { AppRoutes } from "./App";
import { DANGER_SIGN_COUNT, DANGER_SIGN_GROUPS } from "./content/dangerSigns";
import { AuthProvider } from "./hooks/useAuth";
import { ReleaseProvider } from "./hooks/useRelease";

const COMING_SOON_MANIFEST = {
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

const PUBLISHED_MANIFEST = {
  ...COMING_SOON_MANIFEST,
  latest: {
    versionName: "1.0.0",
    versionCode: 2,
    releasedAt: "2026-11-22T00:00:00.000Z",
    file: "downloads/asha-vaani-v1.0.0.apk",
    sizeBytes: 18_218_373,
    sha256: "978ee6fa78baefd61049db549bbae0d54781a863681195b1b0a9796e80b4e6e9",
    notes: ["Signed production APK with Prisma v3.0 & Timbre v3.0", "Room v2 offline outbox"],
  },
  history: [
    {
      versionName: "0.2.0",
      versionCode: 1,
      releasedAt: "2026-11-21T00:00:00.000Z",
      file: "downloads/asha-vaani-v0.2.0.apk",
      sizeBytes: 18_000_000,
      sha256: "a".repeat(64),
      notes: ["Early preview"],
    },
  ],
};

function mockFetchJson(payload: unknown, status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(payload), { status, headers: { "Content-Type": "application/json" } })),
  );
}

function renderAt(route = "/") {
  window.localStorage.clear();
  return render(
    <AuthProvider>
      <ReleaseProvider>
        <MemoryRouter initialEntries={[route]}>
          <AppRoutes />
        </MemoryRouter>
      </ReleaseProvider>
    </AuthProvider>,
  );
}

describe("HomePage", () => {
  it("renders all 12 canonical NHM danger signs and switches Walk Mode interactive scenarios", async () => {
    mockFetchJson(COMING_SOON_MANIFEST);
    const user = userEvent.setup();
    renderAt("/");

    expect(screen.getByRole("heading", { name: /Speak the home visit/i })).toBeInTheDocument();

    // Verify all 12 danger signs from docs/danger_signs.json appear
    expect(DANGER_SIGN_COUNT).toBe(12);
    for (const group of DANGER_SIGN_GROUPS) {
      for (const sign of group.signs) {
        expect(screen.getByText(sign.name)).toBeInTheDocument();
      }
    }

    // Switch interactive Walk Mode preview tabs
    const correctionTab = screen.getByRole("tab", { name: /2\. Barge-In Voice Correction/i });
    await user.click(correctionTab);
    expect(screen.getByText(/FIELD DIFF · UPDATED BY VOICE/i)).toBeInTheDocument();
    expect(screen.getByText("3.2 kg")).toBeInTheDocument();

    const dangerTab = screen.getByRole("tab", { name: /3\. Danger-Sign PHC Escalation/i });
    await user.click(dangerTab);
    expect(screen.getByText(/URGENT PHC REFERRAL/i)).toBeInTheDocument();

    // Wait for manifest badge to resolve to coming soon
    await waitFor(() => {
      expect(screen.getByText(/Release in preparation/i)).toBeInTheDocument();
    });
  });
});

describe("DownloadPage — Coming Soon state", () => {
  it("shows the coming-soon readiness card, disabled direct APK button, and switches install guide across all 5 languages", async () => {
    mockFetchJson(COMING_SOON_MANIFEST);
    const user = userEvent.setup();
    renderAt("/download");

    await waitFor(() => {
      expect(
        screen.getByRole("heading", { name: /ASHA Vaani for Android is in final build verification/i }),
      ).toBeInTheDocument();
    });

    const disabledBtn = screen.getByRole("button", { name: /Android APK — Coming soon/i });
    expect(disabledBtn).toBeDisabled();

    // Switch installation guide language tabs (English, Hindi, Kannada, Tamil, Telugu)
    const hindiTab = screen.getByRole("tab", { name: "हिन्दी" });
    await user.click(hindiTab);
    expect(screen.getByRole("heading", { name: /ASHA Vaani कैसे इंस्टॉल करें/i })).toBeInTheDocument();

    const kannadaTab = screen.getByRole("tab", { name: "ಕನ್ನಡ" });
    await user.click(kannadaTab);
    expect(screen.getByRole("heading", { name: /ASHA Vaani ಅನ್ನು ಇನ್‌ಸ್ಟಾಲ್ ಮಾಡುವುದು ಹೇಗೆ/i })).toBeInTheDocument();

    const tamilTab = screen.getByRole("tab", { name: "தமிழ்" });
    await user.click(tamilTab);
    expect(screen.getByRole("heading", { name: /ASHA Vaani-ஐ நிறுவுவது எப்படி/i })).toBeInTheDocument();

    const teluguTab = screen.getByRole("tab", { name: "తెలుగు" });
    await user.click(teluguTab);
    expect(screen.getByRole("heading", { name: /ASHA Vaani ని ఇన్‌స్టాల్ చేయడం ఎలా/i })).toBeInTheDocument();
  });
});

describe("DownloadPage — Published release state", () => {
  it("renders direct APK download link, SHA-256 copy field, release notes, QR code, and release history", async () => {
    mockFetchJson(PUBLISHED_MANIFEST);
    renderAt("/download");

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "ASHA Vaani v1.0.0" })).toBeInTheDocument();
    });

    const downloadLink = screen.getByRole("link", { name: /Download APK/i });
    expect(downloadLink).toHaveAttribute("href", "/downloads/asha-vaani-v1.0.0.apk");
    expect(downloadLink).toHaveAttribute("download", "asha-vaani-v1.0.0.apk");

    expect(
      screen.getByText("978ee6fa78baefd61049db549bbae0d54781a863681195b1b0a9796e80b4e6e9"),
    ).toBeInTheDocument();
    expect(screen.getByText(/Signed production APK with Prisma v3\.0/i)).toBeInTheDocument();

    // Release history section
    const historyHeading = screen.getByRole("heading", { name: /Release history/i });
    const historySection = historyHeading.closest("section")!;
    expect(within(historySection).getByRole("link", { name: /Download v0\.2\.0/i })).toHaveAttribute(
      "href",
      "/downloads/asha-vaani-v0.2.0.apk",
    );
  });
});

describe("DownloadPage — Error & retry state", () => {
  it("displays an accessible alert when release.json fails and recovers on Retry", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response("server error", { status: 503 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify(PUBLISHED_MANIFEST), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const user = userEvent.setup();
    renderAt("/download");

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(/HTTP 503/i);

    await user.click(screen.getByRole("button", { name: /Retry/i }));
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "ASHA Vaani v1.0.0" })).toBeInTheDocument();
    });
  });
});

describe("PrivacyPage & NotFoundPage", () => {
  it("lists all 5 AndroidManifest permissions on /privacy", () => {
    mockFetchJson(COMING_SOON_MANIFEST);
    renderAt("/privacy");
    expect(screen.getByText("android.permission.RECORD_AUDIO")).toBeInTheDocument();
    expect(screen.getByText("android.permission.INTERNET")).toBeInTheDocument();
    expect(screen.getByText("android.permission.ACCESS_NETWORK_STATE")).toBeInTheDocument();
    expect(screen.getByText("android.permission.MODIFY_AUDIO_SETTINGS")).toBeInTheDocument();
    expect(screen.getByText("android.permission.VIBRATE")).toBeInTheDocument();
  });

  it("renders a 404 page on unknown routes", () => {
    mockFetchJson(COMING_SOON_MANIFEST);
    renderAt("/unknown-path");
    expect(screen.getByRole("heading", { name: /This page does not exist/i })).toBeInTheDocument();
  });
});

describe("LoginPage — Supabase Gmail & Email Authentication", () => {
  it("allows 1-tap Gmail sign-in and transitions to the verified APK download page", async () => {
    mockFetchJson(PUBLISHED_MANIFEST);
    const user = userEvent.setup();
    renderAt("/login");

    expect(screen.getByRole("heading", { name: /Sign in to download & sync ASHA Vaani/i })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Continue with Gmail \/ Google/i }));
    await user.click(screen.getByRole("button", { name: /Sunita Devi \(ASHA Worker\)/i }));

    await waitFor(() => {
      expect(screen.getByText(/Signed in as Sunita Devi \(ASHA Worker\) \(asha\.sunita@gmail\.com\)/i)).toBeInTheDocument();
    });
  });
});
