import { getClientId } from '../data/client';
import { endpoints } from './endpoints';
import type { EndpointName, RequestOptions } from './endpoints';

export class ApiError extends Error {
  constructor(message: string, public status: number, public code?: string, public details?: unknown) { super(message); this.name = 'ApiError'; }
}
export interface ExportFile { blob: Blob; filename: string }
interface ClientOptions { baseUrl: string; clientId: () => string; fetch?: typeof fetch }

export function createApiClient(options: ClientOptions) {
  const send = options.fetch ?? globalThis.fetch;
  async function response<K extends EndpointName>(name: K, request: RequestOptions<K>): Promise<Response> {
    if (!options.baseUrl.trim()) throw new ApiError('API 서버 주소가 설정되지 않았어요.', 0, 'API_NOT_CONFIGURED');
    const endpoint = endpoints[name];
    const params = ('params' in request ? request.params : undefined) as Record<string, string> | undefined;
    const path = endpoint.path.replace(/\{(\w+)\}/g, (_, key: string) => {
      const value = params?.[key];
      if (!value) throw new ApiError(`${key}가 필요해요.`, 0, 'MISSING_PATH_PARAMETER');
      return encodeURIComponent(value);
    });
    const url = new URL(options.baseUrl.replace(/\/$/, '') + path);
    for (const [key, value] of Object.entries(request.query ?? {})) if (value !== undefined) url.searchParams.set(key, String(value));
    if (request.body !== undefined && endpoint.method !== 'POST' && endpoint.method !== 'PATCH' && endpoint.method !== 'PUT') throw new ApiError('이 API에는 요청 본문을 보낼 수 없어요.', 0, 'UNEXPECTED_BODY');
    const headers = new Headers({ 'X-Client-Id': options.clientId() });
    if (request.body !== undefined) headers.set('Content-Type', 'application/json');
    headers.set('Accept', name === 'exportDecision' ? '*/*' : 'application/json');
    let res: Response;
    try { res = await send(url, { method: endpoint.method, headers, signal: request.signal, body: request.body === undefined ? undefined : JSON.stringify(request.body), credentials: 'omit' }); }
    catch (e) { if (e instanceof Error && e.name === 'AbortError') throw e; throw new ApiError('API 서버에 연결하지 못했어요. 서버 주소와 실행 상태를 확인해주세요.', 0, 'NETWORK_ERROR'); }
    if (!res.ok) {
      const body: unknown = await res.json().catch(() => null);
      const root = body && typeof body === 'object' ? body as Record<string, unknown> : {};
      const error = root.error && typeof root.error === 'object' ? root.error as Record<string, unknown> : root;
      throw new ApiError(typeof error.message === 'string' ? error.message : `요청을 처리하지 못했어요. (${res.status})`, res.status, typeof error.code === 'string' ? error.code : undefined, error.details);
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
      try { return JSON.parse(text) as unknown; }
      catch { throw new ApiError('서버가 올바른 JSON 응답을 보내지 않았어요.', res.status, 'INVALID_RESPONSE'); }
    },
    async exportDecision(request: RequestOptions<'exportDecision'>): Promise<ExportFile> {
      const res = await response('exportDecision', request), disposition = res.headers.get('content-disposition') ?? '';
      const extended = disposition.match(/filename\*=UTF-8''([^;]+)/i), plain = disposition.match(/filename="([^"]+)"|filename=([^;]+)/i);
      let filename = `decision-${request.params.decisionId}`;
      try { filename = extended ? decodeURIComponent(extended[1]) : plain ? (plain[1] ?? plain[2]).trim() : filename; } catch { /* Keep fallback for malformed filename encoding. */ }
      filename = filename.replace(/[\\/\x00-\x1f]/g, '_');
      return { blob: await res.blob(), filename };
    },
  };
}

/** Transport ready for integration. Current UI still uses the local repository. */
export const api = createApiClient({ baseUrl: import.meta.env.VITE_API_BASE_URL ?? '', clientId: getClientId });
