export type Platform = "android" | "ios" | "desktop";

/** Best-effort platform detection used only to choose which download hint to show. */
export function detectPlatform(userAgent: string, maxTouchPoints = 0): Platform {
  const ua = userAgent.toLowerCase();
  if (ua.includes("android")) return "android";
  if (/iphone|ipad|ipod/.test(ua)) return "ios";
  // iPadOS 13+ reports itself as desktop Safari ("Macintosh") but has touch support.
  if (ua.includes("macintosh") && maxTouchPoints > 1) return "ios";
  return "desktop";
}

export function currentPlatform(): Platform {
  if (typeof navigator === "undefined") return "desktop";
  return detectPlatform(navigator.userAgent, navigator.maxTouchPoints ?? 0);
}
