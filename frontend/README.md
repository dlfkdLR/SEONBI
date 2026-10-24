# 선비 프론트엔드

PC용 React·TypeScript·Vite 앱입니다. 로그인 없이 `X-Client-Id`로 브라우저의 결정을 구분합니다. **개발기획서 v0.5 7장(스키마 v1)**을 API 기준으로 사용합니다.

## 실행

IntelliJ IDEA에서 **이 `frontend` 폴더**를 열어주세요. Node.js 22.12 이상 또는 24 이상이 필요합니다.

```sh
npm ci
npm run dev
```

화면: http://127.0.0.1:5173

API는 같은 origin의 `/api`로 요청하고, Vite가 `http://127.0.0.1:8000`의 Spring Boot 서버로 전달합니다. 다른 주소를 쓰면 `.env.example`을 `.env`로 복사해 `SEONBI_API_TARGET`을 변경해주세요. 별도 API origin에 직접 요청할 때만 `VITE_API_BASE_URL`을 지정합니다. 이 경우 서버 CORS 설정이 필요합니다. API 주소에는 `/api/v1`을 붙이지 않습니다.

실제 API 서버는 팀의 **B 기반 + A 서비스/HTTP 라우터 + C 서비스 연결**이 합쳐진 백엔드에서 실행해야 합니다. 프론트엔드 ZIP에 백엔드 서버는 포함되지 않습니다. API가 꺼져 있거나 미구현이면 화면에서 오류를 표시합니다. 프론트가 서버 성공이나 분석 결과를 대신 만들어내지 않습니다.

`.run/`에 npm 설치·실행·빌드·검사에 대한 IntelliJ 공유 실행 설정이 있습니다. 보이지 않으면 `package.json` 옆 실행 버튼이나 IDE 터미널을 사용해주세요.

## 데이터 흐름

- 목록, 상세, 생성·수정·삭제·복제, 값·순위·조건 저장은 서버 API를 사용합니다.
- 질문 선택·종료와 가중치·확률·안정성·전환 조건은 서버 응답을 표시합니다. 브라우저의 별도 분석 엔진은 없습니다.
- 입력 중 편집 내용은 메모리에 임시 보관합니다. **변경사항 저장 / 다음 단계**를 누르면 서버로 보냅니다. 저장 실패 시 다음 단계로 이동하지 않습니다. 부분 저장이 있었다면 서버 ID를 이어받아 편집 내용을 유지하고 재시도합니다.
- 새 결정은 제목과 선택지를 입력한 뒤 처음 저장할 때 서버에서 생성됩니다. ID도 서버가 발급합니다.
- `localStorage`에는 식별자 `seonbi.clientId`를 보관합니다. 예전 `seonbi.client-id`만 있으면 그 값을 이어받습니다. 두 키가 이미 다르면 기존 표준 키를 유지하고 임의로 계정/기록을 합치지 않습니다.
- 예전 데모의 `seonbi.decisions.v1`은 삭제하거나 서버에 자동 업로드하지 않습니다. 서버 모드 목록에는 서버가 저장한 기록만 나옵니다.
- 서버 목록이 비어 있으면 읽기 전용 입력 예시 1개를 표시합니다. 내 결정을 저장하면 사라지고 전부 삭제하면 다시 보입니다. 예시는 서버에 저장하지 않으며 가짜 확률·분석도 표시하지 않습니다.
- 확정/내보내기 등에서 `501 NOT_IMPLEMENTED`를 받으면 완료로 처리하지 않습니다. 오류는 모달 안에서도 표시합니다.

## API 계층과 타입

`src/api/http.ts`가 유일한 전송 계층입니다. C의 자유 입력·확정·파일 헬퍼도 이 계층으로 요청합니다. `src/api/endpoints.ts`에는 기획서 7.2의 전체 42개 API가 정의되어 있습니다. 추천 2개는 POST, 값·조건 저장은 PUT입니다.

`src/api/schema.d.ts`는 C가 관리하는 생성 타입을 그대로 사용합니다(기존 B 구성 API 포함). 해당 OpenAPI에는 **구성 API만** 들어 있고 문자열 enum과 응답 필수 여부가 느슨합니다. `src/api/contracts.ts`는 이를 기반으로 UI의 필수 필드와 기획서의 enum을 명시하며, 아직 OpenAPI에 없는 A 응답은 기획서 7.3 기준임을 구분합니다. 자동 생성 타입만으로 미구현 API나 스키마 빈틈이 해결되지는 않습니다.

A의 `QuestionDtos`는 `numeric`·`free_text`를 유형으로 허용하지만, `AnswerValue`는 choice / -5~5 정수 value / skip만 받습니다. 프론트는 numeric에서 이 범위만 보내고, 직접 자유 입력 답변의 `text` 필드를 임의로 추가하지 않습니다. 자유 입력은 C의 추출→사용자 확인 API를 사용합니다. `free_text` 직접 답변의 형식은 팀 계약 확정이 필요합니다.

## 검사

```sh
npm run build
npm test
npm run test:e2e
```

단위 검사는 42개 경로·메서드, PUT JSON, 오류/파일 처리, 클라이언트 ID 이전, 값·동순위·서버 ID 변환과 저장 실패를 검증합니다. 브라우저 검사는 **계약에 맞춘 테스트용 HTTP 응답**으로 생성→저장→질문→분석→확정 흐름, 시뮬레이션, 다운로드, 복제, 예시 표시, 네트워크/501 오류, 실제 Chromium 한글 IME 입력을 확인합니다. 실제 Spring Boot·PostgreSQL 통합 검사와는 다릅니다.

브라우저 검사는 기존 개발 서버를 건드리지 않도록 별도의 5174 포트를 사용합니다. macOS Google Chrome이 없으면 `npx playwright install chromium`으로 테스트 브라우저를 설치해주세요.

검사한 소스와 남은 서버 연결 조건: [API 호환성 보고서](docs/API-CONTRACT.md).

이 폴더는 `frontend/`입니다(이전 `seonbi/`). 타입 산출물(`src/api/schema.d.ts`)은 `backend/openapi.json`에서 `npx openapi-typescript backend/openapi.json -o frontend/src/api/schema.d.ts`로 다시 만들고, C가 갱신을 맡습니다. C가 만든 요청 함수(`src/api/{decisions,questions,analysis}.ts`, `hooks.ts`)와 앱의 요청 계층(`http.ts`)은 같은 `requestJson`을 씁니다. C의 Node 테스트는 `sh test-api.sh`로 돌립니다.

## Vercel 배포

백엔드(Spring Boot + PostgreSQL)는 Vercel에서 돌릴 수 없어 따로 띄웁니다. 프론트만 Vercel에 올리고, `api/[...path].ts`가 `/api/*` 요청을 환경변수 `BACKEND_URL`(끝에 슬래시 없이)의 서버로 넘깁니다. 같은 origin으로 요청하므로 CORS 설정이 필요 없습니다.

```sh
cd frontend
vercel env add BACKEND_URL production    # 백엔드 주소 입력
vercel deploy --prod
```

`BACKEND_URL`이 없으면 `/api` 요청은 `503 BACKEND_NOT_CONFIGURED`, 백엔드에 닿지 못하면 `502 BACKEND_UNREACHABLE`을 돌려주고 화면이 오류를 표시합니다.
