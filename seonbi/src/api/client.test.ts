import { describe, expect, it, vi } from 'vitest';
import contract from '../../tests/contracts/api-spec.json';
import backendOpenApi from '../../tests/contracts/backend-b.openapi.json';
import { endpoints } from './endpoints';
import type { EndpointName, RequestOptions } from './endpoints';
import { ApiError, createApiClient } from './http';

const clientId = '53f18ac0-7024-41d1-b9a3-0a463607ef88';
function setup(handler: (url: URL, init: RequestInit) => Promise<Response> = async () => Response.json({ ok: true })) {
  const send = vi.fn((url: string | URL | Request, init?: RequestInit) => handler(new URL(String(url)), init ?? {}));
  const client = createApiClient({ baseUrl: 'http://localhost:8000', clientId: () => clientId, fetch: send as typeof fetch });
  return { client, send };
}
describe('기획서 v0.5 API 계약 검증', () => {
  it('로그인 제외 전체 42개 행과 메서드·경로가 정확히 일치한다', () => {
    const expected = contract.map(r => `${r.method} ${r.path}`).sort();
    expect(Object.values(endpoints).map(e => `${e.method} ${e.path}`).sort()).toEqual(expected);
    expect(Object.values(endpoints).every(e => !e.path.includes('/auth/') && !e.path.includes('/users'))).toBe(true);
  });
  for (const [name, endpoint] of Object.entries(endpoints)) it(`${endpoint.method} ${endpoint.path} 요청을 보낸다`, async () => {
    const { client, send } = setup(async () => name === 'exportDecision' ? new Response('# 결과', { headers: { 'Content-Type': 'text/markdown', 'Content-Disposition': 'attachment; filename="result.md"' } }) : Response.json({ ok: true }));
    const params = Object.fromEntries([...endpoint.path.matchAll(/\{(\w+)\}/g)].map((m, i) => [m[1], `resource-${i}`]));
    const options = (Object.keys(params).length ? { params } : {}) as RequestOptions<EndpointName>;
    if (name === 'exportDecision') await client.exportDecision(options as RequestOptions<'exportDecision'>);
    else await client.request(name as Exclude<EndpointName, 'exportDecision'>, options as never);
    const [url, init] = send.mock.calls[0];
    expect(String(url)).toBe('http://localhost:8000' + endpoint.path.replace(/\{(\w+)\}/g, (_, key) => params[key]));
    expect(init?.method).toBe(endpoint.method);
    expect(new Headers(init?.headers).get('X-Client-Id')).toBe(clientId);
    expect(init?.credentials).toBe('omit');
  });
});
describe('서버 응답과 오류', () => {
  it('별도 API 주소가 없으면 같은 origin의 Vite 프록시 경로를 사용한다', async () => {
    const send = vi.fn(async (_url: unknown, _init?: RequestInit) => Response.json({ items: [], nextCursor: null }));
    const client = createApiClient({ baseUrl: '', clientId: () => clientId, fetch: send });
    await client.request('listDecisions', {});
    expect(String(send.mock.calls[0][0])).toBe('/api/v1/decisions');
  });
  it('JSON 본문과 ID를 전송하고 URL 경로 및 쿼리 값을 인코딩한다', async () => {
    const { client, send } = setup();
    await client.request('updateDecision', { params: { decisionId: 'a/b 한글' }, body: { title: '선택' }, query: { view: 'A&B', empty: undefined } });
    const [url, init] = send.mock.calls[0]; expect(String(url)).toContain('a%2Fb%20%ED%95%9C%EA%B8%80'); expect(String(url)).toContain('view=A%26B'); expect(String(url)).not.toContain('empty'); expect(init?.body).toBe('{"title":"선택"}');
  });
  it('서버의 상태 코드·오류 코드·상세 정보를 보존한다', async () => {
    const { client } = setup(async () => Response.json({ error: { code: 'DECISION_FINALIZED', message: '확정된 결정입니다.', details: { decisionId: 'd' } } }, { status: 409 }));
    await expect(client.request('deleteDecision', { params: { decisionId: 'd' } })).rejects.toMatchObject({ status: 409, code: 'DECISION_FINALIZED', details: { decisionId: 'd' } });
  });
  it('서버의 HTML 오류 응답도 성공으로 처리하지 않는다', async () => {
    const { client } = setup(async () => new Response('<h1>Error</h1>', { status: 502 }));
    await expect(client.request('listDecisions', {})).rejects.toBeInstanceOf(ApiError);
  });
  it('빈 204 응답을 JSON으로 파싱하지 않는다', async () => {
    const { client } = setup(async () => new Response(null, { status: 204 }));
    await expect(client.request('deleteOption', { params: { decisionId: 'd', optionId: 'o' } })).resolves.toBeUndefined();
  });
  it('파일 응답과 UTF-8 파일명을 유지한다', async () => {
    const { client } = setup(async () => new Response('# 결과', { headers: { 'Content-Disposition': "attachment; filename*=UTF-8''%EC%84%A0%EB%B9%84.md" } }));
    const file = await client.exportDecision({ params: { decisionId: 'd' } }); expect(file.filename).toBe('선비.md'); expect(await file.blob.text()).toBe('# 결과');
  });
  it('네트워크 장애와 잘못된 JSON 응답을 구분한다', async () => {
    const offline = setup(async () => { throw new TypeError('offline'); });
    await expect(offline.client.request('listDecisions', {})).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    const broken = setup(async () => new Response('<html>not json</html>'));
    await expect(broken.client.request('listDecisions', {})).rejects.toMatchObject({ code: 'INVALID_RESPONSE' });
  });
});

describe('최신 서버 미구현·미디어 오류', () => {
  it('PUT 값 저장에 JSON 본문과 Content-Type을 보낸다', async () => {
    const { client, send } = setup();
    await client.request('saveValues', { params: { decisionId: 'd' }, body: { items: [{optionId:'o', criterionId:'c', value:true}], rankings:[] } });
    expect(send.mock.calls[0][1]?.method).toBe('PUT');
    expect(new Headers(send.mock.calls[0][1]?.headers).get('content-type')).toBe('application/json');
  });
  it('501 스텁은 성공으로 처리하지 않는다', async () => {
    const {client}=setup(async()=>Response.json({error:{code:'NOT_IMPLEMENTED',message:'서비스 연결 전입니다.',details:{stub:true}}},{status:501}));
    await expect(client.request('finalizeDecision',{params:{decisionId:'d'},body:{optionId:'o'}})).rejects.toMatchObject({status:501,code:'NOT_IMPLEMENTED'});
  });
  it('200 응답이라도 명시된 stub:true는 거절한다', async()=>{
    const {client}=setup(async()=>Response.json({stub:true,status:'FINALIZED'}));
    await expect(client.request('finalizeDecision',{params:{decisionId:'d'}})).rejects.toMatchObject({code:'NOT_IMPLEMENTED'});
  });
  it('415 미디어 오류의 상세 정보를 보존한다', async()=>{
    const {client}=setup(async()=>Response.json({error:{code:'UNSUPPORTED_MEDIA_TYPE',message:'JSON 형식이 필요합니다.'}},{status:415}));
    await expect(client.request('recommendOptions',{params:{decisionId:'d'},body:{count:3}})).rejects.toMatchObject({status:415,code:'UNSUPPORTED_MEDIA_TYPE'});
  });
});

it('B의 실제 OpenAPI 구성 API 경로와 메서드를 모두 포함한다',()=>{
 const methods=new Set(['get','post','put','patch','delete']);
 const defined=new Set(Object.values(endpoints).map(e=>`${e.method} ${e.path}`));
 for(const [path,operations] of Object.entries(backendOpenApi.paths)) for(const method of Object.keys(operations)) if(methods.has(method)) expect(defined.has(`${method.toUpperCase()} ${path}`)).toBe(true);
});
