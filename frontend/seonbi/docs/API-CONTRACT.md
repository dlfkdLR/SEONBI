# API 연결 상태

## 기준

경로와 메서드는 상위 폴더의 `docs/개발기획서.md` v0.4 7.1 API 목록을 따른다. 로그인·사용자 API는 제외한다. v0.4에서 변경된 추천 요청(`POST`)과 조건 저장(`PUT`), 추가된 상세 조회·값·조건·자유 입력·질문 종료·답변 목록·점수·복제 API를 반영했다.

요청·응답 DTO가 확정되지 않은 항목은 반환값을 `unknown`으로 둔다. 화면은 응답 형태를 확인하고 검증한 뒤 연결해야 한다.

## 코드 위치

- `src/api/endpoints.ts`: API 메서드와 경로, 요청 타입 제약
- `src/api/client.ts`: 공통 fetch, `X-Client-Id`, 오류·파일 처리
- `src/api/operations.ts`: 결정·구성·질문·답변·분석 API 함수
- `src/api/inputs.ts`: 자유 입력 추출·확정·거절·추가 설명 재요청
- `src/api/hooks.ts`: TanStack Query 조회·변경 훅
- `src/data/client.ts`: 브라우저 ID. localStorage가 차단되면 페이지 메모리를 사용

자유 입력은 서버 호출 전에 1~500자로 검증한다. 추가 설명 재요청은 원문과 설명을 합친 결과가 500자를 넘으면 기존 입력을 거절하거나 네트워크를 호출하지 않는다.

## 화면 연결

API 함수와 훅은 준비됐지만 현재 페이지는 아직 localStorage 저장소와 로컬 계산을 사용한다. 서버와 화면이 연결된 상태는 아니다. 화면 연결은 `src/api/hooks.ts`의 `useApiDecisions`, `useApiOptions`, `useApiNextQuestion`, `useApiAnalysis` 등의 훅을 사용하고, DTO 검증·UI 모델 변환을 추가해야 한다.

`VITE_API_BASE_URL`에는 호스트 주소만 설정한다. 예: `http://localhost:8080`. 경로의 `/api/v1`은 클라이언트가 붙인다. 서버 미설정 시 로컬 성공 응답으로 바꾸지 않고 `API_NOT_CONFIGURED` 오류를 반환한다.

## 검증

`src/api/client.test.ts`는 API 목록과 실제 요청 경로·메서드를 비교한다. API 작업 함수, 자유 입력 500자 경계, localStorage 접근 거부 시 클라이언트 ID 동작도 테스트한다.
