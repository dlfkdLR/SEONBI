# 백엔드 C — 연결·결과

> 기준 문서: `docs/개발기획서.md` (v0.5). 이 문서는 기획서를 C 담당 시점에서 요약한 것이다. 내용이 다르면 **기획서가 우선**이다.
>
> v0.5에서 백엔드가 FastAPI에서 **Spring Boot 3 (Java 21) + Spring Data JPA (Gradle)** 로 바뀌었다(17.7). API 명세(7장), 테이블(8장), 알고리즘(5장), 상태 머신(4.2)은 그대로이고 구현 언어와 도구만 바뀐다.

---

## 1. 한 줄 요약

**자유 입력(28~30)과 결과(47~48) API를 만들고, 프론트가 백엔드를 바로 쓸 수 있게 API 계층(타입·fetch 래퍼·TanStack Query 훅)을 깔아 주는 역할.**

### 왜 "백엔드와 프론트를 잇는" 역할인가

- 자유 입력은 A의 LLM 계층(`LlmClient`를 쓰는 `extractConditions`)과 B의 구성 서비스(기준·조건·선택지 생성), A의 답변 서비스(가상 슬라이더 답변) 사이를 잇는다. 사용자가 확인한 결과만 DB에 들어가게 하는 관문이다(6.3).
- 결과 API는 A의 분석 결과를 받아 확정(47)·기록(48)으로 마무리한다. 사용자 흐름의 마지막 칸이다(2.1, 4.2).
- 프론트는 1명이다(15.1). 화면 담당이 API 타입·요청 코드·캐시 규칙까지 신경 쓰지 않도록 C가 `frontend/src/api/`를 맡는다. 타입은 Spring Boot가 springdoc-openapi로 내놓는 OpenAPI 문서에서 만든다(9.3, 10.2).
- 분석 API(42·43·46 포함)의 컨트롤러 연결은 기본 범위가 아니다. A의 일이 밀린다고 팀이 정했을 때만 넘겨받는다(15.1).

---

## 2. 담당 범위

| 맡는 것 | 맡지 않는 것 (담당) |
|---|---|
| 자유 입력 API 28~30 (추출 요청·확정·거절) | `LlmClient`·`LlmRouter`·어댑터, AI 호출 상한 구현 (A) |
| 29번 확정 시 기준·조건·선택지·선호 반영 흐름 조립 | 엔진(`com.seonbi.engine`), 상태 전이 서비스(4.2), 질문 흐름, 47번이 쓰는 상태 로직 보조 함수 (A) |
| 결과 API 47(최종 확정), 48(Markdown 내보내기) | 질문·답변 API 31~38, 분석 API 39~46 (42·43·46 포함) (A) |
| 프론트 API 계층: openapi-typescript 타입 생성, fetch 래퍼, TanStack Query 훅 | 8장 JPA 엔티티·리포지토리 정의·변경 (B) |
| 담당 API의 통합 테스트 (JUnit 5 + MockMvc) | Spring Boot 골격, `core`(설정·`X-Client-Id`·오류 핸들러), 헬스 체크(0번) (B) |
| (A가 밀린다고 팀이 정했을 때만) LLM 호출별 프롬프트·출력 `record` 작성(6.2), P1 분석 API 42·43·46 컨트롤러 연결 | 결정·선택지·기준·값·조건 API 9~27·49 (B) |
| | 화면 컴포넌트·라우팅·Zustand 스토어 (프론트) |
| | 인증·보안·배포 (범위 밖, 12.1) |

---

## 3. 담당 파일·폴더

13.1 저장소 구조 기준이다(`backend/src/main/java/com/seonbi/...`). 파일 이름은 영역별로 나누어 다른 담당과 충돌을 피한다(15.1). 클래스 이름은 제안이다.

| 경로 | 내용 | 비고 |
|---|---|---|
| `web/InputController.java` (제안) | 28·29·30번 컨트롤러 | C 단독 |
| `web/ResultController.java` (제안) | 47·48번 컨트롤러 | C 단독 |
| `service/InputService.java` (제안) | 추출 요청·확정·거절 로직, 확정 항목별 분기 | B·A 서비스 호출 |
| `service/ResultService.java` (제안) | 확정·추천 일치 판정·스냅샷 저장 | A의 분석 결과 사용 |
| `service/MarkdownExporter.java` (제안) | 48번 Markdown 문자열 조립 | 파일 응답 헤더는 컨트롤러에서 |
| `dto/` 아래 입력·결과 관련 `record` (제안) | 7.3 28~30·47~48의 요청/응답 `record` + Bean Validation, 추출 항목 4종 형식 | 7장 v1 그대로. camelCase는 Jackson 기본 |
| `web/AnalysisController.java` 중 42·43·46 | (A 지원 시에만) 컨트롤러 연결 | **파일 주인은 A. 팀이 넘기기로 정했을 때만** |
| `ai/prompts/`, `ai/` 출력 `record` | (A 지원 시에만) 6.2 호출별 프롬프트·출력 스키마 | A가 넘겨줄 때만 |
| `src/test/java/com/seonbi/` 중 inputs·results 관련 | 통합 테스트 | 14장 |
| `frontend/src/api/` | fetch 래퍼, openapi 생성 타입, 쿼리 키, 쿼리·뮤테이션 훅 | 13.1 |

- 엔티티(`domain/`)와 리포지토리(`repository/`)는 건드리지 않는다. `free_text_inputs`, `final_choices`, `questions.origin`(8.1)에 컬럼이 더 필요하면 B에게 요청한다.
- 오류 핸들러(`@RestControllerAdvice`)는 B의 `core`에 있다. C의 API가 내야 하는 오류 코드가 핸들러에 없으면 B에게 추가를 요청한다.

---

## 4. 담당 API

경로는 모두 `/api/v1/decisions/{decisionId}` 아래다(7.1). 모든 요청은 `X-Client-Id` 필수, 결정 조회는 B의 `getDecision`(제안 이름, 기획서 12.2의 `get_decision`)으로 한다.

| # | 메서드 | 경로 | 우선 | 참조 | 핵심 동작 · 검증 · 오류 코드 |
|---|---|---|---|---|---|
| 28 | POST | `/inputs` | P0 | 3.7 A-03·A-04, 6.2, 6.3, 7.3, 12.2 | `context`(other_criterion·other_condition·reason·add_option), `questionId?`, `text` 1~500자 `@Valid` 검증 → AI 상한 확인(18·23·28만 셈) → A의 `extractConditions` 호출 → `free_text_inputs`에 `PENDING`으로 저장 → `201 { id, status, extracted, needsClarification }`. `relevant=false` 또는 items 비면 `status = IRRELEVANT` 행만 남기고 `422 IRRELEVANT_INPUT` + **고정 문구**(id 없음). 상한 초과 `429 AI_QUOTA_EXCEEDED`, LLM 실패 `503 AI_UNAVAILABLE`, 형식 오류 `400 VALIDATION_ERROR`, `FINALIZED`면 `409 DECISION_FINALIZED` |
| 29 | POST | `/inputs/{inputId}/confirm` | P0 | 5.3, 6.3, 7.3, 8.1 | 사용자가 고친 `items`(28번 `extracted`와 같은 형식, 아래 표)를 받아 반영. 응답 `{ status: "CONFIRMED", created: { conditions, criteria, options, answers }, statusChanged }`. 기준이 생기면 19번과 같은 상태 규칙(`QUESTIONING`/`ANALYZED`에서 기준 추가 → `statusChanged: "QUESTIONING"`). 반영 시 B 서비스의 검증(개수 제한 `409 LIMIT_EXCEEDED`, 이름 중복 등)을 그대로 따른다. `PENDING`이 아니면 `409 INVALID_STATE`, `FINALIZED`면 `409 DECISION_FINALIZED` |
| 30 | POST | `/inputs/{inputId}/reject` | P1 | 7.3 | 상태만 `REJECTED`로. DB의 다른 데이터는 바꾸지 않는다. `{ status: "REJECTED" }`. `PENDING`이 아니면 `409 INVALID_STATE`, `FINALIZED`면 `409 DECISION_FINALIZED` |
| 47 | POST | `/final-choice` | P0 | 3.9 R-01, 4.2, 7.3, 8.1 | `{ optionId, memo? }`. 추천과 달라도 허용. **`ANALYZED`에서만** 된다(`DRAFT`·`QUESTIONING`은 `409 INVALID_STATE`, 이미 확정이면 `409 DECISION_FINALIZED`). A의 현재 추천과 비교해 `matchedRecommendation` 저장, `recommendation_snapshot`에 **확정 시점 39번 응답 전체** 저장, 상태 전이는 A의 확정 서비스로 `FINALIZED`. excluded 선택지 `409 OPTION_EXCLUDED`(`force`는 v1에 없음, P2 검토), 없는 선택지 `404`. 응답 `201 { decisionId, optionId, matchedRecommendation, finalizedAt, status }` |
| 48 | GET | `/export?format=md` | P1 | 3.9 R-03, 7.3 | **Markdown만** 생성. **`ANALYZED`·`FINALIZED`에서만**, 그 외는 `422 NOT_READY`. `Content-Disposition: attachment; filename="decision-<id>.md"`. `format`이 `md`가 아니면 `400 VALIDATION_ERROR`. PDF는 프론트가 인쇄 CSS + `window.print()`로 처리(서버 작업 없음) |

### 4.1 추출 항목 형식 (28 `extracted[]`, 29 `items[]` 공통, 7.3)

항목 구분용 `type`과 이름이 겹치므로 **기준의 유형은 `criterionType`**(`numeric`·`rating`·`boolean`)으로 쓴다. DTO `record`를 만들 때 이 이름을 그대로 쓴다.

| `type` | 필드 | 확정 시 생성되는 것 |
|---|---|---|
| `criterion` | 19번 요청과 같음 (`name`, `criterionType`, `direction?`, `unit?`, `levels?`, `inputMode?`) | 기준 (`source = free_text`) |
| `constraint` | 27번 항목과 같음 (`kind`, `criterionId?`, `operator?`, `value?`, `label?`, `satisfiedBy?`). 27번처럼 목록 전체를 바꾸지 않고 기존 조건 목록에 **하나를 추가**한다 | 필수 조건 (`source = free_text`) |
| `option` | 14번 요청과 같음 (`name`, `description?`, `url?`) | 선택지 |
| `preference` | `criterionA`, `criterionB`, `value` (−5~+5, 양수면 A가 더 중요. 기본 ±3, 강한 표현이면 ±4 — 5.3) | 가상 `slider_pair` 질문·답변 |

- 모든 항목에 `confidence` (`high` \| `medium` \| `low`)가 붙는다.
- 항목 4종은 `type` 값으로 갈라지는 하나의 DTO 묶음이다(Jackson 다형성 `@JsonTypeInfo`/`@JsonSubTypes`를 쓰거나, 필드를 모두 가진 `record` 하나를 쓰는 방법이 있다. 구현 방식은 C가 정하되 OpenAPI 문서에서 프론트가 쓰기 좋은 모양이 되는지 확인한다).
- **preference 반영 방식**(7.3 29번, 8.1): 가상 `slider_pair` **질문 행 + 답변 행**을 만든다. 질문 행은 `origin = free_text`, `seq`는 다음 번호, `spec`은 일반 슬라이더와 같다. 답변은 `preference_kind = stated`.
  - 가상 질문은 질문 수(`MAX_QUESTIONS`·`question_limit`), 같은 유형 연속 규칙, 상황형 할당 차례에 세지 않는다. 32번은 반환하지 않고, 36번 목록에는 `question.origin: "free_text"`로 보인다. 31번 `progress.answered`에도 넣지 않는다.
  - `DRAFT`에서도 확정할 수 있다.

### 4.2 `needsClarification` 후속 흐름 (7.3 28번)

해석이 모호하면 28번이 `needsClarification` 문구와 선택지 버튼을 준다. 사용자가 버튼을 고르면 프론트는 **기존 입력을 30번으로 거절하고, 원문에 고른 답을 붙여 28번을 다시 호출**한다. 별도 API는 없다.

### 4.3 C 담당 API의 상태 제약 (4.2 "API별 상태 제약" 표에서 발췌)

| 작업 | `DRAFT` | `QUESTIONING` | `ANALYZED` | `FINALIZED` |
|---|---|---|---|---|
| 자유 입력 (28~30) | 가능 | 가능 | 가능 | `409 DECISION_FINALIZED` |
| 확정 (47) | `409 INVALID_STATE` | `409 INVALID_STATE` (먼저 35번으로 종료) | 가능 | `409 DECISION_FINALIZED` |
| 내보내기 (48) | `422 NOT_READY` | `422 NOT_READY` | 가능 | 가능 |

---

## 5. 프론트 API 계층

목표: 프론트 담당이 **훅 이름만 보고 화면을 붙일 수 있게** 한다. 코드는 C가 `frontend/src/api/`에 둔다.

### 5.1 화면별 사용 API

| 화면 (9.1/9.2) | 경로 | 쓰는 API |
|---|---|---|
| 랜딩 | `/` | 없음 |
| ① 결정 목록 | `/decisions` | 10(목록·상태 필터), 13(삭제), 49(복제, P1) |
| 결정 만들기 1단계 | `/decisions/new` | 9 |
| ② 구성 마법사 | `/decisions/:id/setup` | 11(전체 구성·`readiness`), 14~17(선택지), 19~22(기준), 23(기준 추천, P1), 24·25(값, 자동 저장 500ms), 26·27(조건·제외 미리보기), 28~30(`기타` 자유 입력 시트), 31(질문 시작) |
| ③ 질문 진행 | `/decisions/:id/questions` | 31, 32, 37(답변 + 다음 질문), 33(종료 판단), 35(조기 종료, P1), 36·38(답변 목록·수정, P1), 28~30(이유 추가 바텀 시트), 34(진행률, P2) |
| ④ 결과 | `/decisions/:id/result` | 39(종합 1회 로드, `lowConfidence`면 노란 배너), 42(기여도 막대, P1), 43(민감도, P1), 31(질문 더 하기), 47(확정), 48(내보내기, P1) |
| ⑤ 시뮬레이션 | `/decisions/:id/simulate` | 39 또는 40(원래 추정값), 46(150ms 디바운스) |

- 42·43·46의 **서버 구현은 A 몫**이지만, 그 훅은 C의 API 계층에 함께 둔다.

### 5.2 제공할 것

1. **타입 생성 스크립트** (제안 — 팀 합의 후 확정)
   - Spring Boot가 springdoc-openapi로 내놓는 OpenAPI 문서(`/v3/api-docs`)에서 `openapi-typescript`로 타입 파일을 만든다(9.3, 10.2). 서버는 `localhost:8000`(`server.port=8000`, 11.2)에서 떠 있어야 한다.

     ```
     openapi-typescript http://localhost:8000/v3/api-docs -o frontend/src/api/schema.d.ts
     ```

   - `package.json`에 스크립트로 등록해 명령 한 줄로 재생성한다.
   - API가 바뀌면 생성 타입 커밋을 같은 PR에 넣는다(13.2, 7.1).
   - FastAPI 때와 달라지는 점: 경로·파라미터 이름과 스키마 이름은 **컨트롤러 메서드와 `record` 이름을 따라간다.** JSON 필드는 Jackson 기본으로 camelCase다. 생성 타입에서 함수 이름처럼 쓰이는 `operationId`는 기본값이 컨트롤러 메서드 이름이라 바뀌기 쉬우므로, 컨트롤러에서 `@Operation(operationId = "...")`로 **명시적으로 고정**하는 것을 제안한다. A의 컨트롤러는 `startQuestions`, `getNextQuestion`, `getQuestionStatus`, `getQuestionProgress`, `stopQuestions`, `listAnswers`, `submitAnswer`, `editAnswer`, `getAnalysis`, `getWeights`, `getRankings`, `getScores`, `getSensitivity`, `getStability`, `getChangeConditions`, `simulate` 같은 id를 쓴다. C는 훅이 이 id에 기대므로, A·B에게 **id를 함부로 바꾸지 않도록** 요청하고 바뀌면 알려 달라고 한다. C 자신의 컨트롤러 id(예: `extractInput`, `confirmInput`, `rejectInput`, `finalizeChoice`, `exportDecision`)도 같은 규칙으로 고정한다(이름은 제안).
2. **fetch 래퍼**
   - 앱 시작 시 `localStorage`의 `seonbi.clientId`를 읽고, 없으면 `crypto.randomUUID()`로 만들어 저장한다(3.1, 7.3 클라이언트 식별).
   - **모든 요청**에 `X-Client-Id` 헤더를 붙인다. 화면 코드가 직접 붙이지 않게 한다.
   - Base URL은 `/api/v1`(Vite 프록시가 `/api`를 `localhost:8000`으로 넘김, 11.2).
   - 실패 응답은 공통 오류 형식 `{ error: { code, message, details } }`(7.1)을 파싱해 하나의 오류 타입으로 던진다. 화면은 `code`로 분기한다. 이 형식은 B의 `@RestControllerAdvice`가 만든다. 서버가 형식을 못 지킨 응답(예: 스프링 기본 오류 JSON)이 오면 알 수 없는 오류로 처리하고, 그런 경우를 발견하면 B에게 알린다. C 담당 API에서 화면이 처리해야 할 코드:

     | code | HTTP | 나오는 곳 | 화면 처리 |
     |---|---|---|---|
     | `IRRELEVANT_INPUT` | 422 | 28 | 서버의 고정 문구 표시 |
     | `AI_QUOTA_EXCEEDED` | 429 | 28 (그리고 B의 18) | 상한 안내. 23은 429 대신 기본 기준 목록으로 대체되므로 해당 없음(12.2) |
     | `AI_UNAVAILABLE` | 503 | 28 | "잠시 후 다시 입력해 주세요"(6.2) |
     | `INVALID_STATE` | 409 | 29·30(이미 처리된 입력), 47(`DRAFT`·`QUESTIONING`) | 상태 안내 |
     | `DECISION_FINALIZED` | 409 | 28~30·47 등 | 확정된 결정 안내 |
     | `OPTION_EXCLUDED` | 409 | 47 | 제외된 선택지 안내 |
     | `NOT_READY` | 422 | 48(`DRAFT`·`QUESTIONING`), 분석 API | 내보내기 비활성 등 |

   - 48번은 JSON이 아니라 파일이다. `X-Client-Id`가 필요하므로 `<a href>` 직접 링크가 아니라 래퍼로 받아 Blob으로 저장하는 헬퍼를 둔다. 파일 이름은 응답의 `Content-Disposition`에서 읽는다. `ANALYZED`·`FINALIZED`에서만 되므로, 실패(`422 NOT_READY`) 응답은 JSON 오류로 파싱해 같은 오류 타입으로 던진다.
3. **쿼리 키 규칙** (9.3의 `['decision', id]`, `['analysis', id]`를 기준으로 확장 — 제안, 프론트와 합의 후 확정)

   | 키 | 대상 API |
   |---|---|
   | `['decisions', { status }]` | 10 |
   | `['decision', id]` | 11 (선택지·기준·값·조건·readiness 포함) |
   | `['values', id]`, `['conditions', id]` | 24, 26 (11로 충분하면 생략) |
   | `['question', id]` | 32·33 |
   | `['answers', id]` | 36 |
   | `['analysis', id]` | 39 (40·41·44·45는 필요할 때 `['analysis', id, '<이름>']`) |
   | `['scores', id]`, `['sensitivity', id, criterionId]` | 42, 43 |

4. **mutation 후 invalidate 규칙** (제안)

   | mutation | invalidate |
   |---|---|
   | 9·12·13·49 | `['decisions']`, 해당 `['decision', id]` |
   | 14~17, 19~22, 25, 27 | `['decision', id]`, `['analysis', id]` 계열 (4.3: 구성 변경 → 분석 캐시 무효) |
   | 31, 35, 37, 38 | `['decision', id]`(상태), `['question', id]`, `['answers', id]`, `['analysis', id]` 계열 |
   | 29 (confirm) | `['decision', id]`, `['answers', id]`(가상 답변), `['analysis', id]` 계열. `statusChanged`가 오면 `['question', id]`도 |
   | 28, 30 | 없음 (PENDING은 반영 전) |
   | 47 | `['decision', id]`, `['decisions']`, `['analysis', id]` |
   | 23, 46 | 없음 (저장하지 않음. 23은 비멱등 POST라 mutation으로 둔다, 7.2) |

5. **훅**: API마다 `useXxx`(조회)·`useXxxMutation`(변경) 형태로 하나씩. 훅 이름은 OpenAPI의 `operationId`와 맞춰 두면 서버 변경을 따라가기 쉽다. 46번은 디바운스를 화면에서 걸지 훅에서 걸지 프론트와 정한다. `needsClarification` 재요청(30번 거절 → 28번 재호출, 4.2)은 한 번에 부를 수 있는 헬퍼로 묶는 것을 제안한다.
6. **스텁 연결**: 프론트는 **MSW 없이** 백엔드 스텁 컨트롤러에 바로 붙는다(7.1, 15). 스텁이 7.3 예시 응답을 돌려주는 동안에도 같은 훅으로 붙게 하고, 실제 구현으로 바뀌어도 화면 코드는 바뀌지 않아야 한다.

---

## 6. 다른 담당과 맞물리는 곳

메서드 시그니처는 먼저 합의한다(15.1 "담당 간 연결 지점"). 아래는 모두 **제안 (팀 합의 후 확정)** 이다. 이름은 자바 관례(camelCase)로 적었다.

| 상대 | 무엇을 쓰나 | 제안 시그니처 / 약속 |
|---|---|---|
| A — LLM 계층 | `extractConditions` 호출 | A의 서비스 메서드 `ExtractConditionsOutput extractConditions(String decisionSummary, String questionContext, String text)` (내부에서 `LlmClient.completeStructured`를 씀, 6.4). 출력은 6.2 스키마 `{ relevant, items[], ambiguity? }` 형태의 `record`(항목별 필드는 4.1 표). Jackson 파싱·Bean Validation 재검증·1회 재시도는 A 쪽에서 끝내고 넘긴다. 끝내 실패하면 정해진 예외(예: `AiUnavailableException`)를 던지고, 이를 `503 AI_UNAVAILABLE`로 바꾸는 일은 B의 `@RestControllerAdvice`가 한다 |
| A — AI 호출 상한 | 28번 전에 상한 확인·기록 | 예: `aiQuotaService.ensureQuota(clientId)` + `ai_usage_logs` 기록. **C는 직접 구현하지 않고 A가 만든 것을 쓴다.** 18·23·28만 센다. 초과 시 예외(예: `AiQuotaExceededException`) → `429 AI_QUOTA_EXCEEDED`(12.2), 매핑은 같은 `@RestControllerAdvice` |
| A — 답변/추정 서비스 | 29번 preference 반영 | `Answer addStatedPreference(Decision decision, UUID criterionAId, UUID criterionBId, int value)` — 가상 `slider_pair` 질문 행(`origin = free_text`, `seq` 다음 번호, `spec`은 일반 슬라이더와 같음)과 답변 행(`preference_kind = stated`)을 함께 만든다. 질문 수 계산에서 빼는 것, 재추정·`version` 증가는 A·B 규칙대로. `DRAFT`에서도 호출 가능해야 한다 |
| A — 기준 추가 후처리 | 29번에서 기준이 생길 때 | 기준 추가 시 상태 전이(`QUESTIONING`)와 `question_limit` 조정(4.2)을 처리하는 A의 훅(예: `onCriterionAdded(decision)`). B의 기준 추가 서비스(19번)가 이미 부르고 있으면 C는 B의 서비스만 호출하면 되고, C가 따로 부르지 않는다. `statusChanged` 값을 받아 응답에 담는 방법을 A·B와 정한다 |
| A — 상태 전이 서비스 | 47번 확정 | 예: `Decision finalizeDecision(Decision decision)` 또는 공통 `transition(decision, FINALIZED)`. `ANALYZED`가 아니면 4.2 표대로 예외. 상태를 C가 직접 바꾸지 않는다(4.2). 47번의 상태 판단에 쓰는 보조 로직은 A 몫이다 |
| A — 분석 결과 | 47 일치 판정·스냅샷, 48 본문 | 예: `AnalysisPayload getAnalysis(Decision decision)` (39번 응답과 같은 내용, 스냅샷에 그대로 저장) |
| B — 구성 서비스 | 29번 constraint·criterion·option 생성 | 예: `addCriterion(decision, request, Source.FREE_TEXT)`, `addCondition(..., Source.FREE_TEXT)`, `addOption(...)` — 19·27·14번과 **같은 서비스 메서드**를 재사용해 검증·`version` 증가·상태 변경(기준 추가 시 `QUESTIONING`, `statusChanged` 반환)이 한 곳에서 처리되게 함 |
| B — 공통 | 모든 C 컨트롤러 | `getDecision(decisionId, clientId)` (12.2), `X-Client-Id` 인자 해석기, `@RestControllerAdvice` 오류 핸들러·오류 형식(7.1) |
| B — 엔티티 | `free_text_inputs`, `final_choices`, `answers`, `questions`(`origin` 포함) | 읽기·쓰기만. 컬럼 추가·변경은 B에게 요청 |
| 프론트 | `frontend/src/api/` | 훅 목록·쿼리 키 표를 공유, 7장 스키마 변경 PR에 프론트를 리뷰어로 지정(15.1) |

- A가 밀려 42·43·46을 넘겨받게 되면, A가 노출한 서비스 메서드(`getScores`, `getSensitivity`, `simulate` 등)에 컨트롤러·DTO만 붙인다.

---

## 7. 할 일 체크리스트

의존 순서대로 적었다. 위가 끝나야 아래가 편하다.

**작업 순서 (팀 합의, 2026-10-24)**: 개발은 기획서 v0.5대로 진행한다. 순서는 ① B가 Spring 골격과 JPA 엔티티를 `dev`에 올림 → ② C가 스텁 컨트롤러·DTO와 타입 생성 → ③ A의 `extractConditions`·분석·확정 서비스가 생기면 자유 입력(28~30)·결과(47~48) 연결이다. B의 골격이 올라오기 전에는 7.1(프론트 API 계층 준비)만 하고, 컨트롤러 코드는 시작하지 않는다. A는 그동안 엔진을 자바로 이식하며, 파이썬 코드는 A가 `engine-reference/`로 옮기므로 C는 `backend/`의 파이썬 파일을 건드리지 않는다.

**B에게 달린 부분**: 컨트롤러·DTO 골격은 B의 Spring Boot 골격과 엔티티·리포지토리·`core`가 `dev`에 들어온 뒤에 시작할 수 있다(15.1). 그 전에는 프론트 API 계층을 먼저 준비한다. OpenAPI 문서가 아직 없으면 7.3 예시 응답을 보고 임시 타입을 쓰고, 서버가 올라오면 생성 타입으로 바꾼다.

### 7.1 B의 골격이 올라오기 전 (프론트 API 계층 준비)

- [ ] 클라이언트 ID 발급·저장(`seonbi.clientId`) 모듈
- [ ] fetch 래퍼: `X-Client-Id` 자동 첨부, Base URL, 공통 오류 형식 파싱 (5.2의 오류 코드 표 포함)
- [ ] TanStack Query 기본 설정 (QueryClient, 기본 재시도 규칙 — 4xx는 재시도 안 함 제안)
- [ ] 쿼리 키 모음 파일 + invalidate 규칙을 프론트와 합의
- [ ] 48번 파일 다운로드 헬퍼 (Blob 저장, `Content-Disposition`에서 파일 이름, `422 NOT_READY`는 오류로 파싱)
- [ ] `needsClarification` 재요청 헬퍼 (30 거절 → 28 재호출)
- [ ] 7.3 예시를 보고 임시 타입으로 훅 초안 작성 (서버가 올라오면 교체)

### 7.2 스텁 컨트롤러·DTO (B의 골격·엔티티가 `dev`에 들어온 직후)

- [ ] `InputController`, `ResultController`에 28·29·30·47·48 스텁 추가 — 7.3 예시 응답 그대로 반환
- [ ] 28~30·47·48 요청/응답 `record` 작성 (Bean Validation, 7장 v1 그대로). 29번 응답의 `created.answers`·`statusChanged`, 추출 항목 4종(4.1, `criterionType` 포함) 포함
- [ ] 컨트롤러 `operationId` 명시, `/v3/api-docs`에 담당 API가 보이는지 확인
- [ ] A·B에게 `operationId`를 안정적으로 유지해 달라고 요청

### 7.3 타입 생성과 훅 (스텁이 올라온 뒤)

- [ ] `openapi-typescript` 설치, 타입 생성 스크립트 등록(`http://localhost:8000/v3/api-docs` → `frontend/src/api/schema.d.ts`), 첫 타입 파일 커밋
- [ ] 임시 타입을 생성 타입으로 교체
- [ ] P0 흐름 훅 먼저: 9·10·11·14~17·19~22·24~27·31·32·37·39·47
- [ ] 그다음 P1 훅: 23·28~30·35·36·38·42·43·46·48·49
- [ ] 훅 목록·사용 예를 프론트에게 공유 (짧은 표면 충분)

### 7.4 자유 입력 (A의 `extractConditions`·상한, B의 구성 서비스 시그니처 합의 후)

- [ ] A와 `extractConditions`·상한·`addStatedPreference`·기준 추가 훅 시그니처 합의, AI 예외 → 429/503 매핑을 `@RestControllerAdvice`에서 누가 맡는지 확인
- [ ] B와 기준·조건·선택지 생성 서비스 재사용 방식 합의 (`source = free_text` 전달)
- [ ] 28번: `@Valid` 입력 검증 → 상태 확인(`FINALIZED`면 409) → 상한 확인 → 추출 → `PENDING` 저장 → 응답
- [ ] 28번: 관련 없음은 `IRRELEVANT` 행 저장 + `422 IRRELEVANT_INPUT` 고정 문구(id 없음), LLM 자유 답변 노출 금지
- [ ] 28번: LLM 실패 시 `503 AI_UNAVAILABLE`, 상한 초과 시 `429 AI_QUOTA_EXCEEDED`
- [ ] 28번: `needsClarification` 전달 (`ambiguity` → 응답 필드)
- [ ] 29번: 상태 확인(`PENDING`만, 아니면 `409 INVALID_STATE`), 항목 유형별 분기 반영, 한 트랜잭션(`@Transactional`)으로 처리
- [ ] 29번: preference → A의 `addStatedPreference` 호출 (`DRAFT`에서도 동작)
- [ ] 29번: 응답에 `created.answers`, 기준 추가로 상태가 바뀌면 `statusChanged: "QUESTIONING"`
- [ ] 30번: `REJECTED` 처리, `PENDING`이 아니면 `409 INVALID_STATE`
- [ ] Fake LLM으로 MockMvc 통합 테스트: 관련 없는 입력·스키마 위반이 DB 구성에 반영되지 않음, 확인 전에는 기준·조건이 늘지 않음(14장 AI 계층), 가상 질문이 32번에 나오지 않고 36번에 `origin: "free_text"`로 보임

### 7.5 결과 (A의 분석·확정 서비스가 생긴 뒤)

- [ ] 47번: 상태 확인(`ANALYZED`만), 선택지 검증(존재·excluded), 추천 일치 판정, 39번 응답 전체를 스냅샷으로 저장, A의 확정 서비스로 `FINALIZED`
- [ ] 47번: 확정 뒤 B의 10번 목록에 `finalChoice`가 나오는지 B와 확인
- [ ] 48번: 상태 확인(`ANALYZED`·`FINALIZED`만, 그 외 `422 NOT_READY`)
- [ ] 48번: Markdown 본문을 7.3 순서대로 구성 — 제목·설명·카테고리 → 선택지 × 기준 값 표 → 가중치(평균·구간) → 순위·1위 확률 → 안정성 → 전환 조건 → 숨은 선호 → 제외된 선택지 → 최종 선택·메모(확정된 경우)
- [ ] 48번: `format` 검증, `Content-Disposition` 헤더(`attachment; filename="decision-<id>.md"`), 응답 타입은 Markdown 텍스트
- [ ] 통합 테스트(JUnit 5 + MockMvc + Testcontainers): 다른 클라이언트 ID `404`, `CLIENT_ID_REQUIRED`, 4.3 상태 제약 표의 각 칸, 확정 후 수정 `409 DECISION_FINALIZED`, 오류 형식

### 7.6 A 지원 시 (A가 밀린다고 팀이 정했을 때만)

- [ ] 6.2 호출별 출력 `record`(Bean Validation) 작성: `extract_conditions`, `recommend_criteria`, `phrase_question`, `summarize_options`, `explain_result`
- [ ] 호출별 프롬프트(`ai/prompts/`) 작성 — 제공자 중립 한 벌, `<user_input>` 블록 분리(6.3), "잴 수 없으면 단계형" 규칙(6.2)
- [ ] P1 분석 API 42·43·46 컨트롤러 연결 — A가 노출한 서비스 메서드에 붙이고 응답 DTO를 7.3과 맞춤, 타입 재생성 (엔진·상태 전이·질문 흐름은 A가 유지)

---

## 8. 완료 기준

- 28~30·47·48이 7.3 요청/응답·오류 코드, 4.2 상태 제약 표 그대로 동작하고, `/v3/api-docs`와 생성 타입이 일치한다.
- Fake LLM과 Gemini 양쪽으로 "자유 입력 → 확인 → 기준/조건/선택지/선호 반영" 흐름이 돈다(15장 AI 연동 완료 기준).
- 관련 없는 입력·확인 전 추출 결과는 DB 구성에 반영되지 않는다(테스트로 확인).
- preference 확정 시 가상 질문(`origin = free_text`)·답변(`stated`)이 생기고, 질문 수 계산·32번에 끼어들지 않는다.
- 확정은 `ANALYZED`에서만 되고, 확정 후 상태가 `FINALIZED`이며 이후 수정 요청이 `409 DECISION_FINALIZED`를 받는다.
- 48번으로 받은 `.md` 파일이 7.3의 섹션 순서대로 결정 내용을 사람이 읽을 수 있게 담는다.
- 프론트가 `frontend/src/api/`의 훅만으로 P0 흐름(구성 → 질문 → 결과 → 확정)을 붙일 수 있다. 화면 코드에 `fetch`·헤더 처리가 없다.
- 담당 API 통합 테스트(JUnit 5 + MockMvc: 다른 클라이언트 ID 404, `CLIENT_ID_REQUIRED`, 오류 형식)가 `./gradlew test`로 통과한다(14장).

---

## 9. 지켜야 할 규칙

1. **로그인 없음.** 식별은 `X-Client-Id` 헤더뿐이다. 프론트 래퍼가 `localStorage`의 `seonbi.clientId`를 읽어 모든 요청에 붙인다(3.1, 7.3). 서버는 조회 조건으로만 쓴다.
2. **인증·보안·배포는 범위 밖**이다(12.1). 레이트 리밋·인젝션 탐지 같은 것을 따로 만들지 않는다.
3. **명세를 벗어나지 않는다.** 7장 스키마 v1, 8장 테이블, 4.2 상태 머신·API별 상태 제약 표가 기준이다. 없는 필드·API를 추가하지 않는다. 빈틈을 찾으면 먼저 팀에 공유하고 **기획서부터 수정**한 뒤 구현한다(변경은 PR + 17장 기록 + 타입 재생성, 7.1).
4. **LLM 출력은 사용자 확인 후에만 반영한다.** 28번은 `PENDING` 저장까지만, 실제 반영은 29번 confirm에서만(6.3).
5. **관련 없는 입력은 `IRRELEVANT` 기록 + `422 IRRELEVANT_INPUT` 고정 문구.** LLM의 자유 답변을 사용자에게 보여 주지 않는다(6.3, 7.3).
6. **`type: preference`는 가상의 `slider_pair` 질문·답변으로 등록한다**(질문 `origin = free_text`, 답변 `preference_kind = stated`, 값 ±3/±4, 5.3·7.3). 직접 행을 만들지 말고 A의 `addStatedPreference`를 호출한다.
7. **29번의 기준·조건은 B의 서비스로 `source = free_text`로 만든다.** 검증·상태 변경을 따로 구현하지 않는다.
8. **48번은 Markdown만, `ANALYZED`·`FINALIZED`에서만.** PDF는 프론트의 인쇄 CSS + `window.print()`다(7.3 48번).
9. **AI 호출 상한(`AI_QUOTA_EXCEEDED`)은 A가 구현한 것을 쓴다.** 상한은 18·23·28만 센다. C가 따로 세지 않는다(12.2).
10. **상태는 A의 상태 전이 서비스로만 바꾼다**(4.2). 결정 조회는 B의 `getDecision`으로 한다(12.2).
11. **엔티티 변경이 필요하면 B에게 요청한다**(11.2, 15.1). 각자 DB 테이블을 직접 고치지 않는다.
12. **Flyway는 DB 구조 확정 후** 도입한다. 그 전엔 `ddl-auto=update`, 엔티티가 바뀌어 오류가 나면 `docker compose down -v`(11.2).
13. 자유 입력 500자, 추출 출력 토큰 400 상한(12.2). 사용자 원문은 `ai_usage_logs`에 남기지 않는다(8.1).
14. API 키는 `.env`에만 둔다. 문서·코드·커밋·로그에 적지 않는다(6.4).
15. P0 흐름을 AI 없이 템플릿으로 끝까지 잇는 것이 먼저다. P1은 프론트 진행을 보고 만든다(15.1).
16. **42·43·46은 A 몫이다.** 팀이 넘기기로 정하기 전에는 A의 분석 컨트롤러를 건드리지 않는다(15.1).
17. **브랜치는 각자 만들어 `dev`에 합친다**(13.2, `docs/개발환경-세팅.md` 0번). `dev`에서 직접 코드를 작업하지 않는다.
18. **`operationId`와 DTO 필드 이름은 프론트의 생성 타입에 그대로 나타난다.** 이름을 바꾸면 하위 호환이 깨지는 변경이므로 합의 없이 바꾸지 않는다(7.1).

---

## 10. 참고 — 기획서 절 번호

| 절 | 내용 |
|---|---|
| 2.1 | 전체 사용자 흐름 |
| 3.1 | 사용자 식별 (`X-Client-Id`) |
| 3.6 | 질문 유형 (`free_text`, `slider_pair`) |
| 3.7 | AI 기능 (A-03 추출, A-04 입력 검증) |
| 3.8 | 분석 기능 (N-05·N-06·N-09 = P1) |
| 3.9 | 결과 (R-01 확정, R-03 내보내기) |
| 4.1 | 엔티티 관계 (FREE_TEXT_INPUT, FINAL_CHOICE) |
| 4.2 | 상태 머신, API별 상태 제약 표, `FINALIZED`, 47번 전이 |
| 4.3 | 수정이 분석에 미치는 영향 (캐시 무효화) |
| 5.3 | 자유 입력 선호 → 가상 `slider_pair` (±3/±4) |
| 6.1~6.3 | LLM 역할 분리, 호출 목록(`extract_conditions` 실패 시 503), 안전 장치 (Jackson + Bean Validation) |
| 6.4 | `LlmClient`·`LlmRouter`·어댑터·모델 설정 |
| 7.1 | 공통 규약·오류 형식·오류 코드, 스텁 컨트롤러(MSW 없음), 변경 절차 |
| 7.2 | 엔드포인트 전체 목록 |
| 7.3 | 클라이언트 식별, 28~30(추출 항목 형식, `criterionType`), 36(`question.origin`), 39(`lowConfidence`), 47·48 상세 |
| 8.1 | `free_text_inputs`, `final_choices`, `answers`, `questions.origin`, `ai_usage_logs` |
| 9.1~9.3 | 라우트, 화면별 요구사항, TanStack Query·openapi-typescript(`/v3/api-docs`) |
| 10.1 | 프론트 스택 |
| 10.2 | 백엔드 스택 (Spring Boot·JPA·Bean Validation·springdoc-openapi·Gradle) |
| 11.2 | Docker Compose, DB 구조 규칙, API 포트 8000, Vite 프록시 |
| 12.2 | 최소 규칙 (`getDecision`, AI 상한 18·23·28) |
| 13.1~13.2 | 저장소 구조(`backend/src/main/java/com/seonbi/...`, `frontend/src/api/`), 개발 규칙 |
| 14 | 테스트 전략 (JUnit 5, MockMvc, Testcontainers) |
| 15, 15.1 | 마일스톤, 역할 분담 (42·43·46은 A 몫) |
| 17.7 | v0.5 변경 기록 (Spring Boot 전환) |

---

## 11. 정리된 사항과 남은 것

예전에 이 절에 적었던 기획서 빈틈(P1 연결 범위, 가상 슬라이더 저장 방식, DRAFT에서 preference, 추출 항목 필드, 29번 `statusChanged`, 28~30·47·48 상태·오류 코드, 관련 없는 입력 저장, `needsClarification` 흐름, LLM 장애 응답, 47번 스냅샷, 48번 구성, MSW vs 스텁)은 기획서에 모두 반영됐다. 위 4~9장은 그 내용을 따른다.

남은 것은 기획서가 정하지 않은 아래 항목이다. 모두 제안이며 팀과 합의해 정한다.

- **쿼리 키·invalidate 규칙** (9.3은 예시만 있음): 5.2의 표를 프론트와 합의
- **`operationId` 고정 방식과 이름**: 컨트롤러에서 명시하는 방식, A·B의 id 유지 약속
- **AI 예외 → 429/503 매핑의 소유**: 예외 클래스는 A, 변환은 B의 `@RestControllerAdvice`로 하는 안
- **추출 항목 4종 DTO 모양**: `@JsonTypeInfo` 다형성 또는 단일 `record`, OpenAPI 문서에서 프론트가 쓰기 좋은지
- **A·B 서비스 메서드 시그니처**: 6장 표
