import { getClientId } from './clientId';
import { endpoints } from './endpoints';
import type { EndpointName, RequestOptions } from './endpoints';

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) { super(message); this.name = 'ApiError'; }
}
export interface ExportFile { blob: Blob; filename: string }
interface ClientOptions { baseUrl: string; clientId: () => string; fetch?: typeof fetch }

export function createApiClient(options: ClientOptions) {
  const send = options.fetch ?? ((...args: Parameters<typeof fetch>) => globalThis.fetch(...args));
  async function response<K extends EndpointName>(name: K, request: RequestOptions<K>): Promise<Response> {

    const endpoint = endpoints[name];
    const params = ('params' in request ? request.params : undefined) as Record<string, string> | undefined;
    const path = endpoint.path.replace(/\{(\w+)\}/g, (_, key: string) => {
      const value = params?.[key];
      if (!value) throw new ApiError(0, 'MISSING_PATH_PARAMETER', `${key}가 필요해요.`);
      return encodeURIComponent(value);
    });
    const url = new URL(options.baseUrl.replace(/\/$/, '') + path, globalThis.location?.origin ?? 'http://localhost');
    for (const [key, value] of Object.entries(request.query ?? {})) if (value !== undefined) url.searchParams.set(key, String(value));
    if (request.body !== undefined && !['POST', 'PATCH', 'PUT'].includes(endpoint.method)) throw new ApiError(0, 'UNEXPECTED_BODY', '이 API에는 요청 본문을 보낼 수 없어요.');
    const headers = new Headers({ 'X-Client-Id': options.clientId() });
    if (request.body !== undefined) headers.set('Content-Type', 'application/json');
    headers.set('Accept', name === 'exportDecision' ? '*/*' : 'application/json');
    let res: Response;
    try { res = await send(options.baseUrl.trim() ? url : url.pathname + url.search, { method: endpoint.method, headers, signal: request.signal, body: request.body === undefined ? undefined : JSON.stringify(request.body), credentials: 'omit' }); }
    catch (e) { if (e instanceof Error && e.name === 'AbortError') throw e; throw new ApiError(0, 'NETWORK_ERROR', 'API 서버에 연결하지 못했어요. 서버 주소와 실행 상태를 확인해주세요.'); }
    if (!res.ok) {
      const body: unknown = await res.json().catch(() => null);
      const root = body && typeof body === 'object' ? body as Record<string, unknown> : {};
      const error = root.error && typeof root.error === 'object' ? root.error as Record<string, unknown> : root;
      throw new ApiError(res.status, typeof error.code === 'string' ? error.code : 'HTTP_ERROR', typeof error.message === 'string' ? error.message : `요청을 처리하지 못했어요. (${res.status})`, error.details);
    }
    return res;
  }
  return {
    /** Return unknown until the server's OpenAPI response schema is confirmed.
     * Never substitutes local successes for a failed server request.
     */
    async request<K extends Exclude<EndpointName, 'exportDecision'>>(name: K, request: RequestOptions<K>): Promise<unknown> {
      const res = await response(name, request);
      if (res.status === 204) return undefined;
      const text = await res.text();
      if (!text.trim()) return undefined;
      try { const result: unknown = JSON.parse(text); if (result && typeof result === 'object' && 'stub' in result && result.stub === true) throw new ApiError(501, 'NOT_IMPLEMENTED', '아직 연결되지 않은 서버 기능이에요.'); return result; }
      catch (e) { if (e instanceof ApiError) throw e; throw new ApiError(res.status, 'INVALID_RESPONSE', '서버가 올바른 JSON 응답을 보내지 않았어요.'); }
    },
    async exportDecision(request: RequestOptions<'exportDecision'>): Promise<ExportFile> {
      const res = await response('exportDecision', request), disposition = res.headers.get('content-disposition') ?? '';
      const extended = disposition.match(/filename\*=UTF-8''([^;]+)/i), plain = disposition.match(/filename="([^"]+)"|filename=([^;]+)/i);
      let filename = `decision-${request.params.decisionId}.md`;
      try { filename = extended ? decodeURIComponent(extended[1]) : plain ? (plain[1] ?? plain[2]).trim() : filename; } catch { /* Keep fallback for malformed filename encoding. */ }
      filename = filename.replace(/[\\/\x00-\x1f]/g, '_');
      return { blob: await res.blob(), filename };
    },
  };
}

/** One transport for the screen API and C's free-text/result helpers. */
export const api = createApiClient({ baseUrl: import.meta.env.VITE_API_BASE_URL ?? '', clientId: getClientId });
export function decisionPath(decisionId: string): string { return `/decisions/${encodeURIComponent(decisionId)}`; }
export async function requestJson<T>(path: string, init: RequestInit = {}): Promise<T> {
  const url = new URL(path, 'http://contract');
  const full = '/api/v1' + url.pathname;
  const method = init.method ?? 'GET';
  for (const [name, endpoint] of Object.entries(endpoints)) {
    if (endpoint.method !== method || name === 'exportDecision') continue;
    const keys = [...endpoint.path.matchAll(/\{(\w+)\}/g)].map(x => x[1]);
    const pattern = new RegExp('^' + endpoint.path.replace(/\{\w+\}/g, '([^/]+)') + '$');
    const match = full.match(pattern);
    if (!match) continue;
    const params = Object.fromEntries(keys.map((key, i) => [key, decodeURIComponent(match[i + 1])]));
    return await api.request(name as Exclude<EndpointName, 'exportDecision'>, {
      params, query: Object.fromEntries(url.searchParams), signal: init.signal ?? undefined,
      ...(init.body === undefined ? {} : { body: JSON.parse(String(init.body)) }),
    } as never) as T;
  }
  throw new ApiError(0, 'UNKNOWN_ENDPOINT', '정의되지 않은 API 요청이에요.');
}
export async function requestFile(path: string, fallbackFilename: string): Promise<ExportFile> {
  const match = path.match(/^\/decisions\/([^/]+)\/export(?:\?|$)/);
  if (!match) throw new ApiError(0, 'UNKNOWN_ENDPOINT', '올바르지 않은 내보내기 주소예요.');
  const result = await api.exportDecision({ params: { decisionId: decodeURIComponent(match[1]) }, query: { format: 'md' } });
  return { ...result, filename: result.filename || fallbackFilename };
}
export type DownloadedFile = ExportFile;

/** C helper compatibility, using the same registered transport. */
export const API_BASE_URL = '/api/v1';
export async function requestVoid(path: string, init: RequestInit = {}): Promise<void> { await requestJson(path, init); }
export async function requestBlob(path: string): Promise<Blob> { return (await requestFile(path, 'decision.md')).blob; }
export function apiErrorMessage(error: unknown): string { return error instanceof Error ? error.message : '요청을 처리하지 못했어요.'; }
