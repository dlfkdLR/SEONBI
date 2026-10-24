import { getClientId } from "./clientId";

export const API_BASE_URL = "/api/v1";

export interface ApiErrorBody {
  error: { code: string; message: string; details?: unknown };
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: unknown;

  constructor(status: number, code: string, message: string, details?: unknown) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

/** Message for views that need to show a concise, user-facing API error. */
export function apiErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code === "NOT_IMPLEMENTED") {
    return "이 기능은 아직 준비 중이에요.";
  }
  if (error instanceof ApiError) return error.message;
  return "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
}

export interface DownloadedFile {
  blob: Blob;
  filename: string;
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== "object" || value === null || !("error" in value)) return false;
  const error = value.error;
  return typeof error === "object" && error !== null &&
    "code" in error && typeof error.code === "string" &&
    "message" in error && typeof error.message === "string";
}

async function parseError(response: Response): Promise<ApiError> {
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    // Proxies and unavailable servers can return HTML or an empty body.
  }
  if (isApiErrorBody(body)) {
    return new ApiError(response.status, body.error.code, body.error.message, body.error.details);
  }
  return new ApiError(response.status, "HTTP_ERROR", `요청에 실패했습니다. (${response.status})`);
}

async function send(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  headers.set("X-Client-Id", getClientId());
  if (init.body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  const response = await fetch(`${API_BASE_URL}${path}`, { ...init, headers });
  if (!response.ok) throw await parseError(response);
  return response;
}

export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  return (await send(path, init)).json() as Promise<T>;
}

export async function requestBlob(path: string, init: RequestInit = {}): Promise<Blob> {
  return (await send(path, init)).blob();
}

export async function requestFile(path: string, fallbackFilename: string): Promise<DownloadedFile> {
  const response = await send(path);
  const disposition = response.headers.get("Content-Disposition") ?? "";
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plain = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  const filename = encoded
    ? decodeURIComponent(encoded)
    : plain ?? fallbackFilename;
  return { blob: await response.blob(), filename };
}

export function decisionPath(decisionId: string): string {
  return `/decisions/${encodeURIComponent(decisionId)}`;
}


/** Sends an API request whose successful response has no body (for example HTTP 204). */
export async function requestVoid(path: string, init: RequestInit = {}): Promise<void> {
  await send(path, init);
}
