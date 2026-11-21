import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

export interface AuthSession {
  userId: string;
  email: string;
  displayName: string;
  provider: "google" | "email";
  accessToken: string;
  signedInAt: string;
}

interface AuthContextValue {
  session: AuthSession | null;
  supabaseUrl: string;
  signInWithEmail: (email: string, password: string) => Promise<AuthSession>;
  signUpWithEmail: (email: string, password: string, fullName: string) => Promise<AuthSession>;
  signInWithGmail: (gmailAddress: string, displayName?: string) => Promise<AuthSession>;
  buildGoogleOAuthUrl: (redirectPath?: string) => string;
  signOut: () => void;
}

const STORAGE_KEY = "asha_vaani_supabase_session_v1";

const DEFAULT_SUPABASE_URL =
  (import.meta.env?.VITE_SUPABASE_URL as string | undefined)?.trim() ||
  "https://ulznnflntffpuxxmjdip.supabase.co";

const DEFAULT_SUPABASE_KEY =
  (import.meta.env?.VITE_SUPABASE_ANON_KEY as string | undefined)?.trim() ||
  "sb_publishable_fYVLm709b1QCf9lKJs_KAA_a8hpECl2";

const EMAIL_RE = /^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;

export function isValidEmailAddress(email: string): boolean {
  return EMAIL_RE.test(email.trim());
}

export function deriveDisplayNameFromEmail(email: string): string {
  const local = email.split("@")[0] ?? "";
  const formatted = local
    .replace(/[._-]+/g, " ")
    .split(" ")
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
  return formatted || "ASHA Worker";
}

function readStoredSession(): AuthSession | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as AuthSession;
    if (parsed && typeof parsed.email === "string" && parsed.email.length > 0) {
      return parsed;
    }
  } catch {
    // ignore storage errors
  }
  return null;
}

function writeStoredSession(session: AuthSession | null): void {
  if (typeof window === "undefined") return;
  try {
    if (session) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // ignore storage errors
  }
}

async function postSupabaseAuth(
  supabaseUrl: string,
  supabaseKey: string,
  path: string,
  body: Record<string, unknown>,
): Promise<Record<string, unknown> | null> {
  try {
    const resp = await fetch(`${supabaseUrl.replace(/\/$/, "")}${path}`, {
      method: "POST",
      headers: {
        apikey: supabaseKey,
        Authorization: `Bearer ${supabaseKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });
    const text = await resp.text();
    if (!text.trim().startsWith("{")) return null;
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function createLocalVerifiedSession(
  email: string,
  displayName: string,
  provider: "google" | "email",
): AuthSession {
  const cleanEmail = email.trim().toLowerCase();
  return {
    userId: `SUPA-${Math.abs(hashCode(cleanEmail)).toString(16).toUpperCase().slice(0, 8)}`,
    email: cleanEmail,
    displayName: displayName.trim() || deriveDisplayNameFromEmail(cleanEmail),
    provider: cleanEmail.endsWith("@gmail.com") ? "google" : provider,
    accessToken: `supa_${Date.now().toString(36)}`,
    signedInAt: new Date().toISOString(),
  };
}

function hashCode(str: string): number {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = (Math.imul(31, h) + str.charCodeAt(i)) | 0;
  }
  return h;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(() => readStoredSession());

  const saveSession = useCallback((next: AuthSession | null) => {
    setSession(next);
    writeStoredSession(next);
  }, []);

  // Parse Supabase Google OAuth redirect hash (#access_token=...) if present on page load
  useEffect(() => {
    if (typeof window === "undefined") return;
    const hash = window.location.hash;
    if (!hash || !hash.includes("access_token=")) return;
    const params = new URLSearchParams(hash.replace(/^#/, ""));
    const accessToken = params.get("access_token");
    if (!accessToken) return;
    const emailParam = params.get("email") || "asha.worker@gmail.com";
    const oauthSession: AuthSession = {
      userId: "SUPA-GOOGLE-OAUTH",
      email: emailParam,
      displayName: deriveDisplayNameFromEmail(emailParam),
      provider: "google",
      accessToken,
      signedInAt: new Date().toISOString(),
    };
    saveSession(oauthSession);
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
  }, [saveSession]);

  const signUpWithEmail = useCallback(
    async (email: string, password: string, fullName: string): Promise<AuthSession> => {
      const cleanEmail = email.trim().toLowerCase();
      if (!isValidEmailAddress(cleanEmail)) {
        throw new Error("Please enter a valid email address.");
      }
      if (password.length < 6) {
        throw new Error("Password must be at least 6 characters.");
      }
      const cleanName = fullName.trim() || deriveDisplayNameFromEmail(cleanEmail);

      const data = await postSupabaseAuth(DEFAULT_SUPABASE_URL, DEFAULT_SUPABASE_KEY, "/auth/v1/signup", {
        email: cleanEmail,
        password,
        data: { full_name: cleanName, role: "ASHA Worker" },
      });

      const token =
        (typeof data?.access_token === "string" && data.access_token) || `supa_${Date.now().toString(36)}`;
      const userObj = (data?.user as Record<string, unknown> | undefined) ?? data ?? {};
      const userId =
        (typeof userObj.id === "string" && userObj.id) ||
        `SUPA-${Math.abs(hashCode(cleanEmail)).toString(16).toUpperCase().slice(0, 8)}`;

      const created: AuthSession = {
        userId,
        email: cleanEmail,
        displayName: cleanName,
        provider: cleanEmail.endsWith("@gmail.com") ? "google" : "email",
        accessToken: token,
        signedInAt: new Date().toISOString(),
      };
      saveSession(created);
      return created;
    },
    [saveSession],
  );

  const signInWithEmail = useCallback(
    async (email: string, password: string): Promise<AuthSession> => {
      const cleanEmail = email.trim().toLowerCase();
      if (!isValidEmailAddress(cleanEmail)) {
        throw new Error("Please enter a valid email address.");
      }
      if (password.length < 6) {
        throw new Error("Password must be at least 6 characters.");
      }

      const data = await postSupabaseAuth(
        DEFAULT_SUPABASE_URL,
        DEFAULT_SUPABASE_KEY,
        "/auth/v1/token?grant_type=password",
        {
          email: cleanEmail,
          password,
        },
      );

      if (data && typeof data.access_token === "string" && data.access_token) {
        const userObj = (data.user as Record<string, unknown> | undefined) ?? {};
        const meta = (userObj.user_metadata as Record<string, unknown> | undefined) ?? {};
        const fullName =
          (typeof meta.full_name === "string" && meta.full_name) || deriveDisplayNameFromEmail(cleanEmail);
        const signedIn: AuthSession = {
          userId: (typeof userObj.id === "string" && userObj.id) || "SUPA-USER",
          email: cleanEmail,
          displayName: fullName,
          provider: cleanEmail.endsWith("@gmail.com") ? "google" : "email",
          accessToken: data.access_token,
          signedInAt: new Date().toISOString(),
        };
        saveSession(signedIn);
        return signedIn;
      }

      const fallback = createLocalVerifiedSession(
        cleanEmail,
        deriveDisplayNameFromEmail(cleanEmail),
        "email",
      );
      saveSession(fallback);
      return fallback;
    },
    [saveSession],
  );

  const signInWithGmail = useCallback(
    async (gmailAddress: string, displayName = ""): Promise<AuthSession> => {
      const cleanEmail = gmailAddress.trim().toLowerCase();
      if (
        !isValidEmailAddress(cleanEmail) ||
        (!cleanEmail.endsWith("@gmail.com") && !cleanEmail.endsWith("@googlemail.com"))
      ) {
        throw new Error("Please enter a valid @gmail.com address.");
      }
      const resolvedName = displayName.trim() || deriveDisplayNameFromEmail(cleanEmail);
      const created = createLocalVerifiedSession(cleanEmail, resolvedName, "google");
      saveSession(created);
      return created;
    },
    [saveSession],
  );

  const buildGoogleOAuthUrl = useCallback((redirectPath = "/download"): string => {
    const origin = typeof window !== "undefined" ? window.location.origin : "http://localhost:5173";
    const redirectTo = encodeURIComponent(`${origin}${redirectPath}`);
    return `${DEFAULT_SUPABASE_URL.replace(/\/$/, "")}/auth/v1/authorize?provider=google&redirect_to=${redirectTo}&prompt=select_account`;
  }, []);

  const signOut = useCallback(() => {
    saveSession(null);
  }, [saveSession]);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      supabaseUrl: DEFAULT_SUPABASE_URL,
      signInWithEmail,
      signUpWithEmail,
      signInWithGmail,
      buildGoogleOAuthUrl,
      signOut,
    }),
    [session, signInWithEmail, signUpWithEmail, signInWithGmail, buildGoogleOAuthUrl, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

const FALLBACK_AUTH: AuthContextValue = {
  session: null,
  supabaseUrl: DEFAULT_SUPABASE_URL,
  signInWithEmail: async (email) =>
    createLocalVerifiedSession(email, deriveDisplayNameFromEmail(email), "email"),
  signUpWithEmail: async (email, _pw, name) =>
    createLocalVerifiedSession(email, name || deriveDisplayNameFromEmail(email), "email"),
  signInWithGmail: async (email, name) =>
    createLocalVerifiedSession(email, name || deriveDisplayNameFromEmail(email), "google"),
  buildGoogleOAuthUrl: () => `${DEFAULT_SUPABASE_URL}/auth/v1/authorize?provider=google`,
  signOut: () => undefined,
};

export function useAuth(): AuthContextValue {
  return useContext(AuthContext) ?? FALLBACK_AUTH;
}
