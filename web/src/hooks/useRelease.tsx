import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ManifestError, parseManifest, type ReleaseManifest } from "../lib/release";

export type ReleaseState =
  | { status: "loading" }
  | { status: "ready"; manifest: ReleaseManifest }
  | { status: "error"; message: string };

interface ReleaseContextValue {
  state: ReleaseState;
  retry: () => void;
}

const ReleaseContext = createContext<ReleaseContextValue | null>(null);

export const MANIFEST_URL = `${import.meta.env.BASE_URL}release.json`;

export async function fetchManifest(signal?: AbortSignal, url: string = MANIFEST_URL): Promise<ReleaseManifest> {
  let response: Response;
  try {
    // no-store: a newly published APK must show up without waiting for a cache to expire.
    response = await fetch(url, { cache: "no-store", signal, headers: { Accept: "application/json" } });
  } catch (err) {
    if (err instanceof DOMException && err.name === "AbortError") throw err;
    throw new Error("Could not reach the server. Check your internet connection and try again.");
  }
  if (!response.ok) {
    throw new Error(`Release information is unavailable right now (HTTP ${response.status}).`);
  }
  let json: unknown;
  try {
    json = await response.json();
  } catch {
    throw new Error("Release information is damaged. Please try again later.");
  }
  try {
    return parseManifest(json);
  } catch (err) {
    if (err instanceof ManifestError) {
      console.error("[release.json]", err.message);
      throw new Error("Release information is damaged. Please try again later.");
    }
    throw err;
  }
}

export function ReleaseProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ReleaseState>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    controllerRef.current = controller;
    setState({ status: "loading" });
    fetchManifest(controller.signal)
      .then((manifest) => setState({ status: "ready", manifest }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setState({ status: "error", message: err instanceof Error ? err.message : "Something went wrong." });
      });
    return () => controller.abort();
  }, [attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  const value = useMemo(() => ({ state, retry }), [state, retry]);
  return <ReleaseContext.Provider value={value}>{children}</ReleaseContext.Provider>;
}

export function useRelease(): ReleaseContextValue {
  const ctx = useContext(ReleaseContext);
  if (!ctx) throw new Error("useRelease must be used inside <ReleaseProvider>.");
  return ctx;
}
