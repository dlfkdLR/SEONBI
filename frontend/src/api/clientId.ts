const STORAGE_KEY = "seonbi.clientId";
let memoryClientId: string | undefined;

function createClientId(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  throw new Error("이 브라우저에서는 클라이언트 ID를 만들 수 없습니다.");
}

/** Returns the browser UUID used by the X-Client-Id API contract. */
export function getClientId(): string {
  if (memoryClientId) return memoryClientId;

  try {
    const stored = globalThis.localStorage?.getItem(STORAGE_KEY);
    if (stored) {
      memoryClientId = stored;
      return stored;
    }
  } catch {
    // Private browsing or browser policy can block localStorage.
  }

  memoryClientId = createClientId();
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, memoryClientId);
  } catch {
    // Keep the ID in memory for this page session when storage is unavailable.
  }
  return memoryClientId;
}
