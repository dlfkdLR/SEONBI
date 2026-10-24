# 백엔드 B — 기반·구성

> 기준 문서: `docs/개발기획서.md` (v0.5). 이 문서와 기획서가 다르면 **기획서가 맞다**. 기획서에 없는 내용은 여기서 정하지 않는다.
>
> v0.5에서 백엔드가 FastAPI에서 **Spring Boot 3(Java 21) + Spring Data JPA(Hibernate), Gradle**로 바뀌었다(17.7). API 명세(7장)·테이블(8장)·알고리즘(5장)·상태 전이(4.2)는 그대로이고 구현 언어와 도구만 바뀐다. 기존 파이썬 코드는 `engine-reference/`의 참조 구현이 된다.

---

## 1. 한 줄 요약

**팀 모두가 올라탈 Spring Boot 바닥을 만든다.** Gradle 프로젝트 생성, 실행 환경(Docker Compose), `core`(설정·`X-Client-Id`·오류 형식), 헬스 체크(0번), 8장 JPA 엔티티·리포지토리 전부, 그리고 결정 구성 API(9~27, 49)를 맡는다. 자바 코드베이스를 여는 일이라 이번 구조에서 B가 가장 먼저 움직여야 한다.

**왜 B가 먼저 끝나야 하나**

- A(엔진·질문·분석)와 C(자유 입력·결과)는 B가 만든 Spring 프로젝트 위에서 이어 작업한다. **골격이 `dev`에 들어오기 전에는 A도 C도 자바 코드를 올릴 곳이 없다.** 그래서 B는 기능 API보다 골격을 먼저, 작게 합친다(13.2, 15.1).
- A·C의 API는 모두 `{decisionId}` 아래에 있다. 결정 조회 조건과 오류 형식이 없으면 아무도 컨트롤러를 못 만든다.
- 8장 엔티티는 B가 한꺼번에 만든다(15.1). 엔티티가 없으면 A·C는 DB에 아무것도 저장할 수 없다.
- 질문 시작(31번)은 선택지·기준·값이 있어야 동작한다. 그 데이터를 넣는 API가 B의 14~27번이다.
- 프론트는 1명이라 병목이다. B가 **7장 예시 응답을 돌려주는 스텁 컨트롤러**를 먼저 올려야 프론트가 MSW 없이 바로 붙는다(7.1, 15장 병렬 개발 원칙).

---

## 2. 담당 범위

| 맡는 것 | 맡지 않는 것 (담당) |
|---|---|
| Spring Boot 프로젝트 생성: Gradle(Kotlin DSL), Java 21, Spring Web, Spring Data JPA, PostgreSQL 드라이버, Bean Validation, springdoc-openapi, Jackson, `uuid-creator`, Spotless, 테스트(JUnit 5·AssertJ·jqwik·Testcontainers·ArchUnit) (10.2, 13.2, 14) | 배포·서버·HTTPS (범위 밖, 12.1) |
| 팀 공통 실행 환경: `docker-compose.yml`, `backend/Dockerfile`(멀티 스테이지 JDK 21), `backend/.env.example`, `.gitignore` (11.2~11.3) | 로그인·세션·사용자 테이블·레이트 리밋 (범위 밖, 3.1·12.1) |
| `application.yml`: `ddl-auto=update`, `server.port=8000`, `SPRING_DATASOURCE_*` 읽기 | 상태 전이 서비스 (A, 4.2) — B는 호출만 |
| `SeonbiApplication`, 헬스 체크 `GET /api/health`(0번, `/api/v1` 밖) | 분석 엔진 `engine`·자바 이식 (A) |
| `core`: 설정, `X-Client-Id` 필터·인자 해석기, `@RestControllerAdvice` 오류 처리, `FlowError` 예외 (7.1) | 질문·답변 API 31~38, 분석 API 39~46 (A) |
| **8장 JPA `@Entity` 13개 + Spring Data 리포지토리 전부** + 이후 엔티티 변경 관리 (15.1) | `LlmClient`·`LlmRouter`·LLM 호출 6종·AI 호출 상한 (A) |
| 결정 API 9~13, 49 | 기존 파이썬 코드를 `engine-reference/`로 옮기는 일 (A) |
| 선택지·기준·값·조건 API 14~27 | 자유 입력 API 28~30, 결과 API 47~48, P1 확장 (C) |
| 결정 조회 서비스(id + client id → 404), `decisions.version` 증가 규칙 (8.2) | 프론트 API 계층(openapi 타입, TanStack Query 훅) (C) — B는 **지원만** |
| `readiness` 계산 (7.3 11번), 제외 판정 | 화면 (프론트) |
| API 통합 테스트 기반 (14장) | 엔진 단위·시뮬레이션·골든 파일 대조 테스트 (A) |

---

## 3. 담당 파일·폴더

13.1 구조 기준. 다른 담당과 파일이 겹치지 않게 영역별로 나눈다(15.1 충돌 방지). `engine/`·`ai/` 패키지와 `src/test/resources/golden/`은 A의 영역이다.

| 경로 | 내용 |
|---|---|
| `docker-compose.yml` | `db`(postgres:16.4, 볼륨 `seonbi-pgdata`, `pg_isready` healthcheck) + `api`(depends_on service_healthy) |
| `backend/Dockerfile` | 멀티 스테이지: `eclipse-temurin:21-jdk`로 빌드 → `eclipse-temurin:21-jre`로 실행 |
| `backend/build.gradle.kts`, `settings.gradle.kts`, `gradlew`, `gradle/wrapper/` | 의존성·플러그인(Spotless 포함)·버전 고정. Wrapper와 의존성 잠금 파일 커밋 |
| `backend/.env.example` | 키 없이 도는 기본값 (`SPRING_DATASOURCE_*`, `LLM_MODEL_FAST=fake:default` 등, 11.3) |
| `.gitignore` | `.env`, `build/`, `.gradle/`, IDE 파일 등. `.env.example`은 제외하지 않음 |
| `backend/src/main/resources/application.yml` | `server.port=8000`, `spring.jpa.hibernate.ddl-auto=update`, 데이터소스 환경변수 연결, springdoc 경로 |
| `backend/src/main/java/com/seonbi/SeonbiApplication.java` | 앱 시작점 |
| `backend/src/main/java/com/seonbi/core/` | 설정, `X-Client-Id` 필터·인자 해석기, `@RestControllerAdvice`, `FlowError`, 오류 코드 enum, UUID v7 생성 |
| `backend/src/main/java/com/seonbi/domain/` | 8장 테이블 13개 `@Entity` |
| `backend/src/main/java/com/seonbi/repository/` | Spring Data JPA 리포지토리 인터페이스 전부(A·C 테이블 포함) |
| `backend/src/main/java/com/seonbi/dto/` | B 담당 API(9~27, 49)의 요청·응답 `record` + Bean Validation |
| `backend/src/main/java/com/seonbi/web/` | `HealthController`, `DecisionController`, `OptionController`, `CriterionController`, `ValueController`, `ConditionController` |
| `backend/src/main/java/com/seonbi/service/` | 결정 조회, 결정·선택지·기준·값·조건 서비스, `version` 증가, 제외 판정, readiness |
| `backend/src/test/java/com/seonbi/` | 통합 테스트 기반(Testcontainers, MockMvc), B 담당 API 테스트, 헬스 체크 테스트, ArchUnit 구조 검사 틀 |

`Flyway` 마이그레이션은 DB 구조가 거의 확정된 뒤에 B가 도입한다(11.2).

**기존 파이썬 코드**: 지금 `backend/`에는 파이썬 트리가 있다. 이것을 `engine-reference/`로 옮기는 일은 **A의 몫**이고, B는 지우거나 옮기지 않는다. B는 A와 이동 시점을 맞춘 뒤에 `backend/`를 Spring 프로젝트로 시작한다(7.1).

---

## 4. 담당 API

공통: 0번을 뺀 모든 경로는 `/api/v1` 아래. `{decisionId}`가 있는 모든 API는 결정 조회 서비스(6.1)로 조회하고 없거나 다른 클라이언트면 `404 NOT_FOUND`. `X-Client-Id` 없음·형식 오류는 `400 CLIENT_ID_REQUIRED`. 바디 형식 오류(Bean Validation)는 `400 VALIDATION_ERROR`(7.1). 요청·응답은 `record` DTO이고 필드는 camelCase 그대로다. 상태별 제약은 아래 표(4.2 API별 상태 제약)를 따른다.

| # | 메서드 | 경로 | 우선 | 컨트롤러 | 핵심 검증 · 상태 제약 · 오류 |
|---|---|---|---|---|---|
| 0 | GET | `/api/health` | P0 | `HealthController` | **`/api/v1` 밖**, `X-Client-Id` 불필요. `200 {"status": "ok", "db": "ok"}`, DB 연결 실패 시 `503 {"status": "error", "db": "error"}` |
| 9 | POST | `/decisions` | P0 | `DecisionController` | title 1~100자, description ≤1,000자, category enum. options 0~8개 함께 생성. `client_id` 저장, status `DRAFT`, `rng_seed` 생성 → `201`. 응답의 `options`는 **Option 객체**(11번과 같은 형식: `id`·`name`·`description`·`url`·`excluded`·`excludedReasons`) |
| 10 | GET | `/decisions` | P0 | `DecisionController` | 내 `client_id` + `deleted_at IS NULL`만. 필터는 `?status=` 하나, `&cursor=&limit=20`, `{items, nextCursor}`. `optionCount`, `finalChoice` 포함 |
| 11 | GET | `/decisions/{decisionId}` | P0 | `DecisionController` | 구성 전체(options·criteria·values·rankings·conditions) + 선택지별 `excluded`/`excludedReasons` + `readiness` |
| 12 | PATCH | `/decisions/{decisionId}` | P0 | `DecisionController` | `{title?, description?, category?}`. FINALIZED면 409. **version을 올리지 않는다**(분석 입력이 아님, 8.2) |
| 13 | DELETE | `/decisions/{decisionId}` | P0 | `DecisionController` | **어느 상태에서든 가능**(FINALIZED 포함). `deleted_at`만 채우는 소프트 삭제, 하위 데이터는 그대로 둔다 → `204`. 실제 행 삭제(이때 FK CASCADE 동작)는 30일 후 정리 작업으로 P2 |
| 49 | POST | `/decisions/{decisionId}/duplicate` | P1 | `DecisionController` | **어떤 상태든 가능**. 요청 바디는 생략 가능, 제목 생략 시 원제목 + " (복사본)". 복사: 제목·설명·카테고리·선택지·기준(척도 고정 해제)·값·순위·필수 조건. 복사 안 함: 질문·답변·분석·최종 선택·자유 입력. 응답은 11번 형식, `DRAFT`, `201` |
| 14 | POST | `/options` | P0 | `OptionController` | name ≤60자(8.1), 결정당 최대 8개(`409 LIMIT_EXCEEDED`), 이름 중복 불가. **QUESTIONING/ANALYZED에서 추가하면 `rank` 기준마다 새 선택지를 맨 아래 순위 그룹에 자동 배치하고 `"warning": "RANKING_UPDATED"`**. version +1 |
| 15 | GET | `/options` | P0 | `OptionController` | `{items: [Option]}` |
| 16 | PATCH | `/options/{optionId}` | P0 | `OptionController` | 이름 중복 불가. FINALIZED면 409. version +1 |
| 17 | DELETE | `/options/{optionId}` | P0 | `OptionController` | 관련 값 삭제, version +1. **QUESTIONING/ANALYZED에서 삭제해 2개 미만이 되면 `409 INVALID_STATE`** |
| 18 | POST | `/options/recommendations` | **P2** | `OptionController` | **A의 LLM 호출 `recommend_options`를 부른다**. 요청 `{count}`, 결과는 저장하지 않음. 고르면 14번으로 추가. AI 호출 상한에 포함, 넘으면 `429 AI_QUOTA_EXCEEDED`. 실패 시 기능 숨김(6.2). 맨 마지막에 |
| 19 | POST | `/criteria` | P0 | `CriterionController` | name ≤40자·중복 불가, type enum. **결정당 최대 7개, 8개째는 `409 LIMIT_EXCEEDED`**. rating: `levels` 3~7개·각 1~12자·중복 불가(생략 시 기본 5단계), `inputMode`는 rating만(`score` 기본), direction 고정 `higher_better`. boolean: `true_better`. 선택 필드: `scaleMin`·`scaleMax`(numeric만), `source`(`user` 기본 \| `ai_recommended`. `free_text`는 29번에서 서버가 채움). **QUESTIONING/ANALYZED에서 추가하면 A의 상태 전이 서비스로 `QUESTIONING`으로 바꾸고 응답에 `"statusChanged": "QUESTIONING"`**. version +1 |
| 20 | GET | `/criteria` | P0 | `CriterionController` | 기준 목록 |
| 21 | PATCH | `/criteria/{criterionId}` | P0 | `CriterionController` | `scale_frozen`인데 `scaleMin/scaleMax`·`levels` 개수가 바뀌면 `"warning": "SCALE_CHANGED_AFTER_ANSWERS"`(라벨 이름만 바꾸면 영향 없음). `inputMode` 변경 시 그 기준 값 초기화, score→rank는 기존 점수 순서로 `"prefilledRanking"` 반환. version +1 |
| 22 | DELETE | `/criteria/{criterionId}` | P0 | `CriterionController` | 관련 답변 `is_valid=false` 처리, `{ "invalidatedAnswers": n }` 반환. version +1 |
| 23 | POST | `/criteria/recommendations` | P1 | `CriterionController` | **A의 `recommend_criteria` 호출**. 3~6개, 저장하지 않음. 정상은 `"source": "ai"`. **AI 호출 상한 초과나 LLM 실패 시 오류 대신 카테고리별 기본 기준 목록(6.2) + `"source": "template"`** |
| 24 | GET | `/values` | P0 | `ValueController` | `{items, rankings, missing}` |
| 25 | PUT | `/values` | P0 | `ValueController` | 부분 저장 허용. numeric=숫자, rating(score)=1~L 정수, rating(rank)=결정의 **모든 선택지(제외 포함)가 정확히 한 번**, boolean=true/false. 저장 위치 `value_num`/`value_bool`/`value_rank`. 응답은 24번과 같음. version +1 |
| 26 | GET | `/conditions` | P0 | `ConditionController` | 조건 목록 |
| 27 | PUT | `/conditions` | P0 | `ConditionController` | **목록 전체 교체**. `threshold`(criterionId·operator `<=`/`>=`/`==`·value), `required_tag`(label ≤60자·`satisfiedBy` → `condition_satisfactions`). 항목별 선택 필드 `source`(`user` 기본 \| `ai_recommended`, `free_text`는 29번에서 서버가 채움). 응답에 `excludedOptions`(사유 문장), 전부 제외면 `"warning": "ALL_OPTIONS_EXCLUDED"`. 선택지는 지우지 않음. version +1 |

**API별 상태 제약 (4.2 표 중 B 담당 부분)** — 조회 API(10·11·15·20·24·26)는 모든 상태에서 된다.

| 작업 | `DRAFT` | `QUESTIONING` | `ANALYZED` | `FINALIZED` |
|---|---|---|---|---|
| 구성 수정 (12, 14~17, 19~22, 25, 27) | 가능 | 가능 (4.3 규칙) | 가능 (기준 추가 시 `QUESTIONING`) | `409 DECISION_FINALIZED` |
| 삭제 (13)·복제 (49) | 가능 | 가능 | 가능 | 가능 |

QUESTIONING/ANALYZED에서 B가 따로 챙길 규칙: 14번 `RANKING_UPDATED` 자동 배치, 17번 2개 미만 `409 INVALID_STATE`, 19번 `statusChanged`(위 표). `QUESTIONING → DRAFT` 전이는 없다(4.2).

---

## 5. DB 엔티티 목록

8.1의 테이블은 **B가 처음에 한꺼번에 `@Entity`와 리포지토리로 전부 만든다.** A·C가 쓰는 테이블도 B가 만들고, 이후 컬럼 변경도 B를 거친다(15.1).

공통 규칙 (8장 머리말):

- PK `uuid`: 애플리케이션에서 UUID v7 생성(`uuid-creator`). 시간 컬럼은 `timestamptz` ↔ 자바 `Instant`.
- `jsonb` 컬럼: `@JdbcTypeCode(SqlTypes.JSON)`로 매핑.
- 결정 하위 테이블은 FK CASCADE(8장). 단 결정 삭제(13번)는 `deleted_at`만 채우는 소프트 삭제라 CASCADE가 동작하지 않는다. CASCADE는 P2 정리 작업에서 행을 실제로 지울 때만 쓰인다(4.1, 8.2).
- 엔티티 필드는 8.1 컬럼과 1:1이다. 8.1에 없는 필드·테이블을 만들지 않는다.

| 테이블 (엔티티) | 주 사용 | 비고 |
|---|---|---|
| `decisions` | B (상태 변경은 A) | `client_id` 인덱스(client_id, updated_at DESC), `status`·`category` CHECK, `rng_seed`, `question_limit`(NULL = 첫 라운드), `version`(분석 캐시 키로만 씀, 8.1), `deleted_at` |
| `options` | B | UNIQUE(decision_id, name), `position` |
| `criteria` | B (척도 고정은 A, C의 29번도 생성) | UNIQUE(decision_id, name), `levels` jsonb, `input_mode`, `scale_min/max`, `scale_frozen`, `source`(`user`/`ai_recommended`/`free_text`) |
| `option_values` | B | 복합 PK(option_id, criterion_id), `value_num`/`value_bool`/`value_rank` |
| `conditions` | B (C의 29번도 생성) | `kind` threshold / required_tag, `source` |
| `condition_satisfactions` | B | (condition_id, option_id, satisfied) |
| `questions` | A (C의 29번도 가상 질문 생성) | UNIQUE(decision_id, seq), `spec`·`display` jsonb, `phrasing_source`, **`origin`**(`engine` / `free_text`, 질문 수 계산에서 `free_text`는 뺌), `info_gain` |
| `answers` | A (기준 삭제 시 B가 `is_valid=false`) | `question_id` UNIQUE, `preference_kind` |
| `free_text_inputs` | C | status PENDING/CONFIRMED/REJECTED/IRRELEVANT |
| `analysis_results` | A | `decision_version`이 현재 `version`과 다르면 무효 |
| `estimate_snapshots` | A (P1) | 검증·그래프용 |
| `final_choices` | C | 결정당 하나, `recommendation_snapshot` |
| `ai_usage_logs` | A | INDEX(client_id, created_at), `provider`, 원문 저장 안 함 |

사용자 테이블은 없다(v0.4). 가중치·입자는 저장하지 않는다(8.2). `decisions.version`은 분석 캐시 키 용도뿐이다(8.1). 올리는 규칙은 6.2 참고.

---

## 6. 다른 담당과 맞물리는 곳

아래 시그니처는 모두 **제안 (팀 합의 후 확정)** 이다. 15.1에 따라 함수 시그니처를 먼저 합의하고 구현한다. 아래 이름과 패키지는 자바 기준 초안이다.

### 6.1 B가 먼저 제공하는 공통 기반 (제안, 팀 합의 후 확정)

| 이름 (제안) | 위치 | 하는 일 | 쓰는 사람 |
|---|---|---|---|
| 리포지토리 13개 | `repository` | 8장 테이블 전부의 Spring Data 인터페이스 | A·B·C |
| `ClientId` + 인자 해석기 | `core` | `X-Client-Id` 헤더를 읽어 컨트롤러 인자로 넘김. 헤더 없음·UUID 아님 → `400 CLIENT_ID_REQUIRED`. `/api/health`는 제외 | A·B·C |
| `FlowError(HttpStatus, String code, String message, Map<String,Object> details)` | `core` | 7.1 오류 코드를 담는 예외 | A·B·C |
| `@RestControllerAdvice` 핸들러 | `core` | `FlowError`와 Bean Validation 오류(`MethodArgumentNotValidException` 등)를 `{error: {code, message, details}}`로 변환. 검증 오류는 `400 VALIDATION_ERROR`, details에 필드별 오류 | A·B·C |
| `DecisionResolver.get(UUID decisionId, UUID clientId) -> Decision` | `service` | `id = ? AND client_id = ? AND deleted_at IS NULL`, 없으면 `404 NOT_FOUND` (12.2). 파이썬 `get_decision`에 해당 | A·B·C |
| `ensureNotFinalized(Decision)` | `service` | FINALIZED면 `409 DECISION_FINALIZED`. 13번 삭제·49번 복제에는 쓰지 않는다(4.2) | A·B·C |
| `bumpVersion(Decision)` | `service` | `decisions.version += 1`, `updated_at` 갱신. **같은 트랜잭션 안에서** 호출. 12번에서는 부르지 않음(8.2) | A·B·C |
| `computeReadiness(Decision)` | `service` | `canStartQuestions`, `missing`(`OPTIONS_LT_2`, `CRITERIA_LT_2`, `VALUES_INCOMPLETE`, `RANKING_INCOMPLETE`) | B(11번), A(31번 `422 NOT_READY` 판단) |
| `computeExclusions(Decision)` | `service` | 조건으로 선택지별 `excluded`·사유 계산 | B(11·27번), A(엔진 입력), C |
| UUID v7 생성기 | `core` | 엔티티 PK 발급 | A·B·C |

### 6.2 `version` 증가 규칙 (8.2)

- `version`은 분석 캐시 키로만 쓴다(8.1). 선택지·기준·값·조건·**답변**이 바뀌면 같은 트랜잭션에서 `+1`.
- B 담당: 14·16·17·19·21·22·25·27번.
- **12번(제목·설명·카테고리)은 올리지 않는다.** 분석 결과에 영향이 없기 때문이다(7.3 12번, 8.2).
- A 담당: 답변 제출·수정(37·38번). `bumpVersion`을 같이 쓴다.
- C 담당: 29번 확정으로 선택지·기준·조건·가상 질문 답변이 생기면 올린다. B의 생성 서비스를 쓰면 그 안에서 올라간다(6.5).
- 분석 캐시는 `analysis_results.decision_version != decisions.version`이면 무효. 재계산은 A의 분석 쪽이 한다. B는 **번호만 올린다.**

### 6.3 상태 전이는 A의 서비스로만 (A가 제공, B가 호출)

- 상태는 서버가 관리하고, B는 `decisions.status`를 **직접 바꾸지 않는다**(4.2).
- 19번 기준 추가(QUESTIONING/ANALYZED): A의 상태 전이 서비스를 호출한다. 제안: `transitionOnCriterionAdded(Decision, UUID criterionId)`. `question_limit` 조정(ANALYZED → 추가 라운드 `답변 수 + 3`, QUESTIONING → `max(현재 상한, 답변 수 + 3)`)과 새 기준 우선 질문은 A가 처리한다(4.2 추가 질문 라운드). B는 status·`question_limit`을 직접 건드리지 않는다.
- 기준 삭제(22번)로 답변을 `is_valid=false`로 만드는 것은 B가 하고, 재추정은 A의 분석이 캐시 무효화를 보고 한다.
- 9번 생성 시 `DRAFT`로 만드는 것은 초기값이라 B가 넣는다.
- 척도 고정(`scale_min/max`, `scale_frozen`)은 31번 질문 시작에서 A가 한다. B는 21번에서 frozen 여부만 보고 경고를 붙인다.
- A가 늦으면 19번은 상태 전이 호출 자리만 두고 `statusChanged` 응답 조건까지 먼저 구현한다. 합의한 시그니처로 빈 구현을 둔다.

### 6.4 LLM 호출 (A ↔ B)

- AI 호출 상한은 사용자가 직접 부르는 18·23·28번만 센다(12.2). B 담당 중에는 18·23번이 여기에 들어간다. 상한 판단과 `ai_usage_logs` 기록은 A의 AI 계층이 처리한다.
- 23번: A의 `recommend_criteria(...)`를 호출. 입력은 결정 제목·목적·카테고리·선택지 이름(6.2). **상한 초과나 LLM 실패여도 오류를 내지 않고** 카테고리별 기본 기준 목록(6.2)을 `"source": "template"`로 돌려준다(정상은 `"source": "ai"`, 7.3 23번).
- 18번(P2): A의 `recommend_options(...)` 호출. 상한 초과는 `429 AI_QUOTA_EXCEEDED`, LLM 실패 시 기능 숨김(6.2, 12.2).
- A의 AI 계층이 늦으면 23번은 기본 기준 목록(`"source": "template"`)을 돌려주는 상태로 먼저 연결한다.

### 6.5 C·프론트와

- C의 29번 확정은 B의 생성 서비스를 재사용한다(7.3 29번: 추출 항목 형식이 `criterion`=19번 요청, `constraint`=27번 항목, `option`=14번 요청과 같음). 제안: `CriterionService.create(decision, request, source)`, `OptionService.create(decision, request)`, 조건 추가용 메서드. 그래서 이 서비스들은 다음을 안에서 처리해야 한다.
  - 중복 검사·개수 제한(선택지 8개, 기준 7개 → `409 LIMIT_EXCEEDED`)·`version` +1
  - `source = free_text` 저장(19·27번 요청의 `source`는 `user`/`ai_recommended`만 받는다. `free_text`는 29번에서 서버가 채운다, 7.3 19·27번)
  - 기준 추가 시 19번과 같은 상태 규칙(`statusChanged`), 선택지 추가 시 14번과 같은 `rank` 자동 배치
- 29번의 `preference` 항목은 가상 `slider_pair` 질문(`questions.origin = free_text`)·답변으로 들어간다. B는 `origin` 컬럼을 엔티티에 넣어 두기만 한다(8.1).
- 프론트 API 계층은 C 담당이다. B는 기반이 끝난 뒤 springdoc OpenAPI 스키마가 7장과 맞는지 확인하고, 훅 작성을 **돕기만** 한다.
- 7장 스키마를 바꾸는 PR에는 프론트 담당이 리뷰어로 들어간다(15.1).

---

## 7. 할 일 체크리스트

의존 순서대로 적었다. 위에서부터 진행한다.

**작업 순서 (팀 합의, 2026-10-24)**: 개발은 기획서 v0.5대로 진행한다. **B의 Spring 골격과 JPA 엔티티가 `dev`에 가장 먼저 들어와야** A(서비스·컨트롤러·AI 이식)와 C(스텁 컨트롤러·타입 생성)가 시작할 수 있다. 그래서 7.1을 작게 먼저 끝내 `dev`에 합치고 A·C에게 알린다. 시작 전에 A와 파이썬 코드를 `engine-reference/`로 옮기는 시점을 맞춘다. A는 그동안 엔진 자바 이식을 하므로 B를 기다리지 않는다.

### 7.1 Spring 골격과 공통 환경 (A·C가 기다림)
- [ ] **기존 파이썬 코드는 A가 `engine-reference/`로 옮긴다 — B는 `backend/`를 Spring 프로젝트로 시작하기 전에 A와 이동 시점을 맞춘다** (B가 파이썬 코드를 지우거나 옮기지 않는다)
- [ ] Gradle 프로젝트 생성 (Kotlin DSL, Java 21, Spring Boot 3.x): Spring Web, Spring Data JPA, PostgreSQL 드라이버, Bean Validation, springdoc-openapi, Jackson, `uuid-creator`
- [ ] Spotless(google-java-format) + `-Xlint:all` 설정, 테스트 의존성(JUnit 5, AssertJ, jqwik, Testcontainers, ArchUnit), Gradle Wrapper 커밋
- [ ] `application.yml`: `server.port=8000`, `ddl-auto=update`, `SPRING_DATASOURCE_*` 연결
- [ ] `backend/Dockerfile` (멀티 스테이지: `eclipse-temurin:21-jdk` 빌드 → `eclipse-temurin:21-jre` 실행)
- [ ] `docker-compose.yml` (`db` postgres:16.4 + healthcheck + 볼륨 `seonbi-pgdata`, `api` depends_on service_healthy, 포트 8000·5432)
- [ ] `backend/.env.example` (`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `LLM_MODEL_FAST=fake:default`, `LLM_MODEL_QUALITY`, `AI_DAILY_QUOTA_PER_CLIENT=50`, `ENGINE_PARTICLES=4000`)
- [ ] `.gitignore` (`.env` 제외, `.env.example`은 커밋)
- [ ] `core`: `X-Client-Id` 필터·인자 해석기(`CLIENT_ID_REQUIRED`), `FlowError`, `@RestControllerAdvice`(7.1 형식, Bean Validation → `VALIDATION_ERROR`)
- [ ] `HealthController`: `GET /api/health` (`/api/v1` 밖, 클라이언트 ID 불필요, DB 확인 → `200`/`503`)
- [ ] 8장 `@Entity` 13개 + 리포지토리 전부 (7.2에서 따로 점검)
- [ ] 공통 기반의 시그니처(6.1)를 A·C와 합의하고 기록
- [ ] 골격(Gradle 프로젝트 + `core` + 헬스 체크 + 엔티티 + Compose)을 **작게 먼저 `dev`에 합치고** A·C에게 알리기
- [ ] 팀원 전원이 `docker compose up --build` 한 번 돌려 보기

### 7.2 엔티티 점검
- [ ] 8.1 테이블 13개 전부 `@Entity`로 작성 (CHECK·UNIQUE·INDEX·CASCADE 포함). `decisions.question_limit`(NULL = 첫 라운드), `decisions.version`, `decisions.deleted_at`, `questions.origin`, `criteria.source`·`conditions.source`, `ai_usage_logs.provider` 빠뜨리지 않기
- [ ] `jsonb` 컬럼은 `@JdbcTypeCode(SqlTypes.JSON)`, 시간은 `Instant`, PK는 UUID v7
- [ ] `ddl-auto=update`로 테이블이 생성되는지 확인 (CHECK 제약이 실제로 생겼는지 포함), A·C에 공유

### 7.3 스텁 컨트롤러
- [ ] 7.2의 9~49번 전 경로를 컨트롤러에 등록하고 **7.3 예시 응답을 그대로** 반환 (A·C 담당 경로 포함, 해당 담당이 구현하면 교체). 프론트는 MSW 없이 이 스텁에 붙는다(7.1)
- [ ] 스텁에도 `X-Client-Id` 검사 적용 (0번 헬스 체크는 제외)
- [ ] springdoc OpenAPI 스키마(`/v3/api-docs`)가 나오는지 확인 → 프론트·C에 알림

### 7.4 결정 구성 API
- [ ] 결정 조회 서비스 `DecisionResolver`(id + client id → 404), `ensureNotFinalized`, `bumpVersion`
- [ ] 9~13번 결정 CRUD (9번 응답 options는 Option 객체, 10번 `status` 필터·커서 페이지네이션, 12번은 version 안 올림, 13번은 어느 상태든 `deleted_at`만 채움)
- [ ] 14~17번 선택지 (개수 제한·중복·FINALIZED 409, QUESTIONING/ANALYZED에서 14번 `rank` 자동 배치 + `RANKING_UPDATED`, 17번 2개 미만 `INVALID_STATE`)
- [ ] 19~22번 기준 (8개째 `LIMIT_EXCEEDED`, levels·inputMode 검증, `scaleMin/scaleMax`·`source` 선택 필드, 19번 A의 상태 전이 서비스 호출 + `statusChanged`, 21번 경고·prefilledRanking, 22번 답변 무효화)
- [ ] 24~25번 값 (유형별 검증, rank 검증, missing 계산)
- [ ] 26~27번 조건 (전체 교체, 항목별 `source`, 제외 판정, `ALL_OPTIONS_EXCLUDED`)
- [ ] `computeReadiness`·`computeExclusions`를 11번에 연결하고, A의 31번이 쓸 수 있게 서비스로 공개
- [ ] 모든 구성 쓰기 경로에 `bumpVersion` 연결 확인 (12번 제외)
- [ ] C의 29번이 쓸 생성 서비스(`source = free_text`, 개수 제한·상태 규칙·version 포함)를 C와 맞춰 두기
- [ ] 23번 기준 추천 (A의 `recommend_criteria` 연결, 상한 초과·실패 시 기본 목록 + `"source": "template"`)
- [ ] 49번 결정 복제 (P1)
- [ ] 18번 선택지 추천 (P2, 마지막)

### 7.5 테스트 기반
- [ ] 통합 테스트 기반: `@SpringBootTest` + MockMvc + Testcontainers(PostgreSQL), `X-Client-Id` 헤더 헬퍼, 결정 생성 헬퍼
- [ ] `./gradlew test`가 Docker만 있으면 돌아가는지 확인
- [ ] 공통 테스트: `CLIENT_ID_REQUIRED`, 다른 클라이언트 ID 404, 오류 형식, `VALIDATION_ERROR` (14장)
- [ ] B 담당 API 테스트: 검증 오류, `LIMIT_EXCEEDED`(선택지 9개째·기준 8개째), `DECISION_FINALIZED`, FINALIZED에서도 13·49번 성공, 기준 추가 시 `statusChanged`, `RANKING_UPDATED`, 선택지 2개 미만 `INVALID_STATE`, 구성 쓰기 시 version 증가·12번은 그대로, readiness
- [ ] 헬스 체크 테스트: `X-Client-Id` 없이 `200`
- [ ] 노트북 시나리오(2.2) 구성을 API로 끝까지 하는 테스트
- [ ] ArchUnit 구조 검사 틀 (A가 `engine` 규칙을 채움)

### 7.6 이후 지원
- [ ] DB 구조가 거의 확정되면 Flyway 도입 (`ddl-auto=update` 대체)
- [ ] A·C의 엔티티 변경 요청 받아 반영 (PR에 "DB 초기화 필요")
- [ ] C의 프론트 API 계층(openapi 타입·훅) 작업 지원
- [ ] 15.1에 따라 2명 체제가 되면 C 범위(28~30, 47~48)까지 이어받기

---

## 8. 완료 기준

- [ ] 새로 clone한 컴퓨터에서 `docker compose up --build`만으로 `GET /api/health`가 `200 {"status": "ok", "db": "ok"}` (`X-Client-Id` 없이)
- [ ] `X-Client-Id` 없이 요청하면 `400 CLIENT_ID_REQUIRED`, 다른 `X-Client-Id`로 남의 결정을 조회하면 `404`
- [ ] 모든 오류가 7.1 공통 형식(`error.code/message/details`)으로 나감. Bean Validation 실패는 `400 VALIDATION_ERROR`
- [ ] 노트북 시나리오(2.2) 구성 단계(결정 생성 → 선택지 3개 → 기준 → 값 입력 → 필수 조건 "Windows 지원"으로 맥북 제외)가 API만으로 끝까지 가능. 자유 입력(28~29번)은 C 담당이므로 27번 `required_tag`로 직접 넣어 확인
- [ ] 11번 `readiness`가 정확함: 선택지 <2, 기준 <2, 값 미입력, 순위 미입력 각각에서 `missing` 코드가 맞게 나오고, 다 채우면 `canStartQuestions: true`
- [ ] 선택지·기준·값·조건을 바꾸면 `decisions.version`이 정확히 올라가고, 12번 결정 수정에서는 그대로임
- [ ] FINALIZED 결정에 구성 수정 요청 시 `409 DECISION_FINALIZED`, 삭제(13)·복제(49)는 성공
- [ ] 결정 삭제 후 목록·상세에서 빠지고(404), 하위 행은 DB에 남아 있음 (소프트 삭제)
- [ ] QUESTIONING/ANALYZED 규칙: 기준 추가 시 `statusChanged`, 선택지 추가 시 `RANKING_UPDATED`, 2개 미만 삭제 시 `409 INVALID_STATE`
- [ ] 응답 필드가 7.3 예시와 이름·타입이 같음 (camelCase)
- [ ] `@Entity` 13개가 8.1 컬럼·제약과 일치함 (빠진 컬럼·추가된 컬럼 없음)
- [ ] `./gradlew test`가 통과함 (Testcontainers 사용)

---

## 9. 지켜야 할 규칙

**범위**
- 로그인 없음. 사용자 식별은 `X-Client-Id` 헤더(UUID)뿐이다. 다르면 `404`, 없거나 형식이 틀리면 `400 CLIENT_ID_REQUIRED` (3.1).
- 인증·보안·배포는 범위 밖이다. 세션·CSRF·레이트 리밋·사용자 테이블을 만들지 않는다 (12.1).
- 그래도 12.2 최소 규칙은 지킨다: `record` + Bean Validation 입력 검증, 결정 조회 조건(id + client id + `deleted_at IS NULL`), API 키는 `.env`에만.

**명세**
- 7장 스키마 v1과 8장을 벗어나지 않는다. **없는 필드·없는 API를 추가하지 않는다.**
- 빈틈이 보이면 코드로 메우지 말고 먼저 팀에 공유하고, **기획서부터 수정**한 뒤 구현한다 (변경은 PR + 17장 기록 + OpenAPI 타입 재생성, 7.1).
- 7장 스키마 변경 PR에는 프론트 담당을 리뷰어로 넣는다 (15.1).
- 먼저 7장 예시 응답을 돌려주는 스텁 컨트롤러를 올려 프론트가 MSW 없이 바로 붙게 한다 (7.1, 15장).

**DB**
- DB 구조의 기준은 git의 JPA 엔티티 하나뿐이다. DB에서 직접 테이블을 고치지 않는다 (11.2).
- Flyway는 DB 구조가 거의 확정된 뒤에 도입한다. 그 전엔 `ddl-auto=update`(local만).
- 엔티티가 바뀌면 `docker compose down -v`로 초기화한다. 엔티티를 바꾼 PR 설명에 **"DB 초기화 필요"** 를 적는다.
- 엔티티 변경은 B를 거친다 (15.1).
- 상태(`status`)는 직접 바꾸지 않고 A의 상태 전이 서비스로만 바꾼다 (4.2).
- `version` 증가는 변경과 같은 트랜잭션에서 한다. `version`은 분석 캐시 키로만 쓴다 (8.1·8.2).
- 결정 삭제는 `deleted_at`만 채운다. 하위 행을 지우지 않는다 (4.1·8.2).

**환경**
- Docker Compose는 로컬 개발 환경용이다(`db` postgres:16.4 + `api`). 배포용으로 쓰지 않는다 (11.2).
- Gradle Wrapper(`gradlew`)와 의존성 잠금 파일, `.env.example`은 커밋한다. `.env`와 실제 LLM 키는 커밋하지 않는다.
- 기존 파이썬 코드는 `engine-reference/`로 옮기는 것까지 A의 일이다. B는 지우거나 옮기지 않는다.

**코드·협업 (13.2)**
- 린트·포맷: Spotless(google-java-format) + `-Xlint:all`.
- 커밋: 무엇을 했는지 알아볼 수 있게 쓴다(13.2).
- 브랜치: 각자 자기 브랜치를 만들어 `dev`에 합친다. Spring 골격·엔티티·`core`·스텁은 다른 사람이 기다리므로 작게 먼저 합친다. 개발 기준은 `dev`의 `docs/`이고, 문서가 바뀌면 `git pull origin dev`로 받아 오며, PR 대상은 `main`이 아니라 `dev`다(13.2, `docs/개발환경-세팅.md` 0번).
- API 변경 시 OpenAPI 타입 재생성 커밋 포함.

---

## 10. 참고

| 절 | 내용 |
|---|---|
| 17.7 | v0.5 변경 — 백엔드를 Spring Boot(JPA)로 전환 |
| 2.2 | 노트북 시나리오 (완료 기준) |
| 3.1 | 사용자 식별 `X-Client-Id` |
| 3.2~3.5 | 결정·선택지·기준·값·조건 기능, 기준 유형·단계 라벨·입력 방식 |
| 4.1 | 엔티티 관계, 소프트 삭제와 CASCADE |
| 4.2 | 상태 머신, API별 상태 제약 표, 추가 질문 라운드 |
| 4.3 | 수정이 분석에 미치는 영향, 척도 고정 |
| 6.2 | `recommend_criteria`·`recommend_options`, 카테고리별 기본 기준 (23번 `template` 대체) |
| 7.1 | 공통 규약, 오류 형식, 오류 코드표 |
| 7.2 | 엔드포인트 전체 목록 (0번 헬스 체크 포함) |
| 7.3 | 클라이언트 식별, 0번·9~27번·49번 상세, 29번 추출 항목 형식 |
| 8.1~8.2 | 테이블(`questions.origin` 포함), JPA 매핑 규칙, version 용도·증가 규칙, 소프트 삭제 |
| 10.2 | 백엔드·DB 기술 스택 (Spring Boot, JPA, Gradle, springdoc, `uuid-creator`) |
| 11.1~11.3 | 구성도, Docker Compose 공통 환경(`ddl-auto=update`, 포트 8000), 환경 변수(`SPRING_DATASOURCE_*`) |
| 12.1~12.2 | 범위에서 뺀 것, 최소 규칙, AI 호출 상한(18·23·28만) |
| 13.1~13.2 | 저장소 구조(`backend/` Spring, `engine-reference/`), 개발 규칙 |
| 14 | 테스트 전략 (API 통합, ArchUnit) |
| 15, 15.1 | 병렬 개발 원칙(스텁), 역할 분담 |
| 16.1 | 결정 현황 (백엔드 프레임워크 전환) |
| `docs/개발환경-세팅.md` 0번 | 브랜치 사용 방법 (`dev`) |
