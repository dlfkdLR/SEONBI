/**
 * Vercel 함수: 브라우저의 /api/* 요청을 백엔드(Spring Boot) 서버로 그대로 넘긴다.
 *
 * 백엔드는 Vercel에서 돌릴 수 없어 따로 띄우고, 그 주소를 환경변수 BACKEND_URL에 넣는다(예: https://xxxx.example.com, 끝에
 * 슬래시 없이). 같은 origin의 /api로 요청하게 해 CORS 설정이 필요 없게 한다. 개발 서버(vite)의 /api 프록시와 같은 역할이다.
 *
 * 경로 전달: vercel.json의 rewrite가 /api/v1/decisions/... 를 /api/proxy?__p=v1/decisions/... 로 바꿔 이 함수 하나로 모은다
 * ([...path] 파일 이름 방식은 여러 단계 경로를 404로 떨어뜨렸다). 원래 쿼리(?limit=20 등)는 __p 외의 값으로 그대로 남는다.
 *
 * - X-Client-Id 등 요청 헤더와 본문, 응답 상태·본문·파일 이름(Content-Disposition)을 그대로 전달한다.
 * - BACKEND_URL이 없으면 7.1 오류 형식으로 503, 백엔드에 닿지 못하면 502를 돌려준다. 오류는 서버 로그(vercel logs)에도 남긴다.
 */
const HOP_BY_HOP = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  "host",
  "content-length",
  "content-encoding", // fetch가 압축을 풀어 주므로 그대로 전달하면 본문이 깨진다
]);

function error(status: number, code: string, message: string, details: Record<string, unknown> = {}): Response {
  console.error(`[선비 프록시] ${status} ${code}: ${message}`, details);
  return Response.json({ error: { code, message, details } }, { status });
}

async function proxy(request: Request): Promise<Response> {
  const base = (process.env.BACKEND_URL ?? "").trim().replace(/\/+$/, "");
  if (!base) {
    return error(503, "BACKEND_NOT_CONFIGURED", "백엔드 서버 주소(BACKEND_URL)가 설정되지 않았습니다.");
  }

  const incoming = new URL(request.url);
  const path = (incoming.searchParams.get("__p") ?? "").replace(/^\/+/, "");
  incoming.searchParams.delete("__p");
  const search = incoming.searchParams.toString();
  const target = `${base}/api/${path}${search ? `?${search}` : ""}`;

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    const name = key.toLowerCase();
    if (!HOP_BY_HOP.has(name) && !name.startsWith("x-vercel-") && !name.startsWith("x-forwarded-")) {
      headers.set(key, value);
    }
  });
  // ngrok 같은 터널의 브라우저 경고 페이지를 건너뛴다(터널이 아니면 무시된다)
  headers.set("ngrok-skip-browser-warning", "1");

  const hasBody = request.method !== "GET" && request.method !== "HEAD";
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "manual",
    });
  } catch (e) {
    return error(502, "BACKEND_UNREACHABLE", "백엔드 서버에 연결하지 못했습니다.", {
      target: `${request.method} /api/${path}`,
      reason: e instanceof Error ? e.message : String(e),
    });
  }

  const out = new Headers();
  upstream.headers.forEach((value, key) => {
    if (!HOP_BY_HOP.has(key.toLowerCase())) out.set(key, value);
  });
  return new Response(upstream.body, { status: upstream.status, headers: out });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
export const OPTIONS = proxy;
